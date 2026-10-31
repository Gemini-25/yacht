# AI league — implementation spec

Internal developer spec (English) for the AI league. The user-facing design summary lives in
`DESIGN.md` (Korean). Every number below is a default that `LeagueConfig` can change unless it
says otherwise. The league is in its fifth shape: national leagues, clubs as the players'
affiliation, a continental and a world stage, league coefficients and a 요트협회 (federation)
that proposes rule changes (rules version 3, `LeagueConfig.VERSION`). A season saved under an
older shape plays to its end as it began (section 7.3); the older shapes are kept where such a
season needs them and are described where they still apply (2.1.7, 2.2.6). Section 8 describes
the screens (home and four tabs, pages, the match report and the watch screen).

Ground rules for all code:
- Engine code (`engine/src/main/kotlin/yacht/engine/league/`) is pure Kotlin: no threads, no
  Android, deterministic given seeds. The app's `LeagueController` owns threads and the one lock.
- Save formats are line-based text in the style of `Ladder.encode` / `Season.encode`: a versioned
  header line, one record per line, fields separated by spaces, inner separators `,` `;` `:` and
  `-` for empty. Decoders skip unknown or short lines and never throw on old files (a file that is
  not usable at all throws so the caller starts fresh). Files are written with
  `writeTextAtomically` (app core). The one binary file, `matchlogs.bin`, is CRC-checked (4.5).
- Every fixture has a seed `DiceStream.mix(leagueSeed, season, compCode, fixtureId)`, so a result
  is a pure function of the fixture: after a kill, unrecorded fixtures are replayed with the same
  seed, and a report or a replay re-plays a match from its seed (4.5).
- A legacy season (not national) builds, plays, saves and closes byte for byte as before the
  national leagues (LegacyGoldenTest and the stage golden tests pin it).
- Bounded memory: no per-game history is kept beyond the bounds below.
- Main thread: no engine work. Views are immutable snapshots built on a background thread.

## 1. Roster

- **Generated players** (`origin = GENERATED`): the league is for them only by default
  (`baseAis` off). `LeagueConfig.generated` (default 1000, at most 3000) players, keys
  `g0001`... Player `i` comes from `Random(mix(GEN_SEED, salt, i))` and the nation counts before it (`PlayerGenerator`).
  - **Identity** (`Identity.kt`): nation (28, `Nations.kt`: names written the nation's way,
    cities, a confederation; drawn by weight 100..120 by the logarithm of the population (Sweden 100, China and India 120),
    each draw rebalanced toward the shares from the counts so far (founding players too; a
    pupil's mentor nation above its share comes less often), so no nation
    has more than 1.2 times another's share), gender, debut age (age = debutAge + season - born), abilities
    1-99 (판단력 → mistake rate, 예측력 → how far it plans, 승부 감각 → late-game score sense,
    멘탈 → no temper from 50 up, a stronger temper below), potential, temper kind and whether
    its mistakes come by feel or as slips. Saved as `name=value` tokens on the roster line.
  - **Style** (`PlayStyles.kt`): one of 25 recipes with an intensity, an optional second
    recipe (blend) and an overlay (gambler, locker, panic, streaky, chameleon). The app shows
    it as five two-ended tendency bars (`Tendencies`).
  - **Style genes v2** (`PlayGene.V2`, `play=` token, generated players and pupils from 생성 다양화
    v2 on; saved players keep their older genes and play exactly as before): rescaled intensities
    for the recipes whose k hardly mattered (k 0.7 against 1.7 now changes ≥5% of decisions; the
    same style at k = 1), 예측력 to the hundredth (`Profiles.future2`: 0.7..1, or 1..1.15 for the
    30% that err on the far side), a second style 40% (TOP tier: none that brings a rule along), a
    taste vector (`Taste`: FH/4K/Choice/Yacht/straight likes, upper-box snob, favourite face, bonus,
    zero, fill-order habit, sacrifice box, throw cost; each ~1/3 of the time, cheaper ranges for
    TOP), a score reading (8..20 points ahead/behind → a `Lean`) 35% and a late phase (3..6 boxes
    left → a `Lean`) 30%. All are `tag:value` parts older decoders skip. No newcomer is born with an
    alive player's genes (tastes redrawn), and plain max expectation always has a taste that
    changes ≥3% of its decisions. Pupils take a mentor's main style and k exactly 80% of the time,
    always with mutated tastes, and a main style takes at most 10% of a season's pupils. Measured
    (noise-free choices on 1188 decisions, complete linkage at 2%): 1000 players → 1000 styles, 0
    twins (533 and 91 before); 3000 → 2991 (1233); strength p90 183.1 (183.8 before).
  - **Schools** (`Schools.kt`): each nation favours some recipes and an overlay (×2.5) and
    tilts abilities by a few points; every style and level still comes from every nation.
    A world champion's style comes into fashion for newcomers (fades ×0.6 a season).
  - Tiers of quality at birth (TOP/MID/LOW/NOVICE) by potential.
- **Evolved players** (`origin = EVOLVED`): pupils made at season end (section 5). Keys
  `e<season>_<n>`. Their 스승 (mentors) are the parent links.
- **Base AIs**: the 103 hand-made AIs of `LeagueRoster`. With `baseAis` off they play one
  farewell season and leave the league as its founders (records kept; ranked games and the lab
  still use them).
- **Guests** (`origin = GUEST` "초청", `LeagueRoster.GUESTS`, config `guests`, default on; off in
  the 대혼돈 and 강자만 presets): hand-made AIs that play whatever `baseAis` says. The one guest is
  the 상대 맞춤 승률 AI (`win-adapt`): the app's win AI (exact from 5 boxes) whose opponent model
  is the opponent's record ([OppModel]: mean and sd of its finals against the max-expectation
  player's 191.8 / 37.9, shrunk by n/(n+30), half points and 2% steps, none under 30 games). The
  record is this season's official games once 30, else the career's rated games, read under the
  lock when the job is taken (`LeagueWorld.oppModel`, passed to `LeagueMatch.playJob` as `scout`;
  `LeagueAi.versus` builds the player). Ranked games read my ranked games' mean. A highlight
  replay reads the records as they are then (a changed model plays another game: 다시 볼 수 없음).
  The custom WIN base has the same switch (`CustomAiSpec.adapt`, a 17th field).
- **Made players** (`origin = CREATED`, keys `u1`, `u2`, ... never given twice; `made N` line):
  선수 만들기 (`PlayerMaker`, `PlayerDraft`): name, gender, nation, hometown, age, abilities,
  potential, feel or slips, temper kind, style (recipe, k 0.7..1.7, second style, overlay; a
  drawn or edited player's tastes and situational habits kept), and `keep` (영원히 현역: no
  aging, growth, decline or retirement; saved as `keep=1`). Abilities apply as for generated
  players ([Profiles.apply]). Any league player can be remade the same way (its records,
  debut, parents and peak stay); only made ones can be deleted (they leave as deleted AIs).
- **Mine** (`origin = MINE`): the clone and custom AIs.
- **Where a member plays** (`NationalLeagues.home`): the nation of its club (`ClubBook.playNation`,
  2.2); without a club its nationality; without a nation of `Nations.ALL` (the guest, base AIs,
  mine, a key without an identity) Korea. A made player plays in its chosen nation. A member is
  **abroad** when it has a nationality and its club is of another nation; the nationless are never
  abroad (NationlessTest). Nationality never changes, so national teams (2.3) never do.
- `league/roster.txt`: alive players with identity and style tokens; `retired` lines (newest
  3000, with nation, gender and ages) and `legend` lines (any honour, at most 300).

## 2. Structure of a season

### 2.1 Leagues

#### 2.1.1 National leagues (국가 리그, `NationalLeagues.kt`, `Leagues.kt`)
A season's divisions are grouped in leagues (`Leagues`, a list of `LeagueDef(id, first, tiers)`,
contiguous from division 0, each league a linear ladder: one division per tier).
- **Ids** (`LeagueIds`): a nation's code ("KR"); a confederation's 연합 리그 `XAS`, `XEU`, `XAM`,
  `XAF`; the 국제 연합 리그 `XWO`; "" for the single league of a legacy season. `code(id)` packs an
  id in one archive digit (nations 1..30, XWO 31, regions 32..35; `Nations.ALL` may grow to 30).
  Labels: "한국 리그", "아시아 연합 리그", "국제 연합 리그"; divisions "한국 1부", legacy "3부 B조".
- **Which leagues** (`plan(counts, config, last)`, deterministic, nations in `Nations.ALL` order):
  1. a nation with at least `leagueMin` (24) members plays its own league; one that had a league
     last season keeps it down to `leagueMin - 4` (hysteresis);
  2. per confederation, the members of nations without a league form its 연합 리그 when they are
     at least `leagueMin` (or the region had one last season and they are at least `leagueMin - 4`);
  3. what is left forms `XWO` when it is at least `leagueMin` or no league exists yet; else each
     such nation joins the smallest league of its confederation (else the smallest of all);
  4. `XWO` belongs to its largest nation's confederation (stored on its `league` line).
  Result: 28 national leagues at 1000-3000 players, 4 regional ones at 200-500 (mixed between).
  A league of base AIs only is one KR league; 강자만 (48 players) is one 국제 연합 리그.
- **Tiers** (`tierCount(n, D)`, D = `divisionSize`, 16..24, default 20): one tier below 20 members;
  else `t = max(2, round(n / D))`, raised while a division would hold more than D + 2 (at most 12
  tiers), at most `n / 10`. `sizes` are even shares, the upper divisions taking the extra ones. At
  D = 20 leagues of 30+ get divisions of 15-22, leagues of 20-29 two divisions of 10-14.
- **Round robin**: double (home and away) in national seasons, single in the 빠른 시즌 preset
  (`SINGLE` format). A division of s plays 2(s - 1) rounds (2s when s is odd): R ≤ 42 at D = 20
  and 50 at D = 24. FormationBudgetTest pins, for 200..3000 players and every D, national fixtures
  ≤ the old pyramid's and R + 4 ≤ 55.
- League fixtures (formation of the founding nation counts): 1000 players 17,006 (old pyramid
  24,550), 3000 players 57,630 (73,550), 200 players 3,544 (4,950). Matches per player about 34-38.

#### 2.1.2 One calendar for every league: the aligned schedule (`season 5`)
Leagues of different sizes would end weeks apart (30-42 rounds). A national season spreads each
division's rounds over the R weeks of its longest division so that every league plays its first
round in week 1 and its last in week R: round j of division d (r_d rounds, both 0-based) is
played on matchday `round(j × (R - 1) / (r_d - 1))` (week = matchday + 1). The pairs of each round are the same as an unaligned build
(same random draws); only weeks change; a league idles in a gap week. `LeagueFixture.round` is the
league's own round ("12라운드"); the 일정 labels weeks "12주차". A season is aligned exactly when
it is national and is then written as `season 5`:
```
season 5
number <n> <seed> <double 0|1>
division <i> <keys csv>
league <id> <first division> <count> [CONFED]     (one per league, in division order; CONFED on XWO only)
fixtures <N>
r <id> <games> / standing ... / pos <d> <k> <places>   (as season 4; pos per completed round of d)
```
A legacy season is `season 4`, byte for byte as before. `decode` reads 5, 4 and 3; a `season 5`
whose `league` lines do not cover the divisions, or name a league this version does not know
(`LeagueIds.known`), throws (a fresh season starts).

#### 2.1.3 Matches and tables
- Match = 2 games on mirrored dice (`matchFormat`). **GAMES (판 승점제, the default)**: decided by
  the games alone: 2-0 or a win and a drawn game wins, 1-1 or two drawn games is a draw, no total;
  league points 3/1/0. A 2-0 sweep earns a bonus point (완승 보너스) when `sweepPoints` =
  `matchFormat.sweepBonus && sweepBonus` (the federation's SWEEP lever, 11.2); `Season.bonus`
  (from the season's own rules copy), every group stage (saved as a `bonus 1` line), the odds, the
  highlights and the app read it from there (`Standing.sweeps`). MIRRORED (합계제, the default before): a 1-1 match goes to the
  two-game total, equal totals draw. SINGLE = 1 game, BEST_OF_3 = mirrored + a decider when level.
  Season totals stay a table tiebreak (and 운 보정 승점 compares the totals whatever the rule).
  `JobResult.format` carries how a job was decided, so every board, group table, club table,
  nation cup line, statistic, live board and preview reads it by the rule it was played with;
  the AI lab always plays the total.
- **Rules versions** (`LeagueConfig.VERSION`, the `config N` line): version 1 is the totals,
  legs and single elimination; version 2 brought 판 승점제 and the series and double-elimination
  knockouts (2.3); version 3 national leagues and the keys of section 7. A season keeps the copy
  of the rules it began with (`SeasonPlan.config`, `cfg` lines) and plays to its end under it.

#### 2.1.4 Promotion, relegation and playoffs per league
- Per league and boundary (`Pyramid.crossing(season, config, league, t)`): the top `autoSwap` (2)
  of the lower division go up and the bottom `autoSwap` of the upper go down, at most a third of
  the smaller division. With `promotionPlayoff` the next place on each side plays one series for
  the upper place (`PromotionPlayoff`, `promopo 2`: `t <tier> <upper> <lower> <slot> <league>`
  lines and a `news <ids>` line naming the 4 best-ranked leagues whose boundary-0 ties make news;
  labels "한국 1·2부 승강전"). A national season writes no move honours (UP/DOWN); the moves are
  `Offseason.Move(key, from, to, league)`.
- **Title playoff** per league (`LeagueTitlePlayoffs`, `titlepo 2`): every league whose 1부 has 4
  or more plays its top 4 (1v4, 2v3) in a bracket of its own (double elimination: semis, winners'
  final + losers' round, losers' final, grand final; ids from `bracket × 16`), "한국 4강" … "한국
  최종 결승". The brackets go in **lockstep**: only ties of the first stage some bracket still
  has are handed out, so a 1주씩 run never stalls on a bracket that finished early. No events of
  its own (the title is a close event, 5). View: `Sections` of one `Groups(knockout)` per league.
- **Honours** (`HonourKind`): the kind carries its league: `L0.KR` (champion, after the playoff),
  `REG.KR` (regular-season leader), `L0R.KR` (playoff runner-up), `L1.KR` … (lower-tier titles),
  `LMVP.KR` (league MVP); `CCL.ASIA`/`CCL2.ASIA` carry a confederation. `HonourKind.base`,
  `qualifier`, `leagueOf` (a league id only, null for CCL), `isDivision`, `tier`, `isTopTitle`.
  Kinds without a suffix mean what they always did (the old single league, world titles).

#### 2.1.5 Next season's divisions (`NationalLeagues.next`)
1. Each old league's next tier lists as `Pyramid.next` makes them with its moves (promoted at the
   bottom of their new tier, relegated at the top): every key has an old tier and an order.
2. The leagues of the next season from the members' play nations (`plan`, with last season's ids
   for the hysteresis), their tier counts and sizes.
3. Every key still in the league, not moved abroad and not back home, goes to its new league at
   tier `min(old tier, tiers - 1)`. Sources landing on one tier are concatenated by old tier;
   only sources of the same old tier (the groups of one pyramid tier, the same tier of two merged
   leagues) are interleaved place by place, so a 6부 winner never ranks above a 2부 player.
4. Players moved abroad (2.2.4) join tier 0 of their new league, protected from the refill.
5. Players back home join tier 0 when rated at least its median, else tier 1, in the middle.
6. Newcomers (rookies, pupils, made players) go to the last tier, best rated first.
7. `Pyramid.fill` brings every tier to its size from the top. A relegated player is passed in the
   refill only by players of its own old tier (so at a migration a relegated old-2부 player is not
   overtaken by old 3부-5부 players); the promoted and the transfers in are protected.
The **migration** from a legacy season (7.3) is the same path: Korean old-1부 players start in 한국
1부, the old 2부-6부 Koreans follow on tier 1 by old tier, then place.

#### 2.1.6 The first season
`NationalLeagues.first`: the members by play nation, each league's members best rated first cut
into its sizes (`Offseason.first`). A new league's clubs are founded first (2.2).

#### 2.1.7 The legacy structure (a season begun before national leagues)
- **Linear** (six groups or fewer of `groupSize`, or `pyramid` off): `divisions` (default 4)
  balanced divisions over all AIs except me. ~205 AIs → 4 × 51.
- **Regional pyramid** (`Pyramid.kt`, `pyramid` on and more than six groups of `groupSize`,
  default 50): K = round(N / groupSize) groups; 1부 one group, 2부 two, 3부 four, ...; the rest
  is a tier of its own when at least half the tier above, else joins it (1000: 1/2/4/8/5,
  3000: 1/2/4/8/16/29). Group sizes as even as can be, higher groups first. Each group is a
  division of the `Season`; `Tiers` maps divisions to tiers (saved as a `tiers` line only for
  a pyramid, with region names). Each season a tier's members are sorted by confederation,
  nation, hometown and cut into its groups, named after the region (a nation with half the
  group, else a confederation, else the two biggest). Single round robin per division; matchday
  `k` of every division opens together.
- End of season, per tier boundary: the top s of every lower group go up (s = `autoSwap` linear,
  `groupSwap` (3) pyramid; at most a third of the smallest group) and as many go down from the
  upper groups' bottom places (an uneven share by points per match across groups), with one
  promotion-playoff tie per lower group. Honours `L<t>` per group, `UP<t>`/`DOWN<t>` moves; news
  for 1부-3부 and one `lower_tiers` article. Title playoff (`titlepo 1`): 1st division top 4.
- No new legacy season is created after the flip (7.3): a league whose rules are older moves on
  at its next close. The legacy builders stay for the season in play and the tests.

### 2.2 Clubs

#### 2.2.1 Clubs as affiliation (`Clubs.kt`, `clubs 2`)
Every member belongs to one club of its play nation. Matches stay 1v1: a club is who a player
plays for, not a team.
- **Count**: `C = clamp(round(n / clubSize), 1, 16)` clubs for a nation of n members
  (`clubSize` 8). A club keeps between `floor(0.75 n / C)` and `ceil(1.25 n / C) + 1` members.
- **Ids** `c<n>` ("c01".."c99", "c100".."c1295": the archive packs the number in 2 base-36
  digits), never reused until the club was dissolved more than 30 seasons ago; `ClubBook.idOf`.
- **Names** `<city> <mascot>`: a nation's own cities (by weight, then extra cities; every nation
  has at least 10), 48 mascots in a seeded order per nation, names unique (also against
  dissolved clubs); emblem (shape, two colours, initial) from the id.
