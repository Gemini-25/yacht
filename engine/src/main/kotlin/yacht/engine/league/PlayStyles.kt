package yacht.engine.league

import yacht.engine.Category
import yacht.engine.EvTable
import java.util.Locale
import java.util.Random

/**
 * A named way of playing (유형): a [Style] that scales with an intensity k (1 = the style as
 * measured), with a Korean name and a one-line description (해요체). [avg] is its average score
 * against the max-expectation AI (188.5 on the same dice) at k = 1 without mistakes, from the
 * archetype study; [scales] is false for rule-bound styles that have no intensity. [spec2] is the
 * intensity of genes of [PlayGene.V2] for the styles whose k hardly mattered: the same style at
 * k = 1, and k 0.7 against 1.7 changes at least 5% of the decisions (genes before keep [spec]).
 */
class PlayRecipe(
    val key: String,
    val name: String,
    val desc: String,
    val avg: Double,
    val scales: Boolean = true,
    private val spec2: ((Double) -> String)? = null,
    private val spec: (Double) -> String,
) {
    fun style(k: Double, version: Int = 1): Style = Style.parse(if (version >= PlayGene.V2 && spec2 != null) spec2(k) else spec(k))
}

/** A situation overlay: the style changes with the score, the phase of the game or the player's form. */
class PlayOverlay(val key: String, val name: String, val desc: String)

/** The styles and overlays generated players are drawn from (docs/LEAGUE_SPEC.md 1.2). */
object PlayStyles {
    private fun n(x: Double) = String.format(Locale.ROOT, "%.2f", x + 0.0)
    private fun r(key: String, name: String, desc: String, avg: Double, spec2: ((Double) -> String)? = null, spec: (Double) -> String) =
        PlayRecipe(key, name, desc, avg, true, spec2, spec)
    private fun fixed(key: String, name: String, desc: String, avg: Double, spec: String) = PlayRecipe(key, name, desc, avg, false) { spec }

    /** The max-expectation player's average on the same dice (the reference of [PlayRecipe.avg]). */
    const val EV_AVG = 188.5

