package yacht.engine.league

import java.util.TreeMap

/** A world-event arc's kind (돌발 세계 사건, design ENG 6.1); [major] arcs run one at a time. Saved by name. */
enum class ArcKind(val major: Boolean, val label: String) {
    SUPER_LEAGUE(true, "슈퍼리그 분리 위협"),
    INVESTOR(false, "투자자 인수"),
    CRISIS(true, "금융 위기"),
    MERGER(true, "리그 통합"),
    BOOM(true, "요트 붐"),
}

/** An arc's stage; [code] is saved. */
enum class ArcStage(val code: Char) {
    RUMOUR('R'), ANNOUNCED('A'), SHOWDOWN('S'), ACTIVE('X'), RECOVERY('V'), VOTE('O'), DONE('D');

    companion object {
        fun of(code: Char): ArcStage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * An arc under way (`ev`): its [kind] and [stage] since season [start]; [next] the season whose
 * close (or winter checkpoint, [atWinter]) takes the next step; its [subjects] (clubs, leagues or
 * nations) and [args]; [outcome] '-' until decided (C collapse, P compromise, S success, E exit,
 * M merged, F failed, N new league, L lapsed).
 */
data class WorldArc(
    val id: Int, val kind: ArcKind, val stage: ArcStage, val start: Int,
    val next: Int,
    val atWinter: Boolean,
    val subjects: List<String>,
    val args: List<String>,
    val outcome: Char = '-',
)

/** An arc that ended (`evh`, the newest 30 kept). */
data class EndedArc(val id: Int, val kind: ArcKind, val start: Int, val end: Int, val outcome: Char, val subjects: List<String>)

/** A takeover an arc decides for the next off-season: club [club]'s new owner of [type] (named by the off-season when [name] is null). */
data class Takeover(val club: String, val type: OwnerType, val ambition: Int, val patience: Int, val name: String?, val arc: Int)

/**
 * What the rest of the engine reads of the arcs for a season (design ENG 6.1, 6.7): the arcs change
 * other books only through this, which the club off-season, the checkpoint, the formation of the
 * leagues, the rookie draw and the federation read. [NONE] is a world without arcs: today's output.
 */
data class ArcEffects(
    val sponsorFactor: Double = 1.0, val tvFactor: Double = 1.0, val injectionFactor: Double = 1.0, val walkoutFactor: Double = 1.0,
    /** Nations without a league of their own ([NationalLeagues.plan]'s merged set). */
    val merged: Set<String> = emptySet(),
    val clPrizeFactor: Double = 1.0,
    /** Nation → factor on its rookie draw weight (요트 붐). */
    val rookieBoost: Map<String, Double> = emptyMap(),
    val takeovers: List<Takeover> = emptyList(), val fines: Map<String, Double> = emptyMap(), val injections: Map<String, Double> = emptyMap(),
    /** Club → mood change at the next off-season. */
    val moodShocks: Map<String, Int> = emptyMap(),
    /** The compromise's CL reform, upserted into the federation at every close (ENG 6.8). */
    val emergency: List<Proposal> = emptyList(),
) {
    companion object {
        val NONE = ArcEffects()
    }
}

/**
 * The world events' part of world.txt (design ENG 6.1): the arcs under way (`ev`), the ended ones
 * (`evh`), when each kind may start again (`cool`) and the leagues in the ranking's bottom quarter
 * of each season (`rb`, 5 seasons, for the merger). The arcs' logic is P14's, in this file.
 */
class ArcBook {
    internal val active = ArrayList<WorldArc>()
    internal val endedList = ArrayList<EndedArc>()
    internal val cooldowns = java.util.EnumMap<ArcKind, Int>(ArcKind::class.java)
    internal val bottom = TreeMap<Int, List<String>>()

    /** The arcs under way, by id. */
    val arcs: List<WorldArc> get() = active.sortedBy { it.id }

    /** The ended arcs kept, oldest first. */
    val ended: List<EndedArc> get() = endedList.toList()

    /** The season from which [kind] may start again (0: any time). */
    fun cooldown(kind: ArcKind): Int = cooldowns[kind] ?: 0

    /** The leagues in the bottom quarter of the ranking after [season]. */
    fun bottom(season: Int): List<String> = bottom[season].orEmpty()

    companion object {
        private fun args(xs: List<String>) = if (xs.isEmpty()) "-" else xs.joinToString("|") { Field.esc(it) }

        private fun argsOf(t: String) = if (t == "-") emptyList() else t.split('|').map { Field.unesc(it) }

        private val EV = LineParts.of<WorldBook>("ev", { w, out ->
            for (a in w.events.active.sortedBy { it.id }) {
                out.append("ev ${a.id} ${a.kind.name} ${a.stage.code} ${a.start} ${a.next} ${LineParts.bit(a.atWinter)} ")
                out.append("${LineParts.list(a.subjects) { it }} ${args(a.args)} ${a.outcome}\n")
            }
        }) { w, f ->
            require(f.size >= 10)
            val stage = ArcStage.of(f[3].single()) ?: return@of false
            w.events.active += WorldArc(f[1].toInt(), ArcKind.valueOf(f[2]), stage, f[4].toInt(), f[5].toInt(), f[6] == "1", LineParts.items(f[7]), argsOf(f[8]), f[9].single())
            true
        }

        private val EVH = LineParts.of<WorldBook>("evh", { w, out ->
            for (a in w.events.endedList) out.append("evh ${a.id} ${a.kind.name} ${a.start} ${a.end} ${a.outcome} ${LineParts.list(a.subjects) { it }}\n")
        }) { w, f ->
            require(f.size >= 7)
            w.events.endedList += EndedArc(f[1].toInt(), ArcKind.valueOf(f[2]), f[3].toInt(), f[4].toInt(), f[5].single(), LineParts.items(f[6]))
            true
        }

        private val COOL = LineParts.of<WorldBook>("cool", { w, out ->
            if (w.events.cooldowns.isNotEmpty()) out.append("cool ").append(w.events.cooldowns.entries.joinToString(",") { "${it.key.name}:${it.value}" }).append('\n')
        }) { w, f ->
            for (e in LineParts.items(f[1])) runCatching { w.events.cooldowns[ArcKind.valueOf(e.substringBefore(':'))] = e.substringAfter(':').toInt() }
            true
        }

        private val RB = LineParts.of<WorldBook>("rb", { w, out ->
            for ((s, ids) in w.events.bottom) out.append("rb $s ${LineParts.list(ids) { it }}\n")
        }) { w, f ->
            w.events.bottom[f[1].toInt()] = LineParts.items(f[2])
            true
        }

        /** `ev evh cool rb`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(EV, EVH, COOL, RB)
    }
}

/**
 * World events (design ENG 6): five rare arcs with state machines, saved in world.txt. P14 writes
 * the arcs; until then the world has none and [effects] is [ArcEffects.NONE].
 */
object WorldEvents {
    /** What the arcs of [book] do in [season] (pure). Neutral without arcs. */
    fun effects(book: WorldBook, season: Int): ArcEffects = ArcEffects.NONE

    /** Test-only: forces a trigger (NationalWorldTest, WinterReplayTest); null in the app. */
    @Volatile
    var forceTrigger: ((ArcKind, Int) -> Boolean)? = null
}
