package yacht.engine.league

import java.util.Random

/**
 * A competition whose entrants earn their leagues coefficient points ([Coefficients]): the
 * 챔피언스 리그 and the 대륙 챔피언스.
 */
interface International {
    /**
     * Each entrant's coefficient points so far: group matches won and drawn, knockout series
     * won, how far it got ([Coefficients.placePoints]), a 챔피언스 리그 place; every entrant has
     * a value (0 or more).
     */
    fun coefficientPoints(): Map<String, Int>

    /** Every entrant, in entry order. */
    fun entrantKeys(): List<String>
}

/**
 * Groups of 4 and the bracket of their top 2, for the 챔피언스 리그 (4 groups) and each section
 * of the 대륙 챔피언스 (4 or 2 groups): a double round robin in each group (points 3/1/0, a 2-0
 * sweep's bonus point with [bonus]), then group winners against other groups' runners-up (A1-B2,
 * B1-A2, C1-D2, D1-C2; with 2 groups A1-B2, B1-A2), the winner side A. 4 groups make 8강, 4강,
 * 결승 (6 stages with double elimination), 2 groups 4강 and 결승 (4 stages). Group match ids run
 * from [idBase], knockout ties from [idBase] + 100. Saving is the owner's (its draw) plus
 * [encode]'s result lines.
 *
 * News: `upset` (in group matches only with [groupUpsets]), `qualified` [ai] (the knockout's first
 * stage, group place) and `eliminated` [ai] (조별 리그) for pot-1 entrants out when the groups end;
 * `cup_round`, `final` and `title` [winner] ([title]) in the knockout. [tag] is added to the
 * arguments of every one but `title` (a section's "@아시아 챔피언스").
 */
