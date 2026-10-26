package yacht.engine.league

import yacht.engine.BonusTable
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.EvTable
import yacht.engine.Player
import yacht.engine.ScoreTable
import yacht.engine.TurnKernel
import java.util.Random

// Styles of play (docs/LEAGUE_SPEC.md 1.2): how generated players differ in what they value and
// what they hold, solved exactly turn by turn like the max-expectation plan, with no table of
// their own. Measured in the archetype study: 30 styles told apart 93.5% of the time from the
// numbers on the screens, against 40% for the old taste genes.

/** Which dice a style may hold between throws (stopping, i.e. holding all five, is always allowed). */
enum class KeepRule(val label: String) {
    ANY("자유롭게 남김"),
    HIGH("4·5·6만 남김"),
    ONE_FACE("한 가지 눈만 모음"),
    TWO_FACE("두 가지 눈까지만 모음"),
    RUN("서로 다른 눈만 남김"),
    NONE("남기지 않고 전부 다시"),
    ;

    fun allows(keep: Int): Boolean {
        val k = Dice.keepCounts[keep]
        return when (this) {
            ANY -> true
            HIGH -> k[0] == 0 && k[1] == 0 && k[2] == 0
            ONE_FACE -> k.count { it > 0 } <= 1
            TWO_FACE -> k.count { it > 0 } <= 2
            RUN -> k.all { it <= 1 }
            NONE -> Dice.keepSize[keep] == 0
        }
    }
}

/** Which open boxes a style may write into. */
enum class BoxRule {
    ANY,

    /** Never a 0 while some open box scores. */
    NO_SCRATCH,

    /** Only the first [Style.window] open boxes of ‘1’ … 요트. */
    ORDER_TOP,

    /** Only the first [Style.window] open boxes of 요트 … ‘1’. */
    ORDER_BOTTOM,
}

/**
 * One way of playing a turn, all in points (no new solver table): the end of a turn is worth
 * the points as the style reads them + bonus + [future] × the real max-expectation rest + tastes;
 * between throws it may hold only what [keep] allows, each extra throw costs [rerollCost]
 * points (negative = it likes throwing), and each die held of face f is worth [hold][f] more.
 * The turn is then solved exactly for that valuation (the same backward pass as [yacht.engine.TurnPlan]).
 */
