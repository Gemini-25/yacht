# League v3 — implementation spec

Internal developer spec (English) for the AI league features the user chose. The user-facing
design summary lives in `DESIGN.md` (Korean). Every number below is a default that
`LeagueConfig` can change unless it says otherwise.

Ground rules for all code:
- Engine code (`engine/src/main/kotlin/yacht/engine/league/`) is pure Kotlin: no threads, no
  Android, deterministic given seeds. The app's `LeagueController` owns threads and the one lock.
- Save formats are line-based text in the style of `Ladder.encode` / `Season.encode`: a versioned
  header line, one record per line, fields separated by spaces, inner separators `,` `;` `:` and
  `-` for empty. Decoders skip unknown or short lines and never throw on old files (a file that is
  not usable at all throws so the caller starts fresh). Files are written with
  `writeTextAtomically` (app core).
- Every fixture has a seed `DiceStream.mix(leagueSeed, season, compCode, fixtureId)`, so a result
  is a pure function of the fixture: after a kill, unrecorded fixtures are replayed with the same
  seed, and highlights replay a game from its seed.
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
- **Made players** (`origin = CREATED`, keys `u1`, `u2`, ... never given twice; `made N` line):
  선수 만들기 (`PlayerMaker`, `PlayerDraft`): name, gender, nation, hometown, age, abilities,
  potential, feel or slips, temper kind, style (recipe, k 0.7..1.7, second style, overlay; a
  drawn or edited player's tastes and situational habits kept), and `keep` (영원히 현역: no
  aging, growth, decline or retirement; saved as `keep=1`). Abilities apply as for generated
  players ([Profiles.apply]). Any league player can be remade the same way (its records,
  debut, parents and peak stay); only made ones can be deleted (they leave as deleted AIs).
- **Mine** (`origin = MINE`): the clone and custom AIs.
- `league/roster.txt`: alive players with identity and style tokens; `retired` lines (newest
  3000, with nation, gender and ages) and `legend` lines (any honour, at most 300).

## 2. Structure of a season

### 2.1 Individual league (the pyramid)
- **Linear** (six groups or fewer of `groupSize`, or `pyramid` off): `divisions` (default 4)
  balanced divisions over all AIs except me. ~205 AIs → 4 × 51.
- **Regional pyramid** (`Pyramid.kt`, `pyramid` on and more than six groups of `groupSize`,
  default 50): K = round(N / groupSize) groups; 1부 one group, 2부 two, 3부 four, ...; the rest
  is a tier of its own when at least half the tier above, else joins it (1000: 1/2/4/8/5,
  3000: 1/2/4/8/16/29). Group sizes as even as can be, higher groups first. Each group is a
  division of the `Season`; `Tiers` maps divisions to tiers (saved as a `tiers` line only for
  a pyramid, with region names). Each season a tier's members are sorted by confederation,
  nation, hometown and cut into its groups, named after the region (a nation with half the
  group, else a confederation, else the two biggest).
- Single round robin per division (`doubleRoundRobin` option). Matchdays: the circle method
  splits each division's fixtures into rounds where every AI plays at most once; matchday `k`
  of every division opens together (a calendar). Fixture order inside an open matchday: fewest
  played first, heaviest first once 80% of the league is played. `season.txt` (`season 4`)
  keeps the divisions and seed (fixtures are built again) and the played results only.
- Match = 2 games on mirrored dice (`matchFormat`: SINGLE = 1 game, MIRRORED = 2 games default,
  BEST_OF_3 = mirrored + a decider when level). League points 3/1/0; a 1-1 match goes to the
  two-game total; equal totals draw.
- End of season, per tier boundary (`Pyramid.crossing`): the top s of every lower group go up
  (s = `autoSwap`, default 2, linear; `groupSwap`, default 3, pyramid; at most a third of the
  smallest group) and as many go down from the upper groups' bottom places (each group's last,
  then second last, ...; an uneven share by points per match across groups). With
  `promotionPlayoff` (default on) each lower group's next place plays the next upper one in that
  order (best lower vs first upper), a knockout tie for the place. Linear leagues get exactly
  the old places. Places left by retirements are filled by the best of the tier below who did
  not just come down; newcomers start in the last tier.