class ChampionsBracket(
    private val comp: Comp,
    private val season: Int,
    private val seed: Long,
    /** The groups, each in pot order (pot 1 first): 4 or 2 of 4 entrants. */
    val groups: List<List<DrawEntry>>,
    private val idBase: Int,
    /** Longer knockout ties ([KnockoutLength]). */
    private val long: Boolean,
    /** Knockout ties are series (선승제). */
    private val series: Boolean,
    /** The knockout is double elimination (패자부활). */
    private val double: Boolean,
    /** Group tables give a 2-0 sweep a bonus point (판 승점제's 완승 보너스, [LeagueConfig.sweepPoints]). */
    bonus: Boolean,
    private val timing: Timing,
    /** Put before every fixture label ("아시아 " makes "아시아 A조 1차전"); empty for the 챔피언스 리그. */
    private val labelPrefix: String = "",
    private val title: String = comp.label,
    private val tag: List<String> = emptyList(),
    private val groupUpsets: Boolean = true,
) {
    /**
     * When stages open and would like to be played: group matchday k by [groupOpens] and
     * [groupTargets], knockout stage s by [koOpens] and [koTargets] (one value a stage).
     */
    class Timing(
        val groupOpens: (Int) -> Double,
        val groupTargets: (Int) -> Double,
        val koOpens: List<Double>,
        val koTargets: List<Double>,
    )

    init {
        require(groups.size == 2 || groups.size == 4) { "2 or 4 groups" }
        require(groups.all { it.size == POT }) { "groups of 4" }
    }

    /** Groups (the knockout's first round has as many ties). */
    private val count = groups.size

    /** The winners' rounds by name: 8강, 4강, 결승, or 4강, 결승. */
    private val labels: List<String> = KO_LABELS.takeLast(Integer.numberOfTrailingZeros(2 * count))

    private val stage = GroupStage(groups, double = true, teams = false, bonus = bonus, idBase = idBase, opensAt = timing.groupOpens)

    /** The knockout's stages before the draw: as single elimination, or with the losers' bracket. */
    private val koSizes: List<Int> = Knockout.stageSizes(count, double)
    private val koLabels: List<String> = Knockout.stageLabels(count, double) { labels[it] }

    init {
        require(timing.koOpens.size == koSizes.size && timing.koTargets.size == koSizes.size) { "a time for each knockout stage" }
    }

    /** The knockout stage, drawn once every group match is played. */
    var bracket: Knockout? = null
        private set

    /** Every entrant in entry order (its seed). */
    val entries: List<DrawEntry> = groups.flatten().sortedBy { it.seed }

    /** Group matchdays (6). */
    val groupRounds: Int get() = stage.rounds

    /** Knockout stages. */
    val koStages: Int get() = koSizes.size

    operator fun contains(id: Int): Boolean = id in stage || bracket?.contains(id) == true

    fun next(p: Double, busy: Set<String>): Fixture? =
        stage.next(p, busy, ::fixture) ?: bracket?.let { k -> k.next(p, busy) { t -> fixture(k, t) } }

    private fun fixture(m: GroupStage.Match): Fixture =
        Fixture(comp, m.id, m.a, m.b, Knockout.fixtureSeed(seed, comp, m.id), JobKind.MATCH, label = labelPrefix + stage.matchLabel(m))

    private fun fixture(k: Knockout, t: KnockoutTie): Fixture =
        Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = labelPrefix + k.label(t), firstTo = t.firstTo)

    /** Every group match, then the knockout's ties with both sides known: the fixture and its stored score (null: not played). */
    fun matches(): List<Pair<Fixture, TieScore?>> =
        stage.matches.map { fixture(it) to it.result } + bracket?.let { k -> k.matches.map { fixture(k, it) to it.result } }.orEmpty()

    /** Match or tie [id] as [matches] lists it; null when it is none of them. */
    fun match(id: Int): Pair<Fixture, TieScore?>? {
        stage.match(id)?.let { return fixture(it) to it.result }
        val k = bracket ?: return null
        val t = k.tie(id)?.takeIf { !it.bye && it.a != null && it.b != null } ?: return null
        return fixture(k, t) to t.result
    }

    fun release(id: Int) {
        if (id in stage) stage.release(id) else bracket?.release(id)
    }

    /** The group matchdays, then the knockout stages (labelled by the bracket once drawn). */
    fun stages(): List<Stage> =
        List(stage.rounds) { k -> Stage("조별 ${k + 1}차전", timing.groupOpens(k), timing.groupTargets(k)) } +
            koSizes.indices.map { st -> Stage(koStageLabel(st), timing.koOpens[st], timing.koTargets[st]) }

    /** Knockout stage [st]'s label (the bracket's once drawn). */
    fun koStageLabel(st: Int): String = bracket?.stageLabel(st) ?: koLabels[st]

    /** The stage of fixture [id] in [stages]; -1 when it is not this bracket's. */
    fun stageOf(id: Int): Int {
        if (id in stage) return stage.roundOf(id)
        val r = bracket?.roundOf(id) ?: -1
        return if (r < 0) -1 else stage.rounds + r
    }

    fun stageCounts(): List<StageCount> = stage.counts() + (bracket?.counts() ?: koSizes.map { StageCount(0, it) })

    /** Records match or tie [id] between [a] and [b]; returns the news (empty for a stale result). */
    fun record(id: Int, a: String, b: String, s: TieScore): List<LeagueEvent> {
        if (id in stage) {
            val m = stage.record(id, a, b, s) ?: return emptyList()
            val out = ArrayList<LeagueEvent>()
            val x = stage.entry(m.a)
            val y = stage.entry(m.b)
            if (groupUpsets && x != null && y != null && s.winner != 0) {
                val (w, l) = if (s.winner > 0) x to y else y to x
                KnockoutNews.upset(season, comp, w, l)?.let { out += tagged(it).about(MatchRef(season, comp, id)) }
            }
            if (stage.finished && bracket == null) out += drawKnockout()
            return out
        }
        val k = bracket ?: return emptyList()
        val t = k.record(id, a, b, s) ?: return emptyList()
        val out = ArrayList<LeagueEvent>()
        if (k.isFinal(t)) out += KnockoutNews.final(season, comp, t, title).map { if (it.kind == "title") it else tagged(it) }
        else out += tagged(KnockoutNews.round(season, comp, t, k.label(t)))
        KnockoutNews.upset(season, comp, t.winner!!, t.loser!!)?.let { out += tagged(it) }
        val ref = MatchRef(season, comp, t.id)
        return out.map { it.about(ref) }
    }

    private fun tagged(e: LeagueEvent): LeagueEvent = if (tag.isEmpty()) e else e.copy(args = e.args + tag)

    /** The fixed bracket from the group tables; returns the qualification news. */
    private fun drawKnockout(): List<LeagueEvent> {
        val tables = groups.indices.map { stage.table(it) }
        // Group winners are seeds 1-4 (A-D), runners-up 5-8: the winner is side A in its first tie.
        val first = tables.mapIndexed { g, t -> t[0].entry.let { DrawEntry(it.key, g + 1, it.rating) } }
        val second = tables.mapIndexed { g, t -> t[1].entry.let { DrawEntry(it.key, g + 1 + count, it.rating) } }
        val pairs = first.indices.map { g -> first[g] to second[g xor 1] }
        bracket = Knockout(
            pairs, ::legs, { labels[it] }, { timing.koOpens[it] }, idBase = idBase + KO_BASE,
            winsOf = if (series) { r -> KnockoutLength.wins((2 * count) shr r, long, grandAfter = double) } else null,
            double = double, losersWins = { KnockoutLength.losersWins(it, long) }, grandWins = KnockoutLength.grandWins(long),
        )
        val out = ArrayList<LeagueEvent>()
        for ((g, t) in tables.withIndex()) {
            for (place in 0..1) out += tagged(LeagueEvent("qualified", season, listOf(t[place].entry.key), listOf(bracket!!.label(0), "${stage.groupLabel(g)} ${place + 1}위"), comp))
            for (row in t.drop(2)) if (row.entry.seed <= count) out += tagged(LeagueEvent("eliminated", season, listOf(row.entry.key), listOf("조별 리그"), comp))
        }
        return out
    }

    val finished: Boolean get() = bracket?.finished == true
    val fixturesTotal: Int get() = stage.fixturesTotal + (bracket?.fixturesTotal ?: koSizes.sum())
    val fixturesLeft: Int get() = stage.fixturesLeft + (bracket?.fixturesLeft ?: koSizes.sum())

    val champion: DrawEntry? get() = bracket?.champion
    val runnerUp: DrawEntry? get() = bracket?.champion?.let { bracket?.runnerUp }

    /** Matches in a knockout tie of winners' round [r] (two legs before the final). */
    private fun legs(r: Int): Int = ((2 * count) shr r).let { left -> KnockoutLength.legs(left, if (left > 2) 2 else 1, long) }

    /** Coefficient points of every entrant so far ([International.coefficientPoints], without a 챔피언스 리그 place). */
    fun points(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (e in entries) out[e.key] = 0
        fun add(k: String, p: Int) {
            out[k] = (out[k] ?: 0) + p
        }
        for (m in stage.matches) {
            val s = m.result ?: continue
            when (s.winner) {
                1 -> add(m.a, Coefficients.GROUP_WIN)
                -1 -> add(m.b, Coefficients.GROUP_WIN)
                else -> {
                    add(m.a, Coefficients.GROUP_DRAW)
                    add(m.b, Coefficients.GROUP_DRAW)
                }
            }
        }
        val k = bracket ?: return out
        for (t in k.rounds[0]) for (e in listOfNotNull(t.a, t.b)) add(e.key, Coefficients.KNOCKOUT)
        for (t in k.all) if (!t.bye && t.result != null) t.winner?.let { add(it.key, Coefficients.SERIES_WIN) }
        for ((key, place) in k.places()) add(key, Coefficients.placePoints(place))
        return out
    }

    /** Result lines of the played matches and ties. */
    fun encode(out: StringBuilder) {
        stage.encode(out)
        bracket?.encode(out)
    }

    /** The groups and the bracket for the screen, both titled [title]. */
    fun view(title: String): CompetitionView.Groups = CompetitionView.Groups(
        title,
        groups.indices.map { stage.view(it) },
        CompetitionView.Bracket(
            title,
            bracket?.view() ?: Knockout.placeholder(
                count, { labels[it] }, ::legs, double,
                firstTo = { r -> if (series) KnockoutLength.wins((2 * count) shr r, long, grandAfter = double) else 0 },
                losersFirstTo = { if (series) KnockoutLength.losersWins(it, long) else 0 }, grandFirstTo = if (series) KnockoutLength.grandWins(long) else 0,
            ),
        ),
    )

    companion object {
        /** Entrants of a group. */
        const val POT = 4

        /** Knockout ids start this far after the group matches'. */
        const val KO_BASE = 100
        private val KO_LABELS = listOf("8강", "4강", "결승")
    }
}

