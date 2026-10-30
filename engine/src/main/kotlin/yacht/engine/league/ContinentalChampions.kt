package yacht.engine.league

import java.util.Random

/**
 * 대륙 챔피언스 (national seasons only, [LeagueConfig.continentalCl]): one tournament (section) per
 * [Confed] whose national leagues this season number at least 2 (the 국제 연합 리그 counts for its
 * confederation): 16 players with 4 leagues or more, 8 with 2-3 ([sectionSize]). Each section is
 * a [ChampionsBracket] (groups of 4, a double round robin, then the top 2 of each in a bracket:
 * 4 groups make 8강, 4강 and 결승, 2 groups 4강 and 결승; double elimination with
 * [LeagueConfig.doubleElimination]); section s takes ids from s × [SECTION_IDS].
 *
 * Places: a confederation's leagues by the league ranking the season is played under (leagues
 * without a row after them by strength), each its share [Coefficients.cclSlots]; a league short of
 * players passes what it cannot fill down the ranking. A league's players ([candidates]): last
 * season's champion, then its 1부's final table; a league new this season (or after a legacy
 * season) its 1부 by rating. Seeds go by league rank, then place; pots of the seeds in order,
 * drawn so no group holds more than 2 of one league where the draw allows.
 *
 * Honours CCL.<CONFED> and CCL2.<CONFED> ([HonourKind]). News: `draw_ccl` [section's entrants]
 * (section title) at the start; `qualified`, `eliminated`, `cup_round`, `final` and `upset` (the
 * knockout's only) with the section's "@title" last; `title` [winner] (section title).
 */