- Honours by tier: `L<t>` a group title of tier t (one per group), `UP<t>`/`DOWN<t>` moves into
  tier t. In a pyramid, news covers 1부-3부 titles and the moves around 1부-3부; the rest is one
  `lower_tiers` article a season.
- `titlePlayoff` (default on): 1st division top 4 play semis (1v4, 2v3, two legs) and a final
  (one match); with 긴 토너먼트 (2.3) the semis are 3 matches and the final 4. The winner is champion; the regular-season leader gets "정규리그 1위".

### 2.2 Club league (team competition)
- `clubs` (default 16) clubs of `squad` (default 6) AIs; a match day fields 5 (best rated first,
  board 1 strongest). Clubs: deterministic name `<hometown> <mascot>` and emblem
  (shape, two colours, initial) from the club id.
- Team match: board i is a mirrored 2-game match between the two lineups' i-th AIs; boards won
  (a level board is half) decide; equal → total points over all boards; equal → draw.
- Single round robin (15 rounds), a round every few weeks of the season calendar (2.4); 3/1/0;
  table with board difference.
- Money: every AI has a value `v = max(1, (rating - 1100) / 10)`; clubs have a budget
  (start 100, prize money by final position `40 - 2*pos`, cup/CL money by round).
- Transfer window at season start: in club order (worst budget first) each club may buy up to
  2 AIs (free agents at `v`, other clubs' players at `1.3 v` if the selling club keeps at least
  `squad - 1`), replacing its lowest rated member when full; sold members become free agents.
- Draft after evolution: clubs with open places pick free agents in reverse final order, one
  pick per round, best rated first.

### 2.3 Cups and other competitions
All knockout ties use `TieRule.knockout`: games won (draw = half) → total points → up to 3
single decider games on new deals (seats alternating) → higher seed.
**긴 토너먼트** (`longKnockouts`, default on; off in the 빠른 시즌 preset): one-on-one knockouts
grow toward the final (`KnockoutLength`): 16강 and 8강 2 matches, 4강 3, 결승 4 (a match is two
mirrored games, so a final is 8 games); earlier rounds keep their length. It applies to the
요트컵, the champions league knockout and the title playoff; the 슈퍼컵 is 2 matches, the
promotion playoff stays 2, team matches are unchanged. A competition drawn long saves a
`long 1` line, so a season keeps the lengths it was drawn with (older saves read as short).
- **요트컵** (cup): every AI except me. Bracket of the next power of two with byes for the best
  seeds (by rating), rounds spread over the season calendar from 4% to 88% of the regular
  season (2.4), final last.
- **챔피언스 리그**: 16 AIs — previous season's 1st division top 12, 2nd division top 2 (a
  pyramid: each 2부 group's winner), cup winner and runner-up (taken places pass down the 1st division). Season 1: top 16 by rating.
  4 groups of 4 (pots by entry order, seeded draw), double round robin (6 matchdays, 48
  matches), top 2 to a fixed bracket: QF (A1-B2, B1-A2, C1-D2, D1-C2) and SF over two legs,
  final one match. Matchdays from 8% of the season about every four weeks, QF at 60%, SF at
  72%, final at 94% (2.4).
- **슈퍼컵** (from season 2): previous champion vs previous cup winner (runner-up if the same AI)
  at the season start, one knockout tie.
- **올스타전** (mid-season, 50%): "1부 올스타" (top 5 of division 1 by average match
  rating) vs "도전자 올스타" (top 5 of the other divisions); a team match. Counted as friendly.
- **계열 대항전** (every 3rd season, from season 2): each family except MINE fields its 5 best
  (rating) as a national team ("계열 대표 자동 선발": also announced as news with who was
  dropped/called up versus last time). 9 teams: 3 groups of 3 (single round robin of team
  matches), the 3 group winners and the best runner-up to semis, then a final (team matches).
- **국가대항전** (`NationCup.kt`, `nationCup`): national teams of each nation's five best rated
  players. 요트 월드컵 every fourth season from season 3 (3, 7, 11, ...; a host nation), the
  continental cups (아시아·유럽·아메리카·아프리카컵) in between (5, 9, ...); groups then a
  bracket of team matches. `NationBook` (`nations.txt`) keeps each nation's record, titles and
  best finishes, players' caps and every edition's podium. Rivalries (한일전, ...) and honours
  WC/WC2/WC4/WCW/WCMVP/WCTOP/WCH/CC/CC2/CCW/NSQ.
