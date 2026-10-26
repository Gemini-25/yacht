package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.Player
import java.util.Random

/**
 * How an AI's form changes within a game: a gene of generated players (and an option of the
 * player's own AIs). It scales the AI's mistakes turn by turn, like the 상황 판단 family's
 * tilting, tiring, warming-up and moody players. See [Tempers].
 */
enum class Temper(val label: String, val desc: String) {
    NONE("기복 없음", "판 내내 같은 실력으로 둬요"),
    TILT("멘탈 약함", "0점을 쓰거나 크게 망친 턴이 나오면 그 판 끝까지 실수가 늘어요"),
    TIRED("지구력 부족", "마지막 4턴에 실수가 늘어요"),
    WARMUP("슬로 스타터", "처음 4턴에 실수가 많아요"),
    MOODY("컨디션 기복", "판마다 실수가 절반에서 두 배 사이로 달라져요"),
}

/** One game's form: the mistake level for the turn about to start (1 = the AI's usual level). */
fun interface Mood {
    fun level(me: Board, opp: Board): Double
}

/** The numbers behind [Temper]: when a temper bites and how much it adds to the mistakes. */
object Tempers {
    /** Mistake level for the rest of the game after a bad turn (TILT). */
    const val TILT_LEVEL = 2.5

    /** Mistake level in the tired (last) or warm-up (first) turns. */
    const val PHASE_LEVEL = 3.0
    const val PHASE_TURNS = 4

    /** A turn that loses this many expected final points (points so far plus the expected rest) is a bad one. */
    const val BIG_MISS = 15.0

    /** Range of the per-game level of a moody AI (log-uniform, so its median is 1). */
    const val MOODY_MIN = 0.5
    const val MOODY_MAX = 2.0

    /**
     * Slip chance and feel (in points) added per unit of level above 1: a temper shows even on an
     * AI that otherwise makes no mistakes.
     */
    const val SLIP_FLOOR = 0.02
    const val FEEL_FLOOR = 0.2
    const val MAX_SLIP = 0.6

    /**
     * A fresh mood for one game. [ev] is the real game's max-expectation table (a tilting AI sees
     * the real scoreboard, whatever rules it believes); a moody AI draws its level from [rng],
     * the game's own random source, so the game stays a pure function of its seed.
     */
    fun mood(temper: Temper, ev: EvTable, rng: Random, strength: Double = 1.0): Mood {
        // Strength t scales how far a temper moves the level: 1 + (level - 1)·t, and a moody
        // range of [MIN^t, MAX^t]; t = 1 is each temper as it always was.
        val phase = 1 + (PHASE_LEVEL - 1) * strength
        return when (temper) {
            Temper.NONE -> Mood { _, _ -> 1.0 }
            Temper.TILT -> TiltMood(ev, 1 + (TILT_LEVEL - 1) * strength)
            Temper.TIRED -> Mood { me, _ -> if (Category.COUNT - me.filledCount <= PHASE_TURNS) phase else 1.0 }
            Temper.WARMUP -> Mood { me, _ -> if (me.filledCount < PHASE_TURNS) phase else 1.0 }
            Temper.MOODY -> {
                val lo = Math.pow(MOODY_MIN, strength)
                val hi = Math.pow(MOODY_MAX, strength)
                val level = lo * Math.pow(hi / lo, rng.nextDouble())
                Mood { _, _ -> level }
            }
        }
    }

    /** Slip chance at [level] for an AI that slips with [base] normally (exactly [base] at level 1). */
    fun slip(base: Double, level: Double): Double =
        if (level == 1.0) base else (base * level + SLIP_FLOOR * maxOf(0.0, level - 1)).coerceIn(0.0, MAX_SLIP)

    /** Feel τ (points) at [level] for an AI with τ = [base] normally (exactly [base] at level 1). */
    fun feel(base: Double, level: Double): Double =
        if (level == 1.0) base else base * level + FEEL_FLOOR * maxOf(0.0, level - 1)
}

/**
 * Tilts for the rest of the game once a turn goes badly: a 0 written, or the expected final
 * score (points plus max-expectation of the rest) down by [Tempers.BIG_MISS] over one turn.
 */
private class TiltMood(private val ev: EvTable, private val tiltLevel: Double = Tempers.TILT_LEVEL) : Mood {
    private var last: Board? = null
    private var lastExpected = 0.0
    private var tilted = false

    override fun level(me: Board, opp: Board): Double {
        val expected = me.total + if (me.isComplete) 0.0 else ev[me.mask, me.u]
        val prev = last
        if (me.filledCount == 0) {
            tilted = false
        } else if (prev != null && !tilted) {
            val written = Category.ALL.firstOrNull { me.isFilled(it) && !prev.isFilled(it) }
            if ((written != null && me[written] == 0) || expected - lastExpected <= -Tempers.BIG_MISS) tilted = true
        }
        last = me
        lastExpected = expected
        return if (tilted) tiltLevel else 1.0
    }
}

/**
 * Plays each turn as the player [at] builds for the turn's mistake level ([mood]); players are
 * kept per level, so a game makes only a few (they share the game's random source).
 */
class TemperPlayer(
    override val name: String,
    private val mood: Mood,
    private val at: (level: Double) -> Player,
) : Player {
    private val players = HashMap<Double, Player>()
    private lateinit var active: Player

    override fun startTurn(me: Board, opp: Board) {
        val level = mood.level(me, opp)
        active = players.getOrPut(level) { at(level) }
        active.startTurn(me, opp)
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = active.chooseKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = active.chooseCategory(r)
}