- **Founding** (`ClubBook.found`): a new league, `renew`, a FAST league, a lost file. Per nation
  C clubs, a snake draft by rating with a seeded pick order; the founding counts as that
  season's off-season.
- **Accessors** for the app (no copies per frame): `clubOf(key)`, `club(id)`, `clubsOf(nation)`,
  `members(id)`, `nameOf(id)` (also dissolved clubs), `playNation(key)`, `version` (changes with
  every change: the app rebuilds its badges only then).

#### 2.2.2 The club table (`ClubTable.kt`, F1 constructors' style)
A member scores its league points × its tier's weight (1부 1.0, 2부 0.6, 0.4, 0.3, 0.25, then 0.2)
plus points for its honours (L0 20, REG 5, L0R 10, CUP 15, CUP2 8, CL 30, CL2 15, CCL 20, CCL2 10,
SUPER 5, LMVP 10, MVP 15, SCORER 5, YACHT 5, WCW 5, a lower-tier title 8). A club scores the sum
of its best 6 members, rounded to 0.1 (size-neutral: clubs are within ±25% of each other). A
league's clubs are those of the nations its members' clubs belong to; a member abroad scores for
its foreign club. Ties: more 1부 points, the best member score, id. Worked example (ClubTableTest):
1부 60 and 45, 2부 70/50/40/30/20 → 219 + L0.KR 20 + L1.KR 8 = 247.0. Built on request
(O(league members)), never in the snapshot.

#### 2.2.3 The club off-season (`ClubBook.offseason(closing, input)`)
Idempotent per (season, plan seed); every branch reads the closing plan.
1. The club tables of every league: a `cs` line (top 3 per league with the champion's points,
   best-ranked league first; newest 20 seasons), prize money `max(0, 40 - 2 × place)`, a title for
   each champion, honour money (CUP/CL/CCL to the holder's club, CCL 25, CCL2 12, CLUBCUP 30,
   CLUBCUP2 15).
2. Departures; an `X` move only for a notable player (a major title, or the 1부 at the close).
3. Returns home (귀국) and transfers abroad (해외 이적), 2.2.4.
4. Domestic window per nation, at most C/2 deals, worst club first (last place, then budget, id):
   a buyer takes the best-rated of the top 3 of each club placed above it that it can afford and
   that beats its 6th best; the seller keeps more than the floor; a buyer at the ceiling releases
   its weakest (`R`, to the draft).
5. Resize per nation: found or dissolve clubs to reach C, only when the new count brings the clubs
   closer to `clubSize` (no founding at 12 members and dissolving at 11 the season after);
   dissolve by fewest titles, lowest place, highest id; a nation with no members loses its clubs.
   (Nations of 9-13 members, as at 300 players, still found or fold a second club every few
   seasons as their numbers drift.)
6. Balance: where the clubless members cannot bring every club to its floor, the biggest clubs
   release their weakest member of their own nation (`R`) one at a time.
7. Draft per nation: pupils new to the league join their mentor's club (`Y`, 유스 입단), then
   everyone without a club, best rated first, each to the smallest club (then lower place, id).
8. News, always rebuilt from what the book keeps (so a replay tells the same): `club_title` for
   the 4 best-ranked leagues, `club_champions` for the next 3, `return_home` (≤ 3 major-title
   holders), `transfer_abroad` (the 3 biggest fees), `transfer_window` (digest), `transfer` (3
   biggest domestic fees), `club_dissolved`/`club_founded` (one `club_digest` past 3), `draft` or
   `youth` (3 best-rated rookies), `market_digest`.
At 1084 members the first national off-season made 16 events. 3000 players: 377 clubs, 12
transfers abroad a season, about 180 domestic deals, clubs.txt 51-56 KB, 40-130 ms.

#### 2.2.4 Transfers abroad (해외 이적, `Transfers.kt`)
No randomness; returns first, then transfers.
- **Returns**: a member abroad goes home at 33, or when it finished outside its foreign league's
  1부; it joins its nation's club with the fewest members (then id; no fee, `H` move; to the draft
  when its nation has no club).
- **Transfers**: `round(transfers × members / 1000)` a season (4 at 1000, 12 at 3000, at most 30),
  none without a ranking or with fewer than 2 leagues. Candidates: 1부 at the close, among the 3
  best-rated of their league, aged 20-31, generated or evolved, not abroad, not moved; best rated
  first. Each goes to the best-ranked league at least 3 places above its own whose 1부 median
  rating it reaches, to that league's club with the most room (then budget, id), for the club
  fee of its value (the buyer pays what it can; one with less than half the fee is passed over).
  A league loses and takes at most `1 + n / 28`.
- The member's club, so its play nation and next league, change; its nationality does not.
  Saved as `F` (abroad) and `H` (home) moves; `NationalLeagues.next` places them (2.1.5).

