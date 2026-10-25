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

- **Base AIs**: the 103 hand-made AIs of `LeagueRoster` (permanent; never retired).
- **Generated players** (`origin = GENERATED`): `LeagueConfig.generated` (default 100) light AIs
  built from `CustomAiSpec` genes (EV-shaped 55%, RISK 20% with θ from `LeagueRoster.THETAS`,
  BELIEF 10% from the 6 belief tables, SITUATION overlay 15%; mistakes slip 0.3-30% / feel
  0.1-3 points / none; tastes with probability 0.3 each). Never the WIN base, so all are light.
  Keys `g001`... Spec `i` comes from `Random(mix(GEN_SEED, i))` only, so the first N of N+10 are
  the same players. `CustomAiSpec` gains optional trailing fields `temper` (NONE, TILT, TIRED,
  WARMUP, MOODY) and `belief` (`-` or a belief key); old 12-field lines still decode.
- **Evolved players** (`origin = EVOLVED`): children made at season end (section 5). Keys
  `e<season>_<n>`.
- **Mine** (`origin = MINE`): the clone and custom AIs, as today.
- Every AI has a Korean name (base AIs keep theirs), a hometown (one of 16 cities; base AIs get
  one from a hash of their key) and, for generated/evolved ones, parents and a debut season.
- `league/roster.txt` (`roster 1`): one line per generated/evolved AI alive:
  `ai key name hometown origin born parentA parentB <CustomAiSpec fields>`; plus
  `retired key name born retiredSeason parentA parentB bestRating titles` (newest 500) and
  `legend key name born retired <spec fields> <honours>` (AIs with any honour, kept forever, at
  most 200).

## 2. Structure of a season

### 2.1 Individual league (the pyramid)
- `divisions` (default 4) balanced divisions over all AIs except me. ~205 AIs → 4 × 51.
- Single round robin per division (`doubleRoundRobin` option). Matchdays: the circle method
  splits each division's fixtures into rounds where every AI plays at most once; matchday `k`
  of every division opens together (a calendar). Fixture order inside an open matchday: fewest
  played first, heaviest first once 80% of the league is played.
- Match = 2 games on mirrored dice (`matchFormat`: SINGLE = 1 game, MIRRORED = 2 games default,
  BEST_OF_3 = mirrored + a decider when level). League points 3/1/0; a 1-1 match goes to the
  two-game total; equal totals draw.
- End of season, per boundary d/d+1: `autoSwap` (default 2) go down/up automatically; with
  `promotionPlayoff` on (default), d's (autoSwap+1)-th from bottom plays d+1's (autoSwap+1)-th
  place in a knockout tie for the place in d.
- `titlePlayoff` (default on): 1st division top 4 play semis (1v4, 2v3, two legs) and a final
  (one match). The winner is champion; the regular-season leader gets "정규리그 1위".

### 2.2 Club league (team competition)
- `clubs` (default 16) clubs of `squad` (default 6) AIs; a match day fields 5 (best rated first,
  board 1 strongest). Clubs: deterministic name `<hometown> <mascot>` and emblem
  (shape, two colours, initial) from the club id.
- Team match: board i is a mirrored 2-game match between the two lineups' i-th AIs; boards won
  (a level board is half) decide; equal → total points over all boards; equal → draw.
- Single round robin (15 rounds) opening with league progress; 3/1/0; table with board
  difference.
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
- **요트컵** (cup): every AI except me. Bracket of the next power of two with byes for the best
  seeds (by rating), rounds open at league progress 0.05, 0.15, ... (one per ~0.1), final last.
- **챔피언스 리그**: 16 AIs — previous season's 1st division top 12, 2nd division top 2, cup
  winner and runner-up (taken places pass down the 1st division). Season 1: top 16 by rating.
  4 groups of 4 (pots by entry order, seeded draw), double round robin (6 matchdays, 48
  matches), top 2 to a fixed bracket: QF (A1-B2, B1-A2, C1-D2, D1-C2) and SF over two legs,
  final one match. Matchdays open with league progress.
- **슈퍼컵** (from season 2): previous champion vs previous cup winner (runner-up if the same AI)
  at the season start, one knockout tie.
