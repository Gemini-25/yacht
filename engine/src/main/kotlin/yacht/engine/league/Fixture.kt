package yacht.engine.league

/** The league's competitions; [code] is used in seeds and save files. */
enum class Comp(val code: Char, val label: String) {
    LEAGUE('L', "리그"),
    CUP('C', "요트컵"),
    CHAMPIONS('E', "챔피언스 리그"),

    /** Each confederation's champions of its national leagues (national seasons only). */
    CONT_CHAMPIONS('T', "대륙 챔피언스"),
    SUPER('S', "슈퍼컵"),
    ALL_STAR('A', "올스타전"),
    FAMILY('W', "계열 대항전"),
    WORLD_CUP('N', "요트 월드컵"),
    CONTINENTAL('R', "대륙컵"),
    CLUB('K', "클럽 리그"),

    /** Club champions of the national leagues, every other season (national seasons only). */
    CLUB_CUP('U', "클럽 대항전"),
    TITLE_PLAYOFF('P', "우승 플레이오프"),
    PROMOTION_PLAYOFF('Q', "승강 플레이오프"),
    FRIENDLY('F', "친선 경기"),
    LEGEND('G', "레전드 매치"),
    LAB('X', "AI 연구소"),
    ;

    companion object {
        fun of(code: Char): Comp? = entries.firstOrNull { it.code == code }
    }
}

/** True for a competition between teams (all-star sides, families, nations, clubs): its keys are team ids. */
val Comp.byTeams: Boolean get() = this == Comp.ALL_STAR || this == Comp.FAMILY || this == Comp.WORLD_CUP || this == Comp.CONTINENTAL || this == Comp.CLUB_CUP

/** What a job plays: one match, a knockout tie (legs and deciders), or a team match (boards). */
enum class JobKind { MATCH, TIE, TEAM }

/**
 * One job for a league worker. For MATCH and TIE, [a] and [b] are AI keys; for TEAM they name
 * the teams and [lineupA] / [lineupB] hold the AIs, board 1 first. The result is a pure function
 * of the fixture and its [seed]: a fixture interrupted by a kill is played again identically.
 */
data class Fixture(
    val comp: Comp,
    val id: Int,
    val a: String,
    val b: String,
    val seed: Long,
    val kind: JobKind = JobKind.MATCH,
    /**
     * Matches in a knockout tie (two legs = two mirrored matches); for a series ([firstTo] > 0)
     * the most pairs of games it can take before deciders.
     */
    val legs: Int = 1,
    val lineupA: List<String> = emptyList(),
    val lineupB: List<String> = emptyList(),
    /** Counts on the rating ladder (exhibitions and the lab do not). */
    val rated: Boolean = true,
    /** Short label for the screen ("16강", "B조 3차전", "3라운드"). */
    val label: String = "",
    /** The week of the season it belongs to ([SeasonCalendar]); metadata only, never in the deal. */
    val week: Int = SeasonCalendar.NONE,
    /**
     * A knockout tie played as a series (선승제): the first to this many games won goes through
     * (a drawn game half each); 0 for legs of whole matches.
     */
    val firstTo: Int = 0,
) {
    /** Every AI this job keeps busy. */
    val players: List<String> get() = if (kind == JobKind.TEAM) lineupA + lineupB else listOf(a, b)
}

/** One pairing of a job, seen from side A: its games (deciders last) and their facts. */
class BoardResult(
    val a: String,
    val b: String,
    val games: List<GameScore>,
    /** Facts of each game, same order (empty when not collected). */
    val facts: List<GameFacts>,
    /** Single decider games at the end of [games]. */
    val deciders: Int = 0,
) {
    /** Games won by A (a level game counts half). */
    val gamesA: Double get() = games.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 }
    val totalA: Int get() = games.sumOf { it.a }
    val totalB: Int get() = games.sumOf { it.b }
}

/** A finished job: its pairings and who won (+1 side A, -1 side B, 0 level). */
class JobResult(
    val fixture: Fixture,
    val boards: List<BoardResult>,
    val winner: Int,
    /** Time spent playing (for the cost meter). */
    val nanos: Long,
    /** How its matches were decided (a board, a group or a club match is read by it). */
    val format: MatchFormat = MatchFormat.MIRRORED,
)

/** How matches, ties and team matches are decided. */
object TieRule {
    /**
     * A match: more games won; level on games, the higher total over the games (both sides had
     * the same dice) unless the format decides on games alone ([MatchFormat.totals], 판 승점제);
     * still level, a draw.
     */
    fun match(games: List<GameScore>, format: MatchFormat = MatchFormat.MIRRORED): Int {
        val won = games.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 }
        val lost = games.size - won
        if (won != lost) return if (won > lost) 1 else -1
        if (!format.totals) return 0
        val d = games.sumOf { it.a - it.b }
        return d.coerceIn(-1, 1)
    }

    /** True for a 2-0 sweep by side A (every game won), worth the bonus point under [MatchFormat.sweepBonus]. */
    fun sweptByA(games: List<GameScore>): Boolean = games.size >= 2 && games.all { it.a > it.b }

    /** True for a 2-0 sweep by side B. */
    fun sweptByB(games: List<GameScore>): Boolean = games.size >= 2 && games.all { it.b > it.a }

    /** A team match: boards won (a level board is half, each board by [format]), then points over all boards. */
    fun team(boards: List<BoardResult>, format: MatchFormat = MatchFormat.MIRRORED): Int {
        var a = 0.0
        var b = 0.0
        for (bd in boards) {
            when (match(bd.games, format)) {
                1 -> a += 1.0
                -1 -> b += 1.0
                else -> {
                    a += 0.5
                    b += 0.5
                }
            }
        }
        if (a != b) return if (a > b) 1 else -1
        // 판 승점제: level on boards is a draw (a knockout then goes by the totals, see Knockout.record).
        if (!format.totals) return 0
        val d = boards.sumOf { it.totalA - it.totalB }
        return d.coerceIn(-1, 1)
    }

    /** League points of a match result for side A (+1 win, 0 draw, -1 loss). */
    fun points(result: Int, win: Int = 3, draw: Int = 1): Int = when (result) {
        1 -> win
        0 -> draw
        else -> 0
    }
}
