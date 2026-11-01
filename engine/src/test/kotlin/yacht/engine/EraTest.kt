package yacht.engine

import yacht.engine.league.Era
import yacht.engine.league.EraCandidate
import yacht.engine.league.FamilyStats
import yacht.engine.league.Meta
import yacht.engine.league.MetaBook
import yacht.engine.league.StyleFamily
import yacht.engine.league.StyleFamily.BONUS
import yacht.engine.league.StyleFamily.BOLD
import yacht.engine.league.StyleFamily.CAUTION
import yacht.engine.league.StyleFamily.COMBO
import yacht.engine.league.StyleFamily.EV
import yacht.engine.league.StyleFamily.MIND
import yacht.engine.league.StyleFamily.ODD
import yacht.engine.league.StyleFamily.YACHT
import yacht.engine.league.WorldBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Eras (design ENG 4.5): the candidate is the family winning the most big titles against its
 * share of all players over the last three seasons (`X_f ≥ 1.8`, `T_f ≥ 5`, 1.25 × the second's,
 * families of 3% or more); an era begins after its candidate holds 2 closes and ends when another
 * holds 2 or none holds 3 (춘추전국 begins); a family's later eras are "두 번째 …".
 */
class EraTest {
    /** All players by the neutral shares (1001 in all), unless [share] gives one family's count. */
    private fun people(share: Map<StyleFamily, Int> = emptyMap()) =
        StyleFamily.entries.map { share[it] ?: Math.round(1000 * StyleFamily.NEUTRAL.getValue(it)).toInt() }

    private fun fm(season: Int, titles: List<StyleFamily?>, all: List<Int> = people()) =
        FamilyStats(season, List(8) { 10 }, all, titles, List(8) { 0.0 }, emptyList(), emptyMap())

    private val all9 = { f: StyleFamily -> List(9) { f } }

    /** A season of spread titles: one for each family, two for 족보파. */
    private val spread: List<StyleFamily?> = StyleFamily.entries.toList() + COMBO

    @Test
    fun theCandidateWinsTitlesAgainstItsShareAndLeadsClearly() {
        // 보너스파 takes 9 of 27 big titles, 족보파 6: X = 10 / (27 × 0.107 + 1) ≈ 2.57.
        val clear = mapOf(
            1 to fm(1, List(3) { BONUS } + List(2) { COMBO } + List(4) { YACHT }),
            2 to fm(2, List(3) { BONUS } + List(2) { COMBO } + listOf(EV, CAUTION, BOLD, null)),
            3 to fm(3, List(3) { BONUS } + List(2) { COMBO } + listOf(EV, CAUTION, BOLD, MIND)),
        )
        val s = Meta.scores(clear, 3)
        val bonus = s.single { it.family == BONUS }
        assertEquals(9, bonus.titles)
        assertEquals(27, bonus.total, "a holder without genes counts in the total")
        assertEquals(107.0 / 1001, bonus.share, 1e-12)
        assertEquals(10.0 / (27 * 107.0 / 1001 + 1), bonus.x, 1e-12)
        assertEquals(BONUS, Meta.candidate(clear, 3)?.family)
        // Four titles are too few, however small the family (괴짜파: X ≈ 1.97).
        val nobody = List<StyleFamily?>(9) { null }
        val few = mapOf(3 to fm(3, List(4) { ODD } + List(5) { null }), 2 to fm(2, nobody), 1 to fm(1, nobody))
        assertTrue(Meta.scores(few, 3).single { it.family == ODD }.x >= 1.8)
        assertNull(Meta.candidate(few, 3))
        // Two strong families: the first must lead by 1.25 ×.
        val close = mapOf(3 to fm(3, List(5) { ODD } + List(4) { BONUS }), 2 to fm(2, List(3) { BONUS } + List(6) { COMBO }), 1 to fm(1, all9(COMBO)))
        assertNull(Meta.candidate(close, 3), Meta.scores(close, 3).toString())
        val clearer = mapOf(3 to fm(3, List(6) { ODD } + List(3) { BONUS }), 2 to fm(2, List(4) { BONUS } + List(5) { COMBO }), 1 to fm(1, all9(COMBO)))
        assertEquals(ODD, Meta.candidate(clearer, 3)?.family)
        // A family under 3% of all players is never a candidate.
        val tiny = people(mapOf(ODD to 20))
        val small = mapOf(3 to fm(3, all9(ODD), tiny), 2 to fm(2, spread, tiny), 1 to fm(1, spread, tiny))
        assertNull(Meta.candidate(small, 3))
        // Head-count is not success: the largest family winning its share is no candidate.
        val fair = mapOf(3 to fm(3, List(3) { COMBO } + List(6) { null }), 2 to fm(2, List(3) { COMBO } + List(6) { null }), 1 to fm(1, List(2) { COMBO } + List(7) { null }))
        assertNull(Meta.candidate(fair, 3))
        // Fewer seasons kept: the window has what there is; none at all gives nothing.
        assertEquals(BONUS, Meta.candidate(mapOf(1 to fm(1, all9(BONUS))), 1)?.family)
        assertTrue(Meta.scores(emptyMap(), 4).isEmpty())
        assertNull(Meta.candidate(emptyMap(), 4))
    }

    /** B B N Y Y Y N N N N N B B: two eras, 춘추전국, then a family's second era. */
    private val history: Map<Int, FamilyStats> = "BBNYYYNNNNNBB".withIndex().associate { (i, c) ->
        val s = i + 1
        s to fm(s, when (c) {
            'B' -> all9(BONUS)
            'Y' -> all9(YACHT)
            else -> spread
        })
    }