- **레전드 매치** (`LegendMatch.kt`, every third season from 3): a recently retired legend at
  its peak against the MVP, an exhibition off the ladder.
- **Friendlies**: newcomer placement (20%, 60% while newcomers are >10% of the roster) only;
  no fillers when nothing else is free (the worker waits: fillers went to the light AIs only).

### 2.4 Scheduling and the season calendar (주차)
**Weeks.** One league round is one week: week k is matchday k (`SeasonCalendar`). When the
season is set up every stage of the other competitions (a cup round, a group matchday, a club
round, the super cup, the all-star game) gets a week from its target share of the regular
season (`Stage.target`): `1 + round(target * (R - 1))`, then the first week from there, after
the competition's previous stage, that is still free. Cups, the champions league, the family
cup and the events share one lane (one stage a week while they fit, more only in very short
seasons; ties go to 슈퍼컵, 챔스, 요트컵, 계열 대항전, 올스타전 in that order), the club league
has its own lane. The post-season takes the weeks after the league: playoff stage s is week
R + 1 + s (title semis and the promotion playoffs, then the title final). The calendar is a
pure function of the plan (the league's rounds and the competitions' stages): rebuilt, never
saved. Default season (49 rounds): 슈퍼컵 1, 요트컵 3·10·14·20·26·32·37·43, 챔스 groups
5·9·13·16·21·24, QF 30, SF 36, final 46, 올스타전 25, 계열 대항전 7·17·28, SF 39, final 44,
playoffs 50 and 51.

**Clock.** The week in play is the lowest league round with a fixture left, + 1; after the
league, the first post-season week with a playoff tie left. A stage opens once the clock
reaches its week: `SeasonCalendar.progress` turns the week into the league progress the
competition's own `next(p)` opens exactly those stages at (`Stage.opensAt`). When nothing else
is free, next week's stages may start (so no worker waits); once the league is over everything
left opens. Stages that run past their week (slow AIs) keep their week: 일정 lists them as late.

`SeasonPlan.next(busy, threads)` returns the next fixture. Order: (1) open knockout or group
fixtures (CL first) while fewer than `max(1, T-1)` non-league jobs are in flight; (2) one team
fixture at a time; (3) league fixtures of the current and next matchday; (4) club board
fixtures of open rounds; (5) open cup fixtures uncapped; (6) next week's stages; (7) the
post-season once the league is over. Every fixture it hands out carries its week
(`Fixture.week`, metadata only: never in the deal); so do the events it makes (`LeagueEvent.week`;
0 = 개막 전 for the draws, 10000 = 시즌 마감 for the close), the news articles (an optional 9th
field on news.txt `a` lines) and the ladder feed (an optional 9th field `code:week:label` on
`m` lines). Older lines read as "no week". Heavy fixtures (estimated cost over 50 ms per game
from a per-AI cost meter) are limited to `max(1, T-1)` in flight. The season ends when every
competition is finished; then playoffs; then rollover (section 5). `SeasonPlan` encodes
everything into `league/season.txt` (`season 3`, decoding `season 1` files as a migration that
starts the next season with the new structure).