- **올스타전** (at league progress 0.5): "1부 올스타" (top 5 of division 1 by average match
  rating) vs "도전자 올스타" (top 5 of the other divisions); a team match. Counted as friendly.
- **계열 대항전** (every 3rd season, from season 2): each family except MINE fields its 5 best
  (rating) as a national team ("계열 대표 자동 선발": also announced as news with who was
  dropped/called up versus last time). 9 teams: 3 groups of 3 (single round robin of team
  matches), the 3 group winners and the best runner-up to semis, then a final (team matches).
- **Friendlies**: newcomer placement (20%, 60% while newcomers are >10% of the roster) and light
  fillers when nothing else is free.

### 2.4 Scheduling
`SeasonPlan.next(busy, threads)` returns the next fixture. Progress `p` = league fixtures played
/ league total. Order: (1) open knockout or group fixtures (CL first) while fewer than
`max(1, T-1)` non-league jobs are in flight; (2) league fixtures of open matchdays; (3) club
board fixtures of open rounds; (4) friendlies. Heavy fixtures (estimated cost over 50 ms per game
from a per-AI cost meter) are limited to `max(1, T-1)` in flight. The season ends when every
competition is finished; then playoffs; then rollover (section 5). `SeasonPlan` encodes
everything into `league/season.txt` (`season 3`, decoding `season 1` files as a migration that
starts the next season with the new structure).

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

**Records** (`rec` lines, updated in O(1) per game): best game, biggest margin, biggest comeback,
longest winning streak (from the ladder), most yachts in a game, most points in a season, best
average match rating in a season, most titles (computed from honours).

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
3. Evolution (if `evolution > 0`, default 8): retire the 8 lowest rated generated/evolved AIs
   (never base or mine); keep retired records, legends for those with honours. Make 8 children:
   parents by tournament selection (size 4) among generated/evolved AIs in the top half by
   rating; uniform crossover of genes, mutation with probability 0.15 per gene (small steps).
   Children debut in the lowest division with the hometown of parent A. Genealogy = the parent
   links (계보도: ancestors up to 4 generations and descendants).
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

`LeagueConfig` (`league/config.txt`): divisions (1-6), doubleRoundRobin, matchFormat, autoSwap,
promotionPlayoff, titlePlayoff, generated (0-300), evolution (0-20), clubs (0 = off, 8-24),
squad, cup, champions, superCup, allStar, familyCup. Presets: 현실 축구형 (defaults),
빠른 시즌 (2 divisions, single games, no club league, no family cup), 대혼돈 (only AIs with
mistakes, evolution 20), 강자만 (the top 48 by rating in 2 divisions, no generated players).
Changing structure applies at the next season start.

## 8. Screens (Korean labels)

LeagueScreen tabs: **순위표** · **리그** · **대회** · **클럽** · **기록** · **소식**.
- 순위표: today's ladder + search + origin chip (기본/생성/진화/내 AI) + 응원 ★.
- 리그: division tables (with 운 보정 toggle and odds columns), 일정표 (matchday list with
  results and upcoming fixtures, 프리뷰 on tap), 순위 변동 그래프, 플레이오프.
- 대회: 요트컵 bracket, 챔스 groups + bracket, 슈퍼컵, 올스타전, 계열 대항전 (and 대표 명단).
- 클럽: club table, club card (emblem, squad, budget, honours, transfers), 이적·드래프트 소식.
- 기록: 기록실 (records, dynasties), 시상식·명예의 전당, 시즌 아카이브, 메타 리포트,
  칸별 리그 메타, 상대 전적 매트릭스, 계보도, 레전드 보관소, 성장 곡선 (from a profile).
- 소식: feed (competition badges) + 하이라이트 + 뉴스 기사 + 마일스톤 + 라이브 스코어보드 +
  응원 AI filter.
- Extra entries: AI 연구소 (from the league top bar), 백과사전 (search over AIs, retired,
  legends, clubs, competitions), 규칙·프리셋 (settings), 시즌 결산 (shown once at rollover),
  스카우팅 (ranked start dialog), 프로필 (any AI name opens it: card with honours, stats,
  growth curve, genealogy, highlights).

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
