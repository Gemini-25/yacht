package yacht.engine.league

import yacht.engine.Category
import yacht.engine.explain.Ko
import yacht.engine.game.label
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What an AI scores box by box over its games, from its career statistics (the app maps its
 * AiStats into this), or the whole league's when merged with [league]. Rates are per game.
 */
data class BoxProfile(
    /** Average points per box, [Category.ALL] order (12 values). */
    val average: List<Double>,
    /** Share of games each box was written as 0, same order. */
    val zeroRate: List<Double>,
    val games: Int,
    /** Share of games with a Yacht scored. */
    val yachtRate: Double,
    /** Share of games with the upper bonus. */
    val bonusRate: Double,
    /** Share of games won from far behind (the winner's lowest P(win) was small). */
    val comebackRate: Double,
    /** Share of games lost from far ahead. */
    val blownLeadRate: Double,
) {
    init {
        require(average.size == Category.COUNT && zeroRate.size == Category.COUNT) { "a box profile has ${Category.COUNT} boxes" }
    }

    /** Enough games for the box figures to mean something. */
    val reliable: Boolean get() = games >= MIN_GAMES

    /**
     * Every box against [league], most above the league first. The order is by points over the
     * league per game scaled by the square root of the league's average for the box, so a big
     * box (a Yacht is worth 50) does not always come out as the strongest or the weakest.
     */
    fun edges(league: BoxProfile): List<BoxEdge> = Category.ALL.map { c ->
        val i = c.ordinal
        val diff = average[i] - league.average[i]
        BoxEdge(c, diff, zeroRate[i] - league.zeroRate[i], diff / sqrt(maxOf(1.0, league.average[i])))
    }.sortedByDescending { it.score }

    /** The box it gains most on over [league] (at least [MIN_EDGE] points a game), if any. */
    fun weapon(league: BoxProfile): BoxEdge? = edges(league).firstOrNull()?.takeIf { it.diff >= MIN_EDGE }

    /** The box it loses most on against [league] (at least [MIN_EDGE] points a game), if any. */
    fun weakness(league: BoxProfile): BoxEdge? = edges(league).lastOrNull()?.takeIf { it.diff <= -MIN_EDGE }

    companion object {
        /** Games before the box figures are shown. */
        const val MIN_GAMES = 20

        /** Points per game a box must differ from the league by to be called a weapon or a weakness. */
        const val MIN_EDGE = 0.3

        /**
         * From career sums: [boxSums] and [zeros] per box ([Category.ALL] order), games with a
         * Yacht and with the bonus, comebacks and blown leads. Null without games.
         */
        fun of(games: Int, boxSums: LongArray, zeros: IntArray, yachts: Int, bonuses: Int, comebacks: Int, blownLeads: Int): BoxProfile? {
            if (games <= 0 || boxSums.size < Category.COUNT || zeros.size < Category.COUNT) return null
            val g = games.toDouble()
            return BoxProfile(
                List(Category.COUNT) { boxSums[it] / g }, List(Category.COUNT) { zeros[it] / g }, games,
                yachts / g, bonuses / g, comebacks / g, blownLeads / g,
            )
        }

        /** The league's profile: [profiles] weighted by their games. Null when none has games. */
        fun league(profiles: Collection<BoxProfile>): BoxProfile? {
            val with = profiles.filter { it.games > 0 }
            val total = with.sumOf { it.games }
            if (total == 0) return null
            fun mean(f: (BoxProfile) -> Double) = with.sumOf { f(it) * it.games } / total
            return BoxProfile(
                List(Category.COUNT) { i -> mean { it.average[i] } }, List(Category.COUNT) { i -> mean { it.zeroRate[i] } }, total,
                mean { it.yachtRate }, mean { it.bonusRate }, mean { it.comebackRate }, mean { it.blownLeadRate },
            )
        }

        /** A box as the explanations write it: ‘1’ … ‘6’, 초이스, 포카드, …, 요트. */
        fun boxName(c: Category): String = if (c.isUpper) "‘${c.face}’" else c.label
    }
}

/** One box of an AI against the league: points per game above it, the 0-rate above it, and the sort [score]. */
data class BoxEdge(val category: Category, val diff: Double, val zeroDiff: Double, val score: Double)

/**
 * 경기 프리뷰 of an upcoming fixture: who is expected to win, the head-to-head, recent form,
 * each side's 주무기/약점 boxes and what the match decides. [lines] and [stakes] are Korean
 * sentences ready for the screen; the other fields are there for drawing (bars, form dots).
 */