data class Style(
    val future: Double = 1.0,
    /** Extra points for a non-zero score in a box. */
    val like: Map<Category, Double> = emptyMap(),
    /** Extra points for a 0 in a box (positive = it is glad to get rid of it), only while fewer than [zeroUntil] boxes are filled. */
    val zeroLike: Map<Category, Double> = emptyMap(),
    val zeroUntil: Int = Category.COUNT,
    /** Points lost by any 0 written. */
    val zero: Double = 0.0,
    /** Extra points per upper point (toward the bonus). */
    val bonus: Double = 0.0,
    /** Points lost per die short of three in an upper box that scores. */
    val par: Double = 0.0,
    /** Extra points for a box the opponent has already filled (negative = for one it has not). */
    val copy: Double = 0.0,
    val rerollCost: Double = 0.0,
    val keep: KeepRule = KeepRule.ANY,
    /** Points per die held, per face (index 0 = face 1). */
    val hold: List<Double> = emptyList(),
    val box: BoxRule = BoxRule.ANY,
    val window: Int = 2,
    /** A Full House believed to be worth this many points (-1 = the real sum). */
    val fullHouseAs: Int = -1,
    /**
     * A misread box: it believes the dice score there when they do not (and writes a 0):
     * "ss3" a run of three is a small straight (15), "fh2pair" two pairs are a full house (the sum),
     * "4k3" three of a kind is a four of a kind (the sum). Null = reads the card right.
     */
    val misread: String? = null,
    /** Choice is written only for at least this many points: each point short costs 1.5 (0 = no rule). */
    val choiceMin: Int = 0,
    /** Treats Choice before the last three turns as 10 points lost (the existing saveChoice gene). */
    val saveChoice: Boolean = false,
    /**
     * Extra points the upper bonus is worth to it (on top of the real 35), weighted by the
     * chance of getting it from the end of the turn (the shipped [yacht.engine.BonusTable]).
     */
    val bonusLove: Double = 0.0,
    /** Mistakes: the chance of the second-best option, or the feel τ in points (0 = none). */
    val slip: Double = 0.0,
    val tau: Double = 0.0,
    /** Counts the taste of liked boxes still open (see [HIT]); false = only when written. */
    val hope: Boolean = true,
) {
    /** The style as a short gene string (only fields that differ from the default), read back by [parse]. */
    fun encode(): String {
        val d = Style()
        val out = ArrayList<String>()
        fun put(k: String, v: Any, dv: Any) { if (v != dv) out += "$k=$v" }
        put("future", future, d.future)
        for ((c, v) in like) out += "like.${c.name}=$v"
        for ((c, v) in zeroLike) out += "zero.${c.name}=$v"
        put("zeroUntil", zeroUntil, d.zeroUntil)
        put("zeroPen", zero, d.zero)
        put("bonus", bonus, d.bonus)
        put("par", par, d.par)
        put("copy", copy, d.copy)
        put("cost", rerollCost, d.rerollCost)
        put("keep", keep, d.keep)
        hold.forEachIndexed { f, v -> if (v != 0.0) out += "hold.${f + 1}=$v" }
        put("box", box, d.box)
        put("window", window, d.window)
        put("fh", fullHouseAs, d.fullHouseAs)
        if (misread != null) out += "misread=$misread"
        put("choiceMin", choiceMin, d.choiceMin)
        put("saveChoice", saveChoice, d.saveChoice)
        put("bonusLove", bonusLove, d.bonusLove)
        put("slip", slip, d.slip)
        put("tau", tau, d.tau)
        put("hope", hope, d.hope)
        return out.joinToString(",")
    }

    /** The per-keep adjustment: hold tastes, -inf for keeps the rule forbids. Read-only after the first use. */
    internal val keepAdj: DoubleArray by lazy {
        DoubleArray(Dice.KEEPS) { k ->
            if (Dice.keepSize[k] == 5) 0.0
            else if (!keep.allows(k)) Double.NEGATIVE_INFINITY
            else {
                val c = Dice.keepCounts[k]
                var x = 0.0
                for (f in 0 until 6) if (f < hold.size) x += c[f] * hold[f]
                x
            }
        }
    }
    internal val likeArr: DoubleArray by lazy { DoubleArray(Category.COUNT) { like[Category.of(it)] ?: 0.0 } }
    internal val zeroArr: DoubleArray by lazy { DoubleArray(Category.COUNT) { zeroLike[Category.of(it)] ?: 0.0 } }

    companion object {
        fun parse(spec: String): Style {
            var s = Style()
            val like = HashMap<Category, Double>()
            val zl = HashMap<Category, Double>()
            val hold = DoubleArray(6)
            for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, v) = part.split('=', limit = 2)
                when {
                    k.startsWith("like.") -> like[Category.valueOf(k.removePrefix("like."))] = v.toDouble()
                    k.startsWith("zero.") -> zl[Category.valueOf(k.removePrefix("zero."))] = v.toDouble()
                    k.startsWith("hold.") -> hold[k.removePrefix("hold.").toInt() - 1] = v.toDouble()
                    k == "future" -> s = s.copy(future = v.toDouble())
                    k == "zeroUntil" -> s = s.copy(zeroUntil = v.toInt())
                    k == "zeroPen" -> s = s.copy(zero = v.toDouble())
                    k == "bonus" -> s = s.copy(bonus = v.toDouble())
                    k == "par" -> s = s.copy(par = v.toDouble())
                    k == "copy" -> s = s.copy(copy = v.toDouble())
                    k == "cost" -> s = s.copy(rerollCost = v.toDouble())
                    k == "keep" -> s = s.copy(keep = KeepRule.valueOf(v))
                    k == "box" -> s = s.copy(box = BoxRule.valueOf(v))
                    k == "window" -> s = s.copy(window = v.toInt())
                    k == "fh" -> s = s.copy(fullHouseAs = v.toInt())
                    k == "misread" -> s = s.copy(misread = v)
                    k == "choiceMin" -> s = s.copy(choiceMin = v.toInt())
                    k == "saveChoice" -> s = s.copy(saveChoice = v.toBoolean())
                    k == "bonusLove" -> s = s.copy(bonusLove = v.toDouble())
                    k == "slip" -> s = s.copy(slip = v.toDouble())
                    k == "tau" -> s = s.copy(tau = v.toDouble())
                    k == "hope" -> s = s.copy(hope = v.toBoolean())
                    else -> Unit // a gene of a newer version: ignored
                }
            }
            return s.copy(like = like, zeroLike = zl, hold = if (hold.any { it != 0.0 }) hold.toList() else emptyList())
        }

        /**
         * Chance per turn of making a lower box when playing for it (4K, FH, small, large, Yacht);
         * 0 for the upper boxes and Choice (no hope term).
         */
        val HIT: DoubleArray = DoubleArray(Category.COUNT).also {
            it[Category.FOUR_OF_A_KIND.ordinal] = 0.29
            it[Category.FULL_HOUSE.ordinal] = 0.36
            it[Category.SMALL_STRAIGHT.ordinal] = 0.6
            it[Category.LARGE_STRAIGHT.ordinal] = 0.26
            it[Category.YACHT.ordinal] = 0.046
        }

        val ORDER_TOP: List<Int> = (0 until Category.COUNT).toList()
        val ORDER_BOTTOM: List<Int> = ORDER_TOP.reversed()
    }
}