**Run modes (진행 방식, app).** `LeagueRunMode` (saved in `league/runmode.txt`): 계속 plays on
until ■; 1주씩 plays the week in play and stops by itself. A 1주씩 run takes its goal (season,
week) when it starts (or when the mode is chosen while running) and asks for fixtures with
`next(..., lastWeek = goal)`: the league gives no round after it (`Season.next(lastMatchday)`)
and a competition fixture whose stage week is later is given back at once and the next one
tried, so a later stage first in line holds up nothing. Friendlies and lab games run while the
week does. The week is played once the clock is past it with nothing in flight; the run then
stops (a player's stop: no auto-resume) and says what it played ("12주차를 다 치렀어요"). After a
season's last week the next request closes the season (rollover), finds a new season and stops.

**Never a silent stop (app).** Workers catch every `Throwable` from a job (a match fails, is
retried, then voided; 12 errors in a row stop the league with the reason shown), worker threads
get a 16 MB stack, and a run that ends by itself without a reason says so and saves. A watchdog
in the upkeep loop: nothing in flight and no match for 20 s means something is still marked as
taken; the busy set, cup and team counters and the league's taken fixtures
(`Season.reclaim`) are rebuilt from the jobs really in flight, and it is told to the crash log.

## 3. Per-game facts and statistics

**GameFacts** (computed in `LeagueMatch` from the 24 boards after each half-turn, plus the
`WinEstimate`): both cards (points per box, bonus, yacht, zeros), the first player's P(win)
after each half-turn (24 values, stored as 0-100 bytes), winner's lowest P (comeback), loser's
highest P (blown lead), lead changes, decisive half-turn (last lead change), final margin.
- **Match rating** (평점) per player and game: `6.5 + 3.5 * WPA` clipped to [3.0, 10.0], WPA =
  sum of the changes of the player's own P(win) over its own turns.
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

**Records** (`rec` lines, updated in O(1) per game; `RecordKind`, shown by `RecordGroup` in the
기록실): 레이팅 — best rating ever (before an official match, once a player has 60 games),
biggest rating gain in a season (from its start rating), biggest upset (rating gap a winner
overcame); 한 판·한 경기 — best game, biggest margin, biggest comeback, lowest winning score,
best two-player total in a game, most yachts in a match; 한 시즌 — most points, best average
match rating, best average score, best match win rate (20 matches, or 3/4 of a short season's
rounds), most yachts; 통산 — longest winning streak, most games, wins, yachts and 300-point
games (from every rated game, as the milestones); 우승·수상 — most titles, most MVPs, most
이 주의 선수, youngest and oldest 1부 champion (by age in the title season; earlier seasons are
counted quietly the first time, `StatsBook.honourRecords`). A count one holder keeps raising
(`running`) is one break a season, not one a game.

**Milestones** (news + 마일스톤 list): career games 1000/5000/10000/…, wins 100/500/1000/…,
yachts 50/100/500/…, first title, every 5th title, 300+ games.

**Season lines** per AI (newest 30 seasons): season, division, position, points, W/D/L, final
rating, average match rating — the growth curve (성장 곡선) and the archive.

**Position history** per division and season: each AI's position after every matchday (one byte
per AI per matchday) for the rank-change chart (순위 변동 그래프).

**Honours** per season: division champions (after playoffs), regular-season leader, cup winner
and runner-up, CL winner and runner-up, super cup, all-star winner, family cup winner, club
league champion; awards: MVP (best average match rating in division 1 with ≥ 60% of fixtures
played), 신인왕 (best rookie: first season), 기량발전상 (largest rating gain over the season),
최다 요트상, 득점왕 (best average score, ≥ half the fixtures).

**Hall of fame**: AIs that reach 3 titles, or 5000 games with ≥ 55% wins, or any MVP twice.

**Dynasties**: consecutive titles per competition, doubles (league + cup), trebles
(league + cup + CL), unbeaten league seasons; listed in 기록실.

**Head-to-head matrix**: from the ladder's pair table; families or the selected division.

All of it lives in `league/stats.txt` (`stats 1`: season and career AiStats, records, meta,
position history of the current season) and `league/archive.txt` (`archive 1`: honours of the
newest 100 seasons, season summaries, season lines, highlights archive, dynasties); 450 KB
or less together.

## 4. Highlights, news and the live board

- **Highlights**: the 20 most dramatic games of the season (fixture id, seed, game index, scores,
  tags, drama, the P(win) curve, AI version hashes); 3 per season go to the archive plus an
  all-time top 20. Replay re-plays the one game from its seed through the same code path,
  without recording; if the scores differ (an AI changed), the card says it cannot be replayed.
- **News articles** (`News.kt`): templated Korean headlines and 2-3 sentence bodies from events
  (title race lead change, upset, comeback, record broken, milestone, transfer, draft pick,
  evolution births and retirements, cup giant killing, CL qualification, promotion/relegation
  decided, awards, family team selection). Newest 200 kept.
- **Live board** (라이브 스코어보드): the newest finished fixtures are replayed turn by turn on
  screen from their stored per-half-turn totals (24 pairs per game, kept for the newest 40
  fixtures), six at a time, so the board shows matches "in progress".
- **Win curve storage**: the P(win) curve of every game in the feed (newest 60) and highlights.

## 5. Season rollover (end of season)

