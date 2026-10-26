package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.Mode
import yacht.engine.Player
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.explain.AiStep
import yacht.engine.explain.Commentary
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.league.CloneProfile
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.Family
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Fixture
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.WeekStage
import yacht.engine.league.LeagueWorld
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.NationBook
import yacht.engine.league.Nations
import yacht.engine.league.diff
import yacht.engine.league.Origin
import yacht.engine.league.Club
import yacht.engine.league.ClubLeague
import yacht.engine.league.ClubMatch
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubRow
import yacht.engine.league.AiStats
import yacht.engine.league.Dynasty
import yacht.engine.league.HallOfFameEntry
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MetaReport
import yacht.engine.league.SeasonLine
import yacht.engine.league.SeasonReview
import yacht.engine.league.SeasonSummary
import yacht.engine.league.Article
import yacht.engine.league.Highlight
import yacht.engine.league.LiveFrame
import yacht.engine.league.WatchTurn
import yacht.engine.league.AiOdds
import yacht.engine.league.BoxProfile
import yacht.engine.league.LabResult
import yacht.engine.league.MatchFormat
import yacht.engine.league.MatchPreview
import yacht.engine.league.Odds
import yacht.engine.league.OddsTable
import yacht.engine.league.Preview
import yacht.engine.league.Scouting
import yacht.engine.league.ScoutingReport
import yacht.engine.league.boxProfile
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerRecord
import yacht.engine.league.leagueAi
import yacht.engine.league.WinEstimate
import yacht.engine.league.TierName
import yacht.engine.league.WatchMatch
import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Matchmaker
import yacht.engine.league.LeagueTables
import yacht.engine.league.Tier
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    /** Its division as shown ("3부", "3부 B조"), null when not in this season. */
    val divisionLabel: String? = null,
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
)

/** A match being played: its two sides (team ids for team matches) and where. */
data class LiveMatch(val a: String, val b: String, val comp: Comp, val label: String, val week: Int)

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
)

/** The 클럽 tab: this season's club table, every club and the latest transfers and draft picks. */
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

data class SeasonView(
    val number: Int,
    val played: Int,
    val total: Int,
    val divisions: List<List<SeasonRow>>,
    /** AIs that join the lowest division next season. */
    val newcomers: List<String>,
    /** Latest first. */
    val history: List<SeasonHistoryItem>,
    /** Places after each finished matchday per division, in [order] (순위 변동 그래프). */
    val positions: List<List<ShortArray>> = emptyList(),
    /** Each division's AIs in the order [positions] uses. */
    val order: List<List<String>> = emptyList(),
    val matchdays: Int = 0,
    val currentMatchday: Int = 0,
    /** Which tier each division is in (a regional pyramid's groups), and how many go up from each lower group. */
    val tiers: yacht.engine.league.Tiers = yacht.engine.league.Tiers.linear(divisions.size),
    val swap: Int = 0,
)

