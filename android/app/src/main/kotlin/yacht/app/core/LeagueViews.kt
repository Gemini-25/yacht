package yacht.app.core

import yacht.engine.Category
import yacht.engine.league.AiOdds
import yacht.engine.league.AiStats
import yacht.engine.league.Article
import yacht.engine.league.Club
import yacht.engine.league.ClubDigest
import yacht.engine.league.ClubMatch
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubRow
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Confed
import yacht.engine.league.DramaTag
import yacht.engine.league.Dynasty
import yacht.engine.league.Emblem
import yacht.engine.league.Family
import yacht.engine.league.GameScore
import yacht.engine.league.HallOfFameEntry
import yacht.engine.league.Highlight
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MatchFormat
import yacht.engine.league.MatchPreview
import yacht.engine.league.MetaReport
import yacht.engine.league.Origin
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonLine
import yacht.engine.league.SeasonSummary
import yacht.engine.league.Tier
import yacht.engine.league.WeekStage

// What the league screens draw: the controller's snapshot and the views loaded on request.

/** One AI on the ladder as the screen shows it. */
data class LadderRow(
    val key: String,
    val name: String,
    val family: Family,
    val desc: String,
    val heavy: Boolean,
    /** Null while still in placement. */
    val rank: Int?,
    val rating: Int?,
    /** 95% half-width of the rating. */
    val margin: Int?,
    val tier: Tier?,
    val games: Int,
    val wins: Int,
    val ties: Int,
    val losses: Int,
    val winRate: Double,
    val avgScore: Double,
    val best: Int,
    val yachts: Int,
    val bonuses: Int,
    val streak: Int,
    val bestStreak: Int,
    /** Last results, oldest first ('W', 'D', 'L'). */
    val recent: String,
    /** Empty in snapshots: the profile carries it ([ProfileView.ratingHistory]). */
    val history: List<Int>,
    val peak: Int?,
    /** Rating change in its last match. */
    val lastDelta: Int,
    /** Places gained since the league was started this time (positive = up). */
    val rankChange: Int?,
    val playing: Boolean,
    /** The player's own line (ranked games). */
    val isMe: Boolean = false,
    /** Season division (0 = top), null when not in this season. */
    val division: Int? = null,
    /** 기본 (hand-made), 생성, 진화 or 내 AI. */
    val origin: Origin = Origin.BASE,
    val hometown: String = "",
    /** A generated or evolved player's nation code, flag and age ("" / null for the others). */
    val nation: String = "",
    val flag: String = "",
    val age: Int? = null,
    /** Its division as shown ("한국 1부"; a legacy season's "3부", "3부 B조"), null when not in this season. */
    val divisionLabel: String? = null,
    /** Its league this season ("KR"; "" in a legacy season or when not in this season). */
    val league: String = "",
    /** Its club in national leagues (a key of [LeagueView.badges]); null without one or in a legacy season. */
    val club: String? = null,
) {
    /** The name with its flag, as lists show it ("🇧🇷 루카스 시우바"). */
    val shown: String get() = if (flag.isEmpty()) name else "$flag $name"
}

data class FeedItem(
    val a: String,
    val aName: String,
    val b: String,
    val bName: String,
    val games: List<GameScore>,
    val deltaA: Int,
    val deltaB: Int,
    val time: Long,
    /** "kind|key|value" news, e.g. "승급|win|다이아몬드". */
    val notes: List<String>,
    /** [a]'s P(win) after each half-turn of the first game, percent (when the live board still has it). */
    val curve: IntArray? = null,
    /** Where it was played: the competition (null for matches saved before), its stage or board, the week. */
    val comp: Comp? = null,
    val label: String = "",
    val week: Int = SeasonCalendar.NONE,
    /** The season it was played in (0 when unknown). */
    val season: Int = 0,
    /** Its match ref ("season:code:id[:board]") to open its report; null for a friendly or a match not found. */
    val ref: String? = null,
    /** It can be played again (▶, UX 3.3): its log is kept, or it is this season's with no adaptive side. */
    val replay: Boolean = false,
)

/** A match being played: its two sides (team ids for team matches), where, and its fixture id in its competition. */
data class LiveMatch(val a: String, val b: String, val comp: Comp, val label: String, val week: Int, val firstTo: Int = 0, val id: Int = -1)

/** One week of the season (일정): its stages, the league round first. */
data class WeekRow(val week: Int, val stages: List<WeekStage>) {
    val done: Boolean get() = stages.all { it.done }
}

/**
 * The season's clock and calendar (일정). [week] is the week in play: 1..[leagueWeeks] are the
 * league rounds, the weeks after them the post-season; [weeks] holds every week from 1 to
 * [lastWeek] with its stages and what has been played.
 */
