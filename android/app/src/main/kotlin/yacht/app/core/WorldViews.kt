package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import yacht.engine.league.BracketRound
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.TieView

/**
 * The world's pages on request (`lg.worldPages`): competitions, clubs, leagues, the league
 * ranking, the federation and the transfer market, each loader keeping its last results by key.
 * For now the competition pages, from the screen's snapshot.
 */
class WorldPages internal constructor(private val host: LeagueHost) {
    /** The competition pages loaded last (at most [KEEP]), by [Page.Competition.loadKey]. */
    var competitions by mutableStateOf<Map<String, CompetitionPage>>(emptyMap())
        private set

    /**
     * Loads [comp]'s page at [stage] (an index of [competitionStages]; null or out of range: the
     * stage in play) from the latest snapshot; nothing when the season has no such competition.
     * Main thread; cheap (the snapshot holds the competition's view).
     */
    fun loadCompetition(comp: Comp, stage: Int? = null) {
        val view = host.view()?.comps?.firstOrNull { it.first == comp }?.second ?: return
        val stages = competitionStages(view)
        val at = stage?.takeIf { it in stages.indices } ?: stageInPlay(view, stages, host.favourites())
        val page = CompetitionPage(Page.Competition(comp).loadKey, comp, stages.map { it.label }, at, view)
        competitions = keep(competitions, page.key, page)
    }

    companion object {
        /** Results kept per loader. */
        const val KEEP = 4

        /** [map] with [value] put last under [key], the oldest dropped beyond [KEEP]. */
        internal fun <V> keep(map: Map<String, V>, key: String, value: V): Map<String, V> {
            val m = LinkedHashMap(map)
            m.remove(key)
            m[key] = value
            while (m.size > KEEP) m.remove(m.keys.first())
            return m
        }
    }
}

/**
 * One entry of a competition's stage list: its section ([part], 0 without sections), and its
 * groups ([round] -1) or one round of its bracket ([round] indexes the bracket's rounds).
 */
data class CompetitionStage(val part: Int, val round: Int, val label: String)

/**
 * [view]'s stages as one list that folds section, side and round ("조별 리그", "승자조 8강",
 * "패자조 2R", "최종 결승"; with sections "아시아컵 · 조별 리그", "아시아컵 · 8강").
 */
fun competitionStages(view: CompetitionView): List<CompetitionStage> {
    fun groups(g: CompetitionView.Groups, part: Int, prefix: String): List<CompetitionStage> = buildList {
        if (g.groups.isNotEmpty()) add(CompetitionStage(part, -1, prefix + "조별 리그"))
        g.knockout?.rounds?.forEachIndexed { i, r -> add(CompetitionStage(part, i, prefix + r.label)) }
    }
    return when (view) {
        is CompetitionView.Bracket -> view.rounds.mapIndexed { i, r -> CompetitionStage(0, i, r.label) }
        is CompetitionView.Groups -> groups(view, 0, "")
        is CompetitionView.Sections -> view.parts.flatMapIndexed { p, g -> groups(g, p, g.title + " · ") }
        // A kind of view this screen does not know yet: shown whole.
        else -> emptyList()
    }
}

/** A tie both of whose sides are known, still to play. */
private fun ready(t: TieView): Boolean = !t.played && t.winner == null && t.a != null && t.b != null

/**
 * The round in play among [rounds]: the earliest stage with a ready tie, else the latest with a
 * played one, else the first; null for none.
 */
internal fun roundInPlay(rounds: List<BracketRound>): Int? {
    if (rounds.isEmpty()) return null
    val order = compareBy<Int>({ rounds[it].stage }, { it })
    return rounds.indices.filter { i -> rounds[i].ties.any(::ready) }.minWithOrNull(order)
        ?: rounds.indices.filter { i -> rounds[i].ties.any { it.played } }.maxWithOrNull(order)
        ?: 0
}

/**
 * The stage a competition page opens on: in the section with a followed player (else the first),
 * the bracket's round in play once its first ties are drawn, else the groups.
 */
private fun stageInPlay(view: CompetitionView, stages: List<CompetitionStage>, favourites: Set<String>): Int {
    if (stages.isEmpty()) return 0
    fun inPart(g: CompetitionView.Groups, part: Int): Int {
        val k = g.knockout?.rounds.orEmpty()
        val drawn = k.any { r -> r.ties.any { it.a != null || it.b != null } }
        val round = if (drawn || g.groups.isEmpty()) roundInPlay(k) ?: -1 else -1
        return stages.indexOfFirst { it.part == part && it.round == round }.coerceAtLeast(0)
    }
    return when (view) {
        is CompetitionView.Bracket -> roundInPlay(view.rounds) ?: 0
        is CompetitionView.Groups -> inPart(view, 0)
        is CompetitionView.Sections -> {
            fun followed(g: CompetitionView.Groups) = g.groups.any { t -> t.rows.any { it.key in favourites } } ||
                g.knockout?.rounds.orEmpty().any { r -> r.ties.any { it.a in favourites || it.b in favourites } }
            val part = view.parts.indexOfFirst(::followed).coerceAtLeast(0)
            view.parts.getOrNull(part)?.let { inPart(it, part) } ?: 0
        }
        else -> 0
    }
}