data class MatchPreview(
    val a: String,
    val b: String,
    val ratingA: Double?,
    val ratingB: Double?,
    /** A's chance (Bradley-Terry: expected share, a draw half); null without both ratings. */
    val winA: Double?,
    /** Ladder games between them and A's points in them (a draw half). */
    val h2hGames: Int,
    val h2hPointsA: Double,
    /** This season's last league results, oldest first ('W', 'D', 'L'), at most [Preview.FORM]. */
    val formA: String,
    val formB: String,
    val weaponA: BoxEdge?,
    val weaknessA: BoxEdge?,
    val weaponB: BoxEdge?,
    val weaknessB: BoxEdge?,
    /** Prediction, head-to-head, form and boxes. */
    val lines: List<String>,
    /** The competition and round, the table, and the title, promotion and relegation races. */
    val stakes: List<String>,
)

/** Builds [MatchPreview]s. Pure: every input is a snapshot the caller took under its lock. */
object Preview {
    /** Recent results shown. */
    const val FORM = 5

    /** Probabilities shown as a race: not yet decided either way. */
    private const val OPEN_LOW = 0.03
    private const val OPEN_HIGH = 0.97
    /** The upper tier of a promotion playoff's label ("1·2부 승강전", "한국 1·2부 승강전"). */
    private val UPPER = Regex("(\\d+)·")

    /**
     * The preview of [a] against [b] ([a] first in the first game). Every source may be missing
     * (no ladder yet, a friendly outside the season, no odds computed, too few games for the
     * box figures): the preview says less but stays readable. [name] gives the AIs' names;
     * [fixture] adds its competition and round.
     */
    fun of(
        a: String,
        b: String,
        ladder: Ladder?,
        season: Season?,
        odds: OddsTable?,
        profileA: BoxProfile?,
        profileB: BoxProfile?,
        league: BoxProfile?,
        name: (String) -> String = { it },
        fixture: Fixture? = null,
    ): MatchPreview {
        val na = name(a)
        val nb = name(b)
        val ra = ladder?.rating(a)?.takeIf { !it.isNaN() }
        val rb = ladder?.rating(b)?.takeIf { !it.isNaN() }
        val win = if (ra != null && rb != null) expected(ra, rb) else null
        val (h2hPoints, h2hGames) = ladder?.headToHead(a, b) ?: (0.0 to 0)
        val (formA, formB) = season?.let { form(it, a, b) } ?: ("" to "")
        fun reliable(p: BoxProfile?) = p?.takeIf { it.reliable && league != null }
        val pa = reliable(profileA)
        val pb = reliable(profileB)
        val lines = ArrayList<String>()
        if (win != null) {
            val (x, y) = Ko.pctPair(win, 1 - win)
            lines += "레이팅은 $na ${ra!!.roundToInt()}, ${Ko.ro("$nb ${rb!!.roundToInt()}")} 예상 승률은 $na $x, $nb ${Ko.ieyo(y)}."
        } else if (ladder != null) {
            lines += "아직 레이팅이 정해지지 않아 예상 승률은 계산하지 않았어요."
        }
        if (ladder != null) lines += headToHead(na, nb, h2hPoints, h2hGames)
        if (season != null) {
            for ((n, f) in listOf(na to formA, nb to formB)) lines += formLine(n, f)
        }
        for ((n, p) in listOf(na to profileA, nb to profileB)) boxLine(n, p, league)?.let { lines += it }
        if (lines.isEmpty()) lines += "아직 두 AI의 기록이 없어서 미리 볼 거리가 많지 않아요. 첫 경기를 지켜봐 주세요."
        val stakes = stakes(a, b, na, nb, season, odds, fixture)
        return MatchPreview(
            a, b, ra, rb, win, h2hGames, h2hPoints, formA, formB,
            pa?.weapon(league!!), pa?.weakness(league!!), pb?.weapon(league!!), pb?.weakness(league!!), lines, stakes,
        )
    }

    /** Bradley-Terry: [ra]'s expected share against [rb] on the Elo scale. */
    fun expected(ra: Double, rb: Double): Double = 1.0 / (1.0 + 10.0.pow((rb - ra) / 400.0))