/** The exact solution of one turn under a [Style]. Costs about one [yacht.engine.TurnPlan]. */
class StylePlan(val me: Board, opp: Board, val style: Style, ev: EvTable, bonusTable: BonusTable? = null) {
    private val rules = me.rules
    private val scores = ScoreTable.of(rules)
    private val catValue = DoubleArray(Dice.ROLLS * Category.COUNT) { Double.NEGATIVE_INFINITY }
    val e3 = DoubleArray(Dice.ROLLS)
    private val bestCat = IntArray(Dice.ROLLS)
    private val k2: DoubleArray
    val e2 = DoubleArray(Dice.ROLLS)
    private val k1: DoubleArray

    init {
        require(!me.isComplete)
        val open = me.openCategories.map { it.ordinal }
        val allowed: List<Int> = when (style.box) {
            BoxRule.ORDER_TOP -> Style.ORDER_TOP.filter { it in open }.take(style.window)
            BoxRule.ORDER_BOTTOM -> Style.ORDER_BOTTOM.filter { it in open }.take(style.window)
            else -> open
        }
        val u = me.u
        val cap = rules.bonusThreshold
        val early = me.filledCount < style.zeroUntil
        val like = style.likeArr
        val zl = style.zeroArr
        // Hope: a liked box still open is worth its taste times the chance of making it in the
        // turns left after this one (the real table knows nothing of the taste).
        val left = Category.COUNT - me.filledCount - 1
        val hopeOf = DoubleArray(Category.COUNT)
        var hopeAll = 0.0
        if (style.hope) for (c in open) {
            val h = Style.HIT[c]
            if (like[c] > 0 && h > 0) {
                hopeOf[c] = like[c] * (1 - Math.pow(1 - h, left.toDouble()))
                hopeAll += hopeOf[c]
            }
        }
        for (r in 0 until Dice.ROLLS) {
            var anyScores = false
            if (style.box == BoxRule.NO_SCRATCH) for (c in allowed) if (scores[r, c] > 0) anyScores = true
            var best = Double.NEGATIVE_INFINITY
            var bc = allowed[0]
            for (c in allowed) {
                val s = scores[r, c]
                if (style.box == BoxRule.NO_SCRATCH && anyScores && s == 0) continue
                var pts = s.toDouble()
                if (c == Category.FULL_HOUSE.ordinal && style.fullHouseAs >= 0 && s > 0) pts = style.fullHouseAs.toDouble()
                val seen = if (s == 0 && style.misread != null) misreadPoints(style.misread, r, c) else 0
                if (seen > 0) pts = seen.toDouble()
                if (c == Category.CHOICE.ordinal && style.choiceMin > 0) pts -= 1.5 * maxOf(0, style.choiceMin - s)
                if (c == Category.CHOICE.ordinal && style.saveChoice && me.filledCount < 9) pts -= 10.0
                var bonus = 0
                var u2 = u
                if (c < 6) {
                    bonus = if (u < cap && u + s >= cap) rules.bonusPoints else 0
                    u2 = minOf(cap, u + s)
                }
                var v = pts + bonus + style.future * ev[me.mask or (1 shl c), u2] + hopeAll - hopeOf[c]
                if (s > 0 || seen > 0) v += like[c] else {
                    v -= style.zero
                    if (early) v += zl[c]
                }
                if (style.bonusLove != 0.0 && bonusTable != null && u < cap) {
                    val m2 = me.mask or (1 shl c)
                    v += style.bonusLove * (if (u2 >= cap) 1.0 else if (m2 == Category.FULL_MASK) 0.0 else bonusTable[m2, u2])
                }
                if (c < 6) {
                    v += style.bonus * (u2 - u)
                    if (s > 0 && style.par != 0.0) v -= style.par * maxOf(0, 3 - s / (c + 1))
                }
                if (style.copy != 0.0 && opp.isFilled(Category.of(c))) v += style.copy
                catValue[r * Category.COUNT + c] = v
                if (v > best) {
                    best = v
                    bc = c
                }
            }
            e3[r] = best
            bestCat[r] = bc
        }
        k2 = TurnKernel.expectOne(e3)
        for (r in 0 until Dice.ROLLS) e2[r] = bestValue(2, r)
        k1 = TurnKernel.expectOne(e2)
    }