class ContinentalChampions private constructor(
    val season: Int,
    private val seed: Long,
    val sections: List<Section>,
    /** Longer knockout ties; saved. */
    private val long: Boolean,
    /** Knockout ties are series; saved. */
    private val series: Boolean,
    /** Double elimination; saved. */
    private val double: Boolean,
    /** A 2-0 sweep's bonus point in the group tables; saved. */
    private val bonus: Boolean,
) : ViewableCompetition, International {
    override val comp: Comp get() = Comp.CONT_CHAMPIONS

    /** One confederation's tournament: its groups (4 or 2), each in pot order. */
    class Section(val confed: Confed, val groups: List<List<DrawEntry>>) {
        val title: String get() = title(confed)
        val size: Int get() = groups.size * ChampionsBracket.POT
    }

    private val brackets: List<ChampionsBracket> = sections.mapIndexed { s, sec ->
        val stages = Knockout.stageSizes(sec.groups.size, double).size
        val opens = (if (double) KO_OPENS_DOUBLE else KO_OPENS).takeLast(stages)
        val targets = (if (double) KO_TARGETS_DOUBLE else KO_TARGETS).takeLast(stages)
        ChampionsBracket(
            comp, season, seed, sec.groups, s * SECTION_IDS, long, series, double, bonus,
            ChampionsBracket.Timing({ k -> GROUP_OPENS + GROUP_STEP * k }, { k -> GROUP_TARGET + GROUP_STEP * k }, opens, targets),
            labelPrefix = "${sec.confed.label} ", title = sec.title, tag = listOf("@" + sec.title), groupUpsets = false,
        )
    }

    /** The section with the most knockout stages: its stages are the competition's (smaller sections play the last ones). */
    private val widest: ChampionsBracket = brackets.maxBy { it.koStages }

    private val groupRounds: Int = widest.groupRounds

    /**
     * The knockout's stage labels: the widest section's; generic ones with double elimination when
     * sections differ in size (a smaller section's 4강 would sit under a wider one's losers' round).
     */
    private fun koLabel(st: Int): String =
        if (double && brackets.any { it.koStages != widest.koStages }) {
            if (st == widest.koStages - 1) Knockout.GRAND_LABEL else "토너먼트 ${st + 1}단계"
        } else {
            widest.koStageLabel(st)
        }

    /** Index in the competition's knockout stages of stage [st] of section [s]. */
    private fun koIndex(s: Int, st: Int): Int = widest.koStages - brackets[s].koStages + st

    private fun sectionOf(id: Int): Int = id / SECTION_IDS

    override fun next(p: Double, busy: Set<String>): Fixture? {
        for (b in brackets) b.next(p, busy)?.let { return it }
        return null
    }

    override fun release(f: Fixture) {
        brackets.getOrNull(sectionOf(f.id))?.release(f.id)
    }

    override fun stages(): List<Stage> {
        val own = widest.stages()
        return own.take(groupRounds) + (0 until widest.koStages).map { st -> own[groupRounds + st].copy(label = koLabel(st)) }
    }

    override fun stageOf(f: Fixture): Int {
        val s = sectionOf(f.id)
        val local = brackets.getOrNull(s)?.stageOf(f.id) ?: -1
        return when {
            local < 0 -> -1
            local < groupRounds -> local
            else -> groupRounds + koIndex(s, local - groupRounds)
        }
    }

    override fun stageCounts(): List<StageCount> {
        val played = IntArray(groupRounds + widest.koStages)
        val total = IntArray(played.size)
        for ((s, b) in brackets.withIndex()) {
            for ((i, c) in b.stageCounts().withIndex()) {
                val at = if (i < groupRounds) i else groupRounds + koIndex(s, i - groupRounds)
                played[at] += c.played
                total[at] += c.total
            }
        }
        return played.indices.map { StageCount(played[it], total[it]) }
    }

    override fun record(r: JobResult): List<LeagueEvent> {
        if (!Knockout.current(seed, comp, r)) return emptyList()
        val f = r.fixture
        return brackets.getOrNull(sectionOf(f.id))?.record(f.id, f.a, f.b, TieScore.of(r)).orEmpty()
    }

    override val finished: Boolean get() = brackets.all { it.finished }
    override val fixturesTotal: Int get() = brackets.sumOf { it.fixturesTotal }
    override val fixturesLeft: Int get() = brackets.sumOf { it.fixturesLeft }

    override fun honours(): List<Honour> {
        val out = ArrayList<Honour>()
        for ((s, b) in brackets.withIndex()) {
            val w = b.champion ?: continue
            val c = sections[s].confed.name
            out += Honour(season, HonourKind.of("CCL", c), w.key)
            b.runnerUp?.let { out += Honour(season, HonourKind.of("CCL2", c), it.key) }
        }
        return out
    }

    override fun opening(): List<LeagueEvent> =
        sections.map { sec -> LeagueEvent("draw_ccl", season, sec.groups.flatten().sortedBy { it.seed }.map { it.key }, listOf(sec.title), comp) }

    override fun coefficientPoints(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (b in brackets) out += b.points()
        return out
    }

    override fun entrantKeys(): List<String> = brackets.flatMap { b -> b.entries.map { it.key } }

    override fun encode(out: StringBuilder) {
        out.append(HEADER).append('\n')
        if (long) out.append(KnockoutLength.LINE).append('\n')
        if (series) out.append(KnockoutLength.SERIES_LINE).append('\n')
        if (double) out.append(KnockoutLength.DOUBLE_LINE).append('\n')
        if (bonus) out.append(GroupStage.BONUS_LINE).append('\n')
        for ((s, sec) in sections.withIndex()) out.append("sec $s ${sec.confed.name} ${sec.groups.size}\n")
        for ((s, sec) in sections.withIndex()) for ((g, members) in sec.groups.withIndex()) for (e in members) out.append("g $s $g ").append(e.encode()).append('\n')
        for (b in brackets) b.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Sections(comp.label, brackets.mapIndexed { s, b -> b.view(sections[s].title) })

    companion object {
        const val HEADER = "ccl 1"

        /** Ids a section takes: section s from s × this (groups from +0, its knockout from +100). */
        const val SECTION_IDS = 1000

        /** Group retries for the draw (at most 2 of one league in a group). */
        private const val TRIES = 30

        /** Most of one league in a group, where the draw allows. */
        private const val SAME_LEAGUE = 2

        private const val GROUP_OPENS = 0.03
        private const val GROUP_TARGET = 0.04
        private const val GROUP_STEP = 0.07

        // With the losers' bracket (4 groups): W 8강; W 4강 + L1; L2; W 결승 + L3; L 결승; 최종 결승.
        // A section of 2 groups plays the last 4. Single elimination (8강, 4강, 결승) takes this
        // table's W 4강, W 결승 and 최종 결승 times.
        private val KO_OPENS_DOUBLE = listOf(0.45, 0.50, 0.55, 0.61, 0.68, 0.76)
        private val KO_TARGETS_DOUBLE = listOf(0.46, 0.52, 0.58, 0.65, 0.72, 0.80)
        private val KO_OPENS = listOf(0.50, 0.61, 0.76)
        private val KO_TARGETS = listOf(0.52, 0.65, 0.80)

        /** "아시아 챔피언스", "유럽 챔피언스", "아메리카 챔피언스", "아프리카·중동 챔피언스". */
        fun title(c: Confed): String = "${c.label} 챔피언스"

        /** Players in the section of a confederation of [leagues] leagues: 16 from 4, 8 with 2-3, none with fewer. */
        fun sectionSize(leagues: Int): Int = when {
            leagues >= 4 -> 16
            leagues >= 2 -> 8
            else -> 0
        }

        /**
         * League [id]'s players for its places, best first: when it played last season too, its
         * champion (L0.<id>), then its 1부's final table ([SeasonStart.lastTables] at its 1부 then,
         * [SeasonStart.lastLeagues]); else (new this season, or the season before was legacy) its
         * 1부 now by rating. Players no longer in the world are left out; one who moved abroad still
         * plays for the league it qualified with.
         */
        fun candidates(start: SeasonStart, id: String): List<String> {
            val inWorld = start.ranking.toHashSet()
            val last = start.lastLeagues.byId(id)?.takeIf { it.national }
            if (last != null && last.top < start.lastTables.size) {
                val champion = start.lastHonours.firstOrNull { it.kind == HonourKind.of("L0", id) }?.key
                return (listOfNotNull(champion) + start.lastTables[last.top]).distinct().filter { it in inWorld }
            }
            val lg = start.leagues.byId(id) ?: return emptyList()
            return start.divisions.getOrNull(lg.top).orEmpty().filter { it in inWorld }.sortedWith(compareByDescending<String> { start.rating(it) }.thenBy { it })
        }

        /**
         * The entrants of a section of [size] for leagues [order] (best first), each with the
         * league it qualified with, in seed order (league rank, then place); [used] are players
         * already placed (another section). Fewer than [size] when the leagues run out.
         */
        internal fun entrants(start: SeasonStart, order: List<String>, size: Int, used: Set<String>): List<Pair<String, String>> {
            val lists = order.map { id -> candidates(start, id).filter { it !in used } }
            val taken = order.map { ArrayList<String>() }
            val all = HashSet<String>()
            var carry = 0
            for ((r, list) in lists.withIndex()) {
                val want = Coefficients.cclSlots(order.size, r, size) + carry
                for (k in list) {
                    if (taken[r].size >= want) break
                    if (all.add(k)) taken[r] += k
                }
                carry = want - taken[r].size
            }
            // Places nobody could take go round the leagues again, best first.
            var more = true
            while (all.size < size && more) {
                more = false
                for ((r, list) in lists.withIndex()) {
                    if (all.size >= size) break
                    val k = list.firstOrNull { it !in all } ?: continue
                    all += k
                    taken[r] += k
                    more = true
                }
            }
            return order.indices.flatMap { r -> taken[r].map { it to order[r] } }
        }

        /**
         * Groups of 4 from pots of the seeds in order (as many to a pot as there are groups), drawn
         * again (up to [TRIES] times) while a group holds more than [SAME_LEAGUE] of one league.
         */
        internal fun draw(entries: List<DrawEntry>, leagueOf: (String) -> String, rng: Random): List<List<DrawEntry>> {
            val groups = entries.size / ChampionsBracket.POT
            var last: List<List<DrawEntry>> = emptyList()
            repeat(TRIES) {
                val gs = List(groups) { ArrayList<DrawEntry>() }
                for (pot in entries.chunked(groups)) for ((g, e) in pot.shuffled(rng).withIndex()) gs[g] += e
                last = gs
                if (gs.all { g -> g.groupingBy { leagueOf(it.key) }.eachCount().values.all { it <= SAME_LEAGUE } }) return gs
            }
            return last
        }

        /** This season's tournaments; null when off, in a legacy season, or when no confederation has 2 leagues. */
        fun create(start: SeasonStart): ContinentalChampions? {
            val leagues = start.leagues
            if (!start.config.continentalCl || !leagues.national) return null
            val members = leagues.list.associate { lg -> lg.id to lg.divisions.flatMap { start.divisions.getOrNull(it).orEmpty() } }
            val strength = { id: String -> Coefficients.strength(members[id].orEmpty(), start.rating) }
            val rng = Random(Knockout.drawSeed(start.seed, Comp.CONT_CHAMPIONS))
            val used = HashSet<String>()
            val sections = ArrayList<Section>()
            for (c in Confed.entries) {
                val ids = leagues.list.filter { it.national && leagues.confedOf(it.id) == c }.map { it.id }
                val size = sectionSize(ids.size)
                if (size == 0) continue
                val picks = entrants(start, Coefficients.order(ids, start.leagueRanking, strength), size, used)
                if (picks.size < size) continue
                val by = picks.toMap()
                val entries = picks.mapIndexed { i, (k, _) -> DrawEntry(k, i + 1, start.rating(k)) }
                sections += Section(c, draw(entries, { by.getValue(it) }, rng))
                used += by.keys
            }
            if (sections.isEmpty()) return null
            val cfg = start.config
            return ContinentalChampions(start.number, start.seed, sections, cfg.longKnockouts, cfg.seriesKnockouts, cfg.doubleElimination, cfg.sweepPoints)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            require(lines.firstOrNull()?.trim() == HEADER) { "not a $HEADER section" }
            val declared = LinkedHashMap<Int, Pair<Confed, Int>>()
            val placed = HashMap<Pair<Int, Int>, MutableList<DrawEntry>>()
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    when (f[0]) {
                        "sec" -> declared[f[1].toInt()] = Confed.valueOf(f[2]) to f[3].toInt()
                        "g" -> placed.getOrPut(f[1].toInt() to f[2].toInt()) { ArrayList() } += DrawEntry.decode(f, 3)
                    }
                }
            }
            require(declared.isNotEmpty() && declared.keys.toList() == declared.keys.indices.toList()) { "unusable $HEADER sections" }
            val sections = declared.map { (s, d) ->
                val (confed, count) = d
                require(count == 2 || count == 4) { "a section of 2 or 4 groups" }
                val groups = List(count) { g -> placed[s to g].orEmpty().sortedBy { it.seed } }
                require(groups.all { it.size == ChampionsBracket.POT }) { "incomplete $HEADER groups" }
                Section(confed, groups)
            }
            ContinentalChampions(season, seed, sections, KnockoutLength.saved(lines), KnockoutLength.series(lines), KnockoutLength.double(lines), GroupStage.bonus(lines)).also { c ->
                for (r in TieScore.Saved.parse(lines)) c.brackets.getOrNull(c.sectionOf(r.id))?.record(r.id, r.a, r.b, r.score)
            }
        }
    }
}
