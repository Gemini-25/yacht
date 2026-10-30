package yacht.engine

import yacht.engine.league.ChampionsBracket
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Confed
import yacht.engine.league.DrawEntry
import yacht.engine.league.ContinentalChampions
import yacht.engine.league.Fixture
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.Knockout
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A national season's leagues for the international competitions' tests: [spec] gives each
 * league (any order: they are put in league order) the sizes of its divisions, best first. Keys
 * are "<league><tier>x<place>" ("kr0x03"), rated by league (earlier in [spec] stronger), tier and
 * place, so a league's 1부 is in rating order.
 */
internal class NationalStart(spec: List<Pair<String, List<Int>>>, val worldConfed: Confed? = null) {
    private val strengthOrder = spec.map { it.first }
    private val ordered = spec.sortedBy { LeagueIds.order(it.first) }
    val leagues: Leagues = Leagues.national(ordered.map { it.first }, ordered.map { it.second.size }, worldConfed)
    val divisions: List<List<String>> = ordered.flatMap { (id, sizes) -> sizes.mapIndexed { t, n -> List(n) { i -> "${id.lowercase()}${t}x%02d".format(i) } } }
    val keys: List<String> = divisions.flatten()
    private val ratings: Map<String, Double> = ordered.flatMap { (id, sizes) ->
        sizes.flatMapIndexed { t, n -> List(n) { i -> "${id.lowercase()}${t}x%02d".format(i) to 1900.0 - 25 * strengthOrder.indexOf(id) - 120 * t - 15 * i } }
    }.toMap()

    fun rating(k: String): Double = ratings[k] ?: 1000.0

    /** The 1부 of league [id], best first. */
    fun top(id: String): List<String> = divisions[leagues.byId(id)!!.top]

    /** A season start of these leagues; last season as given (none: the first national season after a legacy one). */
    fun start(
        number: Int = 2,
        config: LeagueConfig = LeagueConfig(national = true),
        lastTables: List<List<String>> = emptyList(),
        lastLeagues: Leagues = Leagues.legacy(yacht.engine.league.Tiers.linear(lastTables.size)),
        lastHonours: List<Honour> = emptyList(),
        ranking: LeagueRanking = LeagueRanking.EMPTY,
        world: List<String> = keys,
    ): SeasonStart = SeasonStart(
        number, 31L * number, config, world.sortedByDescending(::rating), ::rating, divisions, lastTables, lastHonours, { null }, { "서울" },
        leagues = leagues, lastLeagues = lastLeagues, leagueRanking = ranking,
    )

    /** A ranking of [ids] in this order (rank only; no coefficients). */
    fun ranking(ids: List<String>): LeagueRanking =
        LeagueRanking(1, ids.mapIndexed { r, id -> LeagueRank(id, r, 0, 0.0, emptyList(), 0.0, 0, false, 0, leagues.confedOf(id)) })
}

/** 대륙 챔피언스 (ENG 4.2): sections, places, the draw, ids, the save, honours, the brackets of 8 and 4 qualifiers. */
class ContinentalChampionsTest {
    private val ten = listOf(10, 10)

    /** Asia 4 leagues, Europe 2, the Americas 3, Africa 1 plus the 국제 연합 리그. */
    private val world = NationalStart(
        listOf("KR" to ten, "JP" to ten, "CN" to ten, "VN" to ten, "GB" to ten, "FR" to ten, "US" to ten, "BR" to ten, "AR" to ten, "NG" to ten, "XWO" to ten),
        worldConfed = Confed.AFRICA,
    )
    private val print = CompetitionPrint(world::rating)

