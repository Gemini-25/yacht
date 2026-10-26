package yacht.engine.league

import java.util.Random

/**
 * 챔피언스 리그 (docs/LEAGUE_SPEC.md 2.3): 16 AIs in 4 groups of 4 drawn from pots by entry order,
 * a double round robin in each group (6 matchdays opening over league progress 0.05..0.6, 48
 * matches), then the top 2 of each group in a fixed bracket: quarter-finals A1-B2, B1-A2, C1-D2,
 * D1-C2 and semi-finals over two legs (at 0.7 and 0.8), a one-match final (at 0.9); with
 * [LeagueConfig.longKnockouts] the semis are 3 matches and the final 4. Group tables:
 * points 3/1/0, then score difference. Honours CL and CL2.
 *
 * Entrants: the first season takes the top 16 by rating; later ones last season's 1st division
 * top 12, the 2nd division's top 2 (a pyramid's 2부: each group's winner) and the cup winner and runner-up, a place already taken (or an
 * AI that left) passing down the 1st division, then to the best rated.
 *
 * News: `draw_cl` [group members] (group) at the start; `upset` in any match; `qualified` [ai]
 * (8강, group place) and `eliminated` [ai] (조별 리그) for pot-1 AIs that go out when the groups end;
 * `cup_round`, `final` and `title` [winner] (챔피언스 리그) in the knockout.
 */
