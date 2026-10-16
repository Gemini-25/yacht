package yacht.engine

/** One player's score card. Immutable; [with] returns a new card. */
class Board private constructor(val rules: RuleSet, private val cells: IntArray) {
    constructor(rules: RuleSet = RuleSet.NINTENDO) : this(rules, IntArray(Category.COUNT) { EMPTY })

    operator fun get(c: Category): Int? = cells[c.ordinal].takeIf { it != EMPTY }
    fun isFilled(c: Category): Boolean = cells[c.ordinal] != EMPTY

    val mask: Int = cells.foldIndexed(0) { i, m, v -> if (v != EMPTY) m or (1 shl i) else m }
    val filledCount: Int get() = Integer.bitCount(mask)
    val isComplete: Boolean get() = mask == Category.FULL_MASK
    val openCategories: List<Category> get() = Category.ALL.filter { !isFilled(it) }

    val upperSum: Int = (0 until 6).sumOf { if (cells[it] == EMPTY) 0 else cells[it] }
    val bonus: Int get() = if (upperSum >= rules.bonusThreshold) rules.bonusPoints else 0
    val total: Int get() = cells.sumOf { if (it == EMPTY) 0 else it } + bonus

    /** Upper sum capped at the bonus threshold: the solver's "u" coordinate. */
    val u: Int get() = minOf(upperSum, rules.bonusThreshold)

    fun with(c: Category, points: Int): Board {
        require(!isFilled(c)) { "$c already filled" }
        require(points >= 0)
        return Board(rules, cells.copyOf().also { it[c.ordinal] = points })
    }

    fun without(c: Category): Board = Board(rules, cells.copyOf().also { it[c.ordinal] = EMPTY })

    fun toList(): List<Int?> = Category.ALL.map { get(it) }

    override fun equals(other: Any?): Boolean =
        other is Board && other.rules == rules && other.cells.contentEquals(cells)

    override fun hashCode(): Int = cells.contentHashCode()
    override fun toString(): String = Category.ALL.joinToString(" ") { "${it.name}=${get(it) ?: "-"}" }

    companion object {
        private const val EMPTY = -1

        fun of(rules: RuleSet, scores: List<Int?>): Board {
            require(scores.size == Category.COUNT)
            return Board(rules, IntArray(Category.COUNT) { scores[it] ?: EMPTY })
        }
    }
}