    @Test
    fun aSectionForEachConfederationWithTwoLeaguesOrMore() {
        assertEquals(listOf(0, 0, 8, 8, 16, 16, 16), (0..6).map { ContinentalChampions.sectionSize(it) })
        val ccl = assertNotNull(ContinentalChampions.create(world.start()))
        assertEquals(listOf(Confed.ASIA, Confed.EUROPE, Confed.AMERICAS, Confed.AFRICA), ccl.sections.map { it.confed })
        assertEquals(listOf(16, 8, 8, 8), ccl.sections.map { it.size })
        assertEquals(listOf(4, 2, 2, 2), ccl.sections.map { it.groups.size })
        assertEquals(listOf("아시아 챔피언스", "유럽 챔피언스", "아메리카 챔피언스", "아프리카·중동 챔피언스"), ccl.sections.map { it.title })
        // The 국제 연합 리그 counts for the confederation its league line names.
        val africa = ccl.sections.single { it.confed == Confed.AFRICA }.groups.flatten().map { it.key }
        assertEquals(setOf("ng", "xwo"), africa.map { it.dropLast(4) }.toSet())
        // One draw announcement per section; nobody in two sections.
        assertEquals(ccl.sections.map { listOf(it.title) }, ccl.opening().map { it.args })
        assertTrue(ccl.opening().all { it.kind == "draw_ccl" && it.comp == Comp.CONT_CHAMPIONS })
        assertEquals(ccl.entrantKeys().size, ccl.entrantKeys().toSet().size)

        // A small world (one 연합 리그 a confederation) has none; nor a legacy season, nor the rules without it.
        val regions = NationalStart(listOf("XAS" to ten, "XEU" to ten, "XAM" to ten, "XAF" to ten))
        assertNull(ContinentalChampions.create(regions.start()))
        assertNull(ContinentalChampions.create(world.start(config = LeagueConfig(national = true, continentalCl = false))))
        val keys = world.keys
        val legacy = SeasonStart(2, 1L, LeagueConfig(), keys, world::rating, Season.split(keys, 4), emptyList(), emptyList(), { null }, { "" })
        assertNull(ContinentalChampions.create(legacy))
        assertTrue(Competitions.factories().none { it(legacy)?.comp == Comp.CONT_CHAMPIONS })
    }

    @Test
    fun placesGoByTheRankingAndPassDownWhatALeagueCannotFill() {
        assertEquals(listOf(3, 3, 3, 3, 2, 2), (0 until 6).map { Coefficients.cclSlots(6, it, 16) })
        assertEquals(listOf(3, 3, 2, 2, 2, 2, 2), (0 until 7).map { Coefficients.cclSlots(7, it, 16) })
        assertEquals(List(7) { 2 } + listOf(1, 1), (0 until 9).map { Coefficients.cclSlots(9, it, 16) })
        assertEquals(listOf(4, 4), (0 until 2).map { Coefficients.cclSlots(2, it, 8) })
        assertEquals(listOf(3, 3, 2), (0 until 3).map { Coefficients.cclSlots(3, it, 8) })

        val asia = listOf("KR", "JP", "CN", "VN", "IN", "TH")
        val w = NationalStart(asia.map { it to ten } + ("GB" to ten))
        fun counts(start: SeasonStart): Map<String, Int> {
            val sec = assertNotNull(ContinentalChampions.create(start)).sections.single()
            return sec.groups.flatten().groupingBy { it.key.take(2).uppercase() }.eachCount()
        }
        // No ranking yet: by strength (KR the strongest), 3, 3, 3, 3, 2, 2.
        assertEquals(mapOf("KR" to 3, "JP" to 3, "CN" to 3, "VN" to 3, "IN" to 2, "TH" to 2), counts(w.start()))
        // The ranking the season is played under decides.
        assertEquals(mapOf("TH" to 3, "IN" to 3, "VN" to 3, "CN" to 3, "JP" to 2, "KR" to 2), counts(w.start(ranking = w.ranking(asia.reversed()))))
        // A league new this season (no row) comes after the ranked ones.
        assertEquals(mapOf("JP" to 3, "CN" to 3, "VN" to 3, "IN" to 3, "TH" to 2, "KR" to 2), counts(w.start(ranking = w.ranking(asia.drop(1)))))

        // A 1부 of 2 passes its third and fourth place on: the next league takes 6.
        val short = NationalStart(listOf("KR" to listOf(2, 10), "JP" to ten, "CN" to ten, "VN" to ten))
        val sec = assertNotNull(ContinentalChampions.create(short.start(ranking = short.ranking(listOf("KR", "JP", "CN", "VN"))))).sections.single()
        assertEquals(mapOf("KR" to 2, "JP" to 6, "CN" to 4, "VN" to 4), sec.groups.flatten().groupingBy { it.key.take(2).uppercase() }.eachCount())
        // Seeds: league rank, then place.
        assertEquals(listOf("kr0x00", "kr0x01", "jp0x00", "jp0x01"), sec.groups.flatten().sortedBy { it.seed }.take(4).map { it.key })
    }