    val RECIPES: List<PlayRecipe> = listOf(
        fixed("ev", "정석파", "남은 판의 기대 점수를 끝까지 계산해서 둬요.", 188.5, ""),
        // v2: a keener hunter also throws more.
        r("hunter", "요트 사냥꾼", "요트 칸은 끝까지 비워 두고 틈만 나면 요트를 노려요.", 185.8, { "like.YACHT=${n(60 * it)},cost=${n(-3 * (it - 1))}" }) {
            "like.YACHT=${n(60 * it)}"
        },
        r("collector", "같은 눈 수집가", "같은 눈만 남겨요. 스트레이트는 거의 노리지 않아요.", 176.4, {
            "keep=ONE_FACE,like.YACHT=${n(60 * it)},like.FOUR_OF_A_KIND=${n(5 * it)},cost=${n(-3 * (it - 1))}"
        }) {
            "keep=ONE_FACE,like.YACHT=${n(60 * it)},like.FOUR_OF_A_KIND=${n(5 * it)}"
        },
        r("fourkind", "포카드 장인", "같은 눈 네 개가 보이면 포카드부터 크게 챙겨요.", 188.3, { "like.FOUR_OF_A_KIND=${n(25 * it)},cost=${n(-3 * (it - 1))}" }) {
            "like.FOUR_OF_A_KIND=${n(25 * it)}"
        },
        r("straight", "스트레이트 수집가", "스트레이트는 꼭 채우고, 요트는 운이라며 별로 안 노려요.", 186.2) {
            "like.SMALL_STRAIGHT=${n(20 * it)},like.LARGE_STRAIGHT=${n(80 * it)},like.YACHT=${n(-20 * it)}"
        },
        r("purist", "보너스 장인", "위쪽 칸을 같은 눈 세 개 이상으로 채워서 보너스를 거의 놓치지 않아요.", 186.4) { "bonusLove=${n(35 * it)},par=${n(2 * it)}" },
        r("lower", "족보 전문가", "위쪽 칸은 버리는 칸으로 쓰고 포카드·풀하우스·스트레이트에 공을 들여요.", 175.4) {
            "bonusLove=${n(-35 * it)},like.FOUR_OF_A_KIND=${n(6 * it)},like.FULL_HOUSE=${n(6 * it)},like.LARGE_STRAIGHT=${n(6 * it)},like.YACHT=${n(10 * it)}"
        },
        // v2: a stricter one also stops throwing earlier.
        r("noscratch", "0점 거부자", "점수가 나는 칸이 하나라도 있으면 절대 0점을 쓰지 않아요.", 182.3, {
            "box=NO_SCRATCH,zeroPen=${n(10 * it)},like.CHOICE=${n(-6 * it)},cost=${n(2 * (it - 1))}"
        }) { "box=NO_SCRATCH,zeroPen=${n(10 * it)},like.CHOICE=${n(-6 * it)}" },
        r("scratcher", "일찍 지우는 사람", "요트처럼 어려운 칸은 초반에 미련 없이 0점으로 지워요.", 181.9) {
            "zero.YACHT=${n(12 * it)},zero.LARGE_STRAIGHT=${n(6 * it)},zero.FOUR_OF_A_KIND=${n(4 * it)},zeroUntil=6"
        },
        r("bigchoice", "초이스 25점 클럽", "초이스는 25점이 넘을 때만 써요.", 187.4, { "choiceMin=${Math.round(25 + 6 * (it - 1))},cost=${n(-2.5 * (it - 1))}" }) {
            "choiceMin=${Math.round(22 + 3 * it)}"
        },
        r("fullhouse", "풀하우스 덕후", "두 가지 눈까지만 남겨서 풀하우스를 노려요.", 181.9, { "keep=TWO_FACE,like.FULL_HOUSE=${n(15 * it)},cost=${n(-3 * (it - 1))}" }) {
            "keep=TWO_FACE,like.FULL_HOUSE=${n(15 * it)}"
        },
        // v2: with Choice in the safe, a keener hoarder writes a 0 and throws again more readily.
        r("hoarder", "초이스 금고지기", "초이스는 마지막 보험이라 끝까지 아껴 둬요.", 179.0, {
            "like.CHOICE=${n(-12 * it)},zeroPen=${n(-2 * (it - 1))},cost=${n(-2 * (it - 1))}"
        }) { "like.CHOICE=${n(-12 * it)}" },
        r("dumper", "초이스 먼저 쓰기", "애매하면 초이스부터 써 버려요.", 183.9) { "like.CHOICE=${n(10 * it)}" },
        r("timid", "소심한 굴림", "그럭저럭 괜찮으면 더 굴리지 않고 바로 적어요.", 173.9) { "cost=${n(3.5 * it)}" },
        r("reroller", "끝까지 굴리기", "만족을 몰라요. 풀하우스가 나와도 세 번을 다 굴려요.", 182.6, {
            "cost=${n(-10 * it)},like.YACHT=${n(25 * (it - 1))},like.FOUR_OF_A_KIND=${n(6 * (it - 1))}"
        }) { "cost=${n(-10 * it)}" },
        r("small", "작은 눈 챙기기", "1, 2, 3도 버리지 않고 모아서 위쪽 칸을 채워요.", 166.9) { "hold.1=${n(1.5 * it)},hold.2=${n(1.5 * it)},hold.3=${n(1.0 * it)}" },
        r("copycat", "따라쟁이", "상대가 이미 채운 칸을 따라서 채우는 편이에요.", 167.5) { "copy=${n(15 * it)}" },
        r("contrarian", "엇박자", "상대가 아직 안 채운 칸부터 먼저 채워요.", 179.3) { "copy=${n(-6 * it)}" },
        fixed("ss3", "스몰 스트레이트 착각", "세 개만 이어져도 스몰 스트레이트인 줄 알아요.", 181.0, "misread=ss3"),
        fixed("fh2pair", "투페어도 풀하우스", "투페어도 풀하우스로 쳐 주는 줄 알아요.", 181.0, "misread=fh2pair"),
        fixed("high", "큰 눈만 남기기", "4, 5, 6만 남기고 작은 눈은 무조건 다시 굴려요.", 163.3, "keep=HIGH"),
        fixed("topdown", "위에서부터 차근차근", "1번 칸부터 순서대로 채워 내려가요.", 133.9, "box=ORDER_TOP,window=2"),
        fixed("bottomup", "족보부터 거꾸로", "요트 칸부터 거꾸로 채워 올라가요.", 139.0, "box=ORDER_BOTTOM,window=2"),
        fixed("allornothing", "몽땅 다시 굴리기", "하나도 남기지 않고 매번 다섯 개를 다 다시 굴려요.", 120.0, "keep=NONE"),
        fixed("runonly", "스트레이트 외길", "서로 다른 눈만 남겨서 스트레이트만 노려요.", 136.4, "keep=RUN,like.LARGE_STRAIGHT=10"),
    )