data class ClockView(
    val season: Int,
    val week: Int,
    val leagueWeeks: Int,
    val lastWeek: Int,
    /** Everything is played: the season is closing. */
    val over: Boolean,
    val weeks: List<WeekRow>,
    /** Matches being played per (competition, stage); a league round's stage is its matchday. */
    val live: Map<Pair<Comp, Int>, Int> = emptyMap(),
) {
    fun row(w: Int): WeekRow? = weeks.getOrNull(w - 1)

    /** Stages of the weeks before [week] that still have matches to play. */
    val late: List<WeekStage> get() = weeks.take((week - 1).coerceIn(0, weeks.size)).flatMap { r -> r.stages.filter { !it.done && it.set } }

    /** The next weeks' stages that are not league rounds, from week [from] on. */
    fun upcoming(from: Int): List<WeekStage> = weeks.drop((from - 1).coerceAtLeast(0)).flatMap { r -> r.stages.filter { it.comp != Comp.LEAGUE } }
}

data class LeagueView(
    val rows: List<LadderRow>,
    val feed: List<FeedItem>,
    val matches: Long,
    val games: Long,
    /** Matches being played right now. */
    val live: List<LiveMatch>,
    val season: SeasonView? = null,
    /** This season's competitions as the 대회 tab draws them (post-season last). */
    val comps: List<Pair<Comp, CompetitionView>> = emptyList(),
    val clubs: ClubsView? = null,
    /** News articles, newest first. */
    val news: List<Article> = emptyList(),
    /** This season's most dramatic games and the all-time best, best first. */
    val highlights: List<Highlight> = emptyList(),
    val allTime: List<Highlight> = emptyList(),
    /** The season's weeks (null before the first season). */
    val clock: ClockView? = null,
    /**
     * Every national club's badge by id (dissolved ones too, while their ids are theirs), built
     * again only when the club book changes; empty in a legacy league, whose club league is [clubs].
     */
    val badges: Map<String, ClubBadge> = emptyMap(),
)

/**
 * The club league of a legacy season (`clubs 1`): its table, every club and the latest transfers
 * and draft picks. Null with national clubs, which are loaded on request ([WorldPages]).
 */
data class ClubsView(
    /** This season's club league table, best first (empty when there is none this season). */
    val table: List<ClubRow>,
    val clubs: List<Club>,
    /** Newest first. */
    val moves: List<ClubMove>,
    /** This season's club fixtures, by round. */
    val matches: List<ClubMatch>,
    /** Each club's lineup this season (board 1 first), fixed when the season started. */
    val lineups: Map<String, List<String>> = emptyMap(),
)

/** One team in a season table. */
data class SeasonRow(
    val key: String,
    val name: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val points: Int,
    val diff: Int,
    val avgScore: Double,
    val yachts: Int,
    /** +1 promotion zone, -1 relegation zone, +-2 the playoff places, 0 otherwise. */
    val zone: Int,
    /** 운 보정 승점: 3 for the higher two-game total on the same dice, 1 when equal. */
    val aggPoints: Int = 0,
    /** 우승·승강 확률 (Monte Carlo over the rest of the season), null before the first estimate. */
    val odds: AiOdds? = null,
    /** 2-0 sweeps that earned the bonus point (판 승점제's 완승 보너스), counted in [points]. */
    val sweeps: Int = 0,
    /** Its club in national leagues (a key of [LeagueView.badges]); null otherwise. */
    val club: String? = null,
)

data class SeasonHistoryItem(
    val number: Int,
    val champions: List<String>,
    val topScorer: String?,
    val topScore: Double,
    val yachtKing: String?,
    val yachts: Int,
    val promoted: List<String>,
    val relegated: List<String>,
    /** The season's titles and awards besides the division champions: (kind, winner's name). */
    val titles: List<Pair<String, String>> = emptyList(),
    /** Each of [champions]' tier (0 = 1부; a pyramid has a champion per group). */
    val championTiers: List<Int> = emptyList(),
    /** All the moves, of which [promoted] and [relegated] name some in a pyramid. */
    val promotedCount: Int = promoted.size,
    val relegatedCount: Int = relegated.size,
)

/**
 * The season as 순위 › 리그 shows it: in a national season only the shown league ([league]) has
 * its tables here, its divisions from the season's division [firstDivision] on (a legacy season:
 * every division, from 0), so [divisions], [positions], [order], [rounds] and [tiers] are indexed
 * by the division's place in that league; the loaders (`loadForm`, `loadRound`) take the season's
 * index, [firstDivision] + that place.
 */
