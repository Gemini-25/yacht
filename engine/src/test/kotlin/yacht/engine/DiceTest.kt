package yacht.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiceTest {
    @Test
    fun counts() {
        assertEquals(252, Dice.rollCounts.size)
        assertEquals(462, Dice.keepCounts.size)
        assertEquals(0, Dice.keepSize[Dice.EMPTY_KEEP])
    }

    @Test
    fun transitionRowsAreDistributions() {
        for (k in 0 until Dice.KEEPS) {
            val sum = (Dice.tStart[k] until Dice.tStart[k + 1]).sumOf { Dice.tProb[it] }
            assertEquals(1.0, sum, 1e-12)
        }
        assertEquals(1.0, Dice.firstRoll.sum(), 1e-12)
        // Yacht of sixes with five fresh dice.
        assertEquals(1.0 / 7776, Dice.firstRoll[Dice.rollIndex(listOf(6, 6, 6, 6, 6))], 1e-15)
    }

    @Test
    fun subsetsOfRoll() {
        val r = Dice.rollIndex(listOf(1, 2, 3, 4, 5))
        assertEquals(32, Dice.subStart[r + 1] - Dice.subStart[r])
        val y = Dice.rollIndex(listOf(2, 2, 2, 2, 2))
        assertEquals(6, Dice.subStart[y + 1] - Dice.subStart[y])
        // First subset is the whole roll (stop rolling).
        assertEquals(Dice.rollAsKeep[r], Dice.subKeep[Dice.subStart[r]])
        assertTrue(Dice.subKeep.toSet().size == Dice.KEEPS)
    }
}
