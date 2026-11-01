package yacht.engine

import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Confed
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.DrawEntry
import yacht.engine.league.Honour
import yacht.engine.league.Knockout
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Leagues
import yacht.engine.league.Legend
import yacht.engine.league.NationCup
import yacht.engine.league.Nations
import yacht.engine.league.Origin
import yacht.engine.league.RetiredRecord
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.WcCeremony
import yacht.engine.league.WcHosts
import yacht.engine.league.WcInput
import yacht.engine.league.WcVote
import yacht.engine.league.WorldBook
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The World Cup host vote and the draw ceremony (design ENG 5.5): today's rotation and the last-3
 * rule pick who bids; rounds to a majority with self-votes; an unopposed bid and the confederation
 * passed on; [NationCup.hosted] takes the voted host and still makes today's host draw (the groups
 * are today's for the same host); the ceremony's pots, groups and group of death, and the `wcv` and
 * `wcc` lines.
 */
class WcVoteTest {
    private val codes = Nations.ALL.map { it.code }

    /** Nation [code]'s [n] members, "<code>-<i>". */
    private fun members(code: String, n: Int) = List(n) { "${code.lowercase()}-$it" }

    /** A national closing season [number]: every nation a league of its [sizes] members (6 by default). */
    private fun plan(number: Int, sizes: Map<String, Int> = emptyMap(), cfg: LeagueConfig = LeagueConfig(national = true)): SeasonPlan {
        val divisions = codes.map { members(it, sizes[it] ?: 6) }
        return SeasonPlan(Season.start(number, 41L, divisions, true, Leagues.national(codes, codes.map { 1 })), cfg)
    }

    private fun nation(k: String): String = k.substringBefore('-').uppercase()

    /** Stronger by nation order, then by place. */
    private fun drawn(k: String): Double = 1700.0 - 8 * codes.indexOf(nation(k)) - 15 * k.substringAfter('-').toInt()

    private fun input(p: SeasonPlan, history: List<Honour> = emptyList(), seed: Long = 5L) = WcInput(p, history, ::drawn, ::nation, null, seed)

    private fun confed(code: String): Confed = Nations.of(code)!!.confed

