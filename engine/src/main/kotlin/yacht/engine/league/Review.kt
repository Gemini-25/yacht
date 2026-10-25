package yacht.engine.league

import yacht.engine.Category
import yacht.engine.explain.Ko
import yacht.engine.game.label
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** An AI's rating at the start and at the end of a season. */
data class RatingMove(val key: String, val from: Double, val to: Double) {
    val delta: Double get() = to - from
}

/** A change in the league meta, ready to show: [label] in Korean, [percent] for shares (0..1). */
data class MetaChange(val label: String, val before: Double, val after: Double, val percent: Boolean = false) {
    val delta: Double get() = after - before
}

/**
 * The season review (시즌 결산), built when a season ends and shown once: champions and
 * awards, the biggest risers and fallers by rating, records broken, what changed in the league
 * meta, and a few summary sentences in Korean.
 */
data class SeasonReview(
    val season: Int,
    /** Titles and runners-up of every competition, and the regular-season leader. */
    val champions: List<Honour>,
    /** MVP, 신인왕, 기량발전상, 득점왕, 최다 요트상. */
    val awards: List<Honour>,
    val risers: List<RatingMove>,
    val fallers: List<RatingMove>,
    val records: List<RecordBreak>,
    val meta: List<MetaChange>,
    val sentences: List<String>,
) {
    /** "rv" lines for archive.txt. */
    internal fun encode(out: StringBuilder, seen: Boolean) {
        out.append("rv head $season ${if (seen) 1 else 0}\n")
        for (h in champions + awards) out.append("rv h ${h.kind} ${Field.esc(h.key)}\n")
        for ((tag, list) in listOf("up" to risers, "down" to fallers)) {
            for (m in list) out.append("rv $tag ${Field.esc(m.key)} ${Field.num(m.from, 1)} ${Field.num(m.to, 1)}\n")
        }
        for (b in records) out.append("rv rec ${b.encode()}\n")
        for (m in meta) out.append("rv meta ${Field.esc(m.label)} ${Field.num(m.before, 4)} ${Field.num(m.after, 4)} ${if (m.percent) 1 else 0}\n")
        for (s in sentences) out.append("rv say ${s.replace('\n', ' ')}\n")
    }

    companion object {
        /** Reads the "rv" lines back (each line split on spaces, "rv" first); null without a head line. Returns the review and whether it was shown. */
        internal fun decode(lines: List<String>): Pair<SeasonReview, Boolean>? {
            var season: Int? = null
            var seen = false
            val honours = ArrayList<Honour>()
            val up = ArrayList<RatingMove>()
            val down = ArrayList<RatingMove>()
            val records = ArrayList<RecordBreak>()
            val meta = ArrayList<MetaChange>()
            val say = ArrayList<String>()
            for (line in lines) {
                runCatching {
                    val f = line.split(' ')
                    when (f[1]) {
                        "head" -> {
                            season = f[2].toInt()
                            seen = f.getOrNull(3) == "1"
                        }
                        "h" -> honours += Honour(0, f[2], Field.unesc(f[3]))
                        "up", "down" -> (if (f[1] == "up") up else down) += RatingMove(Field.unesc(f[2]), Field.dbl(f[3]), Field.dbl(f[4]))
                        "rec" -> RecordBreak.decode(f, 2)?.let { records += it }
                        "meta" -> meta += MetaChange(Field.unesc(f[2]), Field.dbl(f[3]), Field.dbl(f[4]), f[5] == "1")
                        "say" -> say += line.substringAfter("rv say ")
                    }
                }
            }
            val n = season ?: return null
            val hs = honours.map { it.copy(season = n) }
            return SeasonReview(n, hs.filter { it.kind !in Awards.ANNOUNCED }, hs.filter { it.kind in Awards.ANNOUNCED }, up, down, records, meta, say) to seen
        }

        /**
         * The review of [plan]'s season: [honours] are the season's titles and awards, [keys]
         * the league's AIs, [start] and [rating] their ratings at the start and at the end,
         * [avgRating] their season's average match rating; [meta] and [before] the league meta
         * of this season and the one before.
         */
        internal fun of(
            plan: SeasonPlan,
            honours: List<Honour>,
            keys: List<String>,
            start: (String) -> Double,
            rating: (String) -> Double,
            avgRating: (String) -> Double,
            broken: List<RecordBreak>,
            meta: MetaReport,
            before: MetaReport?,
            family: (String) -> Family?,
            name: (String) -> String,
        ): SeasonReview {
            val n = plan.number
            val champions = honours.filter { Awards.inSummary(it.kind) && it.kind !in Awards.ANNOUNCED }
            val awards = honours.filter { it.kind in Awards.ANNOUNCED }
            val moves = keys.mapNotNull { k ->
                val s = start(k)
                val r = rating(k)
                if (s.isNaN() || r.isNaN()) null else RatingMove(k, s, r)
            }
            val risers = moves.filter { it.delta > 0 }.sortedByDescending { it.delta }.take(MOVES)
            val fallers = moves.filter { it.delta < 0 }.sortedBy { it.delta }.take(MOVES)
            val changes = metaChanges(meta, before)

            fun of(kind: String) = honours.firstOrNull { it.kind == kind }?.key
            val say = ArrayList<String>()
            val champ = of("L0")
            val reg = of("REG")
            if (champ != null) {
                say += "${n}시즌 1부 우승은 ${Ko.ieyo(name(champ))}." + if (reg != null && reg != champ) " 정규리그 1위는 ${Ko.ieosseoyo(name(reg))}." else ""
            }
            val cup = of("CUP")
            val cl = of("CL")
            when {
                champ != null && cup == champ && cl == champ -> say += "${Ko.eun(name(champ))} 리그·요트컵·챔피언스 리그를 모두 휩쓸어 트레블을 달성했어요."
                champ != null && cup == champ -> say += "${Ko.eun(name(champ))} 요트컵까지 들어 올려 2관왕에 올랐어요." +
                    (cl?.let { " 챔피언스 리그는 ${Ko.i(name(it))} 가져갔어요." } ?: "")
                cup != null && cl != null -> say += "요트컵은 ${Ko.i(name(cup))}, 챔피언스 리그는 ${Ko.i(name(cl))} 가져갔어요."
                cup != null -> say += "요트컵은 ${Ko.i(name(cup))} 들어 올렸어요."
                cl != null -> say += "챔피언스 리그 우승은 ${Ko.ieyo(name(cl))}."
            }
            of("MVP")?.let { m ->
                val r = avgRating(m)
                say += if (r.isNaN()) "MVP는 ${Ko.ieyo(name(m))}." else "MVP는 평균 평점 ${fmt(r, 2)}의 ${Ko.ieyo(name(m))}."
            }
            val rookie = of("ROOKIE")
            val mip = of("MIP")
            when {
                rookie != null && mip != null -> say += "신인왕은 ${Ko.i(name(rookie))}, 기량발전상은 ${Ko.i(name(mip))} 받았어요."
                rookie != null -> say += "신인왕은 ${Ko.i(name(rookie))} 받았어요."
                mip != null -> say += "기량발전상은 ${Ko.i(name(mip))} 받았어요."
            }
            fallers.firstOrNull()?.let { f ->
                say += "레이팅이 가장 크게 떨어진 AI는 ${Ko.ieyo("${name(f.key)}(${f.delta.roundToInt()})")}."
            }
            broken.firstOrNull()?.let { b ->
                say += "${Ko.i(name(b.record.holder))} ${b.record.kind.label} 기록(${b.record.kind.text(b.record.value)})을 새로 썼어요."
                if (broken.size > 1) say += "이번 시즌 깨진 리그 기록은 모두 ${broken.size}개예요."
            }
            if (before != null && !meta.avgScore.isNaN() && !before.avgScore.isNaN()) {
                val d = meta.avgScore - before.avgScore
                say += if (abs(d) < 0.1) "리그 평균 점수는 ${fmt(meta.avgScore, 1)}점으로 지난 시즌과 거의 같아요."
                else "리그 평균 점수는 ${fmt(meta.avgScore, 1)}점으로 지난 시즌보다 ${fmt(abs(d), 1)}점 ${if (d > 0) "올랐어요" else "떨어졌어요"}."
            }
            meta.traits.filter { !it.change.isNaN() && abs(it.change) >= TRAIT_NEWS }.maxByOrNull { abs(it.change) }?.let { t ->
                say += "상위권에서 ${t.trait} ‘${t.value}’ 비율이 ${(abs(t.change) * 100).roundToInt()}%p ${if (t.change > 0) "늘었어요" else "줄었어요"}."
            }
            val top = plan.league.divisions.firstOrNull()?.let { plan.league.table(0).take(5).mapNotNull { s -> family(s.key) } }.orEmpty()
            top.groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let { (fam, count) ->
                say += "1부 상위 5위 중 ${count}자리를 ${fam.label} 계열이 차지했어요."
            }
            return SeasonReview(n, champions, awards, risers, fallers, broken, changes, say)
        }

        /** The league average score, the boxes that moved most and the traits that moved most at the top. */
        private fun metaChanges(meta: MetaReport, before: MetaReport?): List<MetaChange> {
            if (before == null) return emptyList()
            val out = ArrayList<MetaChange>()
            if (!meta.avgScore.isNaN() && !before.avgScore.isNaN()) out += MetaChange("리그 평균 점수", before.avgScore, meta.avgScore)
            Category.ALL.filter { !meta.boxChange(before, it).isNaN() }.sortedByDescending { abs(meta.boxChange(before, it)) }.take(BOXES).forEach { c ->
                val box = if (c.isUpper) "‘${c.face}’" else c.label
                out += MetaChange("$box 평균 점수", before.boxAverage(c), meta.boxAverage(c))
            }
            meta.traits.filter { !it.change.isNaN() }.sortedByDescending { abs(it.change) }.take(TRAITS).forEach { t ->
                out += MetaChange("상위권 ${t.trait} ‘${t.value}’ 비율", t.top - t.change, t.top, percent = true)
            }
            return out
        }

        private fun fmt(x: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", x)

        private const val MOVES = 3
        private const val BOXES = 3
        private const val TRAITS = 2

        /** A trait share change worth a sentence (10%p). */
        private const val TRAIT_NEWS = 0.1
    }
}