    @Test
    fun noGroupHoldsMoreThanTwoOfALeague() {
        for (seed in 1..20) {
            val start = world.start(number = seed + 1)
            val ccl = assertNotNull(ContinentalChampions.create(start))
            for (sec in ccl.sections) for (g in sec.groups) {
                assertEquals(4, g.size)
                assertEquals(g.sortedBy { it.seed }, g, "pot order")
                assertTrue(g.groupingBy { it.key.dropLast(4) }.eachCount().values.all { it <= 2 }, "season ${seed + 1} ${sec.title}: ${g.map { it.key }}")
            }
            // One of each pot in every group.
            for (sec in ccl.sections) {
                val pot = sec.groups.size
                for (g in sec.groups) assertEquals((0 until 4).toList(), g.map { (it.seed - 1) / pot })
            }
        }
        // Pots that could put three of one league in a group are drawn again.
        val league = listOf("X", "A", "B", "C", "X", "D", "E", "F", "X", "G", "H", "I", "J", "K", "L", "M")
        val entries = league.mapIndexed { i, _ -> DrawEntry("p%02d".format(i), i + 1, 1500.0) }
        for (seed in 1L..60L) {
            val groups = ContinentalChampions.draw(entries, { league[it.drop(1).toInt()] }, java.util.Random(seed))
            assertTrue(groups.all { g -> g.count { league[it.key.drop(1).toInt()] == "X" } <= 2 }, "seed $seed")
            assertEquals(entries.toSet(), groups.flatten().toSet())
        }
    }

    @Test
    fun sectionsKeepTheirIdsApartAndTheSaveReadsBack() {
        for (config in listOf(LeagueConfig(national = true), LeagueConfig(national = true, matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false))) {
            val ccl = assertNotNull(ContinentalChampions.create(world.start(config = config)))
            val text = print.fingerprint(ccl, setOf(30, 60, 100), season = 2, seed = 62L)
            val handed = text.lines().filter { it.startsWith("f ") }.map { it.split(' ') }
            val ids = handed.map { it[2].toInt() }
            assertEquals(ids.size, ids.toSet().size, "every id once")
            assertEquals(ccl.fixturesTotal, ids.size)
            for (f in handed) {
                val local = f[2].toInt() % ContinentalChampions.SECTION_IDS
                if (f[6] == "MATCH") assertTrue(local < 48, f.toString()) else assertTrue(local - ChampionsBracket.KO_BASE in 0 until 14, f.toString())
            }
            assertEquals(setOf(0, 1, 2, 3), ids.map { it / ContinentalChampions.SECTION_IDS }.toSet())
            // The codec reads a damaged block as far as it can and refuses one without its sections.
            assertTrue(Competitions.codecs.getValue(Comp.CONT_CHAMPIONS).runCatching { decode(listOf("ccl 1", "g 0 0 kr0x00 1 1900"), 2, 62L) }.isFailure)
        }
    }

    /** Plays [ccl] to the end (the better rated wins), returning every fixture and event. */
    private fun playOut(ccl: ContinentalChampions): Pair<List<Fixture>, List<LeagueEvent>> {
        val fixtures = ArrayList<Fixture>()
        val events = ArrayList<LeagueEvent>()
        var p = 0.0
        while (!ccl.finished) {
            val f = ccl.next(p, emptySet())
            if (f == null) {
                p += 0.01
                check(p < 1.1)
                continue
            }
            fixtures += f
            events += ccl.record(print.play(f)).map { it.copy(week = fixtures.size) }
        }
        return fixtures to events
    }

