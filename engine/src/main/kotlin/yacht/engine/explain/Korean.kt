package yacht.engine.explain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Korean particles that depend on the preceding sound, and number wording for explanations. */
object Ko {
    // How digits are read (영 일 이 삼 사 오 육 칠 팔 구): ends in a consonant? in ㄹ?
    private val digitFinal = booleanArrayOf(true, true, false, true, false, false, true, true, true, false)
    private val digitRieul = booleanArrayOf(false, true, false, false, false, false, false, true, true, false)

    /** (ends in a consonant, that consonant is ㄹ) for [word] as read aloud. */
    private fun ending(word: String): Pair<Boolean, Boolean> {
        var w = word.trimEnd()
        while (w.isNotEmpty()) {
            val ch = w.last()
            when {
                ch == ')' && w.contains('(') -> w = w.substring(0, w.lastIndexOf('(')).trimEnd()
                ch in "’'\"”」』" -> w = w.dropLast(1)
                else -> break
            }
        }
        val ch = w.lastOrNull() ?: return false to false
        return when (ch) {
            in '가'..'힣' -> {
                val f = (ch - '가') % 28
                (f != 0) to (f == 8)
            }
            in '0'..'9' -> {
                // 10, 100, 1000 are read 십, 백, 천: all end in a consonant (not ㄹ).
                digitFinal[ch - '0'] to digitRieul[ch - '0']
            }
            else -> false to false // %, %p (퍼센트, 포인트) and Latin letters used here read without one
        }
    }

    fun hasFinal(word: String): Boolean = ending(word).first

    private fun pick(word: String, withFinal: String, without: String) = word + if (hasFinal(word)) withFinal else without

    /** 은/는 */
    fun eun(w: String) = pick(w, "은", "는")

    /** 이/가 */
    fun i(w: String) = pick(w, "이", "가")

    /** 을/를 */
    fun eul(w: String) = pick(w, "을", "를")

    /** 과/와 */
    fun wa(w: String) = pick(w, "과", "와")

    /** (으)로 */
    fun ro(w: String): String {
        val (f, rieul) = ending(w)
        return w + if (f && !rieul) "으로" else "로"
    }

    /** 이에요/예요 */
    fun ieyo(w: String) = pick(w, "이에요", "예요")

    /** 이었어요/였어요 */
    fun ieosseoyo(w: String) = pick(w, "이었어요", "였어요")

    /** 이지만/지만 */
    fun ijiman(w: String) = pick(w, "이지만", "지만")

    /** (이)라도 */
    fun irado(w: String) = pick(w, "이라도", "라도")

    private fun fmt(pattern: String, v: Double) = String.format(Locale.ROOT, pattern, v)

    /** A probability for reading: 61%, 4.6%, 99.4%, 0%, 100%. */
    fun pct(p: Double): String {
        val x = p.coerceIn(0.0, 1.0) * 100
        return when {
            x < 0.05 -> "0%"
            x >= 99.95 -> "100%"
            x < 9.95 || x >= 99.5 -> trimZero(fmt("%.1f", x)) + "%"
            else -> "${x.roundToInt()}%"
        }
    }

    /** Two probabilities shown side by side, with enough digits to tell them apart. */
    fun pctPair(a: Double, b: Double): Pair<String, String> {
        val pa = pct(a)
        val pb = pct(b)
        if (pa != pb || abs(a - b) < 0.0005) return pa to pb
        return fmt("%.1f", a.coerceIn(0.0, 1.0) * 100) + "%" to fmt("%.1f", b.coerceIn(0.0, 1.0) * 100) + "%"
    }

    /** A difference of probabilities in percentage points (positive input). */
    fun pp(d: Double): String {
        val x = abs(d) * 100
        return if (x < 10) trimZero(fmt("%.1f", x)) + "%p" else "${x.roundToInt()}%p"
    }

    /** Points with at most one decimal: 4.1, 12. */
    fun num(x: Double): String = trimZero(fmt("%.1f", x))

    /** Whole points. */
    fun int(x: Double): String = "${x.roundToInt()}"

    private fun trimZero(s: String) = if (s.endsWith(".0")) s.dropLast(2) else s

    /** 한, 두, 세 ... for counting dice. */
    fun count(n: Int): String = when (n) {
        1 -> "한"
        2 -> "두"
        3 -> "세"
        4 -> "네"
        5 -> "다섯"
        else -> "$n"
    }

    /** "열 번에 한 번꼴" when [p] is close to 1/n for a round n, else null. */
    fun oneIn(p: Double): String? {
        val words = listOf(2 to "두", 3 to "세", 4 to "네", 5 to "다섯", 10 to "열", 20 to "스무")
        for ((n, w) in words) {
            val ratio = p * n
            if (ratio in 0.9..1.1) return "$w 번에 한 번꼴"
        }
        return null
    }
}