    /** Value of [keep] after [rollsDone] throws of roll [r] (holding all five = stop and write). */
    fun keepValue(rollsDone: Int, keep: Int, r: Int): Double {
        if (keep == Dice.rollAsKeep[r]) return e3[r]
        val adj = style.keepAdj[keep]
        if (adj == Double.NEGATIVE_INFINITY) return adj
        return (if (rollsDone == 1) k1 else k2)[keep] + adj - style.rerollCost
    }

    private fun bestValue(rollsDone: Int, r: Int): Double {
        var best = Double.NEGATIVE_INFINITY
        for (j in Dice.subStart[r] until Dice.subStart[r + 1]) {
            val v = keepValue(rollsDone, Dice.subKeep[j], r)
            if (v > best) best = v
        }
        return best
    }

    fun bestKeep(rollsDone: Int, r: Int): Int {
        var best = Dice.rollAsKeep[r]
        var bestV = e3[r]
        for (j in Dice.subStart[r] until Dice.subStart[r + 1]) {
            val k = Dice.subKeep[j]
            val v = keepValue(rollsDone, k, r)
            if (v > bestV + 1e-9) {
                bestV = v
                best = k
            }
        }
        return best
    }

    fun categoryValue(r: Int, c: Int): Double = catValue[r * Category.COUNT + c]

    /** Points roll [r] really scores in box [c]. */
    fun points(r: Int, c: Int): Int = scores[r, c]

    /** What a misreading player believes roll [r] scores in box [c] when it really scores 0. */
    private fun misreadPoints(misread: String, r: Int, c: Int): Int {
        val k = Dice.rollCounts[r]
        val sum = (0 until 6).sumOf { k[it] * (it + 1) }
        return when {
            misread == "ss3" && c == Category.SMALL_STRAIGHT.ordinal -> {
                var run = 0
                var best = 0
                for (f in 0 until 6) {
                    run = if (k[f] > 0) run + 1 else 0
                    best = maxOf(best, run)
                }
                if (best >= 3) rules.smallStraightPoints else 0
            }
            misread == "fh2pair" && c == Category.FULL_HOUSE.ordinal -> if (k.count { it >= 2 } >= 2) sum else 0
            misread == "4k3" && c == Category.FOUR_OF_A_KIND.ordinal -> if (k.any { it >= 3 }) sum else 0
            else -> 0
        }
    }
    fun bestCategory(r: Int): Category = Category.of(bestCat[r])
}

/** 승부 감각 applies from this many boxes written. */
const val SENSE_FROM = 4

/** Points below the style's best within which 승부 감각 may pick a box by its chance of winning. */
const val SENSE_WINDOW = 12.0

/** The style and mistake level for one turn. */
data class TurnMode(val style: Style, val slip: Double = 0.0, val tau: Double = 0.0)

/**
 * What an archetype reads each turn to pick its [TurnMode]: the cards, and the game so far
 * ([GameMemory]). Pure functions of the cards and the game's own random source.
 */
fun interface ModePicker {
    fun pick(me: Board, opp: Board, memory: GameMemory): TurnMode
}

/** What a player remembers within one game: the last card and how its last turn went. */
class GameMemory(private val ev: EvTable) {
    var last: Board? = null
        private set

    /** Change of the expected final score (points + max-expectation rest) over my last turn. */
    var lastSwing = 0.0
        private set
    var lastZero = false
        private set

    /** +1 hot, -1 cold, 0 neutral (for streaky players). */
    var heat = 0

    fun expected(b: Board) = b.total + if (b.isComplete) 0.0 else ev[b.mask, b.u]
    fun lead(me: Board, opp: Board) = expected(me) - expected(opp)

    fun update(me: Board) {
        val prev = last
        if (prev == null || me.filledCount == 0) {
            lastSwing = 0.0
            lastZero = false
        } else {
            lastSwing = expected(me) - expected(prev)
            val written = Category.ALL.firstOrNull { me.isFilled(it) && !prev.isFilled(it) }
            lastZero = written != null && me[written] == 0
        }
        last = me
    }
}