    val OVERLAYS: List<PlayOverlay> = listOf(
        PlayOverlay("gambler", "뒤지면 승부수", "지고 있으면 같은 눈을 모아 요트에 승부를 걸어요."),
        PlayOverlay("locker", "앞서면 잠그기", "이기고 있으면 0점을 피하고 안전하게 운영해요."),
        PlayOverlay("panic", "막판 새가슴", "마지막 네 턴이 되면 일찍 멈추고 실수가 늘어요."),
        PlayOverlay("streaky", "기분파", "잘 풀리면 과감해지고, 꼬이면 소심해져요."),
        PlayOverlay("chameleon", "날마다 다른 사람", "판마다 다른 스타일로 둬요."),
    )

    private val byKey = RECIPES.associateBy { it.key }
    private val overlayByKey = OVERLAYS.associateBy { it.key }

    fun recipe(key: String): PlayRecipe? = byKey[key]
    fun overlay(key: String): PlayOverlay? = overlayByKey[key]

    /** Expected-points lead (or deficit) at which the score overlays switch. */
    const val LEAD = 10.0

    /** A turn worth this many expected points (or a 0 written) changes a streaky player's form. */
    const val SWING = 8.0

    internal val ALL_IN = Style.parse("keep=ONE_FACE,like.YACHT=80,cost=-3")
    internal val SAFE = Style.parse("box=NO_SCRATCH,zeroPen=6,cost=5")
    internal val SHAKY = Style.parse("cost=6")
    internal val HOT = Style.parse("like.YACHT=40,cost=-3")
    internal val COLD = Style.parse("cost=5,zeroPen=8")

    /** Minimum slip of a panicking player in its last turns. */
    const val PANIC_SLIP = 0.2

}

/**
 * A way of leaning for a while (a light situational gene of [PlayGene.V2]): a small change to the
 * style, much milder than an overlay. Each changes a few percent of the decisions for a point or so.
 */
enum class Lean(val code: Char, val label: String) {
    /** Avoids a 0 and stops throwing sooner. */
    SAFE('S', "안전하게 둬요"),

    /** Goes for the Yacht and throws more. */
    BOLD('B', "과감해져요"),

    /** Counts less of the rest of the game. */
    NEAR('N', "눈앞의 점수를 챙겨요"),

    /** Counts more of the rest of the game. */
    FAR('F', "멀리 내다봐요"),
    ;

    /** [s] leaning this way. */
    fun apply(s: Style): Style = when (this) {
        SAFE -> s.copy(zero = s.zero + 6, rerollCost = s.rerollCost + 1.5)
        BOLD -> s.copy(like = s.like + (Category.YACHT to (s.like[Category.YACHT] ?: 0.0) + 25), rerollCost = s.rerollCost - 2)
        NEAR -> s.copy(future = s.future * 0.85)
        FAR -> s.copy(future = s.future * 1.12)
    }

    companion object {
        fun of(c: Char): Lean? = entries.firstOrNull { it.code == c }
    }
}

/**
 * A player's own tastes on top of its style (a [PlayGene.V2] gene), all in points and each zero
 * when absent: liking a made Full House, Four of a Kind, Yacht or straight (negative = avoiding
 * it), Choice written ([choice]: positive = spent early, negative = saved), [snob] (an upper box
 * with fewer than three of its face), a favourite [face] (1..6, three or more of it in its box
 * worth [faceBias]), [bonus] per upper point, [zero] (any 0 written), a fill-order habit ([fill],
 * an index of [Style.FILL_ORDERS], worth [fillBias]), a sacrifice box ([scrap], a [Category]
 * ordinal, its 0 worth [scrapBias]) and [cost] per extra throw. Saved as `tag:value` parts of the gene.
 */