#### 2.2.5 `clubs.txt` (`clubs 2`)
```
clubs 2
meta <seed> <season> <lastSeed|-> <next>
club <id> <nation> <city> <mascot> <name> <shape> <rgb1> <rgb2> <initial> <budget> <titles> <founded> <members csv|->
gone <id> <nation> <name> <shape> <rgb1> <rgb2> <initial> <titles> <founded> <dissolved>
cs <season> <league|->:<c1>,<c2>,<c3>:<champion points x10>;...
od <season> <rookies> <domestic> <abroad> <returns> <biggest|-> <from|-> <to|-> <fee> <drafted|-> <bought|-> <abroad key:from:to,..|-> <home key:from:to,..|-> <reform 0|1>
move <season> <T|D|R|X|F|H|Y> <ai> <from|-> <to|-> <amount>
```
Move types (`ClubMoveType`): T transfer, D draft (each club's first pick and the named rookies), R
released, X left, F abroad, H home, Y youth; `ClubMove.kind` keeps the old four for the app. The
`od` line keeps the named players and counts so a replayed off-season tells the same news. The
history keeps `clamp(members / 4, 60, 300)` moves, never cutting the last off-season.

#### 2.2.6 The old club league (a season begun before national leagues)
`clubs 1`: `clubs` (16) clubs of `squad` (6); a 5-board team match a round (board i is a mirrored
2-game match between the i-th AIs, boards won decide, no sweep bonus), single round robin, budgets
(value `max(1, (rating - 1100) / 10)`), a transfer window and a reverse-order draft. Its factory
returns null for national seasons; its codec stays so a season in play keeps its club rounds; the
next open converts the book (`ClubBook.migrate`, 7.3).

### 2.3 Cups and other competitions
**Series (선승제, `seriesKnockouts`, default on from rules version 2).** A one-on-one knockout tie
is first to N games won (a drawn game half each), played in pairs on the same dice dealt as legs
were (pair j on the deal of leg j, A first in its first game: `LeagueMatch.playTie(firstTo)`),
stopping as soon as a side has N and leads; at most N + 1 pairs (`Fixture.legs`), then up to 3
single deciders on new deals while level, then the total, then the higher seed. N
(`KnockoutLength.wins`): 2 before the 8강, 3 in the 8강 and 4강 (and a winners' final before a
grand final), 4 in the final; losers' rounds 2, the losers' final 3; the 슈퍼컵 and the
promotion playoff 3. With **긴 토너먼트** off every series is to 2.
Team knockouts stay one team match a tie.
**Double elimination (패자부활, `doubleElimination`, default on from version 2)** in every
knockout of 4 or more entries: a loss on the winners' side drops to the losers' side, a loss there
ends it; the losers' round 1 pairs the first round's losers, then rounds alternate between taking
the next winners' round's losers (in reverse order every other time) and halving; its final takes
the winners' final's loser; one grand final (`Knockout.GRAND_LABEL` "최종 결승"), no advantage and
no reset. Stages in play order (W1; W2 + L1; L2; W3 + L3; ...; grand final: 2k stages), tie ids in
that order. Places: champion, runner-up, the last four, then pairs of losers' rounds.
**Before version 2** (a season in play keeps it): legs of two-game matches won on games then the
total, deciders, the higher seed; one loss out. Each competition saves `long 1`, `series 1` and
`double 1` lines for the rules it was drawn with.
- **요트컵** (cup): every AI except me. Bracket of the next power of two with byes for the best
  seeds (by rating; the seeds are saved and are the ratings the season was drawn with, 10),
  rounds spread over the season from 4% to 88% of the league weeks, final last.
- **챔피언스 리그** (`ChampionsLeague` on `ChampionsBracket`, 16 entrants, 4 groups of 4, double
  round robin, top 2 to an 8-player double-elimination bracket). After a national season it is
  the **world stage** (`worldEntrants`): last season's holder; the 대륙 챔피언스 champions then
  runners-up (confederation order) while fewer than `16 - clDirect` are in; then the champions
  (`L0.<id>`) of exactly `clDirect` (7) best-ranked leagues, a champion already in passing its
  place to the next league's; then 1부 runners-up in league order; then rating. At the default
  that is holder + 8 + 7. Without a 대륙 챔피언스 last season: the holder, then place by place
  over the leagues in rank order (champion, then the 1부 table, players still in the league).
  After a legacy season the old rule (1부 top 12, 2부 winners, cup finalists).
- **대륙 챔피언스** (`ContinentalChampions`, `Comp.CONT_CHAMPIONS` 'T', `ccl 1`, `continentalCl`):
  one section per confederation, 16 players with 4+ leagues, 8 with 2-3, none with one; groups of
  4 (G = 4 or 2) then the same double-elimination bracket. Slots per league
  `cclSlots(n, rank, size) = size / n + (rank < size % n)` over the ranking (unranked leagues by
  strength); a short league passes its slots down. Entrants: last season's `L0.<id>` and its 1부
  table (`lastLeagues.byId(id).top`), or this season's 1부 by rating for a new league. Seeds by
  league rank then place, at most 2 of one league in a group (redrawn up to 30 times). Labels
  "아시아 A조 1차전", "아시아 승자조 8강"; honours `CCL.<CONFED>`, `CCL2.<CONFED>`; news tagged
  "@<section title>". Not in a legacy season.
- **슈퍼컵** (from season 2): after a national season the 챔피언스 리그 winner (its runner-up when
  the winner left) against the cup winner (runner-up when the same); else the league champion
  against the cup winner. One knockout tie.
- **올스타전** (mid-season): national: the 5 best-rated players of all the leagues' 1부s together
  ("모든 리그의 1부") against the 5 best of every other division; a team match, a friendly.
- **클럽 대항전** (`ClubCup`, `Comp.CLUB_CUP` 'U', `clubcup 1`, `clubCup`, even seasons after a
  national season): last season's club-table champions (`ClubSource.champions`), the 12 strongest
  by the average of their best 5 still in the world (3 needed): groups, semis and a final of
  5-board team matches (the family cup's format, `TeamCup`). Honours `CLUBCUP`/`CLUBCUP2` (club ids
  as keys), `CLUBCUPW` (the winning squad).
- **계열 대항전** (`FamilyCup`, every 3rd season from season 2): each family except MINE fields its
  5 best; 9 teams, 3 groups of 3, semis, final (team matches).
- **국가대항전** (`NationCup.kt`, `nationCup`): national teams of each nation's five best rated
  players (by nationality: a player abroad still plays for its nation). 요트 월드컵 every fourth
  season from season 3 (a host nation), the continental cups (아시아·유럽·아메리카·아프리카컵) in
  between; groups then a bracket of team matches. `NationBook` (`nations.txt`) keeps records,
  titles, caps and podiums; rivalries (한일전, ...); honours WC/WC2/WC4/WCW/WCMVP/WCTOP/WCH/CC/CC2/CCW/NSQ.
- **레전드 매치** (`LegendMatch.kt`, every third season from 3): a recently retired legend at its
  peak against the MVP, an exhibition off the ladder.
- **Friendlies**: newcomer placement (20%, 60% while newcomers are >10% of the roster) only.

### 2.4 Scheduling and the season calendar (주차)
**Weeks.** A national season has R = 38-42 league weeks (42 at 1000 and 3000 players with the
defaults) and up to 4 post-season weeks: 42-46 weeks in all (the old pyramid: 51 + 4). FAST: about
21 weeks and no post-season (it has no playoffs). When the season is set up every stage of the
other competitions gets a week from its target share of the league (`Stage.target`):
`1 + round(target × (R - 1))`, then the first week from there, after the competition's previous
stage, that is still free in its **lane** (one stage a week while they fit):

| Lane | Competitions |
|---|---|
| A 국내 컵·이벤트 | 요트컵, 슈퍼컵, 올스타전, 레전드 매치 (and any other) |
| B 국제 클럽 | 챔피언스 리그, 대륙 챔피언스 |
| C 국가대항전 | 요트 월드컵, 대륙컵, 계열 대항전 |
| D 클럽 | 클럽 대항전 (and the old club league) |

Ties go by `PRIORITY` = SUPER, WORLD_CUP, CHAMPIONS, CONT_CHAMPIONS, CUP, CONTINENTAL, FAMILY,
ALL_STAR, LEGEND, CLUB_CUP. A legacy season keeps its two lanes (everything, and the club league)
and its weeks. The post-season takes the weeks after the league: playoff stage s is week R + 1 + s.
The calendar is a pure function of the plan, rebuilt, never saved. **Measured** on the real stages
of 1000- and 3000-player DEFAULT plans (SeasonCalendarTest pins them), R = 42, last week 46:
- A: 요트컵 at 1000 players 3,4,6,8,10,12,14,15,17,19,21,23,25,26,28,30,32,33,35,37 (20 stages; 24
  stages from 3 to 37 at 3000); 슈퍼컵 1; 올스타 22; 레전드 24.
- B: 챔스 groups 4,8,11,14,18,21, knockout 25,27,29,32,35,39; 대륙 챔피언스 groups 3,6,9,12,15,17,
  knockout 20,22,26,28,31,34.
- C: 월드컵 13,15,17-20,22-26 (11 stages); 대륙컵 13,15,17-20,22. D: 클럽 대항전 6,15,24,34,35,37,38.
- Post-season: title playoff 43-46, promotion playoff 43.
- Every lane holds one stage a week also at R = 38. FAST (R = 21): A 10 cup stages + 슈퍼컵 1 +
  올스타 11, B 9 + 9, all one a week (no club cup, no post-season).
- **Heavy weeks**: lanes are independent, so one week can hold a cup round, a CL or 대륙 챔피언스
  matchday, a nation-cup stage and a club-cup stage at once. That is correct (the busy set keeps
  a player in one match at a time), but such a week takes several times the CPU of a quiet one,
  which shows in 1주씩 mode (DESIGN.md says so).

**Clock.** The week in play is the lowest league round with a fixture left, + 1; after the league,
the first post-season week with a playoff tie left. A stage opens once the clock reaches its
week: `SeasonCalendar.progress` turns the week into the league progress the competition's own
`next(p)` opens exactly those stages at (`Stage.opensAt`). When nothing else is free, next week's
stages may start (so no worker waits); once the league is over everything left opens. Stages that
run past their week keep their week: 일정 lists them as late.

`SeasonPlan.next(busy, threads)` returns the next fixture. Order: (1) open knockout or group
fixtures (CL first) while fewer than `max(1, T-1)` non-league jobs are in flight; (2) one team
fixture at a time; (3) league fixtures of the current and next matchday; (4) club board fixtures
of open rounds; (5) open cup fixtures uncapped; (6) next week's stages; (7) the post-season once
the league is over. Every fixture it hands out carries its week (`Fixture.week`, metadata only:
never in the deal); so do the events it makes (`LeagueEvent.week`; 0 = 개막 전 for the draws, 10000
= 시즌 마감 for the close), the news articles and the ladder feed. Heavy fixtures (estimated cost
over 50 ms per game from a per-AI cost meter) are limited to `max(1, T-1)` in flight. The season
ends when every competition is finished; then playoffs; then rollover (section 5).
`SeasonPlan` encodes everything into `league/season.txt` (`plan 1` with `comp <code>` ... `end`
sections, the season block, `cfg`/`closecfg` copies of the rules).

**Run modes (진행 방식, app).** `LeagueRunMode` (saved in `league/runmode.txt`): 계속 plays on
until ■; 1주씩 plays the week in play and stops by itself. A 1주씩 run takes its goal (season,
week) when it starts and asks for fixtures with `next(..., lastWeek = goal)`: the league gives no
round after it and a competition fixture whose stage week is later is given back at once and the
next one tried. The week is played once the clock is past it, nothing is in flight and nothing of
it is free; the run then stops and says what it played ("12주차를 다 치렀어요"). A run that
resumes by itself keeps its goal; the player's ■ clears it. After a season's last week the next
request closes the season (rollover), finds a new season and stops.

**Never a silent stop (app).** Workers catch every `Throwable` from a job (a match fails, is
retried, then voided; 12 errors in a row stop the league with the reason shown), worker threads
get a 16 MB stack, and a run that ends by itself says why and saves. A watchdog in the upkeep
loop: nothing in flight and no match for 20 s means something is still marked as taken; the busy
set, cup and team counters and the league's taken fixtures (`Season.reclaim`) are rebuilt from
the jobs really in flight, and it is told to the crash log.

## 3. Per-game facts and statistics

**GameFacts** (computed in `LeagueMatch` from the 24 boards after each half-turn, plus the
`WinEstimate`): both cards (points per box, bonus, yacht, zeros), the first player's P(win)
after each half-turn (24 values, stored as 0-100 bytes), winner's lowest P (comeback), loser's
highest P (blown lead), the winner's largest deficit after a full turn (`deficit`), lead changes,
decisive half-turn (last lead change), final margin.

**Match rating 평점 2.0** (`GameFacts.rating`, per player and game):
```
r = 6.0 + 1.4 × (own score − mean) / sd       // the league's game-score scale (below)
        + 1.5 × WPA                           // the change of the player's own P(win) over its own turns
        ± 0.4                                 // win / loss (draw 0)
        + 0.2 yacht + 0.3 upper bonus + 0.3 comeback win (own min P(win) ≤ 0.2) + 0.3 score ≥ 300
        − center                              // the league's mean of the non-score parts
clipped to [1.0, 10.0] at 0.005 steps
```
- **Scale** (`RatingScale(mean, sd, center)`, `StatsBook.ratingScale`): the season before's official
  games; in a league's first season its own games so far once there are 1000 side-games (230/45
  before); a league whose seasons are too short measures over as many seasons as it takes. Fixed
  per season (measured at the close, applied at the next start), rounded to 0.001 so a reload rates
  the same. Measured (1000 generated players, DEFAULT): mean 168.2, SD 39.8, center −0.054; games'
  average 5.95; eligible players' season averages SD 0.77 (about 4.5 to 7.8); 1부 SD 0.55 in season
  1 and 0.45-0.47 once promotion has sorted the 1부s; per game p5 2.35, p95 9.87.