class ChampionsLeague private constructor(
    val season: Int,
    private val seed: Long,
    /** The groups, each in pot order (pot 1 first). */
    val groups: List<List<DrawEntry>>,
    /** Longer knockout ties ([KnockoutLength]: 8강 2, 4강 3, 결승 4); saved. */
    private val long: Boolean,
) : ViewableCompetition {
    override val comp: Comp get() = Comp.CHAMPIONS

    /** Every entrant in entry order (its seed). */
    val entries: List<DrawEntry> = groups.flatten().sortedBy { it.seed }

    private val stage = GroupStage(groups, double = true, teams = false) { md -> GROUP_OPENS + GROUP_STEP * md }

    /** The knockout stage, drawn once every group match is played. */
    private var bracket: Knockout? = null

    override fun next(p: Double, busy: Set<String>): Fixture? =
        stage.next(p, busy) { m -> Fixture(comp, m.id, m.a, m.b, Knockout.fixtureSeed(seed, comp, m.id), JobKind.MATCH, label = stage.matchLabel(m)) }
            ?: bracket?.let { k ->
                k.next(p, busy) { t -> Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = k.label(t.round)) }
            }

    override fun release(f: Fixture) {
        if (f.id in stage) stage.release(f.id) else bracket?.release(f.id)
    }

    override fun stages(): List<Stage> =
        List(stage.rounds) { k -> Stage("조별 ${k + 1}차전", GROUP_OPENS + GROUP_STEP * k, GROUP_TARGET + GROUP_TARGET_STEP * k) } +
            KO_LABELS.indices.map { r -> Stage(KO_LABELS[r], KO_OPENS[r], KO_TARGETS[r]) }

    override fun stageOf(f: Fixture): Int {
        if (f.id in stage) return stage.roundOf(f.id)
        val r = bracket?.roundOf(f.id) ?: -1
        return if (r < 0) -1 else stage.rounds + r
    }

    override fun stageCounts(): List<StageCount> = stage.counts() + (bracket?.counts() ?: KO_SIZES.map { StageCount(0, it) })

    override fun record(r: JobResult): List<LeagueEvent> =
        if (Knockout.current(seed, comp, r)) put(r.fixture.id, r.fixture.a, r.fixture.b, TieScore.of(r)) else emptyList()

    private fun put(id: Int, a: String, b: String, s: TieScore): List<LeagueEvent> {
        if (id in stage) {
            val m = stage.record(id, a, b, s) ?: return emptyList()
            val out = ArrayList<LeagueEvent>()
            val x = stage.entry(m.a)
            val y = stage.entry(m.b)
            if (x != null && y != null && s.winner != 0) {
                val (w, l) = if (s.winner > 0) x to y else y to x
                KnockoutNews.upset(season, comp, w, l)?.let { out += it }
            }
            if (stage.finished && bracket == null) out += drawKnockout()
            return out
        }
        val k = bracket ?: return emptyList()
        val t = k.record(id, a, b, s) ?: return emptyList()
        val out = ArrayList<LeagueEvent>()
        if (t.round == k.rounds.size - 1) out += KnockoutNews.final(season, comp, t, comp.label)
        else out += KnockoutNews.round(season, comp, t, k.label(t.round))
        KnockoutNews.upset(season, comp, t.winner!!, t.loser!!)?.let { out += it }
        return out
    }

    /** The fixed bracket from the group tables; returns the qualification news. */
    private fun drawKnockout(): List<LeagueEvent> {
        val tables = groups.indices.map { stage.table(it) }
        // Group winners are seeds 1-4 (A-D), runners-up 5-8: the winner is side A in its quarter-final.
        val first = tables.mapIndexed { g, t -> t[0].entry.let { DrawEntry(it.key, g + 1, it.rating) } }
        val second = tables.mapIndexed { g, t -> t[1].entry.let { DrawEntry(it.key, g + 5, it.rating) } }
        val pairs = listOf(first[0] to second[1], first[1] to second[0], first[2] to second[3], first[3] to second[2])
        bracket = Knockout(pairs, ::legs, { KO_LABELS[it] }, { KO_OPENS[it] }, idBase = KO_BASE)
        val out = ArrayList<LeagueEvent>()
        for ((g, t) in tables.withIndex()) {
            for (place in 0..1) out += LeagueEvent("qualified", season, listOf(t[place].entry.key), listOf(KO_LABELS[0], "${stage.groupLabel(g)} ${place + 1}위"), comp)
            for (row in t.drop(2)) if (row.entry.seed <= POT) out += LeagueEvent("eliminated", season, listOf(row.entry.key), listOf("조별 리그"), comp)
        }
        return out
    }

    override val finished: Boolean get() = bracket?.finished == true
    override val fixturesTotal: Int get() = stage.fixturesTotal + KO_TIES
    override val fixturesLeft: Int get() = stage.fixturesLeft + (bracket?.fixturesLeft ?: KO_TIES)

    override fun honours(): List<Honour> {
        val w = bracket?.champion ?: return emptyList()
        return listOfNotNull(Honour(season, "CL", w.key), bracket?.runnerUp?.let { Honour(season, "CL2", it.key) })
    }

    override fun opening(): List<LeagueEvent> =
        groups.mapIndexed { g, members -> LeagueEvent("draw_cl", season, members.map { it.key }, listOf(stage.groupLabel(g)), comp) }

    /** Matches in a knockout tie of round [r] (8강, 4강, 결승). */
    private fun legs(r: Int): Int = KnockoutLength.legs(8 shr r, if (r < 2) 2 else 1, long)

    override fun encode(out: StringBuilder) {
        out.append(HEADER).append('\n')
        if (long) out.append(KnockoutLength.LINE).append('\n')
        for ((g, members) in groups.withIndex()) for (e in members) out.append("g $g ").append(e.encode()).append('\n')
        stage.encode(out)
        bracket?.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Groups(
        comp.label,
        groups.indices.map { stage.view(it) },
        CompetitionView.Bracket(
            comp.label,
            bracket?.view() ?: Knockout.placeholder(4, { KO_LABELS[it] }, ::legs),
        ),
    )

    companion object {
        const val HEADER = "cl 1"

        /** Entrants; the champions league needs at least this many AIs. */
        const val SIZE = 16
        private const val POT = 4
        private const val GROUP_OPENS = 0.05
        private const val GROUP_STEP = 0.11
        private const val KO_BASE = 100
        private const val KO_TIES = 7
        private val KO_LABELS = listOf("8강", "4강", "결승")
        private val KO_OPENS = listOf(0.7, 0.8, 0.9)
        private val KO_SIZES = listOf(4, 2, 1)

        // Calendar targets: matchdays from 8% of the season, a stage about every four weeks;
        // the final close to the end.
        private const val GROUP_TARGET = 0.08
        private const val GROUP_TARGET_STEP = 0.08
        private val KO_TARGETS = listOf(0.60, 0.72, 0.94)

        /** Places for 2부's best: its top 2, or each group's top 2 / [groups] (at least its winner) in a pyramid. */
        fun secondPlaces(groups: Int): Int = if (groups <= 0) 0 else maxOf(1, SECOND / groups)

        private const val SECOND = 2

        /** The 16 entrants in entry order (see the class comment). */
        fun entrants(start: SeasonStart): List<String> {
            if (start.lastTables.isEmpty()) return start.ranking.take(SIZE)
            val inLeague = start.ranking.toSet()
            val first = start.lastTables[0].filter { it in inLeague }
            val tiers = start.lastTiers.takeIf { it.size == start.lastTables.size } ?: Tiers.linear(start.lastTables.size)
            val seconds = if (tiers.count > 1) tiers.groups(1).map { d -> start.lastTables[d].filter { it in inLeague } } else emptyList()
            val out = LinkedHashSet<String>()
            out += first.take(12)
            for (second in seconds) out += second.take(secondPlaces(seconds.size))
            for (kind in listOf("CUP", "CUP2")) start.lastHonours.firstOrNull { it.kind == kind }?.key?.takeIf { it in inLeague }?.let { out += it }
            // Places taken twice (or left empty) pass down the 1st division, then go by rating.
            for (k in first.drop(12)) {
                if (out.size >= SIZE) break
                out += k
            }
            for (k in start.ranking) {
                if (out.size >= SIZE) break
                out += k
            }
            return out.take(SIZE)
        }

        /** This season's draw; null when off or with fewer than 16 AIs. */
        fun create(start: SeasonStart): ChampionsLeague? {
            if (!start.config.champions || start.ranking.size < SIZE) return null
            val keys = entrants(start)
            if (keys.size < SIZE) return null
            val entries = keys.mapIndexed { i, k -> DrawEntry(k, i + 1, start.rating(k)) }
            val rng = Random(Knockout.drawSeed(start.seed, Comp.CHAMPIONS))
            val groups = List(4) { ArrayList<DrawEntry>() }
            for (pot in 0 until 4) {
                val drawn = entries.subList(pot * POT, pot * POT + POT).shuffled(rng)
                for (g in 0 until 4) groups[g] += drawn[g]
            }
            return ChampionsLeague(start.number, start.seed, groups, start.config.longKnockouts)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            require(lines.firstOrNull()?.trim() == HEADER) { "not a champions league section" }
            val groups = List(4) { ArrayList<DrawEntry>() }
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] == "g") groups[f[1].toInt()] += DrawEntry.decode(f, 2)
                }
            }
            require(groups.all { it.size == POT }) { "incomplete champions league groups" }
            ChampionsLeague(season, seed, groups.map { g -> g.sortedBy { it.seed } }, KnockoutLength.saved(lines)).also { c ->
                for (r in TieScore.Saved.parse(lines)) c.put(r.id, r.a, r.b, r.score)
            }
        }
    }
}