data class Taste(
    val fullHouse: Double = 0.0,
    val fourKind: Double = 0.0,
    val choice: Double = 0.0,
    val snob: Double = 0.0,
    val face: Int = 0,
    val faceBias: Double = 0.0,
    val yacht: Double = 0.0,
    val straight: Double = 0.0,
    val bonus: Double = 0.0,
    val zero: Double = 0.0,
    val fill: Int = -1,
    val fillBias: Double = 0.0,
    val scrap: Int = -1,
    val scrapBias: Double = 0.0,
    val cost: Double = 0.0,
) {
    val isEmpty: Boolean get() = this == NONE

    /** [s] with these tastes added. */
    fun apply(s: Style): Style {
        if (isEmpty) return s
        val like = HashMap(s.like)
        fun add(c: Category, v: Double) { if (v != 0.0) like[c] = (like[c] ?: 0.0) + v }
        add(Category.FULL_HOUSE, fullHouse)
        add(Category.FOUR_OF_A_KIND, fourKind)
        add(Category.CHOICE, choice)
        add(Category.YACHT, yacht)
        add(Category.SMALL_STRAIGHT, straight)
        add(Category.LARGE_STRAIGHT, straight)
        val three = if (face in 1..6 && faceBias != 0.0) List(6) { (s.three.getOrNull(it) ?: 0.0) + if (it == face - 1) faceBias else 0.0 } else s.three
        val scrapped = if (scrap in 0 until Category.COUNT && scrapBias != 0.0) {
            val c = Category.of(scrap)
            s.scrap + (c to (s.scrap[c] ?: 0.0) + scrapBias)
        } else s.scrap
        val habit = fill in Style.FILL_ORDERS.indices && fillBias != 0.0 && s.fill < 0
        return s.copy(
            like = like, snob = s.snob + snob, three = three, bonus = s.bonus + bonus, zero = s.zero + zero,
            fill = if (habit) fill else s.fill, fillBias = if (habit) fillBias else s.fillBias, scrap = scrapped, rerollCost = s.rerollCost + cost,
        )
    }

    /** The tastes as gene parts ("fh:5", "fv:5x4", ...), in a fixed order. */
    fun parts(): List<String> = buildList {
        fun num(tag: String, v: Double) { if (v != 0.0) add("$tag:${short(v)}") }
        num("fh", fullHouse)
        num("fk", fourKind)
        num("ch", choice)
        num("sn", snob)
        if (face in 1..6 && faceBias != 0.0) add("fv:${face}x${short(faceBias)}")
        num("y", yacht)
        num("st", straight)
        num("b", bonus)
        num("z", zero)
        if (fill >= 0 && fillBias != 0.0) add("fo:${fill}x${short(fillBias)}")
        if (scrap >= 0 && scrapBias != 0.0) add("sx:${scrap}x${short(scrapBias)}")
        num("tc", cost)
    }

    /** One line (해요체) per taste, for the player's page. */
    fun notes(): List<String> = buildList {
        if (choice > 0) add("애매하면 초이스를 일찍 쓰는 편이에요.") else if (choice < 0) add("초이스는 되도록 아껴 둬요.")
        if (fullHouse > 0) add("풀하우스를 좋아해요.") else if (fullHouse < 0) add("풀하우스는 잘 노리지 않아요.")
        if (fourKind > 0) add("포카드를 좋아해요.") else if (fourKind < 0) add("포카드는 잘 노리지 않아요.")
        if (yacht > 0) add("요트를 노리는 편이에요.") else if (yacht < 0) add("요트는 운이라며 덜 노려요.")
        if (straight > 0) add("스트레이트를 좋아해요.") else if (straight < 0) add("스트레이트는 잘 노리지 않아요.")
        if (snob > 0) add("위쪽 칸은 같은 눈 세 개 이상으로 채우고 싶어 해요.")
        if (face in 1..6 && faceBias > 0) add("$face${if (face == 2 || face == 4 || face == 5) "를" else "을"} 좋아해서 $face 칸은 세 개 이상으로 채우려 해요.")
        if (bonus > 0) add("위쪽 칸 점수를 꼬박꼬박 챙겨요.")
        if (zero > 0) add("0점 쓰는 걸 싫어해요.")
        if (fill in Style.FILL_ORDERS.indices && fillBias > 0) add(FILL_NOTES[fill])
        if (scrap in 0 until Category.COUNT && scrapBias > 0) add("버릴 칸이 필요하면 ${BOX_NAMES[scrap]} 칸부터 지워요.")
        if (cost < 0) add("한 번 더 굴리는 걸 좋아해요.") else if (cost > 0) add("웬만하면 일찍 멈춰요.")
    }

    companion object {
        val NONE = Taste()

        /** [x] to the hundredth, without a trailing ".0" ("5", "2.5", "0.15"). */
        private fun short(x: Double): String {
            val r = PlayerGenerator.round(x, 2)
            return if (r == Math.rint(r)) r.toLong().toString() else r.toString()
        }

        private val FILL_NOTES = listOf(
            "1번 칸부터 채우는 버릇이 있어요.", "요트 칸부터 거꾸로 채우는 버릇이 있어요.",
            "아래쪽 족보 칸부터 채우는 버릇이 있어요.", "6번 칸부터 거꾸로 채우는 버릇이 있어요.",
        )
        private val BOX_NAMES = listOf("1", "2", "3", "4", "5", "6", "초이스", "포카드", "풀하우스", "스몰 스트레이트", "라지 스트레이트", "요트")

        /** [t] with the taste of gene part [tag]:[v] (an unknown tag leaves it as it is). */
        fun read(t: Taste, tag: String, v: String): Taste? {
            fun pair(): Pair<Int, Double> = v.split('x', limit = 2).let { (a, b) -> a.toInt() to b.toDouble() }
            return when (tag) {
                "fh" -> t.copy(fullHouse = v.toDouble())
                "fk" -> t.copy(fourKind = v.toDouble())
                "ch" -> t.copy(choice = v.toDouble())
                "sn" -> t.copy(snob = v.toDouble())
                "fv" -> pair().let { (f, x) -> t.copy(face = f.coerceIn(0, 6), faceBias = x) }
                "y" -> t.copy(yacht = v.toDouble())
                "st" -> t.copy(straight = v.toDouble())
                "b" -> t.copy(bonus = v.toDouble())
                "z" -> t.copy(zero = v.toDouble())
                "fo" -> pair().let { (o, x) -> t.copy(fill = o, fillBias = x) }
                "sx" -> pair().let { (c, x) -> t.copy(scrap = c, scrapBias = x) }
                "tc" -> t.copy(cost = v.toDouble())
                else -> null
            }
        }
    }
}