    /** Both AIs' last [FORM] league results this season, oldest first, by matchday order. */
    fun form(season: Season, a: String, b: String): Pair<String, String> {
        val fa = StringBuilder()
        val fb = StringBuilder()
        for (id in 0 until season.fixturesTotal) {
            val f = season.fixture(id)
            if (f.a != a && f.b != a && f.a != b && f.b != b) continue
            val games = season.result(id) ?: continue
            val r = TieRule.match(games, season.format)
            fun mark(side: Int) = when (r * side) {
                1 -> 'W'
                0 -> 'D'
                else -> 'L'
            }
            if (f.a == a) fa.append(mark(1)) else if (f.b == a) fa.append(mark(-1))
            if (f.a == b) fb.append(mark(1)) else if (f.b == b) fb.append(mark(-1))
        }
        return fa.takeLast(FORM).toString() to fb.takeLast(FORM).toString()
    }

    private fun points(x: Double): String = if (x == Math.floor(x)) "${x.toInt()}" else Ko.num(x)

    private fun headToHead(na: String, nb: String, pointsA: Double, games: Int): String {
        // No games kept: never met, or met a few times long ago (the ladder lets those go).
        if (games == 0) return "두 AI의 맞대결 기록이 아직 없어요."
        val pointsB = games - pointsA
        val half = pointsA != Math.floor(pointsA)
        val note = if (half) "(무승부는 반 승으로 쳐요)" else ""
        return when {
            abs(pointsA - pointsB) < 1e-9 -> "맞대결 ${games}판에서 ${points(pointsA)}승씩 주고받아 팽팽해요$note."
            pointsA > pointsB -> "맞대결 ${games}판에서 ${Ko.i(na)} ${points(pointsA)}승 ${points(pointsB)}패로 앞서 있어요$note."
            else -> "맞대결 ${games}판에서 ${Ko.i(nb)} ${points(pointsB)}승 ${points(pointsA)}패로 앞서 있어요$note."
        }
    }

    private fun formLine(n: String, form: String): String {
        if (form.isEmpty()) return "${Ko.eun(n)} 이번 시즌 아직 리그 경기를 치르지 않았어요."
        val w = form.count { it == 'W' }
        val d = form.count { it == 'D' }
        val l = form.count { it == 'L' }
        val record = listOfNotNull(if (w > 0) "${w}승" else null, if (d > 0) "${d}무" else null, if (l > 0) "${l}패" else null).joinToString(" ")
        val last = form.last()
        val streak = form.takeLastWhile { it == last }.length
        val head = "${Ko.eun(n)} 최근 ${form.length}경기에서 ${Ko.eul(record)}"
        return when {
            streak >= 2 && last == 'W' -> "$head 거뒀고, 지금 ${streak}연승 중이에요."
            streak >= 2 && last == 'L' -> "$head 거뒀고, 지금 ${streak}연패에 빠져 있어요."
            else -> "$head 거뒀어요."
        }
    }

    /** 주무기 and 약점 of one side; null when it cannot be told (no league, too few games). */
    private fun boxLine(n: String, p: BoxProfile?, league: BoxProfile?): String? {
        if (league == null) return null
        if (p == null || !p.reliable) return "${Ko.eun(n)} 아직 기록이 적어서 칸별 강약은 더 지켜봐야 해요."
        val weapon = p.weapon(league)
        val weak = p.weakness(league)
        fun w(e: BoxEdge) = Ko.ro(BoxProfile.boxName(e.category))
        return when {
            weapon != null && weak != null ->
                "${n}의 주무기는 ${w(weapon)} 리그 평균보다 판당 ${Ko.num(weapon.diff)}점을 더 따고, 약점인 ${BoxProfile.boxName(weak.category)}에서는 ${Ko.num(-weak.diff)}점을 덜 받아요."
            weapon != null -> "${n}의 주무기는 ${w(weapon)} 리그 평균보다 판당 ${Ko.num(weapon.diff)}점을 더 따요. 뚜렷한 약점은 없어요."
            weak != null -> "${Ko.eun(n)} 뚜렷한 주무기 없이 ${BoxProfile.boxName(weak.category)}에서 리그 평균보다 판당 ${Ko.num(-weak.diff)}점을 덜 받아요."
            else -> "${Ko.eun(n)} 모든 칸에서 리그 평균과 비슷하게 점수를 내요."
        }
    }

    private fun open(p: Double) = p in OPEN_LOW..OPEN_HIGH