data class SeasonView(
    val number: Int,
    /** The whole season's league fixtures played and in all. */
    val played: Int,
    val total: Int,
    val divisions: List<List<SeasonRow>>,
    /** AIs that join the lowest division next season (of the shown league in a national season). */
    val newcomers: List<String>,
    /** Latest first. */
    val history: List<SeasonHistoryItem>,
    /** Places after each finished round per division, in [order] (순위 변동 그래프). */
    val positions: List<List<ShortArray>> = emptyList(),
    /** Each division's AIs in the order [positions] uses. */
    val order: List<List<String>> = emptyList(),
    /** The league stage's matchdays and the one in play (a national season: the shown league's 1부 rounds and the ones it finished). */
    val matchdays: Int = 0,
    val currentMatchday: Int = 0,
    /** Which tier each division is in (a regional pyramid's groups), and how many go up from each lower group. */
    val tiers: yacht.engine.league.Tiers = yacht.engine.league.Tiers.linear(divisions.size),
    val swap: Int = 0,
    /** How the season's matches are decided (판 승점제 gives a 2-0 sweep a bonus point). */
    val format: MatchFormat = MatchFormat.MIRRORED,
    /** The rules the season began with (a change applies from the next season). */
    val rules: LeagueConfig? = null,
    /** The season's leagues for the selector (one entry, id "", in a legacy season), in league order. */
    val leagues: List<LeagueInfo> = emptyList(),
    /** The league shown ("" in a legacy season): the one picked while this season has it, else the default (`LeagueSelector.Built.default`). */
    val league: String = "",
    /** National leagues this season, and in the next (its rules as they are now). */
    val national: Boolean = false,
    val nextNational: Boolean = false,
    /** 2-0 sweeps earn a bonus point this season (판 승점제's 완승 보너스, the season's own rule). */
    val bonus: Boolean = format.sweepBonus,
    /** The season's index of [divisions]' first. */
    val firstDivision: Int = 0,
    /** Rounds each division plays (a national season's lower tiers can play fewer than [matchdays]); empty: [matchdays] each. */
    val rounds: List<Int> = emptyList(),
) {
    /** The shown league's entry of [leagues]. */
    val shown: LeagueInfo? get() = leagues.firstOrNull { it.id == league }

    /** Rounds division [d] (of [divisions]) plays. */
    fun roundsOf(d: Int): Int = rounds.getOrNull(d) ?: matchdays
}

/** One fixture in the calendar (일정표): its games once played, or whether it is being played. */
data class CalendarItem(
    val id: Int,
    val a: String,
    val aName: String,
    val b: String,
    val bName: String,
    val games: List<GameScore>?,
    val live: Boolean,
    /** Once played: +1 A won, -1 B won, 0 a draw, by the season's rule. */
    val result: Int? = null,
)

/**
 * 상대 전적 매트릭스: [score][i][j] is row [i]'s points share against column [j] (ties half),
 * NaN with fewer than 2 games; [games][i][j] the games between them.
 */
class H2hMatrix(val title: String, val labels: List<String>, val score: Array<DoubleArray>, val games: Array<IntArray>)

/** An AI's profile (프로필): where it comes from, its family tree and its honours. */
data class ProfileView(
    val key: String,
    val name: String,
    val origin: Origin,
    val hometown: String,
    /** Debut season of a generated or evolved player (null for the others). */
    val born: Int?,
    /** Season it retired, for a retired player or a legend. */
    val retired: Int?,
    val legend: Boolean,
    /** 계보도: names per generation upwards (parents, grandparents, ...), null where unknown. */
    val ancestors: List<List<String?>>,
    /** Names per generation downwards (children, grandchildren, ...). */
    val descendants: List<List<String>>,
    /** Its honours, newest season first. */
    val honours: List<Honour>,
    val titles: Int,
    /** Genes in words, for generated and evolved players. */
    val genes: String?,
    /** Its best and worst opponents (at least 4 games against them). */
    val best: List<Matchup> = emptyList(),
    val worst: List<Matchup> = emptyList(),
    /** This season's and its career statistics (copies), and the league's box averages to compare. */
    val season: AiStats? = null,
    val career: AiStats? = null,
    val league: MetaReport? = null,
    /** 성장 곡선: one line per season, oldest first. */
    val lines: List<SeasonLine> = emptyList(),
    val hallOfFame: Boolean = false,
    /** Nation, age, abilities, tendencies and style of a generated or evolved player. */
    val identity: IdentityView? = null,
    /** Its rating after each of its last matches (레이팅 흐름). */
    val ratingHistory: List<Int> = emptyList(),
)

/**
 * Who a generated or evolved player is, for its page: nation, gender and age, its nickname, its
 * abilities (with the change since the season began and its best) and tendencies, its style, its
 * national team career, its rival and its 이 주의 선수 count. Only what is known is filled in (a
 * player retired before identities has a nation and an age at most).
 */
data class IdentityView(
    val flag: String,
    val nationCode: String,
    val nation: String,
    val gender: String,
    /** Its age now, or when it retired. */
    val age: Int?,
    val stage: String?,
    val debutAge: Int?,
    val nickname: String? = null,
    val abilities: List<Int>? = null,
    val change: List<Int> = emptyList(),
    val peak: List<Int>? = null,
    val potential: Int? = null,
    val outlook: String? = null,
    val tendencies: List<Int>? = null,
    val style: String? = null,
    val styleNotes: List<String> = emptyList(),
    /** Hidden genes as chips: mistakes by feel or slips, the temper. */
    val chips: List<String> = emptyList(),
    val school: String? = null,
    /** Rival: (name, its points, games). */
    val rival: Triple<String, Double, Int>? = null,
    val caps: Int = 0,
    val capTitles: Int = 0,
    val capBoards: Triple<Int, Int, Int>? = null,
    /** Its rank among its nation's players by rating. */
    val nationRank: Int? = null,
    val nationPlayers: Int = 0,
    val weeks: Int = 0,
)

/** The 기록 tab's records room, archive and meta (read on request). */
data class RecordsRoomView(
    val records: List<LeagueRecord>,
    val dynasties: List<Dynasty>,
    val hallOfFame: List<HallOfFameEntry>,
    /** Newest first. */
    val summaries: List<SeasonSummary>,
    val milestones: List<LeagueEvent>,
    /** This season so far, and the last finished one. */
    val meta: MetaReport?,
    val previousMeta: MetaReport?,
    /** Best average match rating this season (the MVP race), with its matches. */
    val mvpRace: List<Pair<String, AiStats>>,
)