/**
 * A generated player's style genes: a [primary] recipe at intensity [k], a [secondary] mixed
 * in at half strength, an [overlay] (with the [moods] a chameleon switches between, one per
 * game), and [future] (how much of the rest of the game it counts: 예측력). Mistakes and the
 * temper stay in [CustomAiSpec]. Encoded as one space-, '|'- and '='-free... token list joined by '/'.
 *
 * Genes of version [V2] (players generated or born from 생성 다양화 v2 on) also have the
 * rescaled intensities ([PlayRecipe.style]), a foresight to the hundredth that may err on the far
 * side ([far], [Profiles.future2]), their own [taste], a score reading (from [margin] points ahead
 * it leans [ahead], from [margin] behind [behind]) and a late phase (from [lateFrom] boxes left it
 * leans [late]). Older genes keep playing exactly as they did.
 */
data class PlayGene(
    val primary: String,
    val k: Double = 1.0,
    val secondary: String? = null,
    val overlay: String? = null,
    val moods: List<String> = emptyList(),
    val future: Double = 1.0,
    /** 승부 감각, 0..1: the box is chosen by the chance of winning among near-best ones ([StylePlayer]). */
    val sense: Double = 0.0,
    /** The gene's version: 1 as before, [V2] with the parts below. */
    val v: Int = 1,
    /** Errs on the far side: 예측력 short of 99 counts too much of the rest rather than too little. */
    val far: Boolean = false,
    val taste: Taste = Taste.NONE,
    val margin: Int = 0,
    val ahead: Lean? = null,
    val behind: Lean? = null,
    val lateFrom: Int = 0,
    val late: Lean? = null,
) {
    /** "강한 요트 사냥꾼 · +소심한 굴림 · 뒤지면 승부수". */
    val label: String
        get() {
            val p = PlayStyles.recipe(primary)
            val parts = ArrayList<String>()
            parts += (if (p != null && p.scales && k >= 1.3) "강한 " else if (p != null && p.scales && k <= 0.75) "약한 " else "") + (p?.name ?: primary)
            secondary?.let { s -> parts += "+" + (PlayStyles.recipe(s)?.name ?: s) }
            overlay?.let { o -> parts += PlayStyles.overlay(o)?.name ?: o }
            return parts.joinToString(" · ")
        }

    /** Whether it reads the score or plays its last turns its own way. */
    val situational: Boolean get() = (margin > 0 && (ahead != null || behind != null)) || (lateFrom > 0 && late != null)

    /** Plain max expectation: the ev style with no second style, overlay or situation (only its tastes set it apart). */
    val plain: Boolean get() = primary == "ev" && secondary == null && overlay == null && !situational

    /** One line (해요체) per own taste and situation of a [V2] gene (the style and overlay have their own descriptions). */
    fun notes(): List<String> = buildList {
        addAll(taste.notes())
        if (margin > 0) {
            ahead?.let { add("${margin}점 이상 앞서면 ${it.label}.") }
            behind?.let { add("${margin}점 이상 뒤지면 ${it.label}.") }
        }
        if (lateFrom > 0) late?.let { add("남은 칸이 ${lateFrom}개가 되면 ${it.label}.") }
        if (far && future > 1.0) add("남은 판의 점수를 실제보다 크게 쳐요.")
    }

    fun encode(): String = buildString {
        append(primary).append(':').append(PlayerGenerator.round(k, 2))
        secondary?.let { append("/s:").append(it) }
        overlay?.let { append("/o:").append(it) }
        if (moods.isNotEmpty()) append("/m:").append(moods.joinToString("."))
        if (future != 1.0) append("/f:").append(future)
        if (sense != 0.0) append("/c:").append(sense)
        if (v != 1) append("/v:").append(v)
        if (far) append("/fs:1")
        for (part in taste.parts()) append('/').append(part)
        if (margin > 0 && (ahead != null || behind != null)) append("/rd:").append(margin).append(ahead?.code ?: '-').append(behind?.code ?: '-')
        if (lateFrom > 0 && late != null) append("/lt:").append(lateFrom).append(late.code)
    }

    /**
     * The primary style with the secondary's numeric tastes added at half strength (rules stay the
     * primary's), its own tastes, and the foresight.
     */
    fun style(): Style {
        val p = PlayStyles.recipe(primary)?.style(k, v) ?: Style()
        val s = secondary?.let { PlayStyles.recipe(it)?.style(0.5) }
        val mixed = if (s == null) p else {
            val like = HashMap(p.like)
            for ((c, v) in s.like) like[c] = (like[c] ?: 0.0) + v
            val zl = HashMap(p.zeroLike)
            for ((c, v) in s.zeroLike) zl[c] = (zl[c] ?: 0.0) + v
            val hold = List(6) { (p.hold.getOrNull(it) ?: 0.0) + (s.hold.getOrNull(it) ?: 0.0) }
            p.copy(
                like = like, zeroLike = zl, zeroUntil = if (s.zeroLike.isNotEmpty() && p.zeroLike.isEmpty()) s.zeroUntil else p.zeroUntil,
                zero = p.zero + s.zero, bonus = p.bonus + s.bonus, par = p.par + s.par, copy = p.copy + s.copy,
                rerollCost = p.rerollCost + s.rerollCost, hold = if (hold.any { it != 0.0 }) hold else emptyList(),
                bonusLove = p.bonusLove + s.bonusLove, choiceMin = maxOf(p.choiceMin, s.choiceMin),
                keep = if (p.keep == KeepRule.ANY && s.keep == KeepRule.TWO_FACE) s.keep else p.keep,
                box = if (p.box == BoxRule.ANY && s.box == BoxRule.NO_SCRATCH) s.box else p.box,
            )
        }
        val own = taste.apply(mixed)
        return if (future == 1.0) own else own.copy(future = future)
    }

    /**
     * The per-turn picker for one game: the overlay's style for the situation, else the style as
     * the score and the phase of the game lean it, with mistakes [slip] / [tau] at the temper's
     * level ([temper] at [strength], from the game's [rng]).
     */
    fun picker(ev: EvTable, rng: Random, temper: Temper, strength: Double, slip: Double, tau: Double): ModePicker {
        val main = style()
        val mood = Tempers.mood(temper, ev, rng, strength)
        // A chameleon's style of the day keeps its own tastes (genes before v2 have none).
        val game = if (moods.isNotEmpty()) (PlayStyles.recipe(moods[rng.nextInt(moods.size)])?.style(1.0)?.let { taste.apply(it) } ?: main).copy(future = main.future) else main
        val chameleon = overlay == "chameleon"
        val own = if (chameleon) game else main
        // Its own style as the score and the phase lean it, built once a game: index 2 × read (ahead 1, behind 2) + late.
        val leaned = arrayOfNulls<Style>(6)
        fun leaning(read: Int, isLate: Boolean): Style {
            val i = 2 * read + (if (isLate) 1 else 0)
            if (i == 0) return own
            leaned[i]?.let { return it }
            var s = own
            if (read == 1) ahead?.let { s = it.apply(s) }
            if (read == 2) behind?.let { s = it.apply(s) }
            if (isLate) late?.let { s = it.apply(s) }
            leaned[i] = s
            return s
        }
        val reads = margin > 0 && (ahead != null || behind != null)
        val phases = lateFrom > 0 && late != null
        return ModePicker { me, opp, m ->
            val s = when (overlay) {
                "gambler" -> if (m.lead(me, opp) <= -PlayStyles.LEAD) PlayStyles.ALL_IN.copy(future = main.future) else main
                "locker" -> if (m.lead(me, opp) >= PlayStyles.LEAD) PlayStyles.SAFE.copy(future = main.future) else main
                "panic" -> if (Category.COUNT - me.filledCount <= 4) PlayStyles.SHAKY.copy(future = main.future) else main
                "streaky" -> {
                    if (m.lastZero || m.lastSwing <= -PlayStyles.SWING) m.heat = -1 else if (m.lastSwing >= PlayStyles.SWING) m.heat = 1
                    when (m.heat) {
                        1 -> PlayStyles.HOT.copy(future = main.future)
                        -1 -> PlayStyles.COLD.copy(future = main.future)
                        else -> main
                    }
                }
                else -> if (chameleon) game else main
            }
            val level = mood.level(me, opp)
            val panicking = overlay == "panic" && s !== main
            val baseSlip = if (panicking) maxOf(PlayStyles.PANIC_SLIP, slip) else slip
            // The light situational genes lean the player's own style (an overlay's takes over).
            val turn = if (s !== own || !(reads || phases)) s else {
                val lead = if (reads) m.lead(me, opp) else 0.0
                val read = if (reads && lead >= margin && ahead != null) 1 else if (reads && lead <= -margin && behind != null) 2 else 0
                leaning(read, phases && Category.COUNT - me.filledCount <= lateFrom)
            }
            if (tau > 0) TurnMode(turn, 0.0, Tempers.feel(tau, level)) else TurnMode(turn, Tempers.slip(baseSlip, level), 0.0)
        }
    }

    companion object {
        /** The gene version of 생성 다양화 v2. */
        const val V2 = 2

        fun decode(text: String): PlayGene? = runCatching {
            val parts = text.split('/')
            val (p, k) = parts[0].split(':', limit = 2)
            var g = PlayGene(p, k.toDouble())
            for (x in parts.drop(1)) {
                val (tag, v) = x.split(':', limit = 2)
                g = when (tag) {
                    "s" -> g.copy(secondary = v)
                    "o" -> g.copy(overlay = v)
                    "m" -> g.copy(moods = v.split('.').filter { it.isNotEmpty() })
                    "f" -> g.copy(future = v.toDouble())
                    "c" -> g.copy(sense = v.toDouble().coerceIn(0.0, 1.0))
                    "v" -> g.copy(v = v.toInt().coerceAtLeast(1))
                    "fs" -> g.copy(far = v == "1")
                    "rd" -> {
                        val digits = v.takeWhile { it.isDigit() }
                        val leans = v.drop(digits.length)
                        g.copy(margin = digits.toInt(), ahead = leans.getOrNull(0)?.let { Lean.of(it) }, behind = leans.getOrNull(1)?.let { Lean.of(it) })
                    }
                    "lt" -> g.copy(lateFrom = v.dropLast(1).toInt(), late = Lean.of(v.last()))
                    else -> Taste.read(g.taste, tag, v)?.let { g.copy(taste = it) } ?: g
                }
            }
            g
        }.getOrNull()
    }
}
