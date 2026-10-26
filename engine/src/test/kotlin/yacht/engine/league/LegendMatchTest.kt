package yacht.engine.league

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 레전드 매치: a recent legend at its best against last season's MVP, off the ladder, saved midway. */
class LegendMatchTest {
    private fun start(season: Int, honours: List<Honour>) = SeasonStart(
        season, 77L, LeagueConfig(), listOf("g010", "g011", "g012"), { 1500.0 }, listOf(listOf("g010", "g011", "g012")), emptyList(), honours,
        { null }, { "서울" },
    )

    @Test
    fun aRecentLegendMeetsTheMvp() {
        val book = PlayerBook().apply { ensureGenerated(12) }
        val peak = book["g001"]!!
        book.notePeak("g001", 1610.0)
        book.retire("g001", 5, titles = 3, bestRating = 1610.0, honours = "L0:2,CUP:1")
        book.retire("g002", 5, titles = 1, bestRating = 1550.0, honours = "CUP:1")
        // Long gone: not recent enough.
        book.retire("g003", 1, titles = 9, bestRating = 1700.0, honours = "L0:9")
        val mvp = listOf(Honour(5, "MVP", "g011"))
        assertNull(LegendMatch.create(start(5, mvp), book.legends), "not due")
        val m = assertNotNull(LegendMatch.create(start(6, mvp), book.legends))
        assertEquals(listOf("g001", "g011"), m.entries.map { it.key })
        assertEquals("draw_legend", m.opening().single().kind)
        // Exhibition: unrated, mid-season.
        assertNull(m.next(0.4, emptySet()))
        val f = assertNotNull(m.next(0.6, emptySet()))
        assertTrue(!f.rated && f.comp == Comp.LEGEND)
        // Saved before it is played, read back the same.
        val sb = StringBuilder().also { m.encode(it) }
        val back = LegendMatch.codec.decode(sb.toString().trimEnd().lines(), 6, 77L) as LegendMatch
        assertEquals(m.entries, back.entries)
        val games = listOf(GameScore(240, 200), GameScore(230, 210))
        val events = m.record(JobResult(f, listOf(BoardResult(f.a, f.b, games, emptyList())), TieRule.match(games), 0))
        assertEquals(listOf("legend_match"), events.map { it.kind })
        assertEquals(listOf("2-0"), events.single().args)
        assertTrue(m.finished && m.honours().isEmpty())
        // The legend plays at its peak: its abilities of its best season.
        val legend = book.legend("g001")!!
        assertEquals(peak.identity!!.abilities, legend.identity!!.peakAbl)
        assertEquals(Profiles.apply(legend.spec, legend.identity!!.copy(abilities = legend.identity!!.peakAbl!!)), legend.atPeak())
        // No legend of the last three seasons: no match.
        assertNull(LegendMatch.create(start(9, mvp), book.legends))
        // The news reads.
        val a = News.article(events.single(), { k -> if (k == "g001") "레전드" else "현역" })
        assertTrue("레전드" in a.headline + a.body, a.toString())
    }
}