/** A nation in the 국가 랭킹: its national team's record and its players' strength. */
data class NationRow(
    val code: String,
    val flag: String,
    val name: String,
    val confed: String,
    /** Its players in the league now. */
    val players: Int,
    /** Average rating of its five best (its national team's strength), null without five rated. */
    val strength: Int?,
    val best: String?,
    val worldTitles: Int,
    val continentalTitles: Int,
    val worldBest: Int,
    val continentalBest: Int,
    val editions: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
)

/** One nation's sheet: its school, squad, capped players, cups and 국적별 기록. */
data class NationSheet(
    val row: NationRow,
    val motto: String,
    /** Its best players now: (key, name, rating, age). */
    val squad: List<NationPlayer>,
    val capped: List<NationPlayer>,
    /** "시즌 7 요트 월드컵 우승", newest first. */
    val cups: List<String>,
    /** Honours its players won, by kind: (kind, count), most first. */
    val honours: List<Pair<String, Int>>,
    val legends: List<NationPlayer>,
    /** Players by age band: (label, count). */
    val ages: List<Pair<String, Int>>,
    val women: Int,
)

/** A player in a nation's sheet; [note] says what it is listed for. */
data class NationPlayer(val key: String, val name: String, val note: String)

/** A young player worth watching (유망주). */
data class ProspectRow(val key: String, val shown: String, val age: Int, val overall: Int, val potential: Int, val outlook: String?, val rating: Int?)

/** Two players side by side (선수 비교). */
data class CompareView(
    val a: String,
    val b: String,
    val nameA: String,
    val nameB: String,
    val idA: IdentityView?,
    val idB: IdentityView?,
    /** Rating, rank, games, win rate, average score, titles, MVPs: (label, a, b, which is better: 1 a, -1 b, 0 neither). */
    val lines: List<CompareLine>,
    /** a's points share against b and their games, null when they never met. */
    val h2h: Pair<Double, Int>?,
)

data class CompareLine(val label: String, val a: String, val b: String, val better: Int)

/** A retired player or legend for the 레전드 보관소. */
data class PastPlayer(
    val key: String,
    val name: String,
    val born: Int,
    val retired: Int,
    val bestRating: Int?,
    val titles: Int,
    val legend: Boolean,
    val honours: String,
    val hometown: String,
)

/**
 * A matchday of a division in the calendar: [matchday] of the league stage's [matchdays], the
 * one in play [current], as the division's round [round] of its [rounds] (the round in play
 * [currentRound]); [prev] and [next] are the matchdays of its rounds before and after (null at
 * either end). A legacy season plays every division's rounds on matchdays of the same number; a
 * national season spreads a shorter division's rounds over the weeks.
 */
data class CalendarView(
    val division: Int,
    val matchday: Int,
    val matchdays: Int,
    val current: Int,
    val items: List<CalendarItem>,
    val season: Int = 0,
    val round: Int = matchday,
    val rounds: Int = matchdays,
    val currentRound: Int = current,
    val prev: Int? = (matchday - 1).takeIf { it >= 0 },
    val next: Int? = (matchday + 1).takeIf { it < matchdays },
)

/**
 * 최근 폼 of a division: each player's last results in it ('W', 'D', 'L' by the season's rule,
 * oldest first), as of [played] fixtures of the season.
 */
data class FormView(val division: Int, val season: Int, val played: Int, val form: Map<String, String>)

/** A ranked game counted: my rating and tier before and after. */
data class RankResult(
    val opponent: String,
    val won: Boolean,
    val draw: Boolean,
    val before: Int?,
    val after: Int?,
    val tierBefore: Tier?,
    val tierAfter: Tier?,
    val rank: Int?,
    val games: Int,
    val placementLeft: Int,
)

/** A matchup of one AI for its detail page: its points share against [other] in [games] games. */
data class Matchup(val other: String, val name: String, val score: Double, val games: Int)

/** One shown step of a watched game: a throw of one side, with the cards after the turn when it ends. */
data class WatchStep(
    val turn: Int,
    /** 0 = first player. */
    val side: Int,
    val throwNo: Int,
    val dice: List<Int>,
    val held: List<Int>?,
    val category: yacht.engine.Category?,
    val points: Int,
    /** The first player's P(win) after this step. */
    val winFirst: Double?,
    val lines: List<yacht.engine.explain.Comment>,
    val first: yacht.engine.Board,
    val second: yacht.engine.Board,
)

/** What the watch screen plays: a real match again, a highlight, the broadcast channel, or a friendly played now. */
enum class WatchKind { REPLAY, HIGHLIGHT, BROADCAST, FRIENDLY }

/** One game of the board being watched, for the game strip: "1판 · 김민준 선공", its score from side A once known. */
data class WatchGameInfo(val index: Int, val label: String, val first: String, val second: String, val scoreA: Int?, val scoreB: Int?)