/** One fixture in the calendar (일정표): its games once played, or whether it is being played. */
data class CalendarItem(
    val id: Int,
    val a: String,
    val aName: String,
    val b: String,
    val bName: String,
    val games: List<GameScore>?,
    val live: Boolean,
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
data class RecordsView(
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

/** A matchday of a division in the calendar. */
data class CalendarView(val division: Int, val matchday: Int, val matchdays: Int, val current: Int, val items: List<CalendarItem>, val season: Int = 0)

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

/** How many cores the league may use (battery). */
/** What the league does while the app is hidden. */
enum class LeagueBackground(val label: String) {
    /** It stops (battery) and goes on when the app is back. */
    PAUSE("나가면 멈춤"),

    /** It keeps playing: the app shows a notification and keeps the phone awake while it runs. */
    KEEP("나가도 계속"),
}

enum class LeagueSpeed(val label: String) {
    ECO("절약 · 1코어"),
    NORMAL("보통 · 절반"),
    FULL("최대 · 전부");

    fun threads(cores: Int): Int = when (this) {
        ECO -> 1
        NORMAL -> maxOf(1, cores / 2)
        FULL -> maxOf(1, cores)
    }
}

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

/** A game being watched: the two AIs, the steps worked out so far, and the one shown. */
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
    /** A highlight's caption when one is replayed (not counted anywhere). */
    val title: String? = null,
    /** Why it cannot be shown (an AI changed since), instead of the game. */
    val message: String? = null,
)

/**
 * The AI league: dozens of AIs play mirrored matches in the background while the screen shows
 * the ladder and the season live. Runs only while started and the app is visible; everything is
 * saved under [dir]. Also rates the player's ranked games and plays games back for watching.
 */
class LeagueController(
    private val scope: CoroutineScope,
    dir: File,
    /** The seed ladder (a league played on a server), when the app ships one. */
    private val seed: () -> String? = { null },
) {
    private val folder = File(dir, "league").apply { mkdirs() }
    private val configFile = File(folder, "config.txt")
    private val customFile = File(folder, "custom.txt")
    private val prefs = File(folder, "prefs.txt")
    private val backgroundFile = File(folder, "background.txt")

    /** Present while the league should be running: it goes on by itself the next time the app opens. */
    private val resumeFile = File(folder, "resume.txt")
    private val favouriteFile = File(folder, "favourites.txt")
    private val cores = Runtime.getRuntime().availableProcessors()

    var view by mutableStateOf<LeagueView?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    /** What the league does while the app is hidden (saved). */
    var background by mutableStateOf(LeagueBackground.PAUSE)
        private set

    /** Why the league stopped by itself (errors), shown on its screen until the next start. */
    var stopReason by mutableStateOf<String?>(null)
        private set

    /** Told about every error the league meets while it runs (the app logs it). */
    var onError: (Throwable) -> Unit = {}

    /** Errors in a row while running (reset by every recorded match). */
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /** Progress text while the extra tables are built (first start only). */
    var preparing by mutableStateOf<String?>(null)
        private set
    var speed by mutableStateOf(LeagueSpeed.NORMAL)
        private set

    /** Matches per minute in this run. */
    var pace by mutableStateOf(0.0)
        private set

    /** Screen state: the tab shown (ladder, season, news, families) and the AI opened in detail. */
    var tab by mutableStateOf(0)
    var detail by mutableStateOf<String?>(null)

    /** The competition the 대회 tab shows (null: the first), and the week the 일정 tab shows (null: the week in play). */
    var compFocus by mutableStateOf<Comp?>(null)
    var scheduleWeek by mutableStateOf<Int?>(null)

    /** The player's own AIs (saved) and clone profile. */
    var customs by mutableStateOf<List<CustomAiSpec>>(emptyList())
        private set
    var clone by mutableStateOf<CloneProfile?>(null)
        private set

    /** 응원 AI: the AIs the player follows (starred on the ladder, their news first). */
    var favourites by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleFavourite(key: String) {
        favourites = if (key in favourites) favourites - key else favourites + key
        val text = favourites.sorted().joinToString("\n")
        scope.launch(fileWrites) { runCatching { favouriteFile.writeTextAtomically(text) } }
    }

    /** A season that just ended (shown until dismissed). */
    var seasonNews by mutableStateOf<SeasonHistoryItem?>(null)

    private var tables: Tables? = null
    private var baseRoster: List<LeagueAi> = emptyList()
    @Volatile
    private var roster: List<LeagueAi> = emptyList()
    @Volatile
    private var byKey: Map<String, LeagueAi> = emptyMap()
    private var heavyKeys: Set<String> = emptySet()
    private var extra: LeagueTables? = null
    /** The league's state (ladder, season, honours, players); null until the saved league is read. */
    private var world: LeagueWorld? = null
    private val ladder: Ladder? get() = world?.ladder
    private val plan: SeasonPlan? get() = world?.plan
    private val honours: HonourBook get() = world?.honours ?: HonourBook()
    private var estimate: WinEstimate? = null

    /** Average ms per game of each AI (cost meter: heavy fixtures first late in a season). */
    private val costMs = HashMap<String, Double>()

    /** Knockout and group jobs in flight (they may take at most all but one worker). */
    private var cupsInFlight = 0

    /** Time spent holding the lock, by what held it (for the stress harness and tuning). */
    val timings = LockTimings()

    /** Lab jobs in flight (up to half the workers). */
    private var labInFlight = 0

    /** Legends rebuilt for the lab (they are not in the roster). */
    private val legendAis = HashMap<String, LeagueAi>()

    /** A roster AI, or a legend rebuilt from its genes. */
    private fun aiOf(key: String): LeagueAi? = byKey[key] ?: synchronized(lock) {
        legendAis[key] ?: run {
            val t = tables ?: return@run null
            val x = extra ?: return@run null
            world?.players?.legend(key)?.let { leagueAi(it, t, x) }?.also { legendAis[key] = it }
        }
    }

    /** The league's rules (saved); structural changes apply from the next season. */
    var config by mutableStateOf(LeagueConfig())
        private set

    private val lock = Any()
    private val throttle = Any()
    private val busy = HashSet<String>()
    /** Jobs being played, by their two sides. */
    private val live = LinkedHashMap<Pair<String, String>, Fixture>()
    private val lastDelta = HashMap<String, Int>()
    private var startRanks: Map<String, Int> = emptyMap()
    private var stop = AtomicBoolean(true)
    private var runJob: Job? = null
    /** The ladder and its version the last full fit was made for. */
    private var fittedLadder: Ladder? = null
    private var fittedVersion = -1L
    private var runStart = 0L
    private var runMatches = 0
    private var lastPublish = 0L
    private var publishPending = false
    private var snapshots = 0L
    private val snapshotRunning = AtomicBoolean(false)
    private val snapshotAgain = AtomicBoolean(false)
    private var shownSnapshot = 0L

    init {
        runCatching {
            prefs.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueSpeed.entries.firstOrNull { it.name == v }?.let { speed = it } }
        }
        runCatching {
            backgroundFile.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueBackground.entries.firstOrNull { it.name == v }?.let { background = it } }
        }
        customs = runCatching { customFile.takeIf { it.exists() }?.readLines()?.mapNotNull { CustomAiSpec.decode(it) } }.getOrNull().orEmpty()
        config = readConfig()
        favourites = readFavourites()
    }

    /**
     * The saved rules, or the defaults. Rules saved before the pyramid with the old default of
     * 200 players take today's default (a league of thousands): the players come in at the
     * next season's close and the league becomes a pyramid.
     */
    private fun readConfig(): LeagueConfig = runCatching {
        configFile.takeIf { it.exists() }?.readText()?.let { text ->
            val c = LeagueConfig.decode(text)
            if (!text.lines().any { it.startsWith("pyramid ") } && c.generated == OLD_DEFAULT_PLAYERS) c.copy(generated = LeagueConfig.DEFAULT_PLAYERS) else c
        }
    }.getOrNull() ?: LeagueConfig()

    private fun readFavourites(): Set<String> =
        runCatching { favouriteFile.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() }.getOrNull().orEmpty()

    /** Number of AIs in the league. */
    val size: Int get() = roster.size

    /** Loads the roster and the saved ladder and season (once tables are there); no tables are built here. */
    fun attach(t: Tables) {
        if (tables != null) return
        tables = t
        val rules = t.rules
        val r = LeagueTables(
            rules, threads = cores,
            // A table that cannot be read (cut off by an older version) is built again.
            load = { key, believed -> runCatching { tableFile(key).takeIf { it.exists() }?.let { TableIO.readEv(ByteBuffer.wrap(it.readBytes()), believed) } }.getOrNull() },
            save = { key, table: EvTable ->
                val out = java.io.ByteArrayOutputStream()
                TableIO.writeEv(out, table.rules, table.values)
                tableFile(key).writeAtomically(out.toByteArray())
            },
        )
        extra = r
        estimate = WinEstimate(t)
        baseRoster = LeagueRoster.build(t, r)
        rebuildRoster()
        reload()
    }

    /** Completed once the saved league has been read for the first time. */
    val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Matches on the loaded ladder (0 before it is loaded). */
    fun matchCount(): Long = synchronized(lock) { ladder?.matches ?: 0L }

    /** Reads the saved ladder and season again (after a restore). */
    fun reload() {
        scope.launch(rosterWork) {
            try {
                readLeague()
            } finally {
                restoring = false
            }
        }
    }

    /** Reads the league files into a new world (on [rosterWork]). */
    private fun readLeague() {
        val texts = LeagueWorld.FILES.associateWith { name -> runCatching { File(folder, name).takeIf { it.exists() }?.readText() }.getOrNull() }
        // As at start-up: no rules file means the default rules (a restore removes this phone's).
        val cfg = readConfig()
        val reserved = baseRoster.map { it.name }
        // The generated and evolved players first: they are AIs of the roster, so the
        // ladder and the season read with them.
        val players = texts[LeagueWorld.ROSTER]?.let { runCatching { PlayerBook.decode(it, reserved) }.getOrNull() } ?: PlayerBook(reserved)
        // Players added mid-league debut in the season being played.
        val season = texts[LeagueWorld.SEASON]?.let { t -> Regex("(?m)^number (\\d+) ").find(t)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
        // Up to the setting (a raised count joins now), but not while a closed season waits to
        // be closed again after a kill: that close tops up with the rules it was first closed with.
        val closing = texts[LeagueWorld.SEASON]?.contains("\nclosecfg ") == true
        if (!closing && players.size < cfg.generated) players.ensureGenerated(cfg.generated, season, cfg.generated - players.size)
        synchronized(lock) { pendingPlayers = players }
        rebuildRoster()
        val w = synchronized(lock) { LeagueWorld.decode(texts, ladderKeys(), reserved) }
        w.players = players
        synchronized(lock) {
            world = w
            pendingPlayers = null
            // The rules just read (the controller's own reach the screen below).
            configureWorld(w, cfg)
            legendAis.clear()
            oddsNow = null
        }
        val first = !loaded.isCompleted
        loaded.complete(Unit)
        // Running when the app was last closed (or killed): it goes on by itself, once the app is
        // in view (hidden while loading, it waits for the app to come back: the 나가면 멈춤
        // choice, and Android does not let a hidden app start the 나가도 계속 service).
        if (first && resumeFile.exists()) scope.launch { if (visible) start() else resumeOnForeground = true }
        // A restore may have brought the rules and the favourites too.
        val favs = readFavourites()
        val rv = synchronized(lock) { w.archive.pendingReview }
        scope.launch {
            config = cfg
            favourites = favs
            review = rv
            seasonNews = null
        }
        publish(force = true)
    }

    /**
     * Takes a backup's league files (this league has no games): the league stops, the files
     * replace every saved one (a file the backup lacks is removed) and the league is read again.
     * Saves of the league being replaced that are still on their way are dropped.
     */
    suspend fun restore(files: Map<String, ByteArray>) {
        restoring = true
        try {
            stop()
            runJob?.join()
            withContext(fileWrites) {
                for (name in SAVED) {
                    val f = File(folder, name)
                    runCatching { files[name]?.let { f.writeAtomically(it) } ?: f.delete() }
                }
            }
        } catch (e: Throwable) {
            restoring = false
            throw e
        }
        reload()
    }

    /** True from a restore until its league is read: no save may write the old league over it. */
    @Volatile
    private var restoring = false

    private fun ladderKeys() = roster.map { it.key } + Ladder.ME

    /** The players book while the league is being read (before [world] exists). */
    private var pendingPlayers: PlayerBook? = null

    /**
     * The clone and the player's AIs, the hand-made AIs, then the generated and evolved players;
     * the ladder follows (and the league's members, see [members]). Safe under the lock.
     */
    private fun rebuildRoster() {
        val t = tables ?: return
        val x = extra ?: return
        synchronized(lock) {
            val mine = ArrayList<LeagueAi>()
            clone?.let { mine += CustomAis.clone(it, t) }
            for (c in customs) mine += CustomAis.build(c, t, x)
            // The book being read first (a restore reads a new one while the old world is here).
            val players = pendingPlayers ?: world?.players
            val generated = players?.alive.orEmpty().map { leagueAi(it, t, x) }
            val all = mine + baseRoster + generated
            roster = all
            byKey = all.associateBy { it.key }
            players?.let { b ->
                pastNames = (b.retired.map { it.key to it.name } + b.legends.map { it.key to it.name }).toMap()
                legendsNow = b.legends.map { it.key to it.name }
            }
            heavyKeys = all.filter { it.heavy }.map { it.key }.toSet()
            val keys = ladderKeys()
            world?.let { w ->
                if (w.ladder.keys != keys) w.ladder = w.ladder.rekey(keys)
                configureWorld(w)
            }
        }
    }

    /** What the world takes from the roster and the rules: the members, families and config. */
    private fun configureWorld(w: LeagueWorld, c: LeagueConfig = config) {
        w.config = c
        w.family = { k -> byKey[k]?.family }
        // An AI deleted mid-season (a custom AI, the clone gone) places nowhere at its end.
        w.present = { k -> k in byKey }
        w.name = { k -> name(k) }
        // What a highlight replay checks: an AI's play (its description and, for players, its genes).
        w.version = { k -> (byKey[k]?.desc ?: "") + (customs.firstOrNull { it.key == k }?.encode() ?: "") + (w.players[k]?.spec?.encode()?.substringAfter('|')?.substringAfter('|') ?: "") }
        w.members = members(w, c)
    }

    /**
     * Next season's AIs: everyone but me, or only the AIs with mistakes (대혼돈), or the best
     * rated (강자만); my own AIs always play.
     */
    private fun members(w: LeagueWorld, c: LeagueConfig): List<String> {
        var keys = roster.filter { ai ->
            val player = w.players[ai.key]
            // No generated players asked for: the book's players sit out (they stay on the ladder).
            // The hand-made AIs play only when asked (they stay for ranked games and the lab).
            (player == null || c.generated > 0) && (c.baseAis || Origin.of(ai.key) != Origin.BASE) &&
                (!c.onlyFlawed || ai.family == Family.MINE || ai.family in LeagueWorld.FLAWED || player?.spec?.flawed == true)
        }.map { it.key }
        if (c.onlyTop > 0) {
            val mine = keys.filter { byKey[it]?.family == Family.MINE }
            keys = (keys.filter { it !in mine }.sortedByDescending { w.rating(it) }.take(c.onlyTop) + mine)
        }
        return keys
    }

    private fun tableFile(key: String) = File(folder, "$key.bin")

    fun changeSpeed(s: LeagueSpeed) {
        speed = s
        scope.launch(fileWrites) { runCatching { prefs.writeTextAtomically(s.name) } }
        if (running) {
            stop(keepResume = true)
            start()
        }
    }

    // ---- the player's AIs ----------------------------------------------------------------------

    /**
     * Roster changes and reloads run one at a time off the main thread: each re-reads the
     * ladder (hundreds of ms on a phone), and they must not overtake each other.
     */
    private val rosterWork = Dispatchers.Default.limitedParallelism(1)

    /** League files are written one at a time, in order: two saves of a file never overtake each other. */
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

    private fun rosterChanged(after: () -> Unit = {}) {
        scope.launch(rosterWork) {
            rebuildRoster()
            after()
            publish(force = true)
        }
    }

    /** Updates the clone from my finished games (null: not enough games yet). */
    fun updateClone(profile: CloneProfile?) {
        if (profile == clone) return
        clone = profile
        rosterChanged()
    }

    fun saveCustom(spec: CustomAiSpec) {
        val old = customs.firstOrNull { it.id == spec.id }
        customs = customs.filter { it.id != spec.id } + spec
        persistCustoms()
        // Changed play (not just the name): its old results no longer describe it.
        val replay = old != null && old.copy(name = spec.name) != spec
        rosterChanged { if (replay) forget(spec.key) }
    }

    /** Drops [key]'s ladder results so it is placed again. */
    private fun forget(key: String) {
        synchronized(lock) {
            val w = world ?: return
            val keys = ladderKeys()
            w.ladder = w.ladder.rekey(keys - key).rekey(keys)
        }
        scope.launch { save() }
    }

    fun deleteCustom(id: String) {
        customs = customs.filter { it.id != id }
        persistCustoms()
        rosterChanged()
    }

    private fun persistCustoms() {
        val text = customs.joinToString("\n") { it.encode() }
        scope.launch(fileWrites) { runCatching { customFile.writeTextAtomically(text) } }
    }

    // ---- running ---------------------------------------------------------------------------------

    /** Starts (or resumes) the league. */
    fun start() {
        val t = tables ?: return
        if (running || ladder == null || restoring) return
        running = true
        stopReason = null
        failures.set(0)
        scope.launch(fileWrites) { runCatching { resumeFile.writeTextAtomically("1") } }
        val flag = AtomicBoolean(false)
        stop = flag
        val previous = runJob
        runStart = System.currentTimeMillis()
        runMatches = 0
        pace = 0.0
        val threads = speed.threads(cores)
        runJob = scope.launch {
            try {
                // A restart (speed change) waits for the old workers; ranks and the first season
                // need the lock the workers hold: both off the main thread.
                previous?.join()
                withContext(Dispatchers.Default) {
                    synchronized(lock) {
                        val w = world ?: return@synchronized
                        val l = w.ladder
                        startRanks = l.keys.mapNotNull { k -> l.rankOf(k)?.let { k to it } }.toMap()
                        if (w.plan == null) {
                            configureWorld(w)
                            w.openFirstSeason(System.nanoTime())
                        }
                    }
                }
                val r = extra!!
                // Risk-attitude and rule-belief AIs need their tables: built once, then loaded from disk.
                val missing = LeagueRoster.tableSpecs(t.rules).filter { !r.has(it.key) }
                for ((i, th) in missing.withIndex()) {
                    if (flag.get()) break
                    preparing = "새 AI용 계산표 준비 중 ${i + 1}/${missing.size}"
                    withContext(Dispatchers.Default) { r[th] }
                }
                preparing = null
                if (flag.get()) return@launch
                val pool = Executors.newFixedThreadPool(threads) { job -> Thread(job, "yacht-league").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
                val dispatcher = pool.asCoroutineDispatcher()
                try {
                    val workers = (0 until threads).map { w -> launch(dispatcher) { work(t, flag, Random(System.nanoTime() + w)) } }
                    val upkeep = launch(Dispatchers.Default) { upkeep(flag) }
                    workers.joinAll()
                    upkeep.cancel()
                } finally {
                    pool.shutdown()
                    dispatcher.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Preparing the tables or starting the season failed: shown, not a silent stop.
                withContext(kotlinx.coroutines.NonCancellable) { onError(e) }
                stopReason = reasonOf(e)
                clearResume()
            } finally {
                // A run that ended by itself (errors) must not leave the screen showing a league
                // running with no one playing.
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (stop === flag && running) {
                        flag.set(true)
                        running = false
                        preparing = null
                    }
                }
            }
        }
    }

    /**
     * What a worker plays next: the season's next fixture (ending the season and starting the
     * next when it is over), or a friendly (newcomers waiting for a season, or nothing free).
     */
    private fun nextJob(rng: Random): Fixture? {
        val w = world ?: return null
        val l = w.ladder
        // No season (a restore read an old season file while the league was starting): a
        // first one, or the workers would wait forever.
        if (w.plan == null) {
            configureWorld(w)
            w.openFirstSeason(System.nanoTime())
        }
        val p = w.plan ?: return null
        val inLeague = p.league.standings.keys
        val members = w.members
        // Newcomers still in placement (a new roster, a lot of generated players): placed
        // quickly, against light AIs (a heavy one would hold up its own league fixtures).
        val newcomers = members.filter { it !in inLeague && it !in busy && !l.placed(it) }
        val share = if (newcomers.size * 10 > members.size) FRIENDLY_RUSH else FRIENDLY_SHARE
        if (newcomers.isNotEmpty() && rng.nextDouble() < share) {
            Matchmaker.pick(l, rng, busy + (heavyKeys - newcomers.toSet()), among = newcomers)?.let { return friendly(it, rng) }
        }
        // 기준 경기: without the hand-made AIs in the league, the max-expectation AI (1500 by
        // definition) plays one now and then against a member near 1500, so the scale stays put.
        if (!w.config.baseAis && ANCHOR !in busy && rng.nextDouble() < CALIBRATION_SHARE) {
            val near = members.filter { it !in busy && l.placed(it) }
            Matchmaker.opponentFor(l, 1500.0, rng, among = near)?.let { return friendly(ANCHOR to it, rng, CALIBRATION_LABEL) }
        }
        // AI 연구소: up to half the workers on the experiment, alongside the league; a single
        // worker plays the two by turns.
        val labCap = speed.threads(cores) / 2
        if (if (labCap > 0) labInFlight < labCap else rng.nextBoolean()) w.lab.next()?.let { return it }
        val cost = { f: yacht.engine.league.LeagueFixture -> (costMs[f.a] ?: 5.0) + (costMs[f.b] ?: 5.0) }
        val cap = maxOf(1, speed.threads(cores) - 1)
        w.next(busy, cupsInFlight, cap, cost)?.let { return it }
        if (w.seasonOver) {
            rollover(w)
            w.next(busy, cupsInFlight, cap, cost)?.let { return it }
        }
        // Nothing official is free: the worker waits. (Friendlies to fill the wait went to the
        // light AIs only, a heavy one holding up its own fixtures, so the light ones piled up
        // thousands more games than the heavy ones: not fair. Waiting also saves battery.)
        return null
    }

    /**
     * The season is over (under the lock): honours, evolution and new players, the AIs rebuilt,
     * then the next season. Saved at once.
     */
    private fun rollover(w: LeagueWorld) {
        // Next season's divisions and seeds from the converged ratings.
        val t0 = System.nanoTime()
        // The rules saved during the season apply from here (evolution, generated players).
        // A close replayed after a kill keeps the rules of the first one (saved with the
        // finished season), even if they were changed before the league resumed.
        val p = w.plan ?: return
        val rules = p.closing ?: config
        p.closing = rules
        configureWorld(w, rules)
        w.ladder.fit()
        // The finished season on disk first: killed while the close is saved, the league reads
        // the finished season again and closes it the same way (not a checkpoint from before).
        saveAsync()
        val closing = w.closeSeason()
        if (closing.rosterChanged) rebuildRoster()
        // The next season: members and clubs as closed (they must replay the same), its shape
        // (divisions, format, competitions) from the rules as they are now.
        configureWorld(
            w,
            config.copy(
                generated = rules.generated, aging = rules.aging, childShare = rules.childShare, baseAis = rules.baseAis,
                onlyFlawed = rules.onlyFlawed, onlyTop = rules.onlyTop,
                clubs = rules.clubs, squad = rules.squad,
            ),
        )
        w.openSeason()
        timings.note("rollover", System.nanoTime() - t0)
        val news = historyItem(closing.season, closing.honours)
        val rv = w.archive.pendingReview
        scope.launch {
            seasonNews = news
            if (rv != null) review = rv
        }
        saveAsync()
    }

    private fun friendly(pair: Pair<String, String>, rng: Random, label: String = "") =
        Fixture(Comp.FRIENDLY, -1, pair.first, pair.second, rng.nextLong(), label = label)

    /**
     * A job that failed twice (it would fail the same way every time: the deal is fixed): settled
     * level and off the ladder, so the season can go past it (a level knockout tie sends side A,
     * the better seed, through). No games: nothing is counted in the statistics.
     */
    private fun void(job: Fixture): JobResult {
        val games = listOf(GameScore(0, 0))
        val boards = if (job.kind == JobKind.TEAM) job.lineupA.zip(job.lineupB).map { (x, y) -> BoardResult(x, y, games, emptyList()) }
        else listOf(BoardResult(job.a, job.b, games, emptyList()))
        return JobResult(job.copy(rated = false), boards, 0, 0)
    }

    /** Failures per season fixture this run ("season:comp:id"), under the lock. */
    private val jobFailures = HashMap<String, Int>()

    /** A job that cannot be played (an AI was removed): the side still here wins, off the ladder. */
    private fun forfeit(job: Fixture): JobResult {
        val aHere = job.players.take(maxOf(1, job.lineupA.size)).all { byKey.containsKey(it) }
        val games = listOf(if (aHere) GameScore(1, 0) else GameScore(0, 1))
        val boards = if (job.kind == JobKind.TEAM) job.lineupA.zip(job.lineupB).map { (x, y) -> BoardResult(x, y, games, emptyList()) }
        else listOf(BoardResult(job.a, job.b, games, emptyList()))
        return JobResult(job.copy(rated = false), boards, if (aHere) 1 else -1, 0)
    }

    private suspend fun work(t: Tables, flag: AtomicBoolean, rng: Random) {
        while (!flag.get()) {
            var seasonFormat = config.matchFormat
            val job = try {
                synchronized(lock) { takeJob(rng) { f -> seasonFormat = f } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Even the next fixture failed (a season that cannot close): retried a few times,
                // then the league stops and says why.
                if (failed(e, flag)) break
                delay(ERROR_PAUSE_MS)
                continue
            }
            if (job == null) {
                delay(50)
                continue
            }
            publish()
            var recorded = false
            try {
                // The lab always plays mirrored matches (its statistics assume them).
                val format = if (job.comp == Comp.LAB) MatchFormat.MIRRORED else seasonFormat
                val played = LeagueMatch.playJob(t.rules, job, { aiOf(it) }, format, estimate) { flag.get() }
                // A lab AI that is gone ends the experiment (a forfeit would count as a result).
                if (played == null && job.comp == Comp.LAB) {
                    if (!flag.get()) {
                        synchronized(lock) { world?.lab?.stop() }
                        publishLab()
                    }
                    continue
                }
                val result = played ?: forfeit(job)
                synchronized(lock) {
                    val w = world ?: return@synchronized
                    if (flag.get()) return@synchronized
                    // Statistics, the ladder and the season, in that order (see LeagueWorld.recordJob).
                    val t0 = System.nanoTime()
                    val rec = w.recordJob(result, System.currentTimeMillis())
                    timings.note("record", System.nanoTime() - t0)
                    for (m in rec.matches) {
                        lastDelta[m.a] = m.deltaA
                        lastDelta[m.b] = m.deltaB
                    }
                    recorded = true
                    meter(result)
                    runMatches += result.boards.size
                }
                failures.set(0)
            } catch (e: CancellationException) {
                // stopped mid-match: the match is dropped
            } catch (e: Exception) {
                // This match failed: it is given back to the season and the league goes on; the
                // second time (it fails the same way: its deal is fixed) it is settled level.
                if (job.comp != Comp.LAB && job.comp != Comp.FRIENDLY) synchronized(lock) {
                    val w = world
                    val key = "${w?.plan?.number}:${job.comp.code}:${job.id}"
                    val n = (jobFailures[key] ?: 0) + 1
                    jobFailures[key] = n
                    if (n >= 2 && w != null && !flag.get()) {
                        recorded = runCatching { w.recordJob(void(job), System.currentTimeMillis()) }.isSuccess
                        if (recorded) jobFailures.remove(key)
                    }
                }
                if (!failed(e, flag) && !recorded) delay(ERROR_PAUSE_MS)
            } finally {
                synchronized(lock) {
                    if (!recorded && job.comp != Comp.FRIENDLY) world?.release(job)
                    if (job.comp == Comp.LAB) labInFlight-- else {
                        busy -= job.players.toSet()
                        live.remove(job.a to job.b)
                    }
                    if (isCup(job.comp)) cupsInFlight--
                    if (job.kind == JobKind.TEAM) world?.let { w -> w.teamsInFlight = maxOf(0, w.teamsInFlight - 1) }
                }
            }
            publish()
        }
    }

    /** The next job, marked as taken (under the lock); [format] gets the season's match format. */
    private fun takeJob(rng: Random, format: (MatchFormat) -> Unit): Fixture? =
        nextJob(rng)?.also {
            // The season keeps the format it started with (a change applies next season); read
            // after nextJob, which may have opened the next season.
            world?.plan?.config?.matchFormat?.let(format)
            // Lab games may run while their AIs play league games: they take no one's slot.
            if (it.comp == Comp.LAB) labInFlight++ else {
                busy += it.players
                live[it.a to it.b] = it
            }
            if (isCup(it.comp)) cupsInFlight++
            if (it.kind == JobKind.TEAM) world?.let { w -> w.teamsInFlight++ }
        }

    /**
     * An error while running: told to the app; true (and the league stops, saying why) once
     * [MAX_FAILURES] came in a row.
     */
    private fun failed(e: Exception, flag: AtomicBoolean): Boolean {
        // Told on the main thread (the app shows it).
        scope.launch { onError(e) }
        if (failures.incrementAndGet() < MAX_FAILURES) return false
        if (!flag.getAndSet(true)) {
            scope.launch {
                stopReason = reasonOf(e)
                clearResume()
            }
        }
        return true
    }

    private fun reasonOf(e: Throwable): String =
        "리그가 오류로 멈췄어요: ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}. ‘이어서’를 누르면 다시 해 봐요."

    private fun clearResume() {
        scope.launch(fileWrites) { runCatching { resumeFile.delete() } }
    }

    /**
     * While the league runs: the full rating fit every [FIT_MS] (solved off the lock; a match
     * only nudges its two players) and a save every [CHECKPOINT_MS] (stop saves at once).
     */
    private suspend fun upkeep(flag: AtomicBoolean) {
        var lastSave = System.currentTimeMillis()
        while (!flag.get()) {
            delay(FIT_MS)
            try {
                refit()
                refreshOdds()
                if (System.currentTimeMillis() - lastSave >= CHECKPOINT_MS && !flag.get()) {
                    lastSave = System.currentTimeMillis()
                    save()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failed(e, flag)) break
            }
            publish()
        }
    }

    /** The latest 우승·승강 확률, made off the lock at most every 10% of the season. */
    @Volatile
    private var oddsNow: OddsTable? = null

    private fun refreshOdds(force: Boolean = false) {
        var seed = 0L
        val input = synchronized(lock) {
            val w = world ?: return
            val p = w.plan ?: return
            val o = oddsNow
            if (!force && o != null && !o.stale(p.league)) return
            // From the season's seed and progress: the same league gives the same odds.
            seed = p.seed xor p.league.fixturesPlayed.toLong()
            Odds.input(p, { k -> w.rating(k) })
        }
        val table = runCatching { Odds.run(input, Odds.runsFor(Odds.left(input)), seed) }.getOrNull() ?: return
        oddsNow = table
        publish()
    }

    /** The full fit of the ladder when it changed since the last one; the lock is held only to copy and to apply. */
    private fun refit() {
        val (l, input) = synchronized(lock) {
            val l = ladder ?: return
            if (l === fittedLadder && l.version == fittedVersion) return
            if (l.matches == 0L) return
            l to l.fitInput()
        }
        val r = Ladder.solve(input)
        synchronized(lock) {
            if (ladder !== l) return
            l.applyFit(r)
            fittedLadder = l
            fittedVersion = l.version
        }
    }

    /** Knockout and group competitions (capped so the league always has a worker). */
    private fun isCup(c: Comp) = c == Comp.CUP || c == Comp.CHAMPIONS || c == Comp.SUPER || c == Comp.TITLE_PLAYOFF || c == Comp.PROMOTION_PLAYOFF

    /** Updates each AI's ms per game from a finished job (half of each game's time per side). */
    private fun meter(r: JobResult) {
        val games = r.boards.sumOf { it.games.size }
        if (games == 0 || r.nanos == 0L) return
        val perSide = r.nanos / 1e6 / games / 2
        for (k in r.fixture.players) costMs[k] = (costMs[k] ?: perSide) * 0.8 + perSide * 0.2
    }

    /** Changes the league's rules: saved now, applied from the next season. */
    fun changeConfig(c: LeagueConfig) {
        config = c
        val text = c.encode()
        scope.launch(fileWrites) { runCatching { configFile.writeTextAtomically(text) } }
        // The season being played keeps its own copy (SeasonPlan.config); closing it reads these.
        scope.launch(Dispatchers.Default) { synchronized(lock) { world?.config = c } }
    }

    /**
     * Stops after abandoning the matches in progress, and saves. [keepResume]: it goes on by
     * itself later (the app hidden or closed, a speed change); a plain stop is the player's.
     */
    fun stop(keepResume: Boolean = false): Job? {
        if (!keepResume) clearResume()
        if (!running) return null
        stop.set(true)
        running = false
        preparing = null
        val job = runJob
        return scope.launch {
            // Leaving the app stops the league and then clears the scope: the last save must
            // not be cancelled with it.
            withContext(kotlinx.coroutines.NonCancellable) {
                job?.join()
                save()
            }
            publish(force = true)
        }
    }

    /**
     * App hidden: by default no league in the background (battery), and it goes on when the app
     * is back; with [LeagueBackground.KEEP] it keeps playing (the app keeps a notification up).
     */
    fun onBackground() {
        visible = false
        if (background == LeagueBackground.PAUSE) {
            if (running) resumeOnForeground = true
            stop(keepResume = true)
        }
        pauseWatch()
        if (liveJob?.isActive == true) {
            liveJob?.cancel()
            liveOnForeground = true
        }
    }

    /** App shown again: the league goes on if it was running when the app was hidden. */
    fun onForeground() {
        visible = true
        if (liveOnForeground) {
            liveOnForeground = false
            watchLive(true)
        }
        if (!resumeOnForeground) return
        resumeOnForeground = false
        start()
    }

    private var resumeOnForeground = false
    private var liveOnForeground = false

    /** The app is in view (main thread); true until the app says otherwise. */
    private var visible = true

    /** Sets what the league does while the app is hidden (saved). */
    fun changeBackground(b: LeagueBackground) {
        background = b
        scope.launch(fileWrites) { runCatching { backgroundFile.writeTextAtomically(b.name) } }
    }

    /** True when the app ships a seed ladder to start from. */
    val hasSeed: Boolean by lazy { seed() != null }

    /**
     * Starts from the seed ladder (tens of thousands of matches played on a server) instead of
     * from zero; only while this league has no games yet.
     */
    fun loadSeed() {
        if (running || (view?.matches ?: 0L) > 0L) return
        // In the run chain: a start tapped meanwhile waits for the new ladder.
        val previous = runJob
        runJob = scope.launch {
            previous?.join()
            val text = withContext(Dispatchers.IO) { seed() } ?: return@launch
            withContext(rosterWork) {
                val l = Ladder.decode(synchronized(lock) { ladderKeys() }, text)
                synchronized(lock) { renew(world?.players ?: return@synchronized, l) }
            }
            clearLoadedViews()
            save()
            publish(force = true)
        }
    }

    /**
     * A new world under the lock: [players] and [ladder] (a fresh one by default); the season,
     * clubs, stats, archive, news, highlights and the lab start again.
     */
    private fun renew(players: PlayerBook, ladder: Ladder? = null) {
        world = null
        pendingPlayers = players
        rebuildRoster()
        val w = LeagueWorld(ladder ?: Ladder(ladderKeys()), config).also { it.players = players }
        if (w.ladder.keys != ladderKeys()) w.ladder = w.ladder.rekey(ladderKeys())
        world = w
        pendingPlayers = null
        configureWorld(w)
        lastDelta.clear()
        startRanks = emptyMap()
        legendAis.clear()
        oddsNow = null
    }

    /** Forgets every result and season (my AIs and the built tables stay). */
    fun reset() {
        stop()
        // In the run chain: a start tapped meanwhile waits for the new world.
        val previous = runJob
        runJob = scope.launch {
            previous?.join()
            withContext(rosterWork) {
                // Fresh generated players too (the evolved ones go).
                val players = PlayerBook(baseRoster.map { it.name }).apply { ensureGenerated(config.generated) }
                synchronized(lock) { renew(players) }
            }
            withContext(fileWrites) {
                for (name in LeagueWorld.FILES) runCatching { File(folder, name).delete() }
            }
            review = null
            seasonNews = null
            clearLoadedViews()
            save()
            publish(force = true)
        }
    }

    /** Views loaded on request (records, legends, a profile, ...) after the league was replaced: read again or dropped. */
    private fun clearLoadedViews() {
        // An open profile or matrix is read again from the new league (its screen does not ask twice).
        profile?.key?.let { loadProfile(it) }
        lastMatrix?.let { (title, groups, labels) -> loadMatrix(title, groups, labels) }
        calendar = null
        preview = null
        loadRecords()
        loadPastPlayers()
    }

    private suspend fun save() = withContext(Dispatchers.Default) {
        var from: LeagueWorld? = null
        val texts = synchronized(lock) {
            val t0 = System.nanoTime()
            from = world
            world?.encodeFiles().also { timings.note("save", System.nanoTime() - t0) }
        } ?: return@withContext
        withContext(fileWrites) { write(texts, from) }
    }

    /** Saves every league file (under the lock: the encoding needs it). */
    private fun saveAsync() {
        val w = world ?: return
        val texts = w.encodeFiles()
        scope.launch(fileWrites) { write(texts, w) }
    }

    /**
     * Writes [from]'s files, in order; nothing when that world was replaced since (a reset or a
     * restore) or a restore is writing its files.
     */
    private fun write(texts: Map<String, String>, from: LeagueWorld?) {
        for ((name, text) in texts) {
            if (restoring || world !== from) return
            runCatching { File(folder, name).writeTextAtomically(text) }
        }
    }

    /** The league files as they are now (encoded under the lock) and the rules, for a backup. */
    fun backupFiles(): Map<String, ByteArray> {
        val texts = synchronized(lock) { world?.encodeFiles() }.orEmpty()
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, text) in texts) out[name] = text.toByteArray()
        for (f in listOf(configFile, customFile, prefs, favouriteFile)) {
            if (f.exists()) runCatching { out[f.name] = f.readBytes() }
        }
        // Before the league is read: the files on disk.
        if (texts.isEmpty()) for (name in LeagueWorld.FILES) File(folder, name).takeIf { it.exists() }?.let { f -> runCatching { out[name] = f.readBytes() } }
        return out
    }

    // ---- ranked games ----------------------------------------------------------------------------

    /** My rating, or null before my first ranked game. */
    fun myRating(): Double? = synchronized(lock) { ladder?.rating(Ladder.ME)?.takeIf { !it.isNaN() } }

    /**
     * An opponent for a ranked game: near my rating (1400 before I have one), or within [tier]
     * when given. Null when no AI is rated yet.
     */
    fun pickOpponent(rng: Random, tier: TierName? = null): String? {
        // From the screen's snapshot: the lock may be held by a season's rollover for a while.
        val rows = view?.rows ?: return pickOpponentLocked(rng, tier)
        val placed = rows.filter { !it.isMe && it.rank != null && it.rating != null && it.key in byKey }
        val among = if (tier == null) placed else placed.filter { it.tier?.name == tier }
        if (among.isEmpty()) return null
        val r = when {
            tier != null -> among.map { it.rating!!.toDouble() }.average()
            else -> rows.firstOrNull { it.isMe }?.rating?.toDouble() ?: START_RATING
        }
        return Matchmaker.opponentFor(among.map { it.key to it.rating!!.toDouble() }, r, rng)
    }

    private fun pickOpponentLocked(rng: Random, tier: TierName?): String? = synchronized(lock) {
        val l = ladder ?: return null
        val among = if (tier == null) null else l.keys.filter { k -> k != Ladder.ME && l.tier(k)?.name == tier }
        if (among != null && among.isEmpty()) return null
        val r = when {
            tier != null -> among!!.mapNotNull { l.rating(it).takeIf { x -> !x.isNaN() } }.average()
            else -> l.rating(Ladder.ME).takeIf { !it.isNaN() } ?: START_RATING
        }
        Matchmaker.opponentFor(l, r, rng, among) ?: among?.randomOrNull(kotlin.random.Random(rng.nextLong()))
    }

    /** A fresh player for a ranked game against [key]. */
    fun player(key: String, rng: Random): Player? = byKey[key]?.make?.invoke(rng)

    fun tierOf(key: String): Tier? = synchronized(lock) { ladder?.tier(key) }

    /** Counts a finished ranked game in my rating. */
    fun recordMyGame(ai: String, me: Board, opp: Board): RankResult? {
        val result = synchronized(lock) {
            val l = ladder ?: return null
            if (l.entry(ai) == null) return null
            val before = l.rating(Ladder.ME).takeIf { !it.isNaN() }
            val tierBefore = l.tier(Ladder.ME)
            l.record(
                Ladder.ME, ai,
                listOf(GameScore(me.total, opp.total, (me[Category.YACHT] ?: 0) > 0, (opp[Category.YACHT] ?: 0) > 0, me.bonus > 0, opp.bonus > 0)),
                comp = Comp.FRIENDLY, label = RANKED_LABEL, season = plan?.number ?: 0,
            )
            l.fit()
            val e = l.entry(Ladder.ME)!!
            RankResult(
                name(ai), me.total > opp.total, me.total == opp.total, before?.roundToInt(),
                l.rating(Ladder.ME).takeIf { !it.isNaN() }?.roundToInt(), tierBefore, l.tier(Ladder.ME), l.rankOf(Ladder.ME),
                e.games, maxOf(0, Ladder.ME_PLACEMENT - e.games),
            )
        }
        scope.launch { save() }
        publish(force = true)
        return result
    }

    // ---- watching a game -------------------------------------------------------------------------

    var watch by mutableStateOf<WatchView?>(null)
        private set
    private var watchJob: Job? = null
    private var playJob: Job? = null

    /** A game worth watching: a top-20 AI (any AI before ratings) against a rival near its rating. */
    fun featured(rng: Random): Pair<String, String>? {
        // From the screen's snapshot, not under the lock (a rollover may hold it).
        val rows = view?.rows.orEmpty().filter { !it.isMe && it.key in byKey }
        val top = rows.filter { it.rank != null }.take(20).map { it.key }
        val first = top.ifEmpty { roster.map { it.key } }.randomOrNull(kotlin.random.Random(rng.nextLong())) ?: return null
        return rivalOf(first, rng)?.let { first to it }
    }

    /** An opponent to watch [key] against: someone near its rating (from the snapshot). */
    fun rivalOf(key: String, rng: Random): String? {
        val rows = view?.rows.orEmpty().filter { !it.isMe && it.key in byKey }
        val r = rows.firstOrNull { it.key == key }?.rating?.toDouble() ?: return byKey.keys.filter { it != key }.randomOrNull()
        val pool = rows.filter { it.key != key && it.rank != null && it.rating != null }.map { it.key to it.rating!!.toDouble() }
        return Matchmaker.opponentFor(pool, r, rng) ?: byKey.keys.firstOrNull { it != key }
    }

    /**
     * Plays one game of [first] against [second] and shows it throw by throw at [pace] ms per
     * step, with the commentary of each throw and [first]'s P(win). The game counts on the ladder.
     */
    fun startWatch(first: String, second: String, pace: () -> Long) {
        val t = tables ?: return
        val a = byKey[first] ?: return
        val b = byKey[second] ?: return
        watchGame(first, second, pace, null) {
            val rng = Random()
            val turns = WatchMatch.play(t.rules, a.make(Random(rng.nextLong())), b.make(Random(rng.nextLong())), rng)
            val last = turns.last()
            synchronized(lock) {
                ladder?.let { l ->
                    if (l.entry(first) != null && l.entry(second) != null) {
                        l.record(
                            first, second,
                            listOf(GameScore(last.first.total, last.second.total, (last.first[Category.YACHT] ?: 0) > 0, (last.second[Category.YACHT] ?: 0) > 0, last.first.bonus > 0, last.second.bonus > 0)),
                            comp = Comp.FRIENDLY, label = WATCH_LABEL, season = plan?.number ?: 0,
                        )
                    }
                }
            }
            publish(force = true)
            // The watched game is on the ladder: saved now, the league may not be running.
            save()
            turns
        }
    }

    /**
     * Replays a highlight (하이라이트 다시보기) from its deal through the same code, throw by
     * throw; not counted anywhere. When an AI has changed since, the scores differ and the
     * screen says so instead.
     */
    fun replayHighlight(h: Highlight, pace: () -> Long) {
        val t = tables ?: return
        val x = extra ?: return
        // A player as it was in that game (its genes were kept), else as it is now.
        fun aiOf(k: String): LeagueAi? = h.snapshot(k)?.let { s ->
            leagueAi(PlayerRecord(k, name(k), "", Origin.of(k), 1, null, null, s), t, x)
        } ?: byKey[k]
        if (aiOf(h.first) == null || aiOf(h.second) == null) {
            watch = WatchView(h.first, h.second, name(h.first), name(h.second), emptyList(), true, 0, false, h.caption, "이 경기의 선수가 리그를 떠나서 다시 볼 수 없어요.")
            return
        }
        watchGame(h.first, h.second, pace, h.caption) {
            val r = h.replay(t.rules, ::aiOf) ?: return@watchGame null
            if (!r.same) null.also { scope.launch { watch = watch?.copy(message = r.message, complete = true, playing = false) } } else r.turns
        }
    }

    private fun watchGame(first: String, second: String, pace: () -> Long, title: String?, play: suspend () -> List<WatchTurn>?) {
        val t = tables ?: return
        stopWatch()
        watch = WatchView(first, second, name(first), name(second), emptyList(), false, 0, true, title)
        val steps = ArrayList<WatchStep>()
        watchJob = scope.launch {
            val turns = withContext(Dispatchers.Default) { play() } ?: return@launch
            val advisor = Advisor(t.withoutRisk())
            val outlook = OutlookAnalysis(t)
            val talk = Commentary(t)
            var x = Board(t.rules)
            var y = Board(t.rules)
            for ((i, turn) in turns.withIndex()) {
                val (mover, other) = if (turn.side == 0) x to y else y to x
                val moverName = name(if (turn.side == 0) first else second)
                val waitingName = name(if (turn.side == 0) second else first)
                val made = withContext(Dispatchers.Default) {
                    runCatching {
                        val plan = advisor.planTurn(mover, other, Mode.WIN, depth = 1)
                        val otherWin = (1 - plan.startValue).coerceIn(0.0, 1.0)
                        val after = outlook.winAfter(other, mover, otherWin)
                        talk.aiSteps(
                            plan, mover, turn.throws, turn.held, turn.category, after?.let { w -> { c: Board -> w(c) } }, otherWin,
                            judge = true, who = moverName, viewer = waitingName,
                        )
                    }.getOrNull()
                } ?: turn.throws.mapIndexed { k, d ->
                    val end = k == turn.throws.size - 1
                    AiStep(k + 1, d.sorted(), turn.held.getOrNull(k), if (end) turn.category else null, if (end) turn.points else 0, null, emptyList())
                }
                for (st in made) {
                    // myWin is the waiting side's P(win): as the first player's.
                    val winFirst = st.myWin?.let { if (turn.side == 0) 1 - it else it }
                    val end = st.category != null
                    steps += WatchStep(
                        i / 2 + 1, turn.side, st.throwNo, st.dice, st.held, st.category, st.points, winFirst,
                        st.lines.filter { !it.tag.contains("승률") }, if (end) turn.first else x, if (end) turn.second else y,
                    )
                }
                x = turn.first
                y = turn.second
                watch = watch?.copy(steps = steps.toList(), complete = i == turns.size - 1)
            }
        }
        playJob = scope.launch { playLoop(pace) }
    }

    private suspend fun playLoop(pace: () -> Long) {
        while (true) {
            val w = watch ?: return
            if (!w.playing) return
            if (w.shown < w.steps.size) watch = w.copy(shown = w.shown + 1)
            else if (w.complete) {
                watch = w.copy(playing = false)
                return
            }
            delay(maxOf(300L, pace()))
        }
    }

    fun pauseWatch() {
        playJob?.cancel()
        watch = watch?.copy(playing = false)
    }

    fun resumeWatch(pace: () -> Long) {
        val w = watch ?: return
        if (w.playing) return
        watch = w.copy(playing = true)
        playJob = scope.launch { playLoop(pace) }
    }

    /** Jumps to the end of the current turn (or the next one). */
    fun skipWatchTurn() {
        val w = watch ?: return
        var i = w.shown
        while (i < w.steps.size && w.steps[i].category == null) i++
        watch = w.copy(shown = minOf(w.steps.size, i + 1))
    }

    fun stopWatch() {
        watchJob?.cancel()
        playJob?.cancel()
        watch = null
    }

    // ---- the screen ------------------------------------------------------------------------------

    /** At most a few screen updates per second, however fast matches finish. */
    private fun publish(force: Boolean = false) {
        synchronized(throttle) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < PUBLISH_MS) {
                if (!publishPending) {
                    publishPending = true
                    scope.launch {
                        delay(PUBLISH_MS)
                        synchronized(throttle) { publishPending = false }
                        publish(force = true)
                    }
                }
                return
            }
            lastPublish = now
        }
        // The snapshot refits and sorts the whole league (and waits for the lock while a match
        // is recorded): never on the main thread, and one at a time (a request while one runs
        // is folded into one more run after it).
        if (!snapshotRunning.compareAndSet(false, true)) {
            snapshotAgain.set(true)
            return
        }
        scope.launch(Dispatchers.Default) {
            val (v, seq) = try {
                synchronized(lock) {
                    val t0 = System.nanoTime()
                    (snapshot() to ++snapshots).also { timings.note("snapshot", System.nanoTime() - t0) }
                }
            } finally {
                snapshotRunning.set(false)
                if (snapshotAgain.getAndSet(false)) publish(force = true)
            }
            if (v == null) return@launch
            scope.launch {
                // Two snapshots can finish out of order: keep the newer.
                if (seq < shownSnapshot) return@launch
                shownSnapshot = seq
                view = v
                if (running) {
                    val minutes = (System.currentTimeMillis() - runStart) / 60_000.0
                    pace = if (minutes > 0.05) runMatches / minutes else 0.0
                }
            }
        }
    }

    fun name(key: String): String = when {
        key == Ladder.ME -> "나"
        else -> byKey[key]?.name ?: pastNames[key] ?: clubNames[key] ?: Competitions.teamLabel(key)
    }

    /** Whether [key] is an AI, playing or retired (news and honours also name teams and clubs). */
    fun isAi(key: String): Boolean = key == Ladder.ME || key in byKey || key in pastNames

    /** Club names by id (news and honours name clubs by id). */
    @Volatile
    private var clubNames: Map<String, String> = emptyMap()

    /** Names of retired players and legends (news and records still mention them). */
    @Volatile
    private var pastNames: Map<String, String> = emptyMap()

    private fun snapshot(): LeagueView? {
        val l = ladder ?: return null
        val s = plan
        val ranking = l.ranking().filter { it != Ladder.ME || l.entry(it)!!.games > 0 }
        val ids = world?.players?.alive?.associate { it.key to it.identity }.orEmpty()
        val season = s?.number ?: 1
        val rows = ranking.mapNotNull { k ->
            val me = k == Ladder.ME
            val ai = byKey[k]
            if (ai == null && !me) return@mapNotNull null
            val e = l.entry(k)!!
            val rank = l.rankOf(k)
            val rating = l.rating(k)
            LadderRow(
                k, name(k), ai?.family ?: Family.MINE, ai?.desc ?: "랭크전으로 매긴 내 레이팅", ai?.heavy ?: false, rank,
                rating.takeIf { !it.isNaN() }?.roundToInt(),
                l.error(k).takeIf { !it.isNaN() }?.let { (1.96 * it).roundToInt() },
                l.tier(k), e.games, e.wins, e.ties, e.losses, e.winRate, e.avgScore, e.best, e.yachts, e.bonuses,
                // The rating history is read with the profile (thousands of rows, every snapshot).
                e.streak, e.bestStreak, e.recent.joinToString(""), emptyList(), e.peak.takeIf { !it.isNaN() }?.roundToInt(),
                lastDelta[k] ?: 0, rank?.let { r -> startRanks[k]?.let { it - r } }, k in busy, me, s?.league?.divisionOf(k),
                Origin.of(k), world?.hometown(k) ?: "",
                ids[k]?.nation ?: "", ids[k]?.let { Nations.of(it.nation)?.flag } ?: "", ids[k]?.let { id -> world?.players?.get(k)?.let { p -> id.age(season, p.born) } },
                divisionLabel = s?.league?.let { league -> league.divisionOf(k)?.let { league.tiers.label(it) } },
            )
        }
        val curves = world?.live
        val feed = l.feed.take(FEED).map { m ->
            FeedItem(
                m.a, name(m.a), m.b, name(m.b), m.games, m.deltaA, m.deltaB, m.time, m.notes,
                curves?.curves(m.a, m.b)?.firstOrNull()?.let { c -> if (c.a == m.a) c.curveA() else IntArray(c.curveA().size) { 100 - c.curveA()[it] } },
                m.comp, m.label, m.week, m.season,
            )
        }
        // A view equal to the last one is handed over as the same object, so the screen skips it.
        val comps = s?.let { runCatching { Competitions.views(it) }.getOrNull() }.orEmpty()
            .map { c -> lastComps.firstOrNull { it == c } ?: c }
            .let { if (it == lastComps) lastComps else it }
        lastComps = comps
        val clubs = world?.clubs?.let { b ->
            val cl = s?.competition(Comp.CLUB) as? ClubLeague
            clubNames = b.clubs.associate { it.id to it.name }
            ClubsView(cl?.table().orEmpty(), b.clubs, b.history.takeLast(CLUB_MOVES).reversed(), cl?.matches().orEmpty(), cl?.lineups.orEmpty())
        }?.let { if (it == lastClubs) lastClubs else it }
        lastClubs = clubs
        val w = world
        w?.lab?.let { b -> val v = b.current?.results to b.history; scope.launch { lab = v } }
        return LeagueView(
            rows, feed, l.matches, l.games, live.values.map { LiveMatch(it.a, it.b, it.comp, it.label, it.week) }, s?.let { seasonView(it) }, comps, clubs,
            w?.news?.all?.take(NEWS).orEmpty(), w?.highlights?.thisSeason.orEmpty(), w?.highlights?.allTime.orEmpty(),
            s?.let { runCatching { clockView(it) }.getOrNull() },
        )
    }

    /** The season's weeks; rows equal to the last snapshot's are handed over as the same objects. */
    private fun clockView(p: SeasonPlan): ClockView {
        val cal = p.calendar
        val byWeek = p.schedule().groupBy { it.week }
        val rows = (1..cal.lastWeek).map { w ->
            val r = WeekRow(w, byWeek[w].orEmpty())
            lastWeeks.getOrNull(w - 1)?.takeIf { it == r } ?: r
        }
        lastWeeks = rows
        // By stage: a short season can hold two stages of one competition in a week.
        val playing = HashMap<Pair<Comp, Int>, Int>()
        for (f in live.values) {
            if (f.week == SeasonCalendar.NONE) continue
            val stage = if (f.comp == Comp.LEAGUE) f.week - 1 else p.competition(f.comp)?.let { c -> runCatching { c.stageOf(f) }.getOrNull() } ?: -1
            if (stage >= 0) playing.merge(f.comp to stage, 1, Int::plus)
        }
        return ClockView(p.number, p.week, cal.leagueWeeks, cal.lastWeek, p.finished, rows, playing)
    }

    private var lastWeeks: List<WeekRow> = emptyList()

    /** The last snapshot's views (reused when equal: see [snapshot]); under the lock. */
    private var lastComps: List<Pair<Comp, CompetitionView>> = emptyList()
    private var lastClubs: ClubsView? = null
    private var lastHistory: List<SeasonHistoryItem> = emptyList()

    private fun seasonView(p: SeasonPlan): SeasonView {
        val s = p.league
        // The zones as the off-season will read them, on today's tables.
        val zone = HashMap<String, Int>()
        var swap = 0
        for (t in 0 until s.tiers.count - 1) {
            val c = yacht.engine.league.Pyramid.crossing(s, p.config, t)
            if (t == 0) swap = c.swap
            for (k in c.up) zone[k] = 1
            for (k in c.down) zone[k] = -1
            for ((u, l) in c.ties) {
                zone.putIfAbsent(u, -2)
                zone.putIfAbsent(l, 2)
            }
        }
        val divisions = s.divisions.indices.map { d ->
            val table = s.table(d)
            table.mapIndexed { i, st ->
                SeasonRow(
                    st.key, name(st.key), st.played, st.wins, st.draws, st.losses, st.points, st.diff, st.avgScore, st.yachts,
                    zone[st.key] ?: 0,
                    st.aggPoints,
                    oddsNow?.takeIf { it.season == s.number }?.get(st.key),
                )
            }
        }
        val inLeague = s.standings.keys
        return SeasonView(
            // Next season's newcomers: members not in this season (the rules keep the rest out).
            s.number, s.fixturesPlayed, s.fixturesTotal, divisions, world?.members.orEmpty().filter { it !in inLeague }.map { name(it) },
            honours.seasons.reversed().map { historyItem(it, honours.of(it)) }.let { h -> if (h == lastHistory) lastHistory else h.also { lastHistory = it } },
            s.positions.map { it.toList() }, s.divisions, s.matchdays, s.currentMatchday, s.tiers, swap,
        )
    }

    /** The profile asked for last (read off the main thread). */
    var profile by mutableStateOf<ProfileView?>(null)
        private set

    /** Loads [key]'s profile into [profile]. */
    fun loadProfile(key: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val b = w.players
                val alive = b[key]
                val past = if (alive == null) b.retiredRecord(key) else null
                fun nm(k: String?) = k?.let { name(it) }
                ProfileView(
                    key, name(key), Origin.of(key).let { if (past != null) past.origin else it }, alive?.hometown ?: past?.hometown ?: w.hometown(key),
                    alive?.born ?: past?.born, past?.retired, b.legend(key) != null,
                    b.ancestors(key).map { gen -> gen.map { nm(it) } }, b.descendants(key).map { gen -> gen.map { name(it) } },
                    w.honours.of(key).sortedByDescending { it.season }, w.honours.titles(key),
                    (alive?.spec ?: b.legend(key)?.spec)?.describe(),
                ).let { pv -> matchups(key).let { (best, worst) -> pv.copy(best = best, worst = worst) } }.copy(
                    season = w.stats.season(key)?.copy(), career = w.stats.career(key)?.copy(),
                    league = runCatching { w.stats.liveMeta() }.getOrNull() ?: w.stats.meta,
                    lines = w.archive.lines(key).sortedBy { it.season }, hallOfFame = w.archive.inHallOfFame(key),
                    identity = runCatching { identityView(w, key) }.getOrNull(),
                    ratingHistory = w.ladder.entry(key)?.history?.toList().orEmpty(),
                )
            }
            scope.launch { profile = v }
        }
    }

    /** [key]'s identity view (under the lock), null for a player without one (base AIs and mine). */
    private fun identityView(w: LeagueWorld, key: String): IdentityView? {
        val b = w.players
        val season = w.plan?.number ?: (b.evolvedSeason + 1)
        val alive = b[key]
        val legend = b.legend(key)
        val past = if (alive == null) b.retiredRecord(key) else null
        val id = alive?.identity ?: legend?.identity
        val code = id?.nation ?: past?.nation ?: return null
        val nat = Nations.of(code) ?: return null
        val honours = w.honours.of(key)
        val age = when {
            alive != null && id != null -> id.age(season, alive.born)
            past != null && past.retiredAge > 0 -> past.retiredAge
            else -> null
        }
        val spec = alive?.spec ?: legend?.spec
        val gene = spec?.play
        val record = alive ?: legend?.asPlayer()
        val chips = buildList {
            when (spec?.mistake) {
                yacht.engine.league.CustomMistake.FEEL -> add("🎲 감으로 두는 편")
                yacht.engine.league.CustomMistake.SLIP -> add("🎯 가끔 실수")
                yacht.engine.league.CustomMistake.NONE -> add("💎 실수 없음")
                null -> {}
            }
            spec?.temper?.takeIf { it != yacht.engine.league.Temper.NONE }?.let { add("🌀 ${it.label}") }
            spec?.belief?.let { add("🤔 " + (yacht.engine.league.CustomAiSpec.BELIEFS[it] ?: "규칙 착각")) }
        }
        val notes = buildList {
            gene?.let { g ->
                yacht.engine.league.PlayStyles.recipe(g.primary)?.let { add(it.desc) }
                g.secondary?.let { s -> yacht.engine.league.PlayStyles.recipe(s)?.let { add("+${it.name}: ${it.desc}") } }
                g.overlay?.let { o -> yacht.engine.league.PlayStyles.overlay(o)?.let { add("${it.name}: ${it.desc}") } }
            }
        }
        val cap = w.nations.cap(key)
        val compatriots = b.alive.filter { it.identity?.nation == code }
        val rank = if (alive == null) null else compatriots.map { it.key }.sortedByDescending { w.ladder.rating(it).takeIf { r -> !r.isNaN() } ?: 0.0 }.indexOf(key).takeIf { it >= 0 }?.plus(1)
        val members = w.members.toSet()
        return IdentityView(
            nat.flag, code, nat.name, (id?.gender ?: past?.gender)?.label ?: "", age, age?.let { yacht.engine.league.Profiles.stage(it) }.takeIf { alive != null },
            id?.debutAge ?: past?.debutAge?.takeIf { it > 0 },
            nickname = record?.let { yacht.engine.league.Nicknames.of(it, honours, season) },
            abilities = id?.abilities?.toList(),
            change = if (alive != null && id != null) id.abilities.diff(id.lastAbl) else emptyList(),
            peak = id?.peakAbl?.toList(),
            potential = id?.potential,
            outlook = if (alive != null && id != null && age != null) yacht.engine.league.Profiles.outlook(id, age) else null,
            tendencies = gene?.let { yacht.engine.league.Tendencies.of(it.style()).toList() },
            style = gene?.label, styleNotes = notes, chips = chips,
            school = yacht.engine.league.Schools.of(code).motto.takeIf { it.isNotEmpty() },
            rival = w.ladder.rival(key, members)?.let { (o, p, g) -> Triple(name(o), p, g) },
            caps = cap?.caps ?: 0, capTitles = cap?.titles ?: 0, capBoards = cap?.let { Triple(it.won, it.drawn, it.lost) },
            nationRank = rank, nationPlayers = compatriots.size,
            weeks = honours.count { it.kind == "POTW" },
        )
    }

    /** The records room, archive and meta, read on request. */
    var records by mutableStateOf<RecordsView?>(null)
        private set

    fun loadRecords() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val st = w.stats
                RecordsView(
                    st.records.all, w.archive.dynasties.toList(), w.archive.hallOfFame.toList(),
                    w.archive.summaries.sortedByDescending { it.season }, st.milestones.reversed(),
                    runCatching { st.liveMeta() }.getOrNull(), st.previousMeta ?: st.meta,
                    st.top(10, career = false, min = { it.matches >= 10 && !it.avgRating.isNaN() }) { it.avgRating }.map { (k, a) -> k to a.copy() },
                )
            }
            scope.launch { records = v }
        }
    }

    /** The season review (시즌 결산) of the season that just ended, until the player closes it. */
    var review by mutableStateOf<SeasonReview?>(null)
        private set

    fun dismissReview() {
        review = null
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.archive?.reviewShown() }
            save()
        }
    }

    /** The 국가 랭킹, read on request. */
    var nations by mutableStateOf<List<NationRow>>(emptyList())
        private set

    /** The nation sheet opened last. */
    var nationSheet by mutableStateOf<NationSheet?>(null)
        private set

    fun closeNation() {
        nationSheet = null
    }

    /** Ratings of every player, keyed (under the lock). */
    private fun ratingsOf(w: LeagueWorld): Map<String, Double> =
        w.members.associateWith { w.ladder.rating(it) }.filterValues { !it.isNaN() }

    private fun nationRows(w: LeagueWorld, ratings: Map<String, Double>): List<NationRow> {
        val book = w.nations
        val byNation = w.players.alive.groupBy { it.identity?.nation ?: Nations.KR.code }
        return Nations.ALL.map { n ->
            val ps = byNation[n.code].orEmpty().filter { it.key in ratings }.sortedByDescending { ratings[it.key] }
            val r = book[n.code]
            NationRow(
                n.code, n.flag, n.name, n.confed.label, byNation[n.code]?.size ?: 0,
                ps.take(5).takeIf { it.size == 5 }?.map { ratings.getValue(it.key) }?.average()?.roundToInt(),
                ps.firstOrNull()?.name,
                r?.worldTitles ?: 0, r?.continentalTitles ?: 0, r?.worldBest ?: NationBook.NONE, r?.continentalBest ?: NationBook.NONE,
                r?.editions ?: 0, r?.won ?: 0, r?.drawn ?: 0, r?.lost ?: 0,
            )
        }
    }

    fun loadNations() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                nationRows(w, ratingsOf(w))
            } ?: return@launch
            scope.launch { nations = v }
        }
    }

    fun loadNation(code: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val ratings = ratingsOf(w)
                val row = nationRows(w, ratings).firstOrNull { it.code == code } ?: return@synchronized null
                val b = w.players
                val season = w.plan?.number ?: (b.evolvedSeason + 1)
                val mine = b.alive.filter { (it.identity?.nation ?: Nations.KR.code) == code }
                val squad = mine.filter { it.key in ratings }.sortedByDescending { ratings[it.key] }.take(8).map {
                    NationPlayer(it.key, it.name, "레이팅 ${ratings.getValue(it.key).roundToInt()} · ${it.age(season)}세")
                }
                val capped = w.nations.mostCapped(5, code).map { c ->
                    NationPlayer(c.key, name(c.key), "${c.caps}회 선발" + (if (c.titles > 0) " · 우승 ${c.titles}회" else "") + " · ${NationBook.record(c.won, c.drawn, c.lost)}")
                }
                val cups = w.nations.editions.reversed().mapNotNull { e ->
                    val cup = if (e.world) "요트 월드컵" else (e.podiums.firstOrNull { p -> p.winner == code || p.runnerUp == code || code in p.semis }?.confed?.cup ?: "대륙컵")
                    val place = e.podiums.firstNotNullOfOrNull { p ->
                        when (code) {
                            p.winner -> "우승"
                            p.runnerUp -> "준우승"
                            in p.semis -> "4강"
                            else -> null
                        }
                    }
                    place?.let { "시즌 ${e.season} $cup $it" + if (e.host == code) " (개최)" else "" }
                }.take(12)
                // 국적별 기록실: who is of this nation, alive, retired or a legend.
                val nationOf = HashMap<String, String>()
                for (r in b.retired) nationOf[r.key] = r.nation
                for (l in b.legends) nationOf[l.key] = l.identity?.nation ?: l.record.nation
                for (p in b.alive) nationOf[p.key] = p.identity?.nation ?: Nations.KR.code
                val honours = w.honours.all.filter { nationOf[it.key] == code && it.kind in NATION_HONOURS }
                    .groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
                val legends = b.legends.filter { nationOf[it.key] == code }.reversed().take(10).map { l ->
                    NationPlayer(l.key, l.name, "시즌 ${l.record.born}–${l.record.retired}" + if (l.record.titles > 0) " · 우승 ${l.record.titles}회" else "")
                }
                val ages = mine.map { it.age(season) }
                val bands = listOf("~21세" to (0..21), "22~25세" to (22..25), "26~31세" to (26..31), "32~35세" to (32..35), "36세~" to (36..99))
                NationSheet(
                    row, yacht.engine.league.Schools.of(code).motto, squad, capped, cups, honours, legends,
                    bands.map { (l, r) -> l to ages.count { it in r } }, mine.count { it.identity?.gender == yacht.engine.league.Gender.FEMALE },
                )
            } ?: return@launch
            scope.launch { nationSheet = v }
        }
    }

    /** 유망주: young players by potential. */
    var prospects by mutableStateOf<List<ProspectRow>>(emptyList())
        private set

    fun loadProspects() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val season = w.plan?.number ?: (w.players.evolvedSeason + 1)
                w.players.alive.mapNotNull { p ->
                    val id = p.identity ?: return@mapNotNull null
                    val age = id.age(season, p.born)
                    if (age > PROSPECT_AGE) return@mapNotNull null
                    val nat = Nations.of(id.nation)
                    ProspectRow(
                        p.key, nat?.let { "${it.flag} ${p.name}" } ?: p.name, age, id.abilities.overall, id.potential,
                        yacht.engine.league.Profiles.outlook(id, age), w.ladder.rating(p.key).takeIf { !it.isNaN() }?.roundToInt(),
                    )
                }.sortedWith(compareByDescending<ProspectRow> { it.potential }.thenByDescending { it.overall }.thenBy { it.key }).take(60)
            } ?: return@launch
            scope.launch { prospects = v }
        }
    }

    /** 선수 비교: the pair opened last. */
    var compare by mutableStateOf<CompareView?>(null)
        private set

    fun closeCompare() {
        compare = null
    }

    fun loadCompare(a: String, b: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val l = w.ladder
                val st = w.stats
                fun num(x: Double, f: String) = if (x.isNaN()) "–" else f.format(x)
                fun better(x: Double, y: Double, high: Boolean = true) = when {
                    x.isNaN() || y.isNaN() || x == y -> 0
                    (x > y) == high -> 1
                    else -> -1
                }
                val ra = l.rating(a)
                val rb = l.rating(b)
                val ca = st.career(a)
                val cb = st.career(b)
                val rows = (view?.rows).orEmpty()
                val rowA = rows.firstOrNull { it.key == a }
                val rowB = rows.firstOrNull { it.key == b }
                val ta = w.honours.titles(a).toDouble()
                val tb = w.honours.titles(b).toDouble()
                val ma = w.honours.of(a).count { it.kind == "MVP" }.toDouble()
                val mb = w.honours.of(b).count { it.kind == "MVP" }.toDouble()
                val lines = listOf(
                    CompareLine("레이팅", num(ra, "%.0f"), num(rb, "%.0f"), better(ra, rb)),
                    CompareLine("순위", rowA?.rank?.let { "${it}위" } ?: "–", rowB?.rank?.let { "${it}위" } ?: "–", better((rowA?.rank ?: 0).toDouble().takeIf { it > 0 } ?: Double.NaN, (rowB?.rank ?: 0).toDouble().takeIf { it > 0 } ?: Double.NaN, high = false)),
                    CompareLine("판 수", "${rowA?.games ?: 0}", "${rowB?.games ?: 0}", 0),
                    CompareLine("승률", rowA?.let { "%.1f%%".format(100 * it.winRate) } ?: "–", rowB?.let { "%.1f%%".format(100 * it.winRate) } ?: "–", better(rowA?.winRate ?: Double.NaN, rowB?.winRate ?: Double.NaN)),
                    CompareLine("평균 점수", rowA?.let { "%.1f".format(it.avgScore) } ?: "–", rowB?.let { "%.1f".format(it.avgScore) } ?: "–", better(rowA?.avgScore ?: Double.NaN, rowB?.avgScore ?: Double.NaN)),
                    CompareLine("평균 평점", num(ca?.avgRating ?: Double.NaN, "%.2f"), num(cb?.avgRating ?: Double.NaN, "%.2f"), better(ca?.avgRating ?: Double.NaN, cb?.avgRating ?: Double.NaN)),
                    CompareLine("우승", "${ta.toInt()}회", "${tb.toInt()}회", better(ta, tb)),
                    CompareLine("MVP", "${ma.toInt()}회", "${mb.toInt()}회", better(ma, mb)),
                )
                val met = l.opponents(a).firstOrNull { it.first == b }?.let { (_, p, g) -> p / g to g }
                CompareView(
                    a, b, name(a), name(b),
                    runCatching { identityView(w, a) }.getOrNull(), runCatching { identityView(w, b) }.getOrNull(),
                    lines, met,
                )
            } ?: return@launch
            scope.launch { compare = v }
        }
    }

    /** Retired players and legends, newest first (레전드 보관소). */
    var pastPlayers by mutableStateOf<List<PastPlayer>>(emptyList())
        private set

    fun loadPastPlayers() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val b = world?.players ?: return@synchronized emptyList()
                val legends = b.legends.map { l ->
                    val r = l.record
                    PastPlayer(r.key, r.name, r.born, r.retired, r.bestRating.takeIf { !it.isNaN() }?.roundToInt(), r.titles, true, l.honours, r.hometown)
                }
                val legendKeys = legends.map { it.key }.toSet()
                val retired = b.retired.filter { it.key !in legendKeys }.map { r ->
                    PastPlayer(r.key, r.name, r.born, r.retired, r.bestRating.takeIf { !it.isNaN() }?.roundToInt(), r.titles, false, "-", r.hometown)
                }
                (legends + retired).sortedByDescending { it.retired }
            }
            scope.launch { pastPlayers = v }
        }
    }

    /** The head-to-head matrix asked for last (read off the main thread). */
    var h2h by mutableStateOf<H2hMatrix?>(null)
        private set

    /** Loads the matrix between these AIs into [h2h]. */
    fun loadH2h(title: String, keys: List<String>) = loadMatrix(title, keys.map { listOf(it) }, keys.map { name(it) })

    /** Loads the matrix between these groups of AIs (e.g. families) into [h2h]. */
    /** The matrix asked for last (read again when the league is replaced). */
    private var lastMatrix: Triple<String, List<List<String>>, List<String>>? = null

    fun loadMatrix(title: String, groups: List<List<String>>, labels: List<String>) {
        lastMatrix = Triple(title, groups, labels)
        scope.launch(Dispatchers.Default) {
            val m = synchronized(lock) {
                val l = ladder ?: return@synchronized null
                val n = groups.size
                val score = Array(n) { DoubleArray(n) { Double.NaN } }
                val games = Array(n) { IntArray(n) }
                for (i in 0 until n) for (j in 0 until n) {
                    if (i == j) continue
                    var w = 0.0
                    var g = 0
                    for (a in groups[i]) for (b in groups[j]) {
                        if (a == b) continue
                        val (x, y) = l.headToHead(a, b)
                        w += x
                        g += y
                    }
                    games[i][j] = g
                    if (g >= 2) score[i][j] = w / g
                }
                H2hMatrix(title, labels, score, games)
            }
            scope.launch { h2h = m }
        }
    }

    /** 라이브 스코어보드: the newest finished fixtures replayed turn by turn while the board is open. */
    var liveFrames by mutableStateOf<List<LiveFrame>>(emptyList())
        private set
    private var liveJob: Job? = null

    /** Opens (true) or closes the live scoreboard; while open it is redrawn four times a second. */
    fun watchLive(open: Boolean) {
        liveJob?.cancel()
        if (!open) {
            liveOnForeground = false
            return
        }
        val start = System.currentTimeMillis()
        liveJob = scope.launch(Dispatchers.Default) {
            while (true) {
                val frames = synchronized(lock) { world?.live?.window(System.currentTimeMillis(), start).orEmpty() }
                scope.launch { liveFrames = frames }
                delay(250)
            }
        }
    }

    /** The lab's current experiment and the newest finished ones (newest first). */
    var lab by mutableStateOf<Pair<LabResult?, List<LabResult>>>(null to emptyList())
        private set

    /**
     * Starts an experiment of [a] against [b] (AI 연구소): [target] mirrored matches, or 0 until
     * the win share is known to ±1%p. The league is started if it was not running.
     */
    fun startLab(a: String, b: String, target: Int) {
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.lab?.start(a, b, target, System.nanoTime(), name(a), name(b)) }
            publishLab()
            save()
        }
        if (!running) start()
    }

    fun stopLab() {
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.lab?.stop() }
            publishLab()
            save()
        }
    }

    private fun publishLab() {
        val v = synchronized(lock) { world?.lab?.let { b -> b.current?.results to b.history } } ?: return
        scope.launch { lab = v }
    }

    /** Legends the lab can field (they no longer play in the league): key and name. */
    fun legends(): List<Pair<String, String>> = legendsNow

    /** [legends], kept with the roster (read without the lock). */
    @Volatile
    private var legendsNow: List<Pair<String, String>> = emptyList()

    /** The match preview (경기 프리뷰) asked for last. */
    var preview by mutableStateOf<MatchPreview?>(null)
        private set

    fun closePreview() {
        preview = null
    }

    /** Builds the preview of [a] against [b] (a fixture of this season when [fixtureId] is given). */
    fun loadPreview(a: String, b: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val season = w.plan?.league
                val profiles = profilesOf(w)
                Preview.of(
                    a, b, w.ladder, season, oddsNow?.takeIf { it.season == season?.number }, profiles[a], profiles[b],
                    BoxProfile.league(profiles.values), { k -> name(k) },
                )
            }
            scope.launch { preview = v }
        }
    }

    /** Every member's box profile from its career statistics (under the lock). */
    private fun profilesOf(w: LeagueWorld): Map<String, BoxProfile> =
        w.members.mapNotNull { k -> w.stats.career(k)?.boxProfile()?.let { k to it } }.toMap()

    /** The scouting report (스카우팅 리포트) of [key] before a ranked game, or null. */
    fun scouting(key: String): ScoutingReport? = synchronized(lock) {
        val w = world ?: return null
        val ai = byKey[key] ?: return null
        val profiles = profilesOf(w)
        val spec = customs.firstOrNull { it.key == key } ?: w.players[key]?.spec
        runCatching { Scouting.report(ai.name, ai.family, spec, w.stats.career(key)?.boxProfile(), BoxProfile.league(profiles.values), ai.desc) }.getOrNull()
    }

    /** The matchday shown in the calendar (일정표), read off the main thread on request. */
    var calendar by mutableStateOf<CalendarView?>(null)
        private set

    /** Loads [matchday] of [division] into [calendar] (null: the current matchday). */
    fun loadCalendar(division: Int, matchday: Int? = null) {
        // Only the newest request is shown (an older one can finish later).
        val request = calendarRequests.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val s = plan?.league ?: return@synchronized null
                if (s.divisions.isEmpty()) return@synchronized null
                val d = division.coerceIn(0, s.divisions.size - 1)
                val md = (matchday ?: s.currentMatchday).coerceIn(0, maxOf(0, s.matchdays - 1))
                val items = s.matchday(md).filter { it.division == d }.map { f ->
                    CalendarItem(f.id, f.a, name(f.a), f.b, name(f.b), s.result(f.id), !s.isPlayed(f.id) && live.containsKey(f.a to f.b))
                }
                CalendarView(d, md, s.matchdays, s.currentMatchday, items, s.number)
            }
            scope.launch { if (request == calendarRequests.get()) calendar = v }
        }
    }

    private val calendarRequests = java.util.concurrent.atomic.AtomicInteger()

    /** A past season for the hall of fame, from its honours. */
    private fun historyItem(season: Int, h: List<Honour>): SeasonHistoryItem {
        val titles = h.filter { DIVISION.matches(it.kind) }.sortedBy { it.kind.drop(1).toIntOrNull() ?: 0 }
        // A pyramid's dozens of moves: those into 1부 and 2부 by name, the rest counted.
        val up = h.filter { it.kind.startsWith("UP") }
        val down = h.filter { it.kind.startsWith("DOWN") }
        fun named(list: List<Honour>, upper: Int) =
            if (list.size <= MOVES_LISTED) list else list.filter { (it.kind.dropWhile { c -> !c.isDigit() }.toIntOrNull() ?: 0) <= upper }
        return SeasonHistoryItem(
            season, titles.map { name(it.key) }, h.firstOrNull { it.kind == "SCORER" }?.let { name(it.key) }, 0.0,
            h.firstOrNull { it.kind == "YACHT" }?.let { name(it.key) }, 0,
            named(up, 1).map { name(it.key) }, named(down, 2).map { name(it.key) },
            h.filter { it.kind in SHOWN_TITLES }.sortedBy { SHOWN_TITLES.indexOf(it.kind) }.map { it.kind to teamOrName(it.kind, it.key) },
            titles.map { it.kind.drop(1).toIntOrNull() ?: 0 }, up.size, down.size,
        )
    }

    /** An honour's winner: a club, a family or an all-star side by its label, else the AI's name. */
    private fun teamOrName(kind: String, key: String): String = when (kind) {
        "FAMILY", "ALLSTAR", "WC", "WC2", "WC4", "CC", "CC2" -> Competitions.teamLabel(key)
        else -> name(key)
    }

    /** Best and worst opponents of [key] with at least [min] games against them. */
    fun matchups(key: String, min: Int = 4): Pair<List<Matchup>, List<Matchup>> = synchronized(lock) {
        val l = ladder ?: return emptyList<Matchup>() to emptyList()
        val all = l.keys.filter { it != key }.mapNotNull { o ->
            val (w, g) = l.headToHead(key, o)
            if (g < min) null else Matchup(o, name(o), w / g, g)
        }
        all.sortedByDescending { it.score }.take(3) to all.sortedBy { it.score }.take(3)
    }


    companion object {
        private val DIVISION = Regex("L\\d")

        /** Honours the season history lists after the division champions, in this order. */
        val SHOWN_TITLES = listOf("REG", "L0R", "CL", "CL2", "CUP", "CUP2", "SUPER", "CLUB", "FAMILY", "ALLSTAR", "WC", "WC2", "CC", "WCMVP", "MVP", "ROOKIE", "MIP")

        /** League files a backup restores (when this phone's league has no games yet). */
        val SAVED = LeagueWorld.FILES + listOf("config.txt", "favourites.txt")

        /** The default number of players before the pyramid. */
        private const val OLD_DEFAULT_PLAYERS = 200

        /** Moves a season's history names (more: only those into 1부 and 2부). */
        const val MOVES_LISTED = 16

        /** Oldest age listed among the 유망주. */
        const val PROSPECT_AGE = 22

        /** The honours counted in a nation's records (titles and awards its players won). */
        val NATION_HONOURS = setOf("L0", "CUP", "CL", "SUPER", "MVP", "ROOKIE", "MIP", "YACHT", "SCORER", "WCMVP", "WCTOP", "POTW")
        private const val FIT_MS = 2_000L
        private const val CHECKPOINT_MS = 30_000L
        private const val PUBLISH_MS = 300L
        private const val FEED = 60

        /** Share of matches given to AIs waiting for the next season, so they get a rating. */
        private const val CLUB_MOVES = 60
        private const val NEWS = 120
        private const val FRIENDLY_SHARE = 0.2

        /** The rating scale's anchor (1500) and the share of jobs it plays as 기준 경기. */
        private const val ANCHOR = "ev"
        private const val CALIBRATION_SHARE = 0.02
        private const val CALIBRATION_LABEL = "기준 경기"
        private const val FRIENDLY_RUSH = 0.6

        /** Errors in a row after which the league stops and says why. */
        private const val MAX_FAILURES = 12
        private const val ERROR_PAUSE_MS = 500L

        /** Feed labels of the player's ranked games and of watched games (friendlies on the ladder). */
        const val RANKED_LABEL = "랭크전"
        const val WATCH_LABEL = "관전 경기"

        /** My assumed rating before my first ranked game (a solid amateur). */
        const val START_RATING = 1400.0
    }
}

/** Count, mean and worst of a few timed sections (ms), thread-safe. */
class LockTimings {
    private val sums = HashMap<String, DoubleArray>()

    @Synchronized
    fun note(what: String, nanos: Long) {
        val a = sums.getOrPut(what) { DoubleArray(3) }
        val ms = nanos / 1e6
        a[0] += 1.0
        a[1] += ms
        a[2] = maxOf(a[2], ms)
    }

    @Synchronized
    override fun toString(): String = sums.entries.sortedBy { it.key }.joinToString(" · ") { (k, a) ->
        "$k ${a[0].toInt()}× avg ${"%.2f".format(a[1] / a[0])} max ${"%.1f".format(a[2])} ms"
    }
}