- **Versioning** (stats.txt): `rating <v> <mean> <sd> <center>` ("-" measured as it goes),
  `rnext …`, `racc <games> <sum> <squares> <parts>`. A file without `rating` is from before 2.0: a
  season under way finishes on `RatingScale.V1` (`6.5 + 3.5 × WPA`, clipped to [3, 10]); the next
  season switches and resets every career's rating sum. The match report rates on the season's
  scale (`facts.ratings(scale)`).
- **Aggregate points** (운 보정 승점): a mirrored match gives both sides the same dice, so the side
  with the higher two-game total gets 3 (equal: 1) regardless of the games won. The "운 보정
  순위표" ranks a division by these.
- **Drama** score as in the performance design (comeback, last-turn decision, tiny margin,
  300+, upset, stakes multipliers for finals, title races, derbies = same hometown).

**AiStats** (one per AI for the season, one for the career; fixed size): games; game W/D/L;
match W/D/L; score sum and sum of squares; best/worst; box sums and zeros per box (12 each);
yachts; bonuses; 300+; comebacks; blown leads; close wins/losses (|margin| ≤ 5); giant kills
(beat ≥ 100 rating higher); decider W/L; rating sum and count (average match rating);
aggregate points; 25-point score histogram (16 bins); cost EWMA (ms per game).

**League meta per season**: average points and zero rate per box over all games; trait
prevalence (mistake level, risk θ, yacht taste, belief) among generated/evolved AIs in the top
fifth vs the rest, with the change from the previous season (메타 리포트).

**Records** (`rec` lines, updated in O(1) per game; `RecordKind(label, group, lowerIsBetter,
running, retired)`, `RecordKind.withUnit` gives "245점", "87점 차", "17연승", "레이팅 차 514",
"평점 7.85"): 레이팅 — best rating ever (once a player has 60 games), biggest rating gain in a
season, biggest upset (rating gap a winner overcame); 한 판·한 경기 — best game, biggest margin,
**biggest comeback in points** (`COMEBACK_POINTS`: the winner's largest deficit after a full turn),
lowest winning score, best two-player total; 한 시즌 — most points, **best points per match**
(`SEASON_PPM`, 60% of one's own fixtures), **most sweeps** (`SEASON_SWEEPS`, with the bonus),
best average 평점 2.0 (`SEASON_RATING2`), best average score, best win rate, most yachts; 통산 —
longest winning streak, **games in a row with a Yacht** (`YACHT_RUN`, running; the run going on is
the career `c` line's third field), most games, wins, yachts and 300-point games; 우승·수상 — most
titles, MVPs, 이 주의 선수, youngest and oldest 1부 champion of any league. **Retired** kinds are
decoded and kept, shown as 예전 기록, never broken: `COMEBACK` (a P(win) percentage, saturated at
0), `YACHTS_MATCH` (2 of 2), `SEASON_RATING` (the old scale, "구 평점"). A record breaks only by a
strictly better value (higher, or lower for `lowerIsBetter`). **History**: the 3 holders before
(`Records.history`, `hist` lines); a count its own holder raises stays one entry. A `running`
record makes the news once a season; a record new to an older league settles over its first season
without news (`first` lines).

**Record chase** (`RecordChase.of(stats, plan?, keys?, top = 3)`, ~1 ms at 1000 players): per
kind the nearest challengers (`ChaseLine`: value, pace, record, holder; "기록 페이스 +3점",
"통산 최다 요트까지 4개", "연승 기록까지 2연승"). Paces over the player's own league fixtures from
25% (and 5 matches) played; averages as they stand; careers by what they still need.

**Leaderboards** (`Leaders.kt`, `LeaderStat`): 득점왕 (average score), 요트왕, 보너스왕 (bonus
per game), 완승왕 (2-0 wins, season only), 평점왕 (평점 2.0, 60% of league matches), 행운아/불운
(points minus 운 보정 points), 클러치 (deciders and games within 5 points), 역전의 명수 (wins from
P(win) < 20%), 거인 사냥꾼 (beat 100+ higher), 꾸준함 (score SD, low first), 300점 클럽.
`Leaders.season(stat, plan, stats, keys)` / `career(...)`: all 11 boards in ~1 ms at 1000 players.

**Milestones** (news + 마일스톤 list): career games 1000/5000/10000/…, wins 100/500/1000/…,
yachts 50/100/500/…, first title, every 5th title, 300+ games.

**Season lines** per AI (newest 30 seasons): season, tier, position, points, W/D/L, final rating,
average match rating, and the **league and club** that season — the growth curve (성장 곡선)
and the archive. The `archive 1` header and its 13-digit `sl` lines are unchanged (older builds
read them); one optional `sx <key> <entry>...` line after a key's `sl` line records (season, league
code, club number) wherever they change (5 characters an entry; 6 from season 1296 on). Old
decoders skip it.

**Position history** per division and season: each AI's position after every round (one byte
per AI per round) for the rank-change chart (순위 변동 그래프).

**Honours and awards** per season: per league the champion `L0.<id>` (after its playoff), `REG`,
`L0R`, lower titles `L<t>.<id>`; cup, CL, CCL (`CCL.<CONFED>`), super cup, all-star, family cup,
club cup winners and runners-up; awards: **LMVP.<id>** per league (best average 평점 in its 1부
with 60% of one's own scheduled fixtures, `Season.fixturesOf`), **MVP = 올해의 선수** (the league
MVP with the highest ladder rating), 신인왕, 기량발전상, 최다 요트상, 득점왕. Award news reads
"요트협회가 … 발표했어요"; LMVP has no article (28 a season), the federation page lists them.

**Major titles** (`HonourBook.majorTitles(key, major)`): CUP, CL, CCL.*, SUPER, WCW, legacy `L0`,
and `L0.<id>` only when that league was among the season's 8 best-ranked (`Federation.isMajor`,
the `top` line). Hall of fame (3 major titles, or 5000 games with ≥ 55% wins, or two MVPs),
nicknames ("리그의 제왕") and the evolution mentor count major titles in national seasons; a legacy
close counts every title as before.

**Dynasties**: consecutive titles per competition, doubles (league + cup), trebles (league + cup +
CL or CCL), unbeaten league seasons; listed in 기록실.

**Head-to-head matrix**: from the ladder's pair table; families or the selected division.

All of it lives in `league/stats.txt` (`stats 1`: season and career AiStats, records, history,
meta, rating scale, position history of the current season, the season's best-rated game and
biggest upset (`top`, `upset`, `upsets` lines)) and `league/archive.txt` (`archive 1`: honours of
the newest 100 seasons, season summaries, season lines with `sx`, the reviews, dynasties); 450 KB
or less together at 400 AIs and 100 seasons (StatsTest).

## 4. Highlights, news, the review and match logs

### 4.1 Highlights and the live board
- **Highlights**: the 20 most dramatic games of the season (fixture id, seed, game index, scores,
  tags, drama, the P(win) curve, AI version hashes). Replay re-plays the one game from its seed
  through the same code path, without recording; if the scores differ (an AI changed), the card
  says it cannot be replayed. The archive keeps no highlight lines any more (`hl` lines are dropped
  at the next save); the season's best games live in its review (`best`) and in the match log
  archive (4.5).
- **Live board** (라이브 스코어보드): the newest finished fixtures replayed turn by turn from their
  stored per-half-turn totals (24 pairs per game, the newest 40 fixtures), six at a time;
  `LiveBoard.curveOf(ref)` finds a fixture's curves by its ref (season-tagged).
- **Refs** (`MatchRef(season, comp, id, board, game)`, "season:code:id[:board[:game]]"): every
  ladder feed line carries `#id:board`, every event of a recorded match or tie a `ref`
  (`LeagueEvent.ref`), every article an `@ref` (4.3), so any result, bracket tie, calendar row or
  article opens its report.

### 4.2 News articles (`News.kt`)
Templated Korean headlines and 2-3 sentence bodies (해요체) from events (section 9 lists the
kinds). An article carries its importance (1-5), week, `ref` and **scope** (a league id, a
confederation for a 대륙 챔피언스 section's news, "" for the world and for every article of a
legacy season). `NewsBook` keeps two lanes: **MAJOR** = 120 articles of importance ≥ 3, **MINOR**
= 80 of the rest, each dropping its own oldest (200 in all, newest first across both).
news.txt `a` lines: `... importance [week] [@ref] [#scope]` (older readers read the week and stop).

Digests at the source: national closes and off-seasons name the few most notable and digest the
rest (`league_titles_digest`, `lower_tiers`, `milestones`, `dynasties`, `pupils`,
`learned_rules`, `club_champions`, `club_digest`, `transfer_window`, `market_digest`); a digest
that names everyone it counts says so without "등". NewsVolumeTest: ≤ 60 world events and ≤ 80 in
all per national rollover at 1000 players (measured 39-55 and 59-73).

### 4.3 The news desk (`NewsDesk.kt`)
The weekly desk sits between the events and the book: `offer(r)` after each recorded match (before
the ladder: ratings from before it), `sift(events)` on every event, `close(now)` for every week
before the clock's, `flush` at the season's close. Its pending state is saved after the articles
in news.txt (`desk`, `c`, `h`/`t`, `m`, `q`/`g`, `p`, `r`, `x`, `y`/`z` lines, ≤ 2.1 KB at 1000
players), so a replay after a kill never writes a week twice.
- **League upsets** (`league_upset`): a 1부 or 2부 match won by a player rated ≥ 150 lower; ≤ 2 a
  week (the biggest gaps), importance 3 in a 1부 of the 8 best-ranked leagues.
- **Comebacks** (`comeback`): a league match or knockout tie whose winner won its last game from a
  P(win) ≤ 5%; ≤ 1 a week (the lowest P), never a match already told as an upset.
- **Cup stages**: a stage of more than 8 ties of the 요트컵 or the 대륙 챔피언스 is held to its last
  tie, then released as ≤ 6 single ties (a top-8 seed in it, then the biggest giant killings) and
  one `cup_digest` (giant killings, best seed out); smaller stages are written as they go.
  Giant killings before the 8강 are importance 2.
- **Group stages** of the CL and the CCL: `qualified` and top seeds out are held; ≤ 3 top seeds out
  alone and one `group_digest` a competition.
- **Milestones**: every in-season milestone waits for the week's close: ≤ 2 alone (the top of a
  ladder: 10,000 games, 1,000 wins, 1,000 Yachts; a first 300) and one `milestones_week`.
- **Club race** (`club_race`, national): the 4 best-ranked leagues' club tables, a new leader from
  25% of the 1부 played or the lead down to 3.0 from 70%; ≤ 1 a week.
- **International week** (`intl_week`): after a CL or CCL stage, the leagues that earned the most
  coefficient (points per entry); ≤ 1 a competition.
- **Record chase** (`record_chase`): ≤ 1 a week from `RecordChase.near`, each player and kind once
  a season, records of earlier seasons only.
- `candidates`: the refs of the matches waiting to be picked (the match centre keeps their logs).
Measured at 1000 players: 3-20 articles a week (typically 5-15), 0-4 majors a week outside cup and
close weeks.