/** The broadcast queue: pick [index] of [total] (0-based), the next one's title, and the seconds before it starts (null: no countdown). */
data class BroadcastQueue(val index: Int, val total: Int, val next: String?, val countdown: Int?)

/**
 * A game being watched: the two sides of the game shown ([first] moves first), its steps worked
 * out so far and the one shown, and around it the match (its games and boards, the score so far),
 * the broadcaster's lines before and after the game, and the broadcast queue.
 */
data class WatchView(
    val first: String,
    val second: String,
    val firstName: String,
    val secondName: String,
    val steps: List<WatchStep>,
    /** Steps the game has in all (two sides × 12 turns, one to three throws each); known once done. */
    val complete: Boolean,
    val shown: Int,
    val playing: Boolean,
    /** The match header ("요트컵 8강 · 19주차"), or a highlight's caption. */
    val title: String? = null,
    /** Why it cannot be shown (an AI changed since, a log that does not read), instead of the game. */
    val message: String? = null,
    val kind: WatchKind = WatchKind.FRIENDLY,
    /** The real match's ref ("season:code:id"); null for a friendly. */
    val ref: String? = null,
    /** The game shown (its index in the board's games) and the board's games. */
    val game: Int = 0,
    val games: List<WatchGameInfo> = emptyList(),
    /** The board shown (team matches) of [boards], and each board's line ("1번 보드 김민준 2:0 이서연"). */
    val board: Int = 0,
    val boards: Int = 1,
    val boardLines: List<String> = emptyList(),
    /** The match so far: "1 : 0" (series "시리즈 2 : 1", team "보드 2 : 1"); null for a single game. */
    val matchScore: String? = null,
    /** Broadcaster lines before the first throw of this game, and after its last step. */
    val intro: List<String> = emptyList(),
    val outro: List<String> = emptyList(),
    /** 요약 중계: this game shows only its last half-turns (the deciding game plays in full). */
    val summary: Boolean = false,
    val queue: BroadcastQueue? = null,
    val pace: WatchPace = WatchPace.NORMAL,
    /** The match is over and nothing comes after it (the end lines show). */
    val ended: Boolean = false,
    /** A broadcast pick still to be played, the league stopped (▶ 이번 주 진행) or running ("곧 시작해요"). */
    val waiting: String? = null,
) {
    /** Every step of the game is shown. */
    val atEnd: Boolean get() = complete && shown >= steps.size
}

// ---- the redesigned screens' views (the competition page's in use; the rest filled later) ------------

/** A club as badges show it. */
data class ClubBadge(val id: String, val name: String, val city: String, val nation: String, val emblem: Emblem)

/** A league of the selector and the pages: its tiers, size, ranking and continental places. */
data class LeagueInfo(
    val id: String,
    /** "한국 리그" / "아시아 연합 리그"; a legacy season's "리그". */
    val label: String,
    val short: String,
    /** Its flag, its confederation's globe, or 🌐. */
    val icon: String,
    val confed: Confed?,
    /** Its first division's index among the season's divisions. */
    val first: Int,
    /** Each division's label ("1부", "2부"; a legacy pyramid's "3부 B조"), from [first] on. */
    val tierLabels: List<String>,
    val sizes: List<Int>,
    val members: Int,
    /** 1-based in the league ranking (in a first national season by strength); null in a legacy season. */
    val rank: Int?,
    /** Its 리그 계수 (the sum of the ranking's seasons); null before a national season closed with it. */
    val coefficient: Double?,
    /** Next season's places in its 대륙 챔피언스 from its 1부, and the champion straight into the 챔피언스 리그. */
    val cclSlots: Int,
    val clDirect: Boolean,
    /** A followed player, club or nation plays in it, or the league itself is followed. */
    val followed: Boolean,
    /** Nations whose players play in it (a 연합 리그 has several). */
    val nations: Int = 1,
) {
    /** Its tiers (a national league is a ladder: one division a tier). */
    val tiers: Int get() = sizes.size

    val national: Boolean get() = id.isNotEmpty()

    /** A 연합 리그 (of a confederation, or the 국제 연합 리그). */
    val union: Boolean get() = id == yacht.engine.league.LeagueIds.WORLD || yacht.engine.league.LeagueIds.isRegion(id)
}

/** The home dashboard's one banner, the most pressing first. */
sealed interface HomeBanner {
    data object Empty : HomeBanner
    data class SeasonEnd(val season: Int, val lines: List<String>) : HomeBanner
    data class Reform(val season: Int) : HomeBanner

    /** [moves]: (name, "🇯🇵 일본 리그") of followed players. */
    data class Coming(val season: Int, val moves: List<Pair<String, String>>) : HomeBanner
}

/** The home's last lines; [cloneInvite] the games still needed before the clone joins. */
data class HomeFooter(val members: Int, val matches: Long, val cloneInvite: Int?)

enum class FollowKind { PLAYER, CLUB, NATION, LEAGUE }

/** A followed player, club, nation or league on the home and the 응원 page. */
data class FollowCard(
    val kind: FollowKind,
    val key: String,
    val title: String,
    val badge: ClubBadge?,
    val line: String,
    val last: FollowMatch?,
    val next: FollowMatch?,
    val form: String,
    val note: String?,
)