/**
 * An archetype's player: each turn [picker] chooses a style and a mistake level; the turn is
 * solved for that style; slips take the second-best option, feel picks by exp(value / τ).
 */
class StylePlayer(
    override val name: String,
    private val ev: EvTable,
    private val rng: Random,
    private val picker: ModePicker,
    private val bonusTable: BonusTable? = null,
    /**
     * 승부 감각 0..1: from the [SENSE_FROM]th box on, the box is chosen among those within
     * [SENSE_WINDOW] × [sense] points of the style's best by the chance of winning ([win]).
     */
    private val sense: Double = 0.0,
    private val win: WinEstimate? = null,
) : Player {
    private val memory = GameMemory(ev)
    private lateinit var plan: StylePlan
    private lateinit var mode: TurnMode
    private lateinit var me: Board
    private lateinit var opp: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        this.opp = opp
        memory.update(me)
        mode = picker.pick(me, opp, memory).let { if (it.slip == 0.0 && it.tau == 0.0) it.copy(slip = it.style.slip, tau = it.style.tau) else it }
        plan = StylePlan(me, opp, mode.style, ev, bonusTable)
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val best = plan.bestKeep(rollsDone, r)
        if (mode.slip <= 0 && mode.tau <= 0) return best
        val keeps = (Dice.subStart[r] until Dice.subStart[r + 1]).map { Dice.subKeep[it] }
            .filter { plan.keepValue(rollsDone, it, r) > Double.NEGATIVE_INFINITY }
        return choose(best, keeps) { plan.keepValue(rollsDone, it, r) }
    }

    override fun chooseCategory(r: Int): Category {
        val best = senseBest(r) ?: plan.bestCategory(r)
        if (mode.slip <= 0 && mode.tau <= 0) return best
        val open = me.openCategories.filter { plan.categoryValue(r, it.ordinal) > Double.NEGATIVE_INFINITY }
        return choose(best, open) { plan.categoryValue(r, it.ordinal) }
    }

    /** The box 승부 감각 picks: the likeliest win among the near-best boxes (null when it does not apply). */
    private fun senseBest(r: Int): Category? {
        val w = win ?: return null
        if (sense <= 0.0 || me.filledCount < SENSE_FROM) return null
        val top = plan.categoryValue(r, plan.bestCategory(r).ordinal)
        val window = SENSE_WINDOW * sense
        var best: Category? = null
        var bestP = -1.0
        for (c in me.openCategories) {
            val v = plan.categoryValue(r, c.ordinal)
            if (v == Double.NEGATIVE_INFINITY || v < top - window) continue
            val p = w.pWin(me.with(c, plan.points(r, c.ordinal)), opp)
            if (p > bestP + 1e-9) {
                bestP = p
                best = c
            }
        }
        return best
    }

    private fun <T> choose(best: T, options: List<T>, value: (T) -> Double): T {
        if (options.size <= 1) return best
        if (mode.tau > 0) {
            val vs = options.map(value)
            val top = vs.max()
            val w = vs.map { Math.exp((it - top) / mode.tau) }
            var x = rng.nextDouble() * w.sum()
            for (i in options.indices) {
                x -= w[i]
                if (x <= 0) return options[i]
            }
            return options.last()
        }
        if (rng.nextDouble() >= mode.slip) return best
        return options.filter { it != best }.maxByOrNull(value) ?: best
    }
}

/**
 * The style of an existing max-expectation spec (its tastes, sight and mistakes) with no hope
 * term: a [StylePlayer] of it plays the same games as [CustomAis.build] (checked in the study).
 */
fun styleOf(spec: CustomAiSpec): Style {
    val like = HashMap<Category, Double>()
    if (spec.yacht != 0.0) like[Category.YACHT] = spec.yacht
    if (spec.straight != 0.0) {
        like[Category.SMALL_STRAIGHT] = spec.straight
        like[Category.LARGE_STRAIGHT] = spec.straight
    }
    return Style(
        future = spec.future, like = like, zero = spec.zero, bonus = spec.bonus, saveChoice = spec.saveChoice, hope = false,
        slip = if (spec.effectiveMistake == CustomMistake.SLIP) spec.effectiveAmount else 0.0,
        tau = if (spec.effectiveMistake == CustomMistake.FEEL) spec.effectiveAmount else 0.0,
    )
}
