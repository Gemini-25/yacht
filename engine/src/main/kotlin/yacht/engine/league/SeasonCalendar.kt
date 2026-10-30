package yacht.engine.league

import kotlin.math.roundToInt

/**
 * The season's weeks (docs/LEAGUE_SPEC.md 2.4). One league round is one week (week 1 is the
 * first round), and every stage of the other competitions gets a week of its own when the season
 * is set up, from its [Stage.target]. Competitions share lanes ([legacyLane], or [nationalLane] in a
 * national season), where one stage falls in a week (more only when they do not fit), and a
 * competition's stages come in order, at least a week apart while there is room: before national
 * leagues the cups, the champions league and the events share one lane and the club league has
 * one of its own. The post-season takes the weeks after the league: stage s of a playoff is in
 * week [leagueWeeks] + 1 + s (the title playoff's semi-finals and the promotion playoffs first,
 * the title final a week later).
 *
 * A stage opens once the season reaches its week: [progress] is the league progress at which the
 * competition's own [Competition.next] opens exactly the stages due by then. Pure: rebuilt from
 * the plan when needed, never saved, so the same season always gets the same weeks.
 */
class SeasonCalendar private constructor(
    /** Weeks of the regular season: the league's rounds (at least 1). */
    val leagueWeeks: Int,
    /** Every competition with stages, in placement order: the season's, then the post-season's. */
    val entries: List<Entry>,
) {
    /** A competition's stages and the week of each. */
    class Entry(val comp: Comp, val stages: List<Stage>, internal val weeks: IntArray, val post: Boolean) {
        fun week(stage: Int): Int = weeks.getOrElse(stage) { NONE }
    }

    private val byComp: Map<Comp, Entry> = entries.associateBy { it.comp }

    /** Weeks after the league (0 when the season has no playoffs). */
    val postWeeks: Int = entries.filter { it.post }.maxOfOrNull { it.weeks.maxOrNull() ?: leagueWeeks }?.let { it - leagueWeeks }?.coerceAtLeast(0) ?: 0

    /** The last week of the season, post-season included. */
    val lastWeek: Int get() = leagueWeeks + postWeeks

    fun entry(comp: Comp): Entry? = byComp[comp]

    /** The week of stage [stage] of [comp]; [NONE] when it has none. */
    fun week(comp: Comp, stage: Int): Int = byComp[comp]?.week(stage) ?: NONE

    /** The stages in week [week], as (competition, stage) in placement order. */
    fun at(week: Int): List<Pair<Comp, Int>> {
        val out = ArrayList<Pair<Comp, Int>>()
        for (e in entries) for (s in e.stages.indices) if (e.weeks[s] == week) out += e.comp to s
        return out
    }

    /**
     * The league progress at which [comp] opens just the stages due by week [week]: the last such
     * stage's [Stage.opensAt]; [CLOSED] before its first one; NaN for a competition the calendar
     * does not know (it then opens by the league's own progress, as before calendars).
     */
    fun progress(comp: Comp, week: Int): Double {
        val e = byComp[comp] ?: return Double.NaN
        var p = CLOSED
        for (s in e.stages.indices) if (e.weeks[s] <= week) p = maxOf(p, e.stages[s].opensAt)
        return p
    }

    companion object {
        /** No week known (friendlies, the lab, news written before calendars). */
        const val NONE = -1

        /** Before the first round: the draws. */
        const val PRESEASON = 0

        /** After the last week: the season's close (awards, moves, the transfer window). */
        const val CLOSING = 10_000

        /** A league progress at which nothing opens. */
        const val CLOSED = -1.0

        /**
         * The calendar of a season of [leagueWeeks] rounds with competitions [comps] and the
         * post-season [post] (each playoff's competition and stages); [lanes] gives each
         * competition its lane.
         */
        fun build(leagueWeeks: Int, comps: List<Competition>, post: List<Pair<Comp, List<Stage>>>, lanes: (Comp) -> Int = ::legacyLane): SeasonCalendar {
            val r = maxOf(1, leagueWeeks)
            val season = comps.map { it.comp to runCatching { it.stages() }.getOrDefault(emptyList()) }.filter { it.second.isNotEmpty() }
            val placed = HashMap<Comp, IntArray>()
            val byLane = season.groupBy { lanes(it.first) }
            for (lane in byLane.keys.sorted()) place(r, byLane.getValue(lane), placed)
            val entries = ArrayList<Entry>()
            for ((c, stages) in season) entries += Entry(c, stages, placed.getValue(c), post = false)
            for ((c, stages) in post) {
                if (stages.isEmpty() || placed.containsKey(c)) continue
                entries += Entry(c, stages, IntArray(stages.size) { s -> r + 1 + s }, post = true)
            }
            return SeasonCalendar(r, entries)
        }

        /** The week stage target [t] would like in a season of [r] weeks. */
        fun targetWeek(t: Double, r: Int): Int = (1 + (t.coerceIn(0.0, 1.0) * (r - 1)).roundToInt()).coerceIn(1, r)

        /**
         * Places one lane: stages by target (ties: the order of [PRIORITY], then stage), each in
         * the first week from its target, and after its competition's previous stage, that still
         * has room; the last week takes whatever does not fit.
         */
        private fun place(r: Int, lane: List<Pair<Comp, List<Stage>>>, out: MutableMap<Comp, IntArray>) {
            class Item(val c: Int, val s: Int, val target: Double)
            val items = ArrayList<Item>()
            for ((c, entry) in lane.withIndex()) for ((s, st) in entry.second.withIndex()) items += Item(c, s, st.target)
            if (items.isEmpty()) return
            val cap = (items.size + r - 1) / r
            items.sortWith(compareBy<Item> { it.target }.thenBy { PRIORITY.indexOf(lane[it.c].first).let { p -> if (p < 0) PRIORITY.size else p } }.thenBy { it.c }.thenBy { it.s })
            val weeks = lane.map { IntArray(it.second.size) }
            val last = IntArray(lane.size)
            val used = IntArray(r + 1)
            for (it in items) {
                var w = maxOf(targetWeek(it.target, r), last[it.c] + 1).coerceAtMost(r)
                while (used[w] >= cap && w < r) w++
                used[w]++
                weeks[it.c][it.s] = w
                last[it.c] = w
            }
            for ((c, entry) in lane.withIndex()) out[entry.first] = weeks[c]
        }

        /** Who gets a week first when two stages of a lane want the same one. */
        private val PRIORITY = listOf(
            Comp.SUPER, Comp.WORLD_CUP, Comp.CHAMPIONS, Comp.CONT_CHAMPIONS, Comp.CUP, Comp.CONTINENTAL, Comp.FAMILY, Comp.ALL_STAR, Comp.LEGEND, Comp.CLUB_CUP,
        )

        /** Lanes before national leagues: the club league (1) apart from everything else (0). */
        fun legacyLane(c: Comp): Int = if (c == Comp.CLUB) 1 else 0

        /**
         * Lanes of a national season: A (0) the cups and events (요트컵, 슈퍼컵, 올스타전, 레전드
         * 매치), B (1) the international club competitions (챔피언스 리그, 대륙 챔피언스), C (2)
         * the nations' and families' cups, D (3) the clubs' (클럽 대항전, a club league still in play).
         * Each fits at one stage a week in a season of 38-42 weeks.
         */
        fun nationalLane(c: Comp): Int = when (c) {
            Comp.CHAMPIONS, Comp.CONT_CHAMPIONS -> 1
            Comp.WORLD_CUP, Comp.CONTINENTAL, Comp.FAMILY -> 2
            Comp.CLUB, Comp.CLUB_CUP -> 3
            else -> 0
        }
    }
}