In order, idempotent by season number:
1. Title and promotion playoffs (if on) are played as the last fixtures before rollover.
2. Honours, awards, hall of fame, dynasties, records; season lines; archive.
3. Evolution (`Evolution.kt`, `aging` on): every generated/evolved player ages a year; it
   retires by an age table (×1.6 for the worst rated down to ×0.4 for the best; at 40 at the
   latest) and grows toward its potential (peak about 28-30, declining from 31-35, by ability).
   Retired records are kept, legends for those with honours. The book is brought back to
   `generated`: `childShare` (default 40%) of the free places go to pupils (mentors by
   tournament selection; nation 55% the first mentor's, 35% the second's; style mostly the
   first mentor's, mutated a little), the rest to rookies (18-22 mostly). A league grown by the
   rules gets the places beyond the retirees' as a founding class of every age. 이 주의 선수
   (best average score of a matchday's winners) are kept as `POTW` honours.
4. Clubs: prize money; retirements free squad places; transfer window; draft.
5. Promotion/relegation and the next season's divisions; newcomers to the lowest division.
6. Next season's CL entrants, super cup, cup bracket, family cup (if due).
7. The season review report (시즌 결산) is built and shown once.
8. Legend match: every 3rd season, a legend (archived spec rebuilt) plays the current MVP as an
   exhibition (not on the ladder).

## 6. Odds, preview, scouting, lab

- **Odds** (우승·강등 확률): Monte Carlo over the remaining league fixtures (2000 runs) with
  Bradley-Terry match probabilities from current ratings (draw probability from the observed
  level-match rate): P(title or playoff), P(CL place), P(promotion), P(relegation) per AI.
  Recomputed in the background at most every 10% of progress or on request; a few hundred ms.
- **Match preview** (경기 프리뷰): for an upcoming fixture: head-to-head, recent form, predicted
  win % (Bradley-Terry), each side's 주무기/약점 boxes from career stats, what is at stake
  (title race, relegation, cup round).
- **Scouting report** (스카우팅 리포트) before a ranked game: the opponent AI's strongest and
  weakest boxes vs the league average, yacht and bonus rate, comeback/blown-lead rate, risk
  attitude from its family/spec, and a sentence of advice.
- **AI lab** (AI 연구소): any two AIs (including mine and legends); N mirrored matches (200 /
  1000 / 5000 or until ±1%p) run as low-priority jobs in the same workers; results: win share
  ± 95% CI (from per-match shares), rating difference with CI, score difference ± CI,
  P(A stronger), histograms. Lab games are not recorded anywhere else. `league/lab.txt`.

## 7. Settings and presets

`LeagueConfig` (`league/config.txt`): divisions (1-6), pyramid, groupSize (30-80),
groupSwap (1-8), doubleRoundRobin, matchFormat, autoSwap, promotionPlayoff, titlePlayoff,
generated (0-3000), aging, childShare, baseAis, nationCup, clubs (0 = off, 4-24), squad, cup,
champions, superCup, allStar, familyCup, onlyFlawed, onlyTop. Presets: 현실 축구형 (defaults:
1000 players in a pyramid), 빠른 시즌 (2 divisions, single games, no club league, no national
cups), 대혼돈 (only players with mistakes, 70% pupils), 강자만 (the top 48 by rating in 2
divisions, no aging). Rules saved before the pyramid with the old default of 200 players read
as 1000. Changing structure applies at the next season start.

## 8. Screens (Korean labels)

LeagueScreen tabs: **순위표** · **일정** · **리그** · **대회** · **클럽** · **소식** · **기록**.
The control card shows the season clock on every tab: "📅 시즌 3 · 12주차 / 49주", what this week
holds besides the league ("이번 주: 🏆 요트컵 64강 · 🛡 클럽 4라운드") or what comes next, and a bar
of the weeks (played, in play, later; dots for finals). A tap opens 일정.
- 순위표: today's ladder + search (names and nations) + origin and nation chips + 응원 ★.
- 일정: 이번 주 (any week with ◀ ▶: every stage with played/total, live matches, a tap opens its
  table or bracket; late stages of earlier weeks; the week's top news; next week; the finals
  ahead) and 시즌 전체 (a row per week, ✓ played, ● in play, the post-season apart).
- 리그: tier chips, then group chips in a pyramid (the followed player's group first);
  division tables (with 운 보정 toggle and odds columns), 일정표 (matchday list with
  results and upcoming fixtures, 프리뷰 on tap), 순위 변동 그래프, 플레이오프.
- 대회: 요트컵 bracket, 챔스 groups + bracket, 슈퍼컵, 올스타전, 계열 대항전 (and 대표 명단).
  Chips say where each stands ("64강", "13주", "끝"); a card lists every stage with its week
  (✓, ●) and what is on now; bracket round chips carry their week.
- 클럽: club table, club card (emblem, squad, budget, honours, transfers), 이적·드래프트 소식.
- 기록: 기록실 (records, dynasties), 국가 랭킹 (by cups, strength or numbers; followed nations;
  a nation's sheet: school, cups, core squad, most capped, honours by nation, ages, legends),
  유망주 (22 or younger by potential), 시상식·명예의 전당, 시즌 아카이브, 메타 리포트,
  칸별 리그 메타, 상대 전적 매트릭스, 계보도, 레전드 보관소, 성장 곡선 (from a profile).
- 소식: feed (where each match was played: "🏆 요트컵 64강 · 14주차", filters 리그/대회/친선) +
  하이라이트 + 뉴스 기사 by week under week headings + 마일스톤 + 라이브 스코어보드 (with the
  week) + 응원 AI filter.
- Extra entries: AI 연구소 (from the league top bar), 백과사전 (search over AIs, retired,
  legends, clubs, competitions), 규칙·프리셋 (settings), 시즌 결산 (shown once at rollover),
  스카우팅 (ranked start dialog), 프로필 (any AI name opens it: flag, nation, gender, age and
  stage, nickname, ability bars with grades and change, tendency bars, style card, rival,
  national team, honours, stats, growth curve, 사제 계보, highlights; ⚖ 비교 puts two players
  side by side).

## 9. Framework (implemented) and module boundaries

Framework files (engine/league): `Config.kt` (LeagueConfig, presets), `Fixture.kt` (Comp,
Fixture, JobKind, BoardResult, JobResult, TieRule), `Facts.kt` (GameFacts: curve, ratings,
comeback), `WinEstimate.kt`, `Calendar.kt` (round robins), `Season.kt` (league stage by
matchdays, positions, 운 보정 points), `Competition.kt` (Competition, CompetitionCodec,
SeasonStart, Honour, LeagueEvent), `SeasonPlan.kt` (scheduling, post-season, save format
`plan 1` with `comp <code>` ... `end` sections), `Offseason.kt` (honours, moves, next divisions,
next season from factories), `Honours.kt` (HonourBook), `LeagueMatch.playJob` (MATCH, TIE with
legs and deciders, TEAM with boards). The app runs it in `LeagueController` (factories,
postFactories and codecs are the hook lists the modules fill).

Conventions for modules:
- A competition implements `Competition`, provides a factory `(SeasonStart) -> Competition?`
  (or `(SeasonPlan) -> Competition?` for the post-season) and a `CompetitionCodec`.
- Seeds: `DiceStream.mix(start.seed, comp.code.code.toLong(), salt...)`; fixture ids unique
  within the competition.
- A knockout tie is one `JobKind.TIE` fixture with `a` = the higher seed (it goes through when
  still level after the deciders); two legs = `legs = 2`.
- Encode in-progress fixtures as unplayed.
- Events (`LeagueEvent.kind`), keys first then args:
  `upset` [winner, loser] (rating gap) · `comeback` [winner, loser] (lowest P%) ·
  `record` [holder] (record name, value, previous) · `milestone` [ai] (kind, value) ·
  `title` [ai] (division index or comp) · `promoted`/`relegated` [ai] (from, to) ·
  `cup_round` [winner, loser] (round label; an underdog win is a giant killing) ·
  `eliminated` [ai] (round) · `qualified` [ai] (stage) · `final` [winner, runner-up] (score) ·
  `transfer` [ai, fromClub, toClub] (fee) · `draft` [ai, club] (pick) ·
  `born` [child, parentA, parentB] · `retired` [ai] (seasons, titles) · `award` [ai] (kind) ·
  `callup`/`dropped` [ai] (family) · `title_race` [leader, second] (gap) ·
  `hall_of_fame` [ai] · `dynasty` [ai] (kind, count) · `legend_match` [legend, opponent] (result).