/** A followed side's last or next match; [result] +1 won, 0 drawn, -1 lost (null before it is played). */
data class FollowMatch(
    val ref: String,
    val week: Int,
    val where: String,
    val opponent: String,
    val opponentName: String,
    val result: Int?,
    val score: String?,
    val replay: Boolean,
    val winChance: Int?,
)

/** One of the week's broadcast picks ('📺 이번 주 중계'). */
data class BroadcastPick(
    val ref: String,
    val week: Int,
    val comp: Comp,
    val title: String,
    val a: String,
    val b: String,
    val reason: String,
    val tags: List<String>,
    val score: Int,
    val played: Boolean,
    val result: String?,
    val watched: Boolean,
)

/**
 * '📺 이번 주 중계': the picks of [week] (pick order), followed players' other matches this week
 * ([followedMore]), and next week's picks so far ([coming]).
 */
data class BroadcastView(val season: Int, val week: Int, val picks: List<BroadcastPick>, val followedMore: Int, val coming: List<BroadcastPick> = emptyList())

/** Where a replay comes from: a saved decision log, a re-simulation, a highlight's genes, or none. */
enum class ReplaySource { LOG, RESIM, SNAPSHOT, NONE }

enum class ReportState { SCHEDULED, LIVE, PLAYED }

/** A match report (경기 리포트); [key] equals its page's load key. */
data class MatchReport(
    val key: String,
    val ref: String,
    val title: String,
    val state: ReportState,
    val sideA: String,
    val sideB: String,
    val nameA: String,
    val nameB: String,
    val badgeA: ClubBadge?,
    val badgeB: ClubBadge?,
    val ratingA: Int?,
    val ratingB: Int?,
    val result: String,
    val winner: Int,
    val boards: List<BoardReport>,
    val source: ReplaySource,
    val replayable: Boolean,
    val loading: Boolean,
    val note: String?,
    val stakes: List<String>,
    val preview: MatchPreview?,
    val news: List<Article>,
    /** Where: its competition (null when not known), round label, season and week ([SeasonCalendar.NONE]: none). */
    val comp: Comp? = null,
    val label: String = "",
    val season: Int = 0,
    val week: Int = SeasonCalendar.NONE,
    /** "1부 3위" of each side in a league match. */
    val placeA: String? = null,
    val placeB: String? = null,
    /** A team match (its sides are team ids, [boards] its pairings), and a series' games to win (0: none). */
    val team: Boolean = false,
    val firstTo: Int = 0,
    /** Rating changes of each side over the match, when the results feed still has them. */
    val deltaA: Int? = null,
    val deltaB: Int? = null,
)

/** One board of a report; [games] is null until the detail phase of an unlogged tie. */
data class BoardReport(
    val board: Int,
    val a: String,
    val b: String,
    val nameA: String,
    val nameB: String,
    val games: List<GameReport>?,
    val totalA: Int,
    val totalB: Int,
    val result: Int,
)

/** One game of a report: scores, the win-probability curve, each side's boxes, 평점 and drama tags. */
data class GameReport(
    val index: Int,
    val label: String,
    val scoreA: Int?,
    val scoreB: Int?,
    val aFirst: Boolean,
    val yachtA: Boolean,
    val yachtB: Boolean,
    val bonusA: Boolean,
    val bonusB: Boolean,
    val curve: ByteArray?,
    val decisive: Int?,
    val boxA: IntArray?,
    val boxB: IntArray?,
    val ratingA: Double?,
    val ratingB: Double?,
    val tags: List<DramaTag>,
)

/**
 * A match in a player's or club's list; [ref] null for one that cannot be opened; [result] from
 * the list's side (+1 won, 0 drawn, -1 lost, null not played); [comp] where it was played (null
 * when unknown).
 */
data class PlayerMatch(
    val ref: String?,
    val week: Int,
    val where: String,
    val opponent: String,
    val opponentName: String,
    val badge: ClubBadge?,
    val result: Int?,
    val score: String,
    val replay: Boolean,
    val comp: Comp? = null,
)

/** One line of a leaderboard. */
data class LeaderLine(val key: String, val shown: String, val badge: ClubBadge?, val league: String?, val value: Double, val text: String, val sub: String)

data class LeadersView(val filter: LeaderFilter, val boards: List<Pair<LeaderKind, List<LeaderLine>>>)

/** A whole leaderboard; [followed]: followed players outside the top lines, with their place. */
data class LeaderBoardView(
    val kind: LeaderKind,
    val filter: LeaderFilter,
    val rule: String,
    val lines: List<LeaderLine>,
    val followed: List<Pair<Int, LeaderLine>>,
)

/** A statistic of the player page, with its leaderboard place when it has one. */
data class StatCell(val label: String, val value: String, val rank: Int?, val board: LeaderKind?)

/** One box of the player page's box table: its average and zero rate, and the league's. */
data class BoxRow(val category: Category, val avg: Double, val league: Double, val zeroRate: Double, val leagueZero: Double)

/**
 * The player page ([LeagueAnalytics.loadPlayer]); [key] equals its page's load key, [profile]'s
 * key is the player's. The numbers are of [scope]; "the league" is the player's league this
 * season (every member of the season for a player outside it). [badge] and [leagueLabel] come
 * from the league's host only.
 */