### 4.4 The season review (시즌 결산, `Review.kt`, `Archive`)
Built at the close: headline (national: the CL winner, then the champions of the 4 best-ranked
leagues, doubles and trebles), awards, records, meta; and from 평점 2.0 on: `leaders` (top 3 of
득점왕, 요트왕, 보너스왕, 완승왕, 평점왕, 행운아, 클러치, 역전의 명수), `numbers` (경기 수, 평균 점수,
요트율, 보너스율, 300점 경기, 이변, 역전승, 2:0 완승 비율), `best` (the 3 best highlights' ids),
`topRated` (the best-rated official game, by its unbounded rating, with board and game), `upset`
(the biggest rating gap overcome), and later `ranking` (≤ 5 climbers and ≤ 5 fallers),
`federation` (every decided proposal, "‘title’ 가결|부결|무효") and `transfers` (moves abroad),
added by `Archive.amendReview` after the close's resolve and after the club off-season (idempotent).
The archive keeps **6** reviews (3 below 500 members): the newest as the `rv` block an older
build reads, older ones as `rw` blocks; sub-lines `lead`, `num`, `best`, `top`, `upset`, `rank`,
`fed`, `xfer` (older decoders skip them). `Archive.review(season)`, `reviewSeasons`.

### 4.5 Match logs and replays (`MatchLog.kt`, `SeasonMatches.kt`)
- **Recording**: `LeagueMatch.playJob(..., log: LogSink?)` takes each half-turn's keeps and box
  (keep1 5 bits, [keep2 5 bits], box 4 bits, MSB first; ≤ 42 bytes a game, 20-42 typical). It
  draws no randomness and costs nothing without a sink, so results are identical with or without.
  A keep that is not a sub-multiset of its roll is never logged wrong (the board has no log).
- **`GameLogs.turns(rules, deal, bits)`** rebuilds exactly `playGame(watch = true)`'s turns, and
  `MatchLog.turns(rules, game)` checks them against the stored score.
- **Classes** (`LogClass`): FINAL, HIGHLIGHT, BROADCAST, NEWS, ADAPTIVE, KO (saved) and FOLLOWED,
  FRIENDLY (memory only: 200 and 5 kept). A record goes when its last saved class goes.
- **`MatchLogBook`** (`matchlogs.bin`): the current season's block, the previous season's, and an
  archive of single games (the season's 3 best highlights, then the finals) for 30 seasons. Budget
  `caps(members)`: current `clamp(48 B × members, 4 KB, 96 KB)`, previous a quarter of it from
  500 members, archive `clamp(0.6 × members, 92, 830)` bytes a season; eviction order KO →
  BROADCAST (oldest week first) → NEWS → ADAPTIVE → HIGHLIGHT → FINAL. `rollover` at the close
  (idempotent), `dropForeign(season, seed)` drops blocks of another league (seed chain
  `nextSeed(prev, season) = mix(prev, 0x5EA51, season)`, as `Offseason.next`), keeping a block one
  close behind (the app rolls it over at load). File: "YML" 0x01, blocks, records, a trailer of the
  broadcast picks, CRC32; anything unreadable gives an empty book.
- **`SeasonMatches`** rebuilds any fixture of the season exactly as `SeasonPlan.next` handed (or
  will hand) it out: `of(plan, ref)`, `all`, `ofComp`, `ofPlayer(plan, key, index)`, `ofWeek`,
  `find(plan, comp, week, a, b)`; every competition is a `MatchSource`. A report replays a match
  from its log, else re-simulates it (`playJob` on the rebuilt fixture, checked with
  `SeasonMatches.verify` against the stored result), else says why it cannot (the app's match
  centre, `core/MatchCenter.kt`; RunCheck `replay` checks 30/30).

## 5. Season rollover (end of season)

In order, idempotent by season number (a close or open replayed after a kill gives the same files:
NationalWorldTest replays a rollover killed at every file of its save):
1. Title and promotion playoffs (if on) are played as the last fixtures before rollover.
2. **Close** (`LeagueWorld.closeSeason`, branching on the closing plan, never on the next rules):
   - moves and honours per league (2.1.4); a national season stores its league coefficients
     (`Coefficients.season`) and computes the ranking after it (10);
   - the news desk flushes;
   - awards, records, hall of fame, dynasties, season lines, the review, the archive
     (`StatsBook.endSeason(…, club, major, highlights)`); 이 주의 선수 as `POTW` honours;
   - national close events: `league_title` for the 8 best-ranked leagues ("po" after a playoff),
     `league_titles_digest`, `lower_tiers`, `league_moves` for the 4 best-ranked, `season_review`;
   - the federation decides the proposals voted on during the season (11.1) and publishes
     `league_ranking` and one `federation_decision` per decided proposal;
   - evolution (`Evolution.kt`, `aging` on): every generated/evolved player ages a year; it
     retires by an age table (×1.6 for the worst rated down to ×0.4 for the best; at 40 at the
     latest) and grows toward its potential (peak about 28-30, declining from 31-35, by ability);
     retired records are kept, legends for those with honours; the book is brought back to
     `generated` (`childShare` 40% of the free places to pupils, the rest to rookies). National
     closes count major titles and the share of one's own fixtures played.
   - `Closing(season, honours, events, rosterChanged, ruleChanges, ranking)`.
3. The app applies `Federation.applyTo(rules, closing.ruleChanges)` to its rules and saves
   config.txt (after the finished season's save, on the same write queue), then opens.
4. **Open** (`openSeason`, the closing plan and the next rules):
   - national next season: the club off-season (2.2.3, which converts a `clubs 1` book), or a
     founding when there is no book; legacy next season: the old club league's off-season;
   - `Offseason.next` (2.1.5) with the club's play nations, the transfers in and the returned;
   - formation news (`national_reform` at a migration, `league_formed` for a new league);
   - the federation's assembly on the closing season's statistics against the next rules (11.1);
     adopted proposals whose rule the next season does not have are void;
   - the next season's draws (CL, CCL, cup, super cup, club cup, nation cups), its own ranking
     (`SeasonPlan.ranking`) and its `top` line.
5. The season review (시즌 결산) opens once (the app).
6. Legend match: every 3rd season, a legend (archived spec rebuilt) plays the current MVP.

## 6. Odds, preview, scouting, lab

- **Odds** (우승·강등 확률, `Odds.input(plan, rating, leagueId)`): Monte Carlo over one league's
  remaining fixtures (2000 runs) with Bradley-Terry match probabilities from current ratings (draw
  probability from the observed level-match rate, sweep bonus from the plan): P(title or playoff),
  P(international place), P(promotion), P(relegation) per AI. A national league's "champions" is
  a place on next season's international stage: its 1부's 대륙 챔피언스 or 챔피언스 리그 places
  (`stagePlaces`, champion first), from the ranking (by strength without one). A league's title is
  certain as soon as its own playoff final is played. The app computes up to 4 leagues.
- **Match preview** (경기 프리뷰): head-to-head, recent form, predicted win % (Bradley-Terry), each
  side's 주무기/약점 boxes, what is at stake ("한국 1부 순위는 …", "한국 리그 우승 플레이오프
  결승이에요", "아시아 챔피언스 결승이에요", "한국 1·2부 승강전 …").
- **Scouting report** (스카우팅 리포트) before a ranked game: the opponent AI's strongest and
  weakest boxes vs the league average, yacht and bonus rate, comeback/blown-lead rate, risk
  attitude from its family/spec, and a sentence of advice.
- **AI lab** (AI 연구소): any two AIs (including mine and legends); N mirrored matches (200 /
  1000 / 5000 or until ±1%p) run as low-priority jobs in the same workers; results: win share
  ± 95% CI, rating difference with CI, score difference ± CI, P(A stronger), histograms. Lab
  games are not recorded anywhere else. `league/lab.txt`.

## 7. Settings, presets and migration

### 7.1 `LeagueConfig` (`league/config.txt`, `config 3`)
Keys (= field names; `encode()` writes them in this order, `decode()` skips what it does not know
and keeps the constructor's value for what is missing or out of range): divisions (1-6), double,
format, autoSwap (0-5), promotionPlayoff, titlePlayoff, pyramid, groupSize (30-80), groupSwap
(1-8), generated (0-3000), aging, childShare (0-100), baseAis, guests, nationCup, clubs (0, 4-24),
squad (5-8), cup, champions, superCup, allStar, familyCup, longKnockouts, series, doubleElim,
onlyFlawed, onlyTop, and from version 3: **national** (never shown, never a lever), **divisionSize**
(16-24, default 20), **leagueMin** (20-60, 24), **sweepBonus** (on), **transfers** (0-10 per 1000
members, 4), **continentalCl** (on), **clDirect** (0-12, 7), **clubSize** (5-12, 8), **clubCup**
(on), **federation** (on). `valueOf(key)` and `with(key, value)` (null when the value is not one
the rules can take) are what the federation uses. `LeagueConfig()` keeps the legacy structure (the
season in play, the tests); `LeagueConfig.DEFAULT` = national, double round robin (a literal).

### 7.2 Presets
현실 축구형 (`REAL` = `DEFAULT`); 빠른 시즌 (single round robin, SINGLE games, no playoffs, no
long/series/double knockouts, no nation cups, club cup, family cup or federation: about 21 weeks);
대혼돈 (only players with mistakes, 70% pupils, no guest); 강자만 (the 48 best by rating in one 국제
연합 리그 of 3 × 16, no aging, no nation or family cups, no guest).

### 7.3 Migration of an existing league (the flip, rules version 3)
1. The app reads config.txt; a `config 1`/`config 2` file is migrated once (`migrated()`): the
   version-1 step (판 승점제, series, double elimination), then `national = true`, `double =
   matchFormat != SINGLE`, and every key added since national leagues as `DEFAULT` has it;
   everything else stays the league's own (players, aging, guests, cups, `onlyTop`). Saved as
   `config 3`; a v3 file is never migrated again. No config.txt gives `DEFAULT`.
2. The season in play finishes under its own rules (its `cfg config 2` copy decodes as
   `national = false`), as a legacy season: legacy honours, moves and news.
3. Its close is a legacy close (no coefficients; nothing pending to resolve).
4. Its open is national: the `clubs 1` book is converted (`ClubBook.migrate`: Korea keeps its best
   C_KR old clubs with their ids and its members who play for Korea; the rest of the old clubs are
   dissolved; every other member is drafted by its nation; one `club_reform` article), the national
   leagues are formed from the old tiers (2.1.5), `national_reform` is published, and the first
   assembly tables only the structure-free kinds (11.1).
5. The first national season is ranked by strength (all coefficients 0); from its close on the
   federation's coefficients rank the leagues and its proposals apply from the season after next.
A downgrade (an older build reading these files) cannot read `season 5`, `clubs 2` or
`federation.txt` and starts those parts fresh; `archive 1`, honours, stats and the ladder stay
readable (12).

## 8. Screens (Korean labels)

The league screen (`ui/LeagueScreen.kt`) is a slim **control bar**, a **bottom bar of four tabs**
(🏠 홈 · 🎲 경기 · 📊 순위 · 📚 기록), the chip row of the tab shown, and **pages** stacked over
the tabs (at most 8; `core/LeagueNav.kt`). Every menu is a bottom sheet (`Sheet`). All of it is
state in `lg.nav` (tab, chips, filters, pages with their own state, the sheet), so the harness can
set any screen; filters live on across tab switches. Back closes the sheet, then the top page,
then returns to 순위 from another tab, then leaves the league (`core/BackTarget.kt`). The
selector's league (`nav.league`) is the one 홈 · 경기 · 순위 show; `ui.txt` keeps dismissed banners
and the watch pace.

### 8.1 Control bar (`ui/LeagueControlBar.kt`)
← (leave), the status line, ▶ / ■, ⚙ (settings sheet). The status line says the clock and what
runs: "시즌 3 · 12주차 / 42주", "🔴 리그 진행 중 · 요트컵 64강 · 챔스 A조 4차전", "🔴 이번 주만
진행해요 · 120/400경기" (1주씩), "✓ 46주차를 다 치렀어요. ▶를 누르면 47주차를 해요.", "시즌 3
마무리 중", "⏸ 멈춤 · ▶로 계속 진행해요"; a stop the league made itself shows "⚠ {reason}". A tap
opens the weeks sheet (`Sheet.WEEKS`: a row per week, ✓ played, ● in play, the post-season apart).

**Settings sheet** (`Sheet.SETTINGS`, "리그 설정"): players and matches; 진행 방식 (1주씩 / 계속);
속도 (절약 · 보통 · 최대, the pace while running); 앱을 나가면 (멈춤 / 계속, with the notification);
규칙 ("규칙 설정 ›", and "🏛 요트협회 ›" in a national season or the legacy season before the
reform); 선수와 AI (선수 만들기, AI 만들기, AI 연구소); 리그 (the shipped seed league while empty,
리그 초기화).

**League selector** (`Sheet.LEAGUES`, from the header "🇰🇷 한국 리그 ▾" of 홈, 순위 › 리그 and 클럽):
followed leagues first, then 국가 리그 by ranking and 연합 리그 (XAS, XEU, XAM, XAF, then XWO), each
with its tiers, members, ranking place and continental places. The default league: the followed
player's, else a followed club's or nation's, else 한국, else the best-ranked. A legacy season has
one entry.

### 8.2 홈 (`ui/LeagueHome.kt`, `core/HomeFeed.kt`)
At most 1,600 dp at 392 dp wide (the harness fails above), top to bottom:
- **One banner** by priority: 0a an empty league ("▶로 시작해요", the seed league); 0b a season just
  closed (champions, "📜 결산 보기 ›" opens `Page.Review`, until dismissed or two weeks in); 0c the
  first national season ("국가 리그가 시작됐어요", until dismissed); 0d a legacy season with national
  rules next (where the followed players will play).
- **★ 응원**: followed players, clubs, nations and leagues (two lines each: last and next match, form,
  place; "응원 전체 ›" opens `Page.Follows`, each card's ▶/📋/🔮, a club card's "선수단 경기 ›").
- **📺 이번 주 빅매치**: the broadcast picks of the week as cards (▶ played, 🔮 to come), "📺 연속 재생 ›".
- **⚡ 최근 결과**, **📋 내 리그** (the shown league's 1부 top rows, "순위표 전체 ›"), **🌍 국제 무대**
  (CCL and CL where they stand, the ranking's top), a 🏛 line while proposals wait for a vote,
  **🏃 기록 추격** (the nearest record chases), **📰 주요 뉴스**, **🗓 다음 주**, and a footer (counts,
  the clone and ranked-game invitation).

### 8.3 경기 (`ui/LeagueMatchesTab.kt`)
Chips: **이번 주** (the week's stages with played/total, live matches, late stages, the week's top
news, next week; rows open their competition), **결과** (the feed newest first, "필터 ▾": 전체 /
★ 응원 / 리그 / 대회 / 친선; a card opens its report, ▶ replays it), **중계** ('📺 이번 주 중계': the
week's picks, "★ 내 선수 경기 n", "다음 주 빅매치", then the live scoreboard replaying finished
matches turn by turn, six at a time), **하이라이트** (이번 시즌 / 역대, by drama; ▶ plays the game).
A match row's ▶ shows only where the match can be played again (UX 3.3: its log is kept, or it is
this season's official match and neither side adapts).

### 8.4 순위 (`ui/LeagueTablesTab.kt`, `ui/LeagueComps.kt`, `ui/LeagueClubs.kt`, `ui/LeagueWorldPages.kt`)
Chips: **리그** (the league header and its tiers; "보기 ▾" `Sheet.TABLE_MODE`: 순위표, 최근 폼, 운 보정,
라운드, 순위 변동, 상대 전적, 확률; the "완승" column under 완승 보너스; promotion, playoff and relegation
marks per league; a row opens the player), **대회** (a card per competition: where it stands, its
stages with weeks; a card opens `Page.Competition`: "단계: … ▾" `Sheet.STAGE`, the week strip, the
groups (each "이 조 경기 ›") or one bracket round, the national cups' notes and squads), **클럽** (the
league's F1-style club table with this week's W-D-L, every club of its nations by titles,
"이적·드래프트 ›" `Page.Market`; a legacy season shows its club league), **랭킹** (the league
ranking: coefficients of five seasons, the change, continental places; "대륙 ▾" `Sheet.CONFED`;
"🏛 요트협회 ›"; "국가대표 랭킹 ›" `Page.Nations`), **레이팅** (the rating ladder, search, "필터 ▾"
`Sheet.LADDER_FILTER`: 응원, origin, nation, family).

### 8.5 기록 (`ui/LeagueRecords.kt`, `ui/LeagueLeaders.kt`, `ui/LeagueReview.kt`)
Chips: **리더** (the hub: 득점왕, 요트왕, 평점왕, 완승왕, 보너스왕, 역전의 명수, 클러치, 거인 사냥꾼 with
their top 3, then 다른 부문; "필터 ▾" `Sheet.LEADER_FILTER`: 이번 시즌 / 통산 / 최근 10경기 and 내 리그 /
전체 / 국가 / 클럽, 1부만; a card opens `Page.Leaderboard`), **기록실** (this season's new records, every
record by group with its unit and its earlier holders, 예전 기록 folded, the chases, record news),
**전당** (seasons by honours per league, the hall of fame, dynasties, most titles, "📜 연대기 ›"),
**결산** (the kept season reviews; each opens `Page.Review`: Season Wrapped as swipeable cards
(champions and MVP, the best-rated match with ▶, the biggest upset, records broken, ranking moves,
followed players), then leaders, numbers, the 요트협회's decisions, transfers), **더 보기** (탐구 `Page.Facts`,
연대기, 유망주, 레전드 보관소, 계열별, 메타 리포트, 선수 비교, AI 연구소, "🏛 요트협회 기록").

### 8.6 Pages
- **Player** (`Page.Player`, `ui/LeaguePlayerPage.kt`): header (flag, name, club badge, "{리그}
  {n}부 {m}위" opening its table, rating and tier, ★ 응원, ⋯ `Sheet.PLAYER_ACTIONS`: 비교, 관전,
  연구소, 랭크전, 고치기), tabs 개요 (identity, abilities, tendencies, style radar and label, rival,
  rating trend), 기록 (stat cells with league places, each opening its leaderboard; seats, game 1/2,
  score histogram; 이번 시즌 / 통산), 경기 (this season's league and competition matches with ▶,
  last season's kept games), 수상 (titles by major first, awards, places, club honours).
- **Match report** (`Page.Match`, `ui/LeagueMatchReport.kt`): sides with badges and ratings, the
  score by game (boards for a team match), 경기 MVP and graded 평점 2.0 (≥ 8.0 월드클래스 blue, ≥ 7.0
  green, < 5.0 red), the P(win) curve per game, key moments, on demand 정확도 ("정확도 96.2% · 실수
  2개 (−7.4점)", computed off the main thread from the decision log), the articles about the match;
  before it is played a preview (expected chances, stakes from the table and odds). The bar's ▶
  plays it again; the replay source is told (기록 / 다시 계산).
- **Watch screen** (`ui/WatchScreen.kt`, `Screen.WATCH`): the game throw by throw with the
  broadcaster's lines (match, game, between games, the end), both score cards, the P(win) bar,
  pause/next turn/next game, a board and game picker for team matches and series, 요약 중계
  (the last turns only), pace 느리게 / 보통 / 빠르게 (saved); the channel plays the week's picks one
  after another with a countdown, waiting "곧 시작해요" for a pick still to be played.
- **Club** (`Page.Club`): badge, league and place in its club table, budget, titles, this week, the
  squad (✈️ playing abroad), recent results, "선수단 경기 전체 ›" (`Page.Matches`), club records and
  moves. **League** (`Page.League`): its tiers and champions, ranking line, statistics per tier with
  one sentence of style meta, clubs, history. **Nation** (`Page.Nation`): the national team, its
  league, cups, core squad, most capped, legends, its players' numbers.
- **Matches** (`Page.Matches`, `MatchQuery`): this season's matches of a club's squad, a group, a
  week or a league round; played ones newest first, then the ones to come (at most 150 rows);
  each row: the sides with the games (boards) won, a league match's two game scores under them; it opens its
  report, ▶ where it can be replayed.
- **News** (`Page.News`, `ui/LeagueNews.kt`): filters 전체 / 주요 (importance ≥ 4) / 리그 / 국제 /
  이적·협회, "☆ 전체 / ★ 응원만" in the bar; a lead article on top, then articles by week under week
  headings; digests (cup rounds, groups, milestones) as one card with "+n명"; an article with a match
  has "📋 리포트" and ▶; the international week's table only its newest per competition. The league
  notification's tap opens 홈.
- **요트협회** (`Page.Federation`): this season's agenda with evidence and 찬성/반대 (a vote applies at
  the close; without one the 선수 위원회 votes), past decisions, the awards of the last season, a
  line when the federation is off. Also from 순위 › 랭킹, the settings sheet, the rules page and 기록 ›
  더 보기.
- Others: `Page.Follows` (every followed entity), `Page.Leaderboard` (a whole board: the first 50 and
  followed players further down, its rule line), `Page.Review`, `Page.Facts`, `Page.Chronicle`,
  `Page.Compare`, `Page.Rules(part)` (the rules editor as a page, 취소/저장; "기본값으로" → DEFAULT;
  presets), `Page.Lab`, `Page.Market`, `Page.Nations`, `Page.Prospects`, `Page.Legends`,
  `Page.Families`, `Page.Meta`.

### 8.7 What the screens cost
The 300 ms snapshot (`LeagueController.publish`) carries only the shown league's tables, 60 news
articles, the feed and the clock (no club squads; competition views are cached by a result
counter). Every page loads its data on request off the main thread under the league's lock and
keeps a few by key (`WorldPages.KEEP`); reports, re-simulations, 정확도, leaderboards and the home
are never built while nothing asks for them. Compose state is written on the main thread (the
watch screen's steps too: `MatchCenter.onScreen` hands them over).

## 9. Framework (implemented) and module boundaries

Framework files (engine/league): `Config.kt` (LeagueConfig, presets), `Fixture.kt` (Comp,
Fixture, JobKind, BoardResult, JobResult, TieRule), `Facts.kt` (GameFacts: curve, ratings,
comeback, `RatingScale`), `WinEstimate.kt`, `Calendar.kt` (round robins), `Leagues.kt` (LeagueDef,
Leagues, LeagueIds, LeagueRank, LeagueRanking), `HonourKind.kt`, `Season.kt` (league stage by
rounds and weeks, positions, 운 보정 points), `Competition.kt` (Competition, CompetitionCodec,
SeasonStart, Honour, LeagueEvent), `SeasonPlan.kt` (scheduling, post-season, save format
`plan 1` with `comp <code>` ... `end` sections), `SeasonCalendar.kt` (weeks, lanes),
`NationalLeagues.kt`, `Pyramid.kt` (tables, crossing per league; the legacy pyramid),
`Offseason.kt` (honours, moves, next divisions, next season from factories), `Playoffs.kt`,
`Honours.kt` (HonourBook), `Clubs.kt`, `ClubTable.kt`, `Transfers.kt`, `Champions.kt`
(ChampionsBracket, ChampionsLeague, International), `ContinentalChampions.kt`, `TeamCups.kt`
(TeamCup, FamilyCup, ClubCup, AllStar, ClubSource), `Coefficients.kt` (CoefficientStore),
`Federation.kt`, `Stats.kt`, `Records.kt`, `Leaders.kt`, `Archive.kt`, `Review.kt`, `News.kt`,
`NewsDesk.kt`, `Highlights.kt`, `MatchLog.kt`, `SeasonMatches.kt`, `LeagueMatch.playJob` (MATCH, TIE
with legs and deciders, TEAM with boards, an optional `LogSink`), `LeagueWorld.kt` (the world: every
book, recordJob, close, open, files). The app runs it in `LeagueController` (factories, postFactories
and codecs are the hook lists the modules fill).

Conventions for modules:
- A competition implements `Competition` and `MatchSource`, provides a factory
  `(SeasonStart) -> Competition?` (or `(SeasonPlan) -> Competition?` for the post-season) and a
  `CompetitionCodec`; it returns null for a season it does not belong to (legacy or national).
- Seeds: `DiceStream.mix(start.seed, comp.code.code.toLong(), salt...)`; fixture ids unique
  within the competition.
- A knockout tie is one `JobKind.TIE` fixture with `a` = the higher seed (it goes through when
  still level after the deciders); two legs = `legs = 2`; a series has `firstTo = N` and
  `legs = N + 1` (the most pairs before deciders).
- Encode in-progress fixtures as unplayed.
- Events (`LeagueEvent.kind`), keys first then args (and a `ref` for a recorded match):
  `upset` [winner, loser] (rating gap) · `comeback` [winner, loser] (lowest P%) ·
  `record` [holder] (record name, value, previous) · `milestone` [ai] (kind, value) ·
  `title` [ai] (division index or comp) · `promoted`/`relegated` [ai] (from, to) ·
  `cup_round` [winner, loser] (round label; an underdog win is a giant killing) ·
  `eliminated` [ai] (round) · `qualified` [ai] (stage) · `final` [winner, runner-up] (score) ·
  `transfer` [ai, fromClub, toClub] (fee) · `draft` [ai, club] (pick) ·
  `born` [child, parentA, parentB] · `retired` [ai] (seasons, titles) · `award` [ai] (kind) ·
  `callup`/`dropped` [ai] (family) · `title_race` [leader, second] (gap[, league]) ·
  `hall_of_fame` [ai] · `dynasty` [ai] (kind, count) · `legend_match` [legend, opponent] (result).
- National and club kinds: `league_title` [champion] (league, "0", "po"|"", rank) ·
  `league_titles_digest` [champions] (count, "league:key"…) · `league_moves` [ups…, downs…]
  (league, ups, downs) · `lower_tiers` · `season_review` · `league_ranking` [] (3 best, climber,
  climb) · `national_reform` [] (national, regional, ids…) · `league_formed` [] (league, tiers,
  members) · `draw_ccl` [entrants] (section) · `draw_clubcup` [clubs] (group) · `club_title`
  [club] (league, points) · `club_champions` · `club_reform` · `club_founded` · `club_dissolved` ·
  `club_digest` · `transfer_abroad` [ai, from, to] (fromLeague, toLeague, fee[, star]) ·
  `return_home` · `transfer_window` · `youth` · `market_digest` · `federation_assembly` [] (count,
  voting season, applies from, titles…) · `federation_decision` [] (title, A|R|V, U|C|-, yes, no,
  applies from) · digests `milestones`, `dynasties`, `pupils`, `learned_rules`.
- Desk kinds (4.3): `league_upset`, `club_race`, `intl_week`, `cup_digest`, `group_digest`,
  `milestones_week`, `record_chase`.

## 10. League coefficients and the ranking (리그 계수, `Coefficients.kt`)

- A season's **entrants** are those of its 챔피언스 리그 and 대륙 챔피언스. Each league is credited
  with the points its entrants earn **in the league they played in that season** (a transfer counts
  for its new league): group match won 2 / drawn 1, knockout series won 2, reached the knockout
  +1, the last four +1, the final +1, the title +1, CL participation +2.
- `Coefficients.season(plan)`: league id → [points, entries] for **every** league of a national
  season ([0, 0] without entrants, so "existed without entrants" differs from "no line").
  Season value `c = points / entries` (0 with no entries).
- Ranking coefficient `K = Σ c` over the 5 newest closed seasons; a season without a line inherits
  (a nation from its 연합 리그, a 연합 리그 the mean of its confederation's nations; else 0).
- `Coefficients.ranking(store, leagues, closed, strength, config)`: K descending, then the newest c,
  then strength (the average rating of a league's best 5), then id. `LeagueRank(id, rank,
  confedRank, coefficient, seasons, strength, cclSlots, clDirect = rank < config.clDirect, change,
  confed)`. In the first national season every K is 0: the ranking is by strength.
- **Which ratings**: the strength reads the ratings the season was drawn with (the 요트컵's seeds,
  else stats.txt's start ratings, else the ladder's whole points), so the ranking published at a
  close, the ranking the next draws use, and a ranking recomputed after a restart agree, and a
  league's climb counts from the ranking its season was played under. The next season's own
  ranking (`SeasonPlan.ranking`) is computed again over its own leagues and seeds; leagues level on
  coefficients can then sit in another order near ranks 8-14 right after the first national season.
- Storage in `federation.txt`: `coef <season> <league> <points> <entries>` (newest 10 seasons) and a
  `top <season> <ids>` line for every national season (its 8 best-ranked leagues: the major titles).
  The raw sums are kept, so the formula can change.
- Uses: next season's CCL slots and CL direct places, the transfers' destinations, the committee,
  the 8 leagues of the close news, `isMajor`.

## 11. 요트협회 (yacht federation, `Federation.kt`)

### 11.1 Timeline
- **Resolve at the close** of season n (closing state only): the proposals P(n − 1) are decided. A
  user's choice (찬성/반대 in the app, `Federation.choose`) decides; the rest go to the 선수
  위원회's vote. Adopted ones go to `Closing.ruleChanges`; with the federation off they are void.
- The app applies them to its rules and saves config.txt; then the open.
- **Assemble at the open** after season n, against the rules season n + 1 really uses: P(n) is
  built from season n's statistics (`FederationStats.of`), voted during season n + 1 (투표 중),
  decided at its close, and applies from season n + 2 ("시즌 3 동안 투표를 받아 시즌 4부터
  적용해요"). Tabled only when the rules to come are national and the federation is on; every open
  records the season's statistics either way.
- **First assembly** at the migration open: only the structure-free kinds (SWEEP_*, LONG_KO_*),
  whose statistics a legacy season has; a legacy season's numbers never speak for national rules.
- Both steps are idempotent (`resolved`/`assembled` guards; a stored P(n) counts as assembled):
  a close replayed after a kill gets the stored adopted list, and `applyTo` skips a change already
  in the rules, in either order of the kill (config.txt or the league files written first).

### 11.2 Levers and kinds
The federation can change exactly six rules (`Lever`): `autoSwap` (자동 승강 인원), `titlePlayoff`
(우승 플레이오프), `sweepBonus` (2:0 완승 보너스), `divisionSize` (부 인원), `longKnockouts` (긴
토너먼트), `clDirect` (챔피언스 리그 직행 리그 수). Never the match format, the population keys or
`national`, and never the dice scoring rules (not part of `LeagueConfig`; the AI tables are
precomputed for them). FederationTest pins the six keys and that no engine file but Federation.kt,
Config.kt and LeagueWorld.kt names `Lever`. Twelve `ProposalKind`s, two directions per lever:
SWAP_DOWN/UP (autoSwap ∓1), TITLE_PO_OFF/ON, SWEEP_OFF/ON, SIZE_DOWN/UP (divisionSize ∓2),
LONG_KO_ON/OFF, CL_DIRECT_UP/DOWN (clDirect ±1).

### 11.3 Statistics (`FederationStats.of(closed plan, honours, federation, next rules, gone)`)
Raw counts, kept as the `fs` line: the elevator (members promoted into a 1부 last season who
played it, and of those the relegated now; none after a legacy season), `champPpm` (2부
champions' points per match minus their 1부's median, averaged over leagues), title playoffs whose
regular-season leader did not win (`REG` vs `L0`), runaway leaders (≥ 8 points clear), decided
league matches and their 2-0 sweeps (two-game formats only), the rounds R, the fixtures at
divisionSize + 2 against the old pyramid's, 요트컵 ties from the winners' 8강 on and those won by
the side rated ≥ 100 lower at the draw, and the 챔피언스 리그 points per match of the entrants
who came as league champions against those from the 대륙 챔피언스 (`clEdge`), plus the six lever
values the season was played with (`lv`). One pass, 15-55 ms at 3000 players.

### 11.4 Generators and their calibration
Each kind has a guard (the rules can take the change, a sample: ≥ 10 promoted, ≥ 8 playoff leagues,
≥ 8 leagues, ≥ 10 cup ties, ≥ 12 CL matches a side, ≥ 30 decided matches; a world of the four 연합
리그 alone, below about 1000 players, never reads its title playoffs on 4 leagues), a statistic x, a floor f
and a threshold t. Past its floor it is a candidate with `pressure = min(3, 0.3 + 0.7 (x − f) /
(t − f))`: 0.3 at the floor, 1.0 at the threshold (t < f for "lower is worse"). An assembly:
candidates → drop a lever with a pending proposal, or **whose value differs among the season to
come, the season played and the one before** (changed in either direction in the last 2 seasons,
by the federation or by hand), or whose change the rules cannot take → one per lever (the higher
pressure) → by pressure, then kind order → the first **2**, and a third at pressure ≥ 0.6. None:
"이번 총회는 규칙을 그대로 두기로 했어요". The evidence always carries the real numbers.

**Calibration** (S20, `FederationCalibration`, opt-in: `FEDERATION_CALIBRATION=1`; the CSVs are in
the S20 hand-off): real games through `LeagueWorld`, DEFAULT at 1000 generated players, about 40 s
a season on 4 cores. Run A held the default rules for 10 seasons (league seed 5), run C held
autoSwap 3, no title playoff and short knockouts for 6 (seed 11), runs B and D let the federation
and its committee change the rules for 10 each (seeds 7 and 13). Per season:

| Statistic | Default rules (A, B) | Other rules (B, C) | Notes |
|---|---|---|---|
| elevator | 15-40%, median 23% (autoSwap 2) | 28-47%, median 36% (autoSwap 3) | it differs from league to league (A 15-30%, D 17-40%) |
| champPpm | +0.8 to +1.0 | +0.8 to +0.9 | a 2부 champion plays weaker opposition: the SWAP_UP guard always holds |
| leader lost the playoff | 50-86% of 28, mean 70% | | a 4-player playoff barely favours its top seed |
| runaway leader | 14-46%, mean 29% | 18-39% | the same with or without a playoff |
| 2-0 among decided | 95.8-96.8% | the same | a decided match that is no 2-0 needs a drawn game |
| rounds R | 42 | 42 | |
| 요트컵 upsets from the 8강 | 0-6 of 22, none in 13 of 30 | 0-1 of 22 with short series after the first season (C: 6, 1, 0, 0, 0, 1) | rating gaps of 100 are rare among the last 8 |
| clEdge | −0.54 to +0.38 | −0.51 to +0.65 | about 50-60 CL matches a side: SD about 0.3 |

The floors are set so that each statistic that tells seasons apart passes its floor in about a
third of the seasons under the rules it argues against, and its threshold only in an unusual one:

| Kind | Floor → threshold | Past the floor |
|---|---|---|
| SWAP_DOWN | 38% → 50% | 1 of 20 at autoSwap 2 (D); 2 of 7 (B) and 2 of 5 (C) at autoSwap 3 |
| SWAP_UP | 20% → 12% | 4 of 9 (A), 1 of 9 (D) |
| TITLE_PO_OFF | 78% → 90% (22 of 28 leagues) | 2 of 10 (A), 2 of 6 (B), 0 of 10 (D) |
| TITLE_PO_ON | 33% → 50% (10 of 28) | the statistic 3 of 10 (A) and 3 of 10 (D) with a playoff; 0 of 4 (B) and 1 of 6 (C) without |
| SWEEP_OFF / SWEEP_ON | 99% → 100% / 52% → 45% | never: out of reach under 판 승점제 |
| SIZE_DOWN / SIZE_UP | 46 → 48 / 36 → 34 weeks | never at D = 20 (the dead band 37-45 by design) |
| LONG_KO_ON | 9% → 25% (2 of 22) | rare with short series |
| LONG_KO_OFF | no upset at all, pressure 0.3 (the threshold lies below zero) | 13 of 30: a standing argument, never adopted in B and D |
| CL_DIRECT_UP / DOWN | +0.30 → +0.65 / −0.30 → −0.65 | 2 of 32 / 8 of 32 |

On run A's ten seasons the assemblies table 0, 2, 0, 0, 2, 1, 2, 1, 1, 1 proposals at pressures
0.30-0.74 (FederationTest pins the floors and these seasons). With the federation deciding (run B,
before TITLE_PO_OFF's floor moved from 72% to 78%): 2, 1, 2, 1, 2, 2, 0, 1, 0, 1 tabled, 3 of 11
adopted (autoSwap 2 → 3, the title playoff off, clDirect 7 → 6), no kind tabled every season and no
lever in more than half. Run D (the final floors): 1, 0, 0, 1, 2, 1, 0, 2, 0, 2 tabled (LONG_KO_OFF 4
times, CL_DIRECT_DOWN 3, SWAP_UP and SWAP_DOWN once each) and none of the 7 decided adopted. So a
typical assembly tables one or two proposals and a quiet season none (at least one in 6-8
assemblies of 10, two or more in 3-4); the committee passes a proposal at 0.3 about 1 time in 5.5
and one at 1.0 about 3 times in 4, so with the typical pressures of 0.3-0.8 a rule changes every
few seasons (3 of the 18 proposals decided in B and D).
ENG 6.4's target of two proposals in 80% of the assemblies is not met on purpose: with four
statistics that tell seasons apart and none past its floor in more than half the seasons, two or
more come in about 30-40%; more would need floors inside the typical range of each statistic, so the
same kinds would fire every season (as a migrated save showed before the calibration: every
proposal at 1.6-2.8 and a rule changed every season). The sweep share and the 8강 upsets hardly
tell one season from another; a statistic that does (the 2-0 share of all league matches, upsets
from the 32강 on, a smaller rating gap) would make those two levers live.

### 11.5 Committee vote, application, hard rule
- **선수 위원회** (`Federation.committee`): the best-rated member of each of the 9 best-ranked
  leagues (unranked by strength), then the best-rated others up to 9. Seat i votes yes when
  `Random(mix(seed, 0xFED0, season × 16 + number, i)).nextDouble() < clamp(0.25 + 0.35 × pressure,
  0.1, 0.9)` with the closing plan's seed; 5 adopt. A choice by the user decides (`by = U`).
- **`Federation.applyTo(rules, adopted)`** in proposal order, only on lever keys: a rule already at
  its new value is a replay (no change); a rule no longer at its old value (changed by hand since
  the assembly) is **void** (무효: "규칙이 이미 바뀌어 적용하지 않았어요"), as is a value the rules
  cannot take; LeagueWorld marks such proposals void at the open (`markVoid`).

### 11.6 `federation.txt` (`federation 1`, ≤ 12 KB)
```
federation 1
meta <assembled> <resolved>
coef <season> <leagueId> <points> <entries>          (newest 10 seasons)
top <season> <ids csv>                               (every national season, 8 ids)
up <season> <keys csv|->                             (the last closed season's promoted into a 1부)
fs <season> national=1,elevator=b/p,champPpm=x,leaderLost=k/n,runaway=k/n,sweep=k/n,rounds=R,projected=..,legacy=..,koUpsets=k/n,clChamp=p/m,clCcl=p/m,lv=a/b/c/d/e/f   (10 seasons)
prop <season> <number> <kind> <key> <from> <to> <pressure %.2f> <choice> <result> <by> <yes> <no> <args joined by '|'>   (newest 30 and every pending one)
```
Written after clubs.txt; a legacy league writes one too (the statistics only). A missing or
unreadable file is an empty federation (no coefficients; the first national season ranks by
strength).

## 12. Files

`league/` (the app's `GameStorage`), written by `LeagueWorld.encodeFiles` in this order so that a
kill between any two files replays the close to the same result: honours.txt, archive.txt,
stats.txt, roster.txt, clubs.txt, federation.txt, highlights.txt, news.txt, lab.txt, nations.txt,
ladder.txt, season.txt; then `matchlogs.bin` (the match centre). Also config.txt, runmode.txt,
custom.txt, favourites.txt (`club:<id>`, `league:<id>`, `nation:<code>` too), prefs.txt, ui.txt.

| File | Header | Notes |
|---|---|---|
| season.txt | `plan 1` with `season 5` (national) / `season 4` | the season, every competition's block (`ccl 1`, `clubcup 1`, `titlepo 2`, `promopo 2`, ...), `cfg`/`closecfg` rules copies |
| clubs.txt | `clubs 2` (`clubs 1` read) | 2.2.5 |
| federation.txt | `federation 1` | 11.6 |
| archive.txt | `archive 1` | `sl` + optional `sx` lines, `rv` + `rw` reviews, no `hl` lines |
| stats.txt | `stats 1` | rating scale lines, records with `hist`, `top`/`upset` trackers |
| news.txt | `news 1` | `a` lines with `@ref`/`#scope`, then the desk's lines |
| ladder.txt | as before | feed lines with `#id:board` |
| matchlogs.bin | "YML" 1 | 4.5; the budget bounds it |

Backups carry every file (`ui.txt` and `matchlogs.bin` too); a restore of an older backup deletes
the files it lacks. An older build reading these files keeps its honours, stats, archive lines and
ladder, and starts the season, clubs and federation fresh (it cannot read `season 5`, `clubs 2`,
`federation 1`).

## 13. CPU and save size (measured)

Measured by S20 on the merged tree (S1-S18) through the app's real controller on a 4-core server
shared with other builds. **RunCheck `size`** plays new leagues of the app's default rules from a
fixed seed for 3 seasons at FULL speed, stopping after each close, and reads the folder after the
close's saves; it also times a fixed reference workload, since the shared machine's speed drifts
(its `ref` was 3.05-3.20 s when the baseline was recorded and 3.3-4.9 s now). The baseline
(`uicheck/src/main/resources/size_baseline.txt`) was recorded on the 945de5e engine at 200 and
1000 players. It has no 3000-player league, so the same RunCheck on the 945de5e engine (S2's tree)
was run again at 3000, and at 1000 for a same-day comparison.

| | 200: baseline → now | 1000: baseline → now | 3000: 945de5e today → now |
|---|---|---|---|
| folder after 3 seasons | 478,915 → 407,905 B (−15%) | 2,191,785 → 1,580,207 B (−28%) | 6,675,303 → 4,902,474 B (−27%) |
| 30-season projection | 666,355 → 693,985 B (+4.1%, see below) | 2,770,875 → 2,320,697 B (−16%) | 8,198,853 → 6,580,824 B (−20%) |
| CPU a season (process) | 66.0 → 57.6 s (−13%) | 176.0 → 150.2 s (−15%) | 673.2 → 498.6 s (−26%) |
| CPU per reference | 20.6 → 13.0 (−37%) | 57.7 → 35.3 (−39%) | 162 → 118 (−27%) |

- **Against ENG 8** (the design's estimate: −25% of the match work a season at 1000 players, −18%
  at 3000): the 945de5e engine measured the same day took 220 s a season at 1000 players (ref
  4.54 s), so the new one is −32% in seconds and −27% per reference there, and −26% / −27% at 3000.
  The first season of each run is the dearest (the AIs and their tables are built: 161 / 589 s new,
  264 / 787 s old at 1000 / 3000).
- Where the bytes went at 1000 players: ladder.txt −689 KB (about 20 league opponents twice
  instead of about 49 once: fewer pairs), clubs.txt +20 KB, archive.txt +25 KB (the `sx` lines and
  the kept reviews), matchlogs.bin +14 KB, news.txt +9 KB (refs, scope, the desk's lines),
  federation.txt +2.5 KB, roster +5 KB. At 3000: ladder.txt −1.92 MB, archive.txt +59 KB, clubs.txt
  +55 KB (ENG 8.2: about 62), matchlogs.bin +26 KB, news.txt +10 KB, federation.txt +2.5 KB,
  honours.txt −9 KB (28 leagues' titles instead of the pyramid's groups); season.txt after the open
  is the same size (185 KB).
- ladder.txt is not in the projection (RunCheck counts archive.txt, honours.txt and matchlogs.bin):
  its pair table grows while new pairs meet, now about half as fast (1000 players: +129 then
  +82 KB a season, 945de5e +268 then +242 KB; 3000: +488 then +408 KB, 945de5e +955 then +723 KB).
- **The 200-player projection** (the folder after season 3 plus 30 times season 3's growth of
  archive.txt, honours.txt and matchlogs.bin) is above the gate's +3% margin. The growth it
  extrapolates is not a steady one: archive.txt grows 6.5-6.7 KB a season while its kept reviews
  fill up (3 below 500 members, about 2 KB each; 945de5e kept one), then 3-4 KB like the
  baseline's 3.2 KB. Played for 6 seasons (RunCheck with `SIZE_SEASONS` 6, a local change, on both
  engines): archive.txt grows +6.5, +6.7, +3.9, +3.6, +2.9 KB (945de5e +4.3, +3.1, +3.0, +3.7,
  +3.2 KB); after season 6 the folder is 451 KB against 546 KB (−17%) and the projection from
  season 6 579 KB against 691 KB (−16%); the CPU 88, 62, 59, 50, 49, 47 s a season (ref 4.11 s;
  945de5e 83, 64, 59, 56, 63, 56 s, ref 3.93 s).

**Stress** (`stress/`: the app's controllers on a plain JVM under a 512 MB heap, a person's ranked
games, watching, the league's pages, their AIs, backups and going to the background at random
while the league plays at FULL; `stress ../tables <minutes> <seed> <players> FULL`):

| | 1000 players, 15 min (seed 1) | 3000 players, 25 min (seed 2) |
|---|---|---|
| seasons, failures | 10, none | 4 (the first at 1000, then grown to 3000), none |
| a season: wall / process CPU / matches | 50-136 s / 181-359 s / 19.5-20.7k | 252-284 s / 649-693 s / 64-67k (the season that grew the league: 683 s / 1,754 s) |
| heap: peak / live after a GC | 372 / 99 MB | 462 / 148 MB |
| lock held to record a match (avg / max) | 0.29 / 250 ms | 0.30 / 244 ms |
| lock held for a rollover | 0.44 / 0.74 s | 1.31 / 1.98 s |
| lock held to save | 0.11 / 0.51 s | 0.19 / 0.51 s |
| lock held for a snapshot | 12 / 133 ms | 26 / 446 ms |
| season.txt at its largest | 756 KB | 2.48 MB |

- v4's stress (DESIGN.md, the 945de5e engine): 7 seasons in 15 min at 1000 players, 8 in 20 min
  at 3000, live heap 72 / 150 MB; at 3000 a rollover 0.55 / 0.66 s, a save 0.10 / 0.24 s, a
  snapshot 12 / 74 ms. The seasons at 3000 took longer here on a busier machine (load 5-9 from
  another stage's builds; RunCheck's same-day comparison above is the fair one), but the rollover
  and the snapshot under the lock are about twice v4's at 3000 players.
- **season.txt** at its largest (just before the close): 756 KB at 1000 players (the `r` lines
  550 KB: 19.5k results at 28.2 B; `pos` 106 KB, `standing` 50 KB, the knockouts' and playoffs'
  `e` entries 17 KB) and 2.48 MB at 3000 (`r` 1.87 MB for 65.2k results, `pos` 367 KB, `standing`
  155 KB, `e` 54 KB). That is below today's 0.95-0.98 MB and about 2.8 MB (−20% to −23% and −11%)
  but 8-10% above ENG 8.2's 0.69 MB and 2.3 MB, which counted the league results at 27 B and left
  out the cups' results and the `e` lines.
- No exception in either run. The harness reports state written off the main thread by the match
  centre's step publisher (`MatchCenter.publishSteps`, `show`, `waits` on a background
  dispatcher: 68 and 130 writes) besides the controllers' constructors, and one main-thread call
  of 366 ms at 1000 players that the harness then named after the last labelled action (it now
  names every action; a rerun of the same 15 minutes, seed 1, had no call over 100 ms: 10 seasons,
  no failure, live heap ≤ 89 MB, rollover 0.42 / 0.68 s, snapshot 9 / 84 ms).
- The federation through the app (federation on): at 1000 players the ten assemblies tabled 0, 1,
  1, 0, 1, 0, 1, 2, 2, 2 proposals and the committee adopted 2 of 8 (clDirect 7 → 8, the title
  playoff off); at 3000 the season after the league grew from 1000 pressed SWAP_UP at 1.49 (5 of
  78 promoted went back down) and adopted autoSwap 3.
