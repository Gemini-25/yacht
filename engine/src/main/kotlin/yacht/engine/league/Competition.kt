package yacht.engine.league

/** Something that happened, for the news (News.kt turns it into an article). [kind] is a short tag. */
data class LeagueEvent(
    val kind: String,
    val season: Int,
    val keys: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val comp: Comp? = null,
) {
    fun encode(): String = "$kind|$season|${comp?.code ?: '-'}|${keys.joinToString(",")}|${args.joinToString("\u001f")}"

    companion object {
        fun decode(line: String): LeagueEvent? = runCatching {
            val f = line.split('|')
            LeagueEvent(
                f[0], f[1].toInt(), f[3].split(',').filter { it.isNotEmpty() }, f[4].split('\u001f').filter { it.isNotEmpty() },
                f[2].singleOrNull()?.let { Comp.of(it) },
            )
        }.getOrNull()
    }
}

/**
 * A title or award of a season. [kind]: "L<d>" division champion (after playoffs), "REG"
 * regular-season leader of division 1, "UP<d>"/"DOWN<d>" moved into division d, "CUP"/"CUP2"
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
)

/**
 * A season's competition besides the league: it hands out fixtures as they open (by league
 * progress) and takes their results. Implementations are pure and deterministic (draws from the
 * season seed), and encode themselves for season.txt.
 */
interface Competition {
    val comp: Comp

    /**
     * The next fixture that is open at league progress [p] (0..1) and whose AIs are all free
     * (not in [busy]); it is then taken until [record] or [release]. Null when none now.
     */
    fun next(p: Double, busy: Set<String>): Fixture?

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

    /** This competition's lines for season.txt; [CompetitionCodec] reads them back. */
    fun encode(out: StringBuilder)
}

/** Reads a competition back from its lines in season.txt. */
fun interface CompetitionCodec {
    /** [lines]: the lines between "comp <code>" and "end" (exclusive). */
    fun decode(lines: List<String>, season: Int, seed: Long): Competition
}