/**
 * 챔피언스 리그 (docs/LEAGUE_SPEC.md 2.3): 16 AIs in 4 groups of 4 drawn from pots by entry order,
 * a double round robin in each group (6 matchdays opening over league progress 0.05..0.6, 48
 * matches), then the top 2 of each group in a fixed bracket: quarter-finals A1-B2, B1-A2, C1-D2,
 * D1-C2 and semi-finals over two legs (at 0.7 and 0.8), a one-match final (at 0.9); with
 * [LeagueConfig.longKnockouts] the semis are 3 matches and the final 4. Group tables:
 * points 3/1/0 (and 1 for a 2-0 sweep under 판 승점제), then score difference. With series
 * and double elimination (rules version 2) the knockout is 14 ties in 6 stages. Honours CL and CL2.
 * The groups and bracket are a [ChampionsBracket].
 *
 * Entrants ([entrants]): the first season takes the top 16 by rating. After a legacy season, last
 * season's 1st division top 12, the 2nd division's top 2 (a pyramid's 2부: each group's winner)
 * and the cup winner and runner-up, a place already taken (or an AI that left) passing down the
 * 1st division, then to the best rated. After a national season it is the world stage: the
 * holder, the 대륙 챔피언스' champions and runners-up and the champions of the best-ranked
 * leagues ([worldEntrants]).
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
    /** Knockout ties are series (선승제); saved. */
    private val series: Boolean = false,
    /** The knockout is double elimination (패자부활); saved. */
    private val double: Boolean = false,
    /** Group tables give a 2-0 sweep a bonus point (판 승점제); saved. */
    private val bonus: Boolean = false,
) : ViewableCompetition, International, MatchSource {
    override val comp: Comp get() = Comp.CHAMPIONS

    private val stage = ChampionsBracket(
        comp, season, seed, groups, 0, long, series, double, bonus,
        ChampionsBracket.Timing(
            { md -> GROUP_OPENS + GROUP_STEP * md }, { k -> GROUP_TARGET + GROUP_TARGET_STEP * k },
            if (double) KO_OPENS_DOUBLE else KO_OPENS, if (double) KO_TARGETS_DOUBLE else KO_TARGETS,
        ),
    )

    /** Every entrant in entry order (its seed). */
    val entries: List<DrawEntry> = stage.entries

    override fun next(p: Double, busy: Set<String>): Fixture? = stage.next(p, busy)

    override fun release(f: Fixture) = stage.release(f.id)

    override fun stages(): List<Stage> = stage.stages()

    override fun stageOf(f: Fixture): Int = stage.stageOf(f.id)

    override fun stageCounts(): List<StageCount> = stage.stageCounts()

    override fun record(r: JobResult): List<LeagueEvent> =
        if (Knockout.current(seed, comp, r)) stage.record(r.fixture.id, r.fixture.a, r.fixture.b, TieScore.of(r)) else emptyList()

    override fun seasonMatches(): List<SeasonMatch> = stage.matches().map { (f, s) -> matchOf(season, f, s) }

    override fun seasonMatch(id: Int): SeasonMatch? = stage.match(id)?.let { (f, s) -> matchOf(season, f, s) }

    override val finished: Boolean get() = stage.finished
    override val fixturesTotal: Int get() = stage.fixturesTotal
    override val fixturesLeft: Int get() = stage.fixturesLeft

    override fun honours(): List<Honour> {
        val w = stage.champion ?: return emptyList()
        return listOfNotNull(Honour(season, "CL", w.key), stage.runnerUp?.let { Honour(season, "CL2", it.key) })
    }

    override fun opening(): List<LeagueEvent> =
        groups.mapIndexed { g, members -> LeagueEvent("draw_cl", season, members.map { it.key }, listOf("${'A' + g}조"), comp) }

    /** Every entrant's coefficient points: the bracket's and [Coefficients.CL_ENTRY] for the place. */
    override fun coefficientPoints(): Map<String, Int> = stage.points().mapValues { it.value + Coefficients.CL_ENTRY }

    override fun entrantKeys(): List<String> = entries.map { it.key }

    override fun encode(out: StringBuilder) {
        out.append(HEADER).append('\n')
        if (long) out.append(KnockoutLength.LINE).append('\n')
        if (series) out.append(KnockoutLength.SERIES_LINE).append('\n')
        if (double) out.append(KnockoutLength.DOUBLE_LINE).append('\n')
        if (bonus) out.append(GroupStage.BONUS_LINE).append('\n')
        for ((g, members) in groups.withIndex()) for (e in members) out.append("g $g ").append(e.encode()).append('\n')
        stage.encode(out)
    }

    override fun view(): CompetitionView = stage.view(comp.label)

    internal fun restore(results: List<TieScore.Saved>) {
        for (r in results) stage.record(r.id, r.a, r.b, r.score)
    }

    companion object {
        const val HEADER = "cl 1"

        /** Entrants; the champions league needs at least this many AIs. */
        const val SIZE = 16
        private const val POT = ChampionsBracket.POT
        private const val GROUP_OPENS = 0.05
        private const val GROUP_STEP = 0.11
        private val KO_OPENS = listOf(0.7, 0.8, 0.9)

        // With the losers' bracket: W 8강; W 4강 + L1; L2; W 결승 + L3; L 결승; 최종 결승.
        private val KO_OPENS_DOUBLE = listOf(0.62, 0.67, 0.72, 0.78, 0.84, 0.9)

        // Calendar targets: matchdays from 8% of the season, a stage about every four weeks;
        // the final close to the end.
        private const val GROUP_TARGET = 0.08
        private const val GROUP_TARGET_STEP = 0.08
        private val KO_TARGETS = listOf(0.60, 0.72, 0.94)
        private val KO_TARGETS_DOUBLE = listOf(0.58, 0.63, 0.69, 0.76, 0.84, 0.93)

        /** Places for 2부's best: its top 2, or each group's top 2 / [groups] (at least its winner) in a pyramid. */
        fun secondPlaces(groups: Int): Int = if (groups <= 0) 0 else maxOf(1, SECOND / groups)

        private const val SECOND = 2

        /** The 16 entrants in entry order (see the class comment). */
        fun entrants(start: SeasonStart): List<String> {
            if (start.lastTables.isEmpty()) return start.ranking.take(SIZE)
            if (start.lastLeagues.national) return worldEntrants(start)
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

        /**
         * The world stage after a national season, 16 in this order (a player already in, or no
         * longer in the world, is skipped):
         * 1. last season's holder (CL);
         * 2. the 대륙 챔피언스' champions (CCL.*) and 3. runners-up (CCL2.*), in [Confed] order,
         *    leaving [LeagueConfig.clDirect] places free;
         * 4. the champions (L0.<id>) of the best-ranked leagues ([SeasonStart.leagueRanking]),
         *    [LeagueConfig.clDirect] of them (a place whose champion is already in passes down to
         *    the next league's);
         * 5. then the 1부 runners-up (L0R.<id>, else the best other of its 1부) in league order;
         * 6. then rating.
         * Without a 대륙 챔피언스 last season (a small world: one 연합 리그 a confederation), after
         * the holder each league in turn, by rank, adds its next player still in the world (its
         * champion, then its 1부 table): 4 champions, 4 seconds, 4 thirds, ...
         */
        fun worldEntrants(start: SeasonStart): List<String> {
            val inWorld = start.ranking.toHashSet()
            val out = LinkedHashSet<String>()
            fun add(k: String?): Boolean = k != null && k in inWorld && out.size < SIZE && out.add(k)
            fun honour(kind: String): String? = start.lastHonours.firstOrNull { it.kind == kind }?.key
            val last = start.lastLeagues
            val tops = last.list.filter { it.national }.associate { it.id to start.lastTables.getOrNull(it.top).orEmpty() }
            val order = Coefficients.order(tops.keys, start.leagueRanking) { id -> Coefficients.strength(tops.getValue(id), start.rating) }
            // A league's places: its champion (after the title playoff), then its 1부's table.
            fun places(id: String): List<String> = (listOfNotNull(honour(HonourKind.of("L0", id))) + tops.getValue(id)).distinct()
            add(honour("CL"))
            if (start.lastHonours.any { HonourKind.base(it.kind) == "CCL" }) {
                val direct = minOf(start.config.clDirect, SIZE)
                val continental = Confed.entries.map { honour(HonourKind.of("CCL", it.name)) } + Confed.entries.map { honour(HonourKind.of("CCL2", it.name)) }
                for (k in continental) if (out.size < SIZE - direct) add(k)
                var champions = 0
                for (id in order) if (champions < direct && add(places(id).firstOrNull())) champions++
                for (id in order) add(honour(HonourKind.of("L0R", id)) ?: places(id).drop(1).firstOrNull { it in inWorld })
            } else {
                // One who left passes the place to the next of the same league (as in the 대륙 챔피언스).
                val lists = order.map { id -> places(id).filter { it in inWorld } }
                var p = 0
                while (out.size < SIZE && lists.any { p < it.size }) {
                    for (l in lists) add(l.getOrNull(p))
                    p++
                }
            }
            for (k in start.ranking) add(k)
            return out.toList()
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
            val c = start.config
            return ChampionsLeague(start.number, start.seed, groups, c.longKnockouts, c.seriesKnockouts, c.doubleElimination, c.sweepPoints)
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
            ChampionsLeague(
                season, seed, groups.map { g -> g.sortedBy { it.seed } }, KnockoutLength.saved(lines), KnockoutLength.series(lines), KnockoutLength.double(lines),
                GroupStage.bonus(lines),
            ).also { c -> c.restore(TieScore.Saved.parse(lines)) }
        }
    }
}