data class PlayerPage(
    val key: String,
    val profile: ProfileView,
    /** Its ladder line in the screen's snapshot; null for a player no longer in the league. */
    val row: LadderRow?,
    val badge: ClubBadge?,
    val leagueLabel: String?,
    /** Its place in its division's table this season. */
    val position: Int?,
    /** The six shown first, then the rest. */
    val cells: List<StatCell>,
    /** The 12 boxes in [Category.ALL] order. */
    val box: List<BoxRow>,
    /** Upper section sum (the player's average, the league's), and the bonus rate (0..1) likewise. */
    val upper: Pair<Double, Double>,
    val bonus: Pair<Double, Double>,
    /** Games by score, [yacht.engine.league.AiStats.BIN]-point bars; null before its first game. */
    val histogram: IntArray?,
    /** This season's matches, newest first (league fixtures by week, then the knockout ties). */
    val matches: List<PlayerMatch>,
    val previous: List<PlayerMatch>,
    val highlights: List<Highlight>,
    /** Articles about it, newest first. */
    val news: List<Article>,
    /** Records it holds. */
    val records: List<LeagueRecord>,
    /** Its milestones, newest first. */
    val milestones: List<LeagueEvent>,
    /** Its next league fixture this season. */
    val next: FollowMatch?,
    val scope: StatScope,
    /** This season's division (global index) and its tier's label inside its league ("1부", "3부 B조"). */
    val division: Int? = null,
    val tierLabel: String? = null,
    /** The league's median game score ([histogram]'s line), NaN without games. */
    val median: Double = Double.NaN,
    /** Its rival (라이벌: the member it met most with a close record, [yacht.engine.league.Ladder.rival]). */
    val rival: Matchup? = null,
    /** The dynasties it built (연속 우승). */
    val dynasties: List<Dynasty> = emptyList(),
    /** The names of the clubs its season lines name, by id. */
    val clubNames: Map<String, String> = emptyMap(),
)

/** A season review in the 결산 list; [kept] when its full review is still there to open. */
data class ReviewItem(val season: Int, val kept: Boolean, val lines: List<String>)

/** Sums of a group of players' statistics (a club, a league, a nation). */
data class AggregateStats(
    val members: Int,
    val games: Int,
    val winRate: Double,
    val avgScore: Double,
    val yachtRate: Double,
    val bonusRate: Double,
    /** 평점. */
    val avgRating: Double,
    val avgElo: Int?,
    val over300: Int,
    val comebacks: Int,
    val sweeps: Int,
    /** The 12 boxes' averages. */
    val box: List<Double>,
)

/**
 * A club in its league's club table (F1 constructors' style): [points] = [leaguePart] (its
 * [counted] members' league points weighted by tier) + [bonus] (their honour points); [week] its
 * members' results in the week in play ("3승 1무 2패", "" before any).
 */
data class ClubStandingRow(
    val badge: ClubBadge,
    val position: Int,
    val points: Double,
    val leaguePart: Double,
    val bonus: Double,
    val counted: List<String>,
    val members: Int,
    val week: String,
)

/** A club of "클럽 전체 (우승 순)": its members now, money and club-table titles. */
data class ClubListRow(val badge: ClubBadge, val members: Int, val budget: Int, val titles: Int)

/**
 * 순위 › 클럽 of national league [league] (the loader's key) in [season]: the club table best first
 * ([ClubStandingRow.week] counts [week]), and every club of the league's nations by titles, then
 * money ([all]). A legacy season's club league is [LeagueView.clubs] instead.
 */
data class ClubTableView(val league: String, val season: Int, val week: Int, val rows: List<ClubStandingRow>, val all: List<ClubListRow>)

/**
 * A club's member on its page: [contribution] its points for the club table when counted (null
 * otherwise), [record] its league W-D-L this season, [abroad] when it plays for a club of a nation
 * not its own (해외).
 */
data class ClubMember(
    val key: String,
    val shown: String,
    val tierLabel: String?,
    val position: Int?,
    val contribution: Double?,
    val rating: Int?,
    val record: String,
    val abroad: Boolean,
)

/**
 * A national club's page; [key] equals its page's load key. [league] is the league it plays in
 * this season ("" when none), [places] its club-table place per kept season (only when in the top
 * 3, newest first), [moves] the moves in and out (newest first), [recent] its members' last league
 * matches, [gone] the season after which it was dissolved (null while it plays).
 */
data class ClubPage(
    val key: String,
    val badge: ClubBadge,
    val league: String,
    val leagueLabel: String,
    val standing: ClubStandingRow?,
    val budget: Int,
    val titles: Int,
    val founded: Int,
    val members: List<ClubMember>,
    val week: String,
    val places: List<Pair<Int, Int>>,
    val moves: List<ClubMove>,
    val recent: List<PlayerMatch>,
    val aggregate: AggregateStats?,
    val gone: Int? = null,
)

/**
 * A competition's page: its stages ("아시아 · 승자조 8강") and the one shown ([stage] indexes
 * [stages], which [competitionStages] of [view] gives in the same order).
 */
data class CompetitionPage(val key: String, val comp: Comp, val stages: List<String>, val stage: Int, val view: CompetitionView)