    @Test
    fun theRotationPicksTheConfederationAndTheLastThreeHostsSitOut() {
        val p = plan(2)
        assertEquals(3, WcHosts.voteSeason(p))
        val bids = WcHosts.bids(input(p))
        assertEquals(WcHosts.BIDS, bids.size)
        assertTrue(bids.all { confed(it.nation) == Confed.entries[0] }, bids.toString())
        assertEquals(bids.sortedByDescending { it.score }, bids)
        // The confederation is the one today's rule draws the host from.
        val start = SeasonStart(
            3, 9L, LeagueConfig(), p.league.standings.keys.sortedByDescending(::drawn), ::drawn, p.league.divisions, emptyList(), emptyList(),
            { null }, { "서울" }, emptyList(), ::nation,
        )
        assertEquals(confed(bids[0].nation), confed(NationCup.world(start)!!.host!!))
        // Three hosts so far: Africa's turn, its last hosts out.
        val history = listOf(Honour(3, "WCH", "NG"), Honour(7, "WCH", "KR"), Honour(11, "WCH", "EG"))
        val later = WcHosts.bids(input(plan(14), history))
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { confed(it.nation) == Confed.AFRICA && it.nation !in setOf("NG", "EG") }, later.toString())
        // No African nation with a team: the next confederation.
        val noAfrica = codes.filter { confed(it) == Confed.AFRICA }.associateWith { 3 }
        val passed = WcHosts.bids(input(plan(14, noAfrica), history))
        assertTrue(passed.isNotEmpty() && passed.all { confed(it.nation) == Confed.entries[(3 + 1) % 4] }, passed.toString())
    }

    @Test
    fun roundsGoToAMajorityAndEveryCandidateVotesForItself() {
        for (seed in 1L..12L) {
            val p = plan(6)
            val v = assertNotNull(WcHosts.vote(input(p, listOf(Honour(3, "WCH", "GB")), seed)))
            assertEquals(7, v.season)
            val bids = WcHosts.bids(input(p, listOf(Honour(3, "WCH", "GB")), seed)).map { it.nation }
            val voters = codes.size
            assertTrue(v.rounds.isNotEmpty() && v.rounds.size <= WcHosts.MAX_ROUNDS)
            assertEquals(bids.toSet(), v.rounds[0].map { it.first }.toSet())
            assertTrue(v.rounds[0].all { it.second >= 1 }, "a candidate votes for itself: ${v.rounds[0]}")
            for ((i, r) in v.rounds.withIndex()) {
                assertEquals(voters, r.sumOf { it.second })
                assertEquals(r.sortedByDescending { it.second }.map { it.second }, r.map { it.second })
                if (i > 0) assertEquals(v.rounds[i - 1].dropLast(1).map { it.first }.toSet(), r.map { it.first }.toSet(), "the last drops")
                val last = i == v.rounds.lastIndex
                assertEquals(last, 2 * r[0].second > voters || r.size == 1, "round ${i + 1} of $seed")
            }
            assertEquals(v.rounds.last()[0].first, v.host)
            assertTrue(confed(v.host) == Confed.EUROPE)
            // The same vote whenever asked (the winter's bids are the close's).
            assertEquals(v, WcHosts.vote(input(p, listOf(Honour(3, "WCH", "GB")), seed)))
        }
        // Different seeds elect different hosts at times.
        assertTrue((1L..12L).map { WcHosts.vote(input(plan(6), listOf(Honour(3, "WCH", "GB")), it))!!.host }.distinct().size > 1)
    }

    @Test
    fun aLoneBidIsElectedUnopposedAndNoVoteWithoutAWorldCup() {
        // The Americas' turn with only the US fielding a team.
        val sizes = codes.filter { confed(it) == Confed.AMERICAS && it != "US" }.associateWith { 4 }
        val history = listOf(Honour(3, "WCH", "KR"), Honour(7, "WCH", "GB"))
        val v = assertNotNull(WcHosts.vote(input(plan(10, sizes), history)))
        assertEquals(WcVote(11, "US", emptyList()), v)
        val e = WcHosts.voteEvent(10, v)
        assertEquals(listOf("US", "-"), e.keys)
        assertEquals(listOf("0", "-", "-"), e.args)
        // No World Cup next season, or none at all, or too few teams: no bids, no vote.
        assertNull(WcHosts.voteSeason(plan(3)))
        assertTrue(WcHosts.bids(input(plan(3))).isEmpty())
        assertNull(WcHosts.vote(input(plan(3))))
        assertNull(WcHosts.vote(input(plan(2, cfg = LeagueConfig(national = true, nationCup = false)))))
        val few = codes.drop(3).associateWith { 2 }
        assertTrue(WcHosts.bids(input(plan(2, few))).isEmpty())
        assertNull(WcHosts.vote(input(plan(2, few))))
        assertNull(WcHosts.voteSeason(SeasonPlan(Season.start(2, 3L, listOf(members("KR", 6))), LeagueConfig())))
    }

    @Test
    fun theNewsOfTheBidsAndTheVote() {
        val p = plan(2)
        val bids = WcHosts.bids(input(p))
        val b = assertNotNull(WcHosts.bidsEvent(2, bids, week = 21))
        assertEquals("wc_bids", b.kind)
        assertEquals(bids.map { it.nation }, b.keys)
        assertEquals(listOf("3"), b.args)
        assertEquals(21, b.week)
        assertNull(WcHosts.bidsEvent(2, emptyList()))
        val v = WcHosts.vote(input(p))!!
        val e = WcHosts.voteEvent(2, v)
        assertEquals("wc_host_vote", e.kind)
        assertEquals(listOf(v.host, v.rounds.last()[1].first), e.keys)
        assertEquals(listOf(v.rounds.size.toString(), v.rounds.last()[0].second.toString(), v.rounds.last()[1].second.toString()), e.args)
        assertEquals(SeasonCalendar.CLOSING, e.week)
        assertEquals(Comp.WORLD_CUP, e.comp)
    }

    /** A World Cup season [season]'s start (as NationCupTest's): nations with 5-12 players. */
    private fun start(season: Int, n: Int = 14, history: List<Honour> = emptyList()): SeasonStart {
        val rng = Random(7)
        val nation = HashMap<String, String>()
        val rating = HashMap<String, Double>()
        var i = 0
        for ((c, code) in codes.withIndex()) repeat(if (c < n) 5 + (c * 7) % 8 else 1 + c % 4) {
            val k = "g" + (++i).toString().padStart(4, '0')
            nation[k] = code
            rating[k] = 1450 + 120 * rng.nextGaussian() + (code.hashCode() % 7) * 12
        }
        val ranking = rating.keys.sortedByDescending { rating.getValue(it) }
        val divisions = ranking.chunked(maxOf(5, (ranking.size + 3) / 4))
        return SeasonStart(season, 7L * 31 + season, LeagueConfig(), ranking, { rating[it] ?: 1400.0 }, divisions, divisions, emptyList(), { null }, { "서울" }, history, { nation[it] })
    }

    private fun encode(c: NationCup): String = StringBuilder().also { c.encode(it) }.toString()

    @Test
    fun theCupTakesTheVotedHostAndStillMakesTodaysHostDraw() {
        val s = start(3)
        val today = NationCup.world(s)!!
        // A vote for the nation today's rule draws: today's cup exactly.
        assertEquals(encode(today), encode(NationCup.hosted(s) { today.host }!!))
        assertEquals(encode(today), encode(NationCup.hosted(s) { null }!!))
        // Another host: it leads group A, and the groups are drawn with the numbers left after today's host draw.
        val (teams, _) = NationCup.teams(s)
        val other = teams.first { it.id != today.host && confed(it.id) != confed(today.host!!) }.id
        val voted = NationCup.hosted(s) { if (it == 3) other else null }!!
        assertEquals(other, voted.host)
        assertEquals(other, voted.groups[0][0].key)
        val rng = Random(Knockout.drawSeed(s.seed, Comp.WORLD_CUP))
        val pick = teams.filter { confed(it.id) == Confed.entries[0] }
        rng.nextInt(pick.size)
        val ordered = teams.filter { it.id == other } + teams.filter { it.id != other }
        val entries = ordered.mapIndexed { i, t -> DrawEntry(t.id, i + 1, t.strength) }
        assertEquals(NationCup.drawGroups(entries, NationCup.groupCount(teams.size), rng), voted.groups)
        assertNotEquals(today.groups, voted.groups)
        // A voted nation without a team: today's host.
        assertEquals(encode(today), encode(NationCup.hosted(s) { "ZZ" }!!))
        // The season's builders with the voted host.
        val built = NationCup.withHosts(Competitions.factories()) { if (it == 3) other else null }.mapNotNull { f -> runCatching { f(s) }.getOrNull() }
        assertEquals(other, built.filterIsInstance<NationCup>().single { it.comp == Comp.WORLD_CUP }.host)
        val plain = NationCup.withHosts(Competitions.factories()) { null }.mapNotNull { f -> runCatching { f(s) }.getOrNull() }
        assertEquals(encode(today), encode(plain.filterIsInstance<NationCup>().single { it.comp == Comp.WORLD_CUP }))
    }

    @Test
    fun theCeremonyShowsThePotsTheGroupsAndTheGroupOfDeath() {
        val s = start(3, n = 14)
        val cup = NationCup.world(s)!!
        val c = assertNotNull(WcHosts.ceremony(cup, "g0001"))
        val g = cup.groups.size
        assertTrue(g >= 3)
        assertEquals(3, c.season)
        assertEquals("g0001", c.presenter)
        assertEquals(cup.groups.indices.map { ('A' + it).toString() }, c.groups.map { it.first })
        assertEquals(cup.groups.map { es -> es.map { it.key } }, c.groups.map { it.second })
        // Pot p: the p-th seeds by g, the host in pot 1.
        assertEquals(cup.teams.map { it.id }.chunked(g), c.pots)
        assertEquals(cup.host, c.pots[0][0])
        val means = cup.groups.map { es -> es.map { Math.round(it.rating).toDouble() }.average() }
        assertEquals(('A' + means.indexOf(means.max())).toString(), c.death)
        // Read back from season.txt, the cup gives the same ceremony; the world book keeps it.
        val back = NationCup.worldCodec.decode(encode(cup).trimEnd().lines(), 3, s.seed) as NationCup
        assertEquals(c, WcHosts.ceremony(back, "g0001"))
        val w = WorldBook()
        w.media.putCeremony(c)
        assertEquals(c, WorldBook.decode(w.encode()).media.ceremony(3))
        assertEquals(c.copy(presenter = null), WorldBook.decode(WorldBook().also { it.media.putCeremony(c.copy(presenter = null)) }.encode()).media.ceremony(3))
        // The open's news.
        val e = WcHosts.ceremonyEvent(c)
        assertEquals("wc_draw_ceremony", e.kind)
        assertEquals(listOf("g0001") + c.groups.first { it.first == c.death }.second, e.keys)
        assertEquals(listOf(c.death), e.args)
        assertEquals(SeasonCalendar.PRESEASON, e.week)
        // A continental cup has no ceremony.
        assertNull(WcHosts.ceremony(NationCup.continental(start(5))!!, null))
    }

    @Test
    fun thePresenterIsTheHostsMostDecoratedLegend() {
        fun record(key: String, nation: String, titles: Int, best: Double) =
            RetiredRecord(key, "선수 $key", 1, 9, null, null, best, titles, "서울", Origin.GENERATED, nation)
        fun legend(key: String, nation: String, titles: Int, best: Double, honours: String = "L0:$titles") =
            Legend(record(key, nation, titles, best), CustomAiSpec(key, "선수 $key"), honours)
        val legends = listOf(legend("a", "KR", 3, 1600.0), legend("b", "KR", 5, 1500.0), legend("c", "JP", 9, 1700.0), legend("d", "KR", 5, 1550.0, "L0:5,CUP:2"))
        val retired = listOf(record("r1", "GB", 0, 1520.0), record("r2", "GB", 0, 1580.0), record("r3", "GB", 0, Double.NaN), record("r4", "FR", 0, 1700.0))
        assertEquals("d", WcHosts.presenter("KR", legends, retired))
        assertEquals("c", WcHosts.presenter("JP", legends, retired))
        assertEquals("r2", WcHosts.presenter("GB", legends, retired))
        assertNull(WcHosts.presenter("BR", legends, retired))
        assertNull(WcHosts.presenter(null, legends, retired))
    }

    @Test
    fun theVotesAreKeptInTheWorldBook() {
        val w = WorldBook()
        val v = WcVote(7, "GB", listOf(listOf("GB" to 12, "FR" to 9, "DE" to 7), listOf("GB" to 16, "FR" to 12)))
        w.media.putVote(v)
        w.media.putVote(WcVote(11, "US", emptyList()))
        val back = WorldBook.decode(w.encode())
        assertEquals(v, back.media.vote(7))
        assertEquals(WcVote(11, "US", emptyList()), back.media.vote(11))
        assertNull(back.media.vote(3))
        for (s in 1..40) w.media.putVote(WcVote(4 * s + 15, "KR", emptyList()))
        assertEquals(30, (0..200).count { w.media.vote(it) != null })
        val ceremony = WcCeremony(3, null, "A", listOf("A" to listOf("KR", "JP")), listOf(listOf("KR"), listOf("JP")))
        w.media.putCeremony(ceremony)
        assertEquals(ceremony, WorldBook.decode(w.encode()).media.ceremony(3))
    }
}
