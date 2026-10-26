package yacht.engine.league

import kotlin.test.Test
import kotlin.test.assertEquals
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