    @Test
    fun honoursNewsAndTheBracketsOfEightAndFourQualifiers() {
        val ccl = assertNotNull(ContinentalChampions.create(world.start()))
        val stages = ccl.stages()
        // Six matchdays, then the widest section's six stages; the smaller sections play the last four.
        assertEquals(12, stages.size)
        assertEquals((1..6).map { "조별 ${it}차전" } + listOf("토너먼트 1단계", "토너먼트 2단계", "토너먼트 3단계", "토너먼트 4단계", "토너먼트 5단계", Knockout.GRAND_LABEL), stages.map { it.label })
        assertEquals(listOf(0.03, 0.10, 0.17, 0.24, 0.31, 0.38, 0.45, 0.50, 0.55, 0.61, 0.68, 0.76), stages.map { Math.round(it.opensAt * 100) / 100.0 })
        val (fixtures, events) = playOut(ccl)
        assertEquals(ccl.fixturesTotal, fixtures.size)
        assertTrue(ccl.stageCounts().all { it.done })
        assertEquals(fixtures.size, ccl.stageCounts().sumOf { it.total })
        // A section's knockout fixtures sit in the last stages: a 4-qualifier section never in the first two.
        for (f in fixtures.filter { it.kind == JobKind.TIE }) {
            val s = ccl.stageOf(f)
            assertTrue(s >= 6, f.label)
            if (f.id / ContinentalChampions.SECTION_IDS > 0) assertTrue(s >= 8, "${f.label} at stage $s")
        }
        // Labels name the confederation.
        assertTrue(fixtures.filter { it.kind == JobKind.MATCH }.all { it.label.matches(Regex("(아시아|유럽|아메리카|아프리카·중동) [A-D]조 [1-6]차전")) })
        val view = ccl.view() as CompetitionView.Sections
        assertEquals(ccl.sections.map { it.title }, view.parts.map { it.title })
        // 8 qualifiers: 4 first-round ties; 4 qualifiers: 2 (승자조 4강).
        assertEquals(listOf(4, 2, 2, 2), view.parts.map { it.knockout!!.rounds.first().ties.size })
        assertEquals(listOf("승자조 8강", "승자조 4강", "승자조 4강", "승자조 4강"), view.parts.map { it.knockout!!.rounds.first().label })
        val honours = ccl.honours()
        assertEquals(Confed.entries.flatMap { listOf("CCL.${it.name}", "CCL2.${it.name}") }, honours.map { it.kind })
        for ((sec, h) in ccl.sections.zip(honours.chunked(2))) assertTrue(h.all { x -> sec.groups.flatten().any { it.key == x.key } })
        // Upsets only in the knockout; the section's name on everything but the title.
        val groupKeys = fixtures.filter { it.kind == JobKind.MATCH }.map { setOf(it.a, it.b) }.toSet()
        val tieKeys = fixtures.filter { it.kind == JobKind.TIE }.map { setOf(it.a, it.b) }.toSet()
        for (e in events.filter { it.kind == "upset" }) assertTrue(e.keys.toSet() in tieKeys, e.toString())
        assertTrue(groupKeys.isNotEmpty())
        for (e in events.filter { it.kind in setOf("qualified", "eliminated", "cup_round", "final", "upset") }) assertTrue(e.args.last().startsWith("@") && e.args.last().endsWith(" 챔피언스"), e.toString())
        val titles = events.filter { it.kind == "title" }
        assertEquals(ccl.sections.map { listOf(it.title) }, titles.map { it.args })
        for (sec in ccl.sections) assertEquals(2 * sec.groups.size, events.count { it.kind == "qualified" && it.args.last() == "@${sec.title}" })
    }

    @Test
    fun singleEliminationSectionsShareTheStagesFromTheEnd() {
        val config = LeagueConfig(national = true, seriesKnockouts = false, doubleElimination = false)
        val ccl = assertNotNull(ContinentalChampions.create(world.start(config = config)))
        assertEquals((1..6).map { "조별 ${it}차전" } + listOf("8강", "4강", "결승"), ccl.stages().map { it.label })
        val (fixtures, _) = playOut(ccl)
        val europe = fixtures.filter { it.kind == JobKind.TIE && it.label.startsWith("유럽") }
        assertEquals(listOf("유럽 4강", "유럽 4강", "유럽 결승"), europe.map { it.label })
        assertEquals(listOf(7, 7, 8), europe.map { ccl.stageOf(it) })
        // A 4-qualifier bracket's stages come from Knockout's tables, as a 16-player section's do.
        assertEquals(listOf("4강", "결승"), Knockout.stageLabels(2, false) { listOf("4강", "결승")[it] })
        assertEquals(listOf("승자조 4강", "승자조 결승 · 패자조 1R", "패자조 결승", Knockout.GRAND_LABEL), Knockout.stageLabels(2, true) { listOf("4강", "결승")[it] })
    }
}