/** A league in the league ranking (리그 랭킹); [live] its points per entry this season so far (null before its first). */
data class RankRow(val rank: LeagueRank, val label: String, val short: String, val icon: String, val live: Double?)

/**
 * 순위 › 랭킹: the ranking national season [season] is played under (its places were drawn by it),
 * best first; [byStrength] while no national season has closed (the leagues go by the average
 * rating of their best 5); [published] the season whose close published it (0 when none has).
 * Empty [rows] in a legacy season, and with [season] 0 before a new league's first.
 */
data class RankingView(val season: Int, val rows: List<RankRow>, val byStrength: Boolean, val published: Int)

/** The home's 국제 무대: the stages of the world competitions, the ranking's top and this season's race. */
data class WorldSnippet(val stages: List<Pair<Comp, String>>, val ranking: List<RankRow>, val live: List<Pair<String, Double>>)

/** The home dashboard, built on request (never in the snapshot). */
data class HomeView(
    val season: Int,
    val week: Int,
    val banner: HomeBanner?,
    val followed: List<FollowCard>,
    val followedMore: Int,
    val picks: List<BroadcastPick>,
    val table: List<SeasonRow>,
    val world: WorldSnippet?,
    val pendingProposals: Int,
    val news: List<Article>,
    val footer: HomeFooter,
)

/**
 * A league's page; [key] equals its page's load key. [top] its 1부's first 5, [clubs] its club
 * table's first 5, [stars] its best 5 by rating, [champions] (season, champion's name) of its kept
 * 1부 titles, newest first; [moves] the transfers abroad and home of the last window that touch it,
 * [tiers] each division's label and numbers.
 */
data class LeaguePage(
    val key: String,
    val info: LeagueInfo,
    val rank: LeagueRank?,
    val top: List<SeasonRow>,
    val clubs: List<ClubStandingRow>,
    val stars: List<LadderRow>,
    val champions: List<Pair<Int, String>>,
    val moves: List<ClubMove>,
    val tiers: List<Pair<String, AggregateStats>>,
)

/** A federation proposal; [choice] the player's (null: none), [result] and [by] as decided ([Proposal]'s codes). */
data class ProposalView(
    val season: Int,
    val number: Int,
    val title: String,
    val evidence: String,
    val lever: String,
    val from: String,
    val to: String,
    val choice: Boolean?,
    val result: Char,
    val by: Char,
    val yes: Int,
    val no: Int,
    val appliesFrom: Int,
)

/**
 * The 요트협회's page: [enabled] its rules switch (the next season's), [pending] the proposals on
 * the vote (decided at the close of season [nextClose]), [history] the decided ones (newest first),
 * [awards] the last closed season's awards (label, winner) and [awardsSeason] that season (0: none).
 */
data class FederationView(
    val enabled: Boolean,
    val pending: List<ProposalView>,
    val history: List<ProposalView>,
    val awards: List<Pair<String, String>>,
    val nextClose: Int,
    val awardsSeason: Int = 0,
)

/** 이적·드래프트 of national clubs: the kept moves, newest first, and the last window's numbers. */
data class MarketView(val season: Int, val moves: List<ClubMove>, val digest: ClubDigest?)

// ---- UI preferences (league/ui.txt) ------------------------------------------------------------

/**
 * What the league screens remember across restarts and backups (league/ui.txt): the season a
 * banner was dismissed for, by banner ([REFORM], [COMING]), and the watch screen's pace.
 */
data class UiPrefs(val dismissed: Map<String, Int> = emptyMap(), val watchPace: WatchPace = WatchPace.NORMAL) {
    /** True when [banner] was dismissed for [season]. */
    fun dismissed(banner: String, season: Int): Boolean = dismissed[banner] == season

    fun dismiss(banner: String, season: Int): UiPrefs = copy(dismissed = dismissed + (banner to season))

    fun encode(): String = buildString {
        append("ui $VERSION\n")
        for ((banner, season) in dismissed.entries.sortedBy { it.key }) append("dismissed $banner $season\n")
        append("watchpace ${watchPace.name}\n")
    }

    companion object {
        const val VERSION = 1
        const val FILE = "ui.txt"

        /** The banners: the first national season's, and the one telling of a reform to come. */
        const val REFORM = "reform"
        const val COMING = "coming"

        /** [text] as written by [encode]; a missing or unreadable file, or a line that is not understood, gives the defaults. */
        fun decode(text: String?): UiPrefs {
            val lines = text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (lines.firstOrNull()?.split(' ')?.let { it.size == 2 && it[0] == "ui" && it[1].toIntOrNull() == VERSION } != true) return UiPrefs()
            var prefs = UiPrefs()
            for (line in lines.drop(1)) {
                val f = line.split(' ')
                when {
                    f.size == 3 && f[0] == "dismissed" && f[1].isNotEmpty() -> f[2].toIntOrNull()?.let { prefs = prefs.dismiss(f[1], it) }
                    f.size == 2 && f[0] == "watchpace" -> WatchPace.entries.firstOrNull { it.name == f[1] }?.let { prefs = prefs.copy(watchPace = it) }
                }
            }
            return prefs
        }
    }
}