    private fun stakes(a: String, b: String, na: String, nb: String, season: Season?, odds: OddsTable?, fixture: Fixture?): List<String> {
        val out = ArrayList<String>()
        fixture?.let { roundLine(it, season)?.let { l -> out += l } }
        if (season != null) {
            val da = season.divisionOf(a)
            val db = season.divisionOf(b)
            if (da != null && da == db) {
                val table = season.table(da)
                val pa = table.indexOfFirst { it.key == a } + 1
                val pb = table.indexOfFirst { it.key == b } + 1
                val sa = season.standings.getValue(a)
                val sb = season.standings.getValue(b)
                if (sa.played + sb.played > 0) {
                    out += "${season.leagues.label(da)} 순위는 $na ${pa}위(승점 ${sa.points}), $nb ${Ko.ieyo("${pb}위(승점 ${sb.points})")}."
                }
            }
        }
        // Odds of another season (computed before a rollover) say nothing about this one.
        val current = odds?.takeIf { season == null || it.season == season.number }
        val oa = current?.get(a)
        val ob = current?.get(b)
        if (oa != null || ob != null) out += races(na, nb, oa, ob, season?.leagues)
        return out
    }

    /**
     * The competition and round of a fixture outside the league. A national league's playoffs
     * name it ("한국 결승" is the 한국 리그's title playoff final, its 1부 the upper side of "한국
     * 1·2부 승강전"), from the label and the [season] it is played in.
     */
    private fun roundLine(f: Fixture, season: Season?): String? {
        val label = f.label.trim()
        val final = Drama.isFinalLabel(label)
        // What a final is named after ("아시아" of "아시아 결승" and "아시아 최종 결승"); empty for "결승".
        val finalOf = if (final) label.removeSuffix(Knockout.GRAND_LABEL).removeSuffix("결승").trim() else ""
        // Double elimination: a loss on the winners' side drops to the losers' side, not out.
        val winnersSide = label.contains("승자조")
        val series = if (f.firstTo > 0) " ${f.firstTo}판을 먼저 이기는 쪽이 올라가요." else ""
        // The national league the playoff is of: its label starts with the league's short name.
        val lg = season?.leagueOf(f.a)?.takeIf { it.national && label.startsWith(LeagueIds.short(it.id) + " ") }
        return when (f.comp) {
            Comp.LEAGUE, Comp.FRIENDLY, Comp.LAB -> null
            Comp.TITLE_PLAYOFF -> {
                val playoff = lg?.let { "${LeagueIds.label(it.id)} 우승 플레이오프" } ?: "우승 플레이오프"
                val round = lg?.let { label.removePrefix(LeagueIds.short(it.id) + " ") } ?: label
                val champion = lg?.let { "${LeagueIds.label(it.id)} 챔피언" } ?: "1부 챔피언"
                when {
                    final -> "$playoff 결승이에요. 이기는 쪽이 ${champion}이 돼요.$series"
                    winnersSide -> "$playoff ${Ko.ieyo(round)}. 지는 쪽은 패자조로 내려가 한 번 더 기회가 있어요.$series"
                    else -> "$playoff ${Ko.ieyo(round.ifEmpty { "4강" })}. 지는 쪽은 우승 도전이 끝나요.$series"
                }
            }
            Comp.PROMOTION_PLAYOFF -> {
                // "1·2부 승강전" ("한국 1·2부 승강전"): the winner plays in the upper of the two next season.
                val upper = UPPER.find(label)?.groupValues?.get(1)?.let { "${it}부" }?.let { t -> lg?.let { "${LeagueIds.short(it.id)} $t" } ?: t } ?: "상위 부"
                "${Ko.ieyo(label.ifEmpty { "승강 플레이오프" })}. 이기는 쪽이 다음 시즌 ${upper}에서 뛰어요."
            }
            Comp.ALL_STAR, Comp.LEGEND -> "${Ko.ieyo(f.comp.label)}. 순위와 상관없는 이벤트 경기예요."
            else -> {
                // A 대륙 챔피언스 section's fixture starts with its confederation ("아시아 A조 1차전"):
                // named after the section ("아시아 챔피언스 A조 1차전", "아시아 챔피언스 결승").
                val section = if (f.comp != Comp.CONT_CHAMPIONS) null else Confed.entries.firstOrNull { label.startsWith(it.label + " ") }
                val title = section?.let { ContinentalChampions.title(it) } ?: f.comp.label
                val round = section?.let { label.removePrefix(it.label + " ") } ?: label
                val what = if (round.isEmpty() || round == title) title else "$title $round"
                when {
                    f.kind != JobKind.TIE -> "${Ko.ieyo(what)}."
                    section != null && final -> "$title 결승이에요. 이기는 쪽이 우승해요.$series"
                    final -> "${f.comp.label} ${if (finalOf.isEmpty()) "" else "$finalOf "}결승이에요. 이기는 쪽이 우승해요.$series"
                    what == title -> "${Ko.ieyo(what)}. 이기는 쪽이 우승해요.$series"
                    winnersSide -> "${Ko.ieyo(what)}. 지는 쪽은 패자조로 내려가요.$series"
                    else -> "${Ko.ieyo(what)}. 지는 쪽은 바로 탈락해요.$series"
                }
            }
        }
    }

