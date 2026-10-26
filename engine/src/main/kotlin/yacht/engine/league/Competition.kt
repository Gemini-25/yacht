package yacht.engine.league

/** Something that happened, for the news (News.kt turns it into an article). [kind] is a short tag. */
data class LeagueEvent(
    val kind: String,
    val season: Int,
    val keys: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val comp: Comp? = null,
    /** The week of the season it happened in ([SeasonCalendar]), [SeasonCalendar.NONE] when unknown. */
    val week: Int = SeasonCalendar.NONE,
) {
    fun encode(): String =
        "$kind|$season|${comp?.code ?: '-'}|${keys.joinToString(",")}|${args.joinToString("\u001f")}" + if (week == SeasonCalendar.NONE) "" else "|$week"

    /** This event in [w] when its week is not known yet. */
    fun at(w: Int): LeagueEvent = if (week == SeasonCalendar.NONE && w != SeasonCalendar.NONE) copy(week = w) else this

    companion object {
        fun decode(line: String): LeagueEvent? = runCatching {
            val f = line.split('|')
            LeagueEvent(
                f[0], f[1].toInt(), f[3].split(',').filter { it.isNotEmpty() }, f[4].split('\u001f').filter { it.isNotEmpty() },
                f[2].singleOrNull()?.let { Comp.of(it) }, f.getOrNull(5)?.toIntOrNull() ?: SeasonCalendar.NONE,
            )
        }.getOrNull()
    }
}

/**
 * A title or award of a season. [kind]: "L<t>" division champion (after playoffs) of tier t
 * (a pyramid: of a group of it), "REG" regular-season leader of division 1, "UP<t>"/"DOWN<t>"
 * moved into tier t, "CUP"/"CUP2"
 * winner/runner-up, "CL"/"CL2", "SUPER", "ALLSTAR" (a team label in [key]), "FAMILY" (a family
 * name), "CLUB" (a club id), "MVP", "ROOKIE", "MIP", "YACHT", "SCORER". See docs/LEAGUE_SPEC.md.
 */
data class Honour(val season: Int, val kind: String, val key: String) {
    fun encode() = "$season $kind $key"

    companion object {
        fun decode(f: List<String>): Honour? = runCatching { Honour(f[0].toInt(), f[1], f[2]) }.getOrNull()
    }
}

/** What a competition knows when it is set up at the start of a season. */
class SeasonStart(
    val number: Int,
    /** Season seed: competitions derive their draws and deals from it and their [Comp] code. */
    val seed: Long,
    val config: LeagueConfig,
    /** Every AI in the league this season (me excluded), best rated first. */
    val ranking: List<String>,
    val rating: (String) -> Double,
    /** This season's divisions, best first. */
    val divisions: List<List<String>>,
    /** Last season's final tables per division (best first); empty in the first season. */
    val lastTables: List<List<String>>,
    /** Last season's honours (empty in the first season). */
    val lastHonours: List<Honour>,
    val family: (String) -> Family?,
    val hometown: (String) -> String,
    /** Every season's honours so far (oldest first), e.g. for the family cup's last squads. */
    val history: List<Honour> = emptyList(),
    /** Each AI's nation code (generated and evolved players; null for the others). */
    val nation: (String) -> String? = { null },
    /** This season's tiers ([divisions]' pyramid groups), and last season's ([lastTables]'). */
    val tiers: Tiers = Tiers.linear(divisions.size),
    val lastTiers: Tiers = Tiers.linear(lastTables.size),
)

/**
 * A stage of a competition (a knockout round, a group matchday, a club round) on the season
 * calendar: [Competition.next] opens it at league progress [opensAt]; [target] is the share of
 * the regular season (0..1) it would like to be played at, from which [SeasonCalendar] gives it
 * a week.
 */
data class Stage(val label: String, val opensAt: Double, val target: Double = opensAt)

/** Fixtures of a stage: [played] of [total] (a stage not drawn yet counts the fixtures it will have). */
data class StageCount(val played: Int, val total: Int) {
    val done: Boolean get() = played >= total
}

/**
 * A season's competition besides the league: it hands out fixtures as they open (by league
 * progress, which [SeasonPlan] turns the calendar's weeks into) and takes their results. Implementations are pure and deterministic (draws from the
 * season seed), and encode themselves for season.txt.
 */
interface Competition {
    val comp: Comp

    /**
     * The next fixture that is open at league progress [p] (0..1) and whose AIs are all free
     * (not in [busy]); it is then taken until [record] or [release]. Null when none now.
     */
    fun next(p: Double, busy: Set<String>): Fixture?

    /**
     * Players of fixtures open at progress [p] that wait only for busy players: other team
     * competitions leave them alone so a one-off match (the all-star game) is not starved.
     */
    fun waiting(p: Double): Set<String> = emptySet()

    /** A fixture taken by [next] that was not played (stopped): playable again. */
    fun release(f: Fixture)

    /** Records the result of a taken fixture; returns what happened, for the news. */
    fun record(r: JobResult): List<LeagueEvent>

    /** Every fixture played. */
    val finished: Boolean

    val fixturesLeft: Int
    val fixturesTotal: Int

    /** Titles once finished (winner, runner-up, ...); empty before. */
    fun honours(): List<Honour>

    /**
     * The stages in play order (rounds, matchdays), for the season calendar: stage k opens at
     * [Stage.opensAt], and opening it opens every earlier stage too. Empty when it has none.
     */
    fun stages(): List<Stage> = emptyList()

    /** The stage (index in [stages]) of a fixture this competition handed out; -1 when unknown. */
    fun stageOf(f: Fixture): Int = -1

    /** Played and total fixtures of each of [stages]; may scan (for the screen, not per job). */
    fun stageCounts(): List<StageCount> = emptyList()

    /** News when the competition is set up (the draw, squads); asked once, right after the factory. */
    fun opening(): List<LeagueEvent> = emptyList()

    /** This competition's lines for season.txt; [CompetitionCodec] reads them back. */
    fun encode(out: StringBuilder)
}

/** Reads a competition back from its lines in season.txt. */
fun interface CompetitionCodec {
    /** [lines]: the lines between "comp <code>" and "end" (exclusive). */
    fun decode(lines: List<String>, season: Int, seed: Long): Competition
}