    @Test
    fun erasBeginAfterTwoClosesAndEndByAnotherOrByNone() {
        val world = WorldBook()
        val news = (1..13).associateWith { Meta.close(world, history, it) }
        val book = world.meta
        fun peak(f: StyleFamily, seasons: IntRange) = seasons.maxOf { s -> Math.round(Meta.scores(history, s).single { it.family == f }.x * 100).toInt() }
        assertEquals(
            listOf(
                Era(1, BONUS, 2, 5, peak(BONUS, 2..5)),
                Era(2, YACHT, 6, 10, peak(YACHT, 6..10)),
                Era(3, null, 11, 12, 0),
                Era(4, BONUS, 13, 0, peak(BONUS, 13..13)),
            ),
            book.eras,
        )
        assertEquals(listOf("보너스파의 시대", "요트파의 시대", "춘추전국 시대", "두 번째 보너스파의 시대"), book.eras.map { book.name(it) })
        assertEquals(book.eras.last(), book.era)
        assertEquals(EraCandidate(BONUS, 2), book.candidate)
        // The news of each close: begins and ends, nothing in between.
        assertEquals(setOf(2, 6, 11, 13), news.filterValues { it.isNotEmpty() }.keys)
        assertEquals(listOf("era_begins" to listOf("B", "18", "18", "보너스파의 시대")), news.getValue(2).map { it.kind to it.args })
        assertEquals(
            listOf("era_ends" to listOf("B", "보너스파의 시대", "4"), "era_begins" to listOf("Y", "27", "27", "요트파의 시대")),
            news.getValue(6).map { it.kind to it.args },
        )
        assertEquals(
            listOf("era_ends" to listOf("Y", "요트파의 시대", "5"), "era_begins" to listOf("-", "0", "27", "춘추전국 시대")),
            news.getValue(11).map { it.kind to it.args },
        )
        assertEquals(
            listOf("era_ends" to listOf("-", "춘추전국 시대", "2"), "era_begins" to listOf("B", "19", "27", "두 번째 보너스파의 시대")),
            news.getValue(13).map { it.kind to it.args },
        )
        // Asked again (a close replayed), and read back from world.txt: the same news and eras.
        for (s in 1..13) assertEquals(news.getValue(s), Meta.events(book, history, s), "close $s")
        assertEquals(news.getValue(6), Meta.close(world, history, 6))
        val back = WorldBook.decode(world.encode())
        assertEquals(book.eras, back.meta.eras)
        assertEquals(news.getValue(13), Meta.close(back, history, 13))
        assertEquals(13, back.metaSeason)
    }

    @Test
    fun theStepsOfTheEraMachine() {
        fun score(f: StyleFamily, x: Double) = Meta.EraScore(f, 9, 27, 0.1, x)
        val book = MetaBook()
        // None for three closes with no era yet: nothing begins (춘추전국 follows an era only).
        for (s in 1..3) Meta.stepEras(book, s, null, emptyList())
        assertTrue(book.eras.isEmpty())
        assertEquals(EraCandidate(null, 3), book.candidate)
        // A candidate's first close: held 1, no era; its second: the era begins with that season.
        Meta.stepEras(book, 4, score(MIND, 2.0), emptyList())
        assertTrue(book.eras.isEmpty())
        Meta.stepEras(book, 5, score(MIND, 2.4), emptyList())
        assertEquals(listOf(Era(1, MIND, 5, 0, 240)), book.eras)
        // Its X while the era goes on raises the peak (as the candidate or not).
        Meta.stepEras(book, 6, null, listOf(score(MIND, 3.1)))
        assertEquals(310, book.era!!.peak)
        // Another candidate for one close, then none, then it again: no change (holds count in a row).
        Meta.stepEras(book, 7, score(EV, 2.0), emptyList())
        Meta.stepEras(book, 8, null, emptyList())
        Meta.stepEras(book, 9, score(EV, 2.0), emptyList())
        assertEquals(1, book.eras.size)
        // Two in a row: EV's era begins, MIND's ends the season before.
        Meta.stepEras(book, 10, score(EV, 2.2), emptyList())
        assertEquals(listOf(Era(1, MIND, 5, 9, 310), Era(2, EV, 10, 0, 220)), book.eras)
        // The going era's own family as the candidate changes nothing.
        Meta.stepEras(book, 11, score(EV, 2.0), emptyList())
        Meta.stepEras(book, 12, score(EV, 2.1), emptyList())
        assertEquals(2, book.eras.size)
        // Two of none are not enough, three are.
        Meta.stepEras(book, 13, null, emptyList())
        Meta.stepEras(book, 14, null, emptyList())
        assertEquals(2, book.eras.size)
        Meta.stepEras(book, 15, null, emptyList())
        assertEquals(Era(3, null, 15, 0, 0), book.era)
        assertEquals(Era(2, EV, 10, 14, 220), book.eras[1])
        // 춘추전국 lasts through more closes of none.
        repeat(4) { Meta.stepEras(book, 16 + it, null, emptyList()) }
        assertEquals(3, book.eras.size)
        // A family's third era.
        book.eraList.clear()
        book.eraList += listOf(Era(1, ODD, 1, 2, 200), Era(2, YACHT, 3, 4, 200), Era(3, ODD, 5, 6, 200), Era(4, ODD, 7, 0, 200))
        assertEquals(listOf("괴짜파의 시대", "요트파의 시대", "두 번째 괴짜파의 시대", "세 번째 괴짜파의 시대"), book.eras.map { book.name(it) })
    }
}