    /**
     * Title, playoff, champions league (a national league's: its international places), promotion
     * and relegation races still open for either side, each named after its league ([leagues]).
     */
    private fun races(na: String, nb: String, oa: AiOdds?, ob: AiOdds?, leagues: Leagues?): List<String> {
        val out = ArrayList<String>()
        fun pct(p: Double) = Ko.pct(p)
        fun both(label: String, intro: String, f: (AiOdds) -> Double): Boolean {
            if (oa == null || ob == null || oa.division != ob.division) return false
            val x = f(oa)
            val y = f(ob)
            if (!open(x) || !open(y) || x + y < 0.2) return false
            val (px, py) = Ko.pctPair(x, y)
            out += "$intro $label 확률은 $na $px, $nb ${Ko.ieyo(py)}."
            return true
        }
        fun single(o: AiOdds?, n: String, label: String, f: (AiOdds) -> Double) {
            val x = o?.let(f) ?: return
            if (open(x)) out += "${n}의 $label 확률은 ${Ko.ieyo(pct(x))}."
        }
        // The national league of a side's odds, when the season has it.
        fun league(o: AiOdds?): LeagueDef? = o?.takeIf { leagues != null && it.division < leagues.size }?.let { leagues!!.of(it.division) }?.takeIf { it.national }
        // Each side's own division title (a cup tie may bring two divisions together): "2부 우승";
        // a national league's "한국 리그 우승", "한국 2부 우승".
        fun titleLabel(o: AiOdds?): String {
            league(o)?.let { lg -> return if (o!!.tier == 0) "${LeagueIds.label(lg.id)} 우승" else "${leagues!!.label(o.division)} 우승" }
            return o?.division?.takeIf { it > 0 }?.let { d -> (leagues?.takeIf { d < it.size }?.label(d) ?: "${d + 1}부") + " 우승" } ?: "우승"
        }
        if (!both(titleLabel(oa ?: ob), "우승을 다투는 맞대결이에요.") { it.title }) {
            single(oa, na, titleLabel(oa)) { it.title }
            single(ob, nb, titleLabel(ob)) { it.title }
        }
        for ((o, n) in listOf(oa to na, ob to nb)) {
            if (o == null || o.tier != 0 || o.playoff <= 0.0) continue
            val top = league(o)?.let { "${LeagueIds.short(it.id)} 1부" } ?: "1부"
            if (open(o.playoff)) out += "${Ko.i(n)} 우승 플레이오프($top 4위 안)에 나갈 확률은 ${Ko.ieyo(pct(o.playoff))}."
        }
        if (!both("승격", "승격을 다투는 맞대결이에요.") { it.promotion }) {
            single(oa, na, "승격") { it.promotion }
            single(ob, nb, "승격") { it.promotion }
        }
        if (!both("강등", "강등을 피하려는 두 AI의 맞대결이에요.") { it.relegation }) {
            single(oa, na, "강등") { it.relegation }
            single(ob, nb, "강등") { it.relegation }
        }
        for ((o, n) in listOf(oa to na, ob to nb)) {
            if (o == null) continue
            val spot = o.promotionPlayoff + o.relegationPlayoff
            if (spot >= 0.1 && spot <= OPEN_HIGH) out += "${Ko.i(n)} 승강 플레이오프로 갈 확률은 ${Ko.ieyo(pct(spot))}."
            // A national league's places: its 대륙 챔피언스 places, or the champions league straight away.
            val stage = if (league(o) != null) "국제 무대" else "챔피언스 리그"
            if (o.champions in 0.05..0.95) out += "${n}의 다음 시즌 $stage 진출 확률은 ${Ko.ieyo(pct(o.champions))}."
        }
        return out
    }
}
