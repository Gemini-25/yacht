package yacht.engine.league

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 별명 and 라이벌: from honours, abilities, age and style; the rival is the closest frequent opponent. */
class NicknamesTest {
    @Test
    fun nicknamesComeFromTheStory() {
        val p = PlayerBook().apply { ensureGenerated(40) }.alive
        val one = p.first { it.spec.play?.primary == "hunter" || it.spec.play?.primary == "purist" }
        // The style, when there is nothing else to tell.
        val plain = Nicknames.of(one.copy(identity = one.identity!!.copy(abilities = Abilities(60, 60, 60, 60), debutAge = 25)), emptyList(), 1)
        assertNotNull(plain)
        assertTrue("{" !in plain, plain)
        // Titles before style; a world champion first of all.
        assertEquals("리그의 제왕", Nicknames.of(one, List(3) { Honour(it + 1, "L0", one.key) }, 4))
        val wc = Nicknames.of(one, listOf(Honour(3, "WCW", one.key), Honour(4, "L0", one.key)), 5)!!
        assertTrue(wc.endsWith("월드컵 영웅"), wc)
        // Every generated player gets a nickname or none, never a broken one.
        for (r in p) Nicknames.of(r, emptyList(), 1)?.let { assertTrue(it.isNotBlank() && "{" !in it, it) }
    }

    @Test
    fun theKingOfTheLeagueNeedsThreeMajorLeagueTitles() {
        val p = PlayerBook().apply { ensureGenerated(40) }.alive
        val one = p.first { it.spec.play?.primary == "hunter" || it.spec.play?.primary == "purist" }
        // Three titles of a national league: a king while they are major (the best-ranked leagues), not once they are not.
        val jp = List(3) { Honour(it + 1, "L0.JP", one.key) }
        assertEquals("리그의 제왕", Nicknames.of(one, jp, 4))
        assertNotEquals("리그의 제왕", Nicknames.of(one, jp, 4) { _, league -> league != "JP" })
        // The old single league's title always counts.
        val mixed = listOf(Honour(1, "L0", one.key), Honour(2, "L0.KR", one.key), Honour(3, "L0.JP", one.key))
        assertEquals("리그의 제왕", Nicknames.of(one, mixed, 4))
        assertNotEquals("리그의 제왕", Nicknames.of(one, mixed, 4) { _, league -> league == "KR" })
        assertEquals("리그의 제왕", Nicknames.of(one, mixed + Honour(4, "L0.KR", one.key), 5) { _, league -> league == "KR" })
        // Lower tiers are titles (five make a serial winner), never the league's crown.
        val lower = List(5) { Honour(it + 1, "L${1 + it % 2}.KR", one.key) }
        assertEquals("우승 청부사", Nicknames.of(one, lower, 6))
        // A 대륙 챔피언스 title counts with the titles too.
        assertEquals("우승 청부사", Nicknames.of(one, lower.take(4) + Honour(5, "CCL.ASIA", one.key), 6))
    }

    @Test
    fun theRivalIsTheClosestFrequentOpponent() {
        val l = Ladder(listOf("ev", "a", "b", "c"))
        repeat(10) { l.record("ev", "a", listOf(GameScore(200, 150), GameScore(150, 200))) }
        repeat(12) { l.record("ev", "b", listOf(GameScore(200, 150), GameScore(200, 150))) }
        repeat(2) { l.record("ev", "c", listOf(GameScore(200, 150), GameScore(150, 200))) }
        assertEquals("a", l.rival("ev")!!.first)
        assertEquals(20, l.rival("ev")!!.third)
        // Too lopsided or too few games: no rival.
        assertNull(l.rival("b"))
        assertNull(l.rival("c"))
        assertEquals(3, l.opponents("ev").size)
    }
}
