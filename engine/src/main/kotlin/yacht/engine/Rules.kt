package yacht.engine

enum class Category {
    ACES, DEUCES, THREES, FOURS, FIVES, SIXES,
    CHOICE, FOUR_OF_A_KIND, FULL_HOUSE, SMALL_STRAIGHT, LARGE_STRAIGHT, YACHT;

    val bit: Int get() = 1 shl ordinal
    val isUpper: Boolean get() = ordinal < 6

    /** Face counted by an upper category (1..6). */
    val face: Int get() = ordinal + 1

    companion object {
        const val COUNT = 12
        const val FULL_MASK = (1 shl COUNT) - 1
        const val UPPER_MASK = (1 shl 6) - 1
        val ALL: List<Category> = entries.toList()
        fun of(index: Int): Category = ALL[index]
    }
}

/**
 * Scoring rules. Defaults are the Nintendo "Clubhouse Games: 51 Worldwide Classics" rules:
 * 4 of a Kind scores all five dice and a Yacht also counts as a Full House.
 */
data class RuleSet(
    val fullHouseAcceptsYacht: Boolean = true,
    val fourKindScoresAllFive: Boolean = true,
    val bonusThreshold: Int = 63,
    val bonusPoints: Int = 35,
    val smallStraightPoints: Int = 15,
    val largeStraightPoints: Int = 30,
    val yachtPoints: Int = 50,
) {
    /** Number of distinct capped upper-sum values (0..bonusThreshold). */
    val upperStates: Int get() = bonusThreshold + 1

    /** Score of [category] for dice given as counts per face (index 0 = face 1). */
    fun score(counts: IntArray, category: Category): Int {
        var sum = 0
        var maxCount = 0
        for (f in 0 until 6) {
            sum += counts[f] * (f + 1)
            if (counts[f] > maxCount) maxCount = counts[f]
        }
        return when (category) {
            Category.CHOICE -> sum
            Category.FOUR_OF_A_KIND -> {
                if (maxCount < 4) 0
                else if (fourKindScoresAllFive) sum
                else 4 * (counts.indexOfFirst { it >= 4 } + 1)
            }
            Category.FULL_HOUSE -> {
                val hasThree = counts.any { it == 3 }
                val hasTwo = counts.any { it == 2 }
                if ((hasThree && hasTwo) || (fullHouseAcceptsYacht && maxCount == 5)) sum else 0
            }
            Category.SMALL_STRAIGHT -> if (hasRun(counts, 4)) smallStraightPoints else 0
            Category.LARGE_STRAIGHT -> if (hasRun(counts, 5)) largeStraightPoints else 0
            Category.YACHT -> if (maxCount == 5) yachtPoints else 0
            else -> counts[category.ordinal] * category.face
        }
    }

    fun score(faces: List<Int>, category: Category): Int = score(Dice.countsOf(faces), category)

    private fun hasRun(counts: IntArray, length: Int): Boolean {
        var run = 0
        for (f in 0 until 6) {
            run = if (counts[f] > 0) run + 1 else 0
            if (run >= length) return true
        }
        return false
    }

    /** Stable id stored in table headers so stale tables are rejected. */
    val fingerprint: Int
        get() = listOf(
            fullHouseAcceptsYacht, fourKindScoresAllFive, bonusThreshold, bonusPoints,
            smallStraightPoints, largeStraightPoints, yachtPoints,
        ).joinToString(",").hashCode()

    companion object {
        val NINTENDO = RuleSet()
    }
}
