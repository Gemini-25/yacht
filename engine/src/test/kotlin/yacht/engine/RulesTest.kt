package yacht.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class RulesTest {
    private val rules = RuleSet.NINTENDO
    private fun score(c: Category, vararg faces: Int) = rules.score(faces.toList(), c)

    @Test
    fun upperCategoriesSumMatchingFaces() {
        assertEquals(3, score(Category.ACES, 1, 1, 1, 4, 5))
        assertEquals(18, score(Category.SIXES, 6, 6, 6, 2, 3))
        assertEquals(0, score(Category.FIVES, 1, 2, 3, 4, 6))
    }

    @Test
    fun fourOfAKindScoresAllFiveDice() {
        assertEquals(26, score(Category.FOUR_OF_A_KIND, 6, 6, 6, 6, 2))
        assertEquals(25, score(Category.FOUR_OF_A_KIND, 5, 5, 5, 5, 5))
        assertEquals(0, score(Category.FOUR_OF_A_KIND, 6, 6, 6, 2, 2))
        assertEquals(24, RuleSet(fourKindScoresAllFive = false).score(listOf(6, 6, 6, 6, 2), Category.FOUR_OF_A_KIND))
    }

    @Test
    fun fullHouseScoresSumAndAcceptsYacht() {
        assertEquals(13, score(Category.FULL_HOUSE, 3, 3, 3, 2, 2))
        assertEquals(25, score(Category.FULL_HOUSE, 5, 5, 5, 5, 5))
        assertEquals(0, score(Category.FULL_HOUSE, 5, 5, 5, 5, 2))
        assertEquals(0, RuleSet(fullHouseAcceptsYacht = false).score(listOf(5, 5, 5, 5, 5), Category.FULL_HOUSE))
    }

    @Test
    fun straightsAndYacht() {
        assertEquals(15, score(Category.SMALL_STRAIGHT, 1, 2, 3, 4, 6))
        assertEquals(15, score(Category.SMALL_STRAIGHT, 3, 4, 5, 6, 6))
        assertEquals(15, score(Category.SMALL_STRAIGHT, 2, 3, 4, 5, 6))
        assertEquals(0, score(Category.SMALL_STRAIGHT, 1, 2, 3, 5, 6))
        assertEquals(30, score(Category.LARGE_STRAIGHT, 2, 3, 4, 5, 6))
        assertEquals(0, score(Category.LARGE_STRAIGHT, 1, 2, 3, 4, 6))
        assertEquals(50, score(Category.YACHT, 4, 4, 4, 4, 4))
        assertEquals(0, score(Category.YACHT, 4, 4, 4, 4, 3))
        assertEquals(17, score(Category.CHOICE, 1, 2, 3, 5, 6))
    }

    @Test
    fun boardTotalsIncludeBonus() {
        var b = Board()
        b = b.with(Category.SIXES, 24).with(Category.FIVES, 20).with(Category.FOURS, 16)
        assertEquals(60, b.upperSum)
        assertEquals(0, b.bonus)
        b = b.with(Category.THREES, 3)
        assertEquals(35, b.bonus)
        assertEquals(63, b.u)
        assertEquals(98, b.total)
    }
}
