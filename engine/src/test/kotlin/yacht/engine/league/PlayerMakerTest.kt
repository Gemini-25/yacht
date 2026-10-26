package yacht.engine.league

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerMakerTest {
    private fun draft() = PlayerMaker.blank("BR").copy(
        name = "호나우 실바", gender = Gender.FEMALE, hometown = "상파울루", age = 24,
        abilities = Abilities(95, 80, 70, 40), potential = 97, feel = false, temper = Temper.MOODY,
    ).withPrimary("hunter").let { it.copy(play = it.play.copy(k = 1.4, secondary = "purist")) }.withOverlay("gambler")

    @Test
    fun aMadePlayerIsWhoItWasDrawnToBe() {
        val book = PlayerBook().apply { ensureGenerated(50) }
        val r = book.make(draft(), 3)
        assertEquals("u1", r.key)
        assertEquals(Origin.CREATED, Origin.of(r.key))
        assertEquals(Origin.CREATED, r.origin)
        val id = assertNotNull(r.identity)
        assertEquals("BR", id.nation)
        assertEquals(Gender.FEMALE, id.gender)
        assertEquals(24, id.age(3, r.born))
        assertEquals(Abilities(95, 80, 70, 40), id.abilities)
        assertEquals(97, id.potential)
        assertEquals("상파울루", r.hometown)
        val g = assertNotNull(r.spec.play)
        assertEquals("hunter", g.primary)
        assertEquals(1.4, g.k, 1e-9)
        assertEquals("purist", g.secondary)
        assertEquals("gambler", g.overlay)
        // Its abilities play: no mistakes from 판단력 92, a temper below 멘탈 50.
        assertEquals(CustomMistake.NONE, r.spec.mistake)
        assertEquals(Temper.MOODY, r.spec.temper)
        // The next one takes the next key, also after a save.
        val back = PlayerBook.decode(book.encode())
        assertEquals(r, back["u1"])
        assertEquals("u2", back.make(draft().copy(name = "다음 선수"), 3).key)
    }

    @Test
    fun aKeyIsNeverGivenTwiceEvenAfterTheFirstLeft() {
        val book = PlayerBook()
        book.make(draft(), 1)
        assertTrue(book.remove("u1"))
        assertNull(book["u1"])
        assertEquals("u2", book.make(draft(), 1).key)
        book.retire("u2", 2)
        val back = PlayerBook.decode(book.encode())
        assertEquals("u3", back.make(draft(), 3).key)
        // Only players the player made can be removed.
        val gen = PlayerBook().apply { ensureGenerated(3) }
        assertFalse(gen.remove(gen.alive.first().key))
    }

    @Test
    fun editingKeepsRecordsAndTheHabitsItDoesNotTouch() {
        val book = PlayerBook().apply { ensureGenerated(20) }
        val p = book.alive.first()
        val d = PlayerMaker.of(p, 4)
        assertEquals(p.name, d.name)
        assertEquals(p.age(4), d.age)
        val e = assertNotNull(book.edit(p.key, d.copy(abilities = Abilities(99, 99, 99, 99), age = 30), 4))
        assertEquals(p.born, e.born)
        assertEquals(p.origin, e.origin)
        assertEquals(30, e.age(4))
        assertEquals(Abilities(99, 99, 99, 99), e.identity!!.abilities)
        // Its tastes and situational habits stay (only what was changed changes).
        assertEquals(p.spec.play!!.taste, e.spec.play!!.taste)
        assertEquals(p.spec.play!!.primary, e.spec.play!!.primary)
        assertEquals(CustomMistake.NONE, e.spec.mistake)
    }

    @Test
    fun aPlayerForeverInItsPrimeNeverAgesNorRetires() {
        val book = PlayerBook().apply { ensureGenerated(100) }
        val old = book.make(draft().copy(name = "영원한 선수", age = 39, keep = true), 1)
        val aging = book.make(draft().copy(name = "늙는 선수", age = 39), 1)
        for (season in 1..6) Evolution.run(book, season, { 1500.0 }, { 0 }, 7L, 102)
        val still = assertNotNull(book[old.key])
        assertEquals(39, still.age(7))
        assertEquals(old.identity!!.abilities, still.identity!!.abilities)
        assertTrue(still.keep)
        // Without it, 39 is near the end (retired by 41 at the latest).
        assertNull(book[aging.key])
    }

    @Test
    fun drawnDraftsAreValidAndProblemsAreNamed() {
        val rng = Random(3)
        repeat(20) {
            val d = PlayerMaker.random(rng, emptySet())
            assertNull(PlayerMaker.problem(d, emptySet()), d.toString())
            assertTrue(d.age in PlayerMaker.MIN_AGE..PlayerMaker.MAX_AGE)
        }
        assertEquals("이름을 적어 주세요.", PlayerMaker.problem(PlayerMaker.blank(), emptySet()))
        assertEquals("같은 이름의 선수가 이미 있어요.", PlayerMaker.problem(draft(), setOf("호나우 실바")))
        // A chameleon gets styles of the day; a fixed style has no intensity.
        val c = PlayerMaker.build(draft().withOverlay("chameleon"), "u9", 1, null)
        assertEquals(PlayerMaker.MOODS, c.spec.play!!.moods)
        val f = PlayerMaker.build(draft().withPrimary("topdown"), "u9", 1, null)
        assertEquals(1.0, f.spec.play!!.k, 1e-9)
    }
}
