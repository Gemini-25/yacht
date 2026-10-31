package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Locale
import java.util.Random
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * The competition rules the 요트협회 can change: the only [LeagueConfig] keys it ever touches
 * ([key] as [LeagueConfig.encode] writes it). The match format, the population keys and national
 * leagues are not among them, and the dice scoring rules are not part of [LeagueConfig] at all.
 */
enum class Lever(val key: String, val label: String) {
    AUTO_SWAP("autoSwap", "자동 승강 인원"),
    TITLE_PO("titlePlayoff", "우승 플레이오프"),
    SWEEP("sweepBonus", "2:0 완승 보너스"),
    DIVISION_SIZE("divisionSize", "부 인원"),
    LONG_KO("longKnockouts", "긴 토너먼트"),
    CL_DIRECT("clDirect", "챔피언스 리그 직행 리그 수"),
}

/**
 * What a proposal (안건) changes, one direction of one [lever] each. A kind is tabled when its
 * statistic x ([FederationStats]) is past its [floor]: its pressure is 0.3 there and 1.0 at its
 * [threshold] ([Federation.pressure]; a kind whose threshold is below its floor fires as x falls).
 * [structureFree] kinds read statistics a legacy season has too, so they are the only ones of the
 * first assembly, at the move to national leagues.
 */
enum class ProposalKind(val lever: Lever, val floor: Double, val threshold: Double, val structureFree: Boolean = false) {
    /** Many promoted players go straight back down (elevator): autoSwap - 1. */
    SWAP_DOWN(Lever.AUTO_SWAP, 0.35, 0.50),

    /** Few go back down and the 2부 champions outscore the 1부's middle: autoSwap + 1. */
    SWAP_UP(Lever.AUTO_SWAP, 0.30, 0.20),

    /** The regular-season leader often loses the title playoff (leaderLost): off. */
    TITLE_PO_OFF(Lever.TITLE_PO, 0.40, 0.60),

    /** Without a playoff the leader often runs away with the title (runaway): on. */
    TITLE_PO_ON(Lever.TITLE_PO, 0.25, 0.40),

    /** Most decided matches are 2-0 sweeps anyway (sweep): the bonus off. */
    SWEEP_OFF(Lever.SWEEP, 0.60, 0.70, true),

    /** Few are: the bonus on. */
    SWEEP_ON(Lever.SWEEP, 0.52, 0.45, true),

    /** The league stage takes many weeks (rounds R): divisionSize - 2. */
    SIZE_DOWN(Lever.DIVISION_SIZE, 46.0, 48.0),

    /** It takes few, and bigger divisions would cost no more than the old pyramid: divisionSize + 2. */
    SIZE_UP(Lever.DIVISION_SIZE, 36.0, 34.0),

    /** The 요트컵's last 8 on are often won by the much lower rated side (koUpsets): long knockouts on. */
    LONG_KO_ON(Lever.LONG_KO, 0.30, 0.40, true),

    /** They seldom are: off. */
    LONG_KO_OFF(Lever.LONG_KO, 0.15, 0.10, true),

    /** League champions do better in the 챔피언스 리그 than the 대륙 챔피언스' finalists (clEdge): clDirect + 1. */
    CL_DIRECT_UP(Lever.CL_DIRECT, 0.15, 0.30),

    /** They do worse: clDirect - 1. */
    CL_DIRECT_DOWN(Lever.CL_DIRECT, -0.15, -0.30),
}

/**
 * A proposal (안건) of the assembly after season [season] (P(season)): number [number] (1..3)
 * changes [kind]'s lever [from] → [to] (values as [LeagueConfig.encode] writes them, against the
 * rules of season [season] + 1). It is voted on during season [season] + 1 (투표 중) and decided at
 * its close; an adopted one applies from season [appliesFrom]. [args] are the evidence's numbers
 * ([evidence]). [choice] is the user's vote ([NONE], [YES] 찬성, [NO] 반대); [result] is [NONE]
 * while pending, then [YES] 가결, [NO] 부결 or [VOID] 무효; [by] says who decided ([USER], [COMMITTEE]),
 * [yes] and [no] are the committee's votes.
 */
data class Proposal(
    val season: Int,
    val number: Int,
    val kind: ProposalKind,
    val from: String,
    val to: String,
    val pressure: Double,
    val args: List<String>,
    val choice: Char = NONE,
    val result: Char = NONE,
    val by: Char = NONE,
    val yes: Int = 0,
    val no: Int = 0,
) {
    val lever: Lever get() = kind.lever

    /** Not decided yet (투표 중). */
    val pending: Boolean get() = result == NONE

    /** Adopted (가결): its change is due to apply. */
    val adopted: Boolean get() = result == YES

    /** The season an adopted change applies from (the one after the vote's). */
    val appliesFrom: Int get() = season + 2

    /** The 안건 title: "자동 승강 인원 2명 → 1명", "우승 플레이오프 없애기". */
    val title: String
        get() = when (kind) {
            ProposalKind.SWAP_DOWN, ProposalKind.SWAP_UP -> "자동 승강 인원 ${from}명 → ${to}명"
            ProposalKind.TITLE_PO_OFF -> "우승 플레이오프 없애기"
            ProposalKind.TITLE_PO_ON -> "우승 플레이오프 다시 열기"
            ProposalKind.SWEEP_OFF -> "2:0 완승 보너스 없애기"
            ProposalKind.SWEEP_ON -> "2:0 완승 보너스 다시 주기"
            ProposalKind.SIZE_DOWN, ProposalKind.SIZE_UP -> "부 인원 ${from}명 → ${to}명"
            ProposalKind.LONG_KO_ON -> "긴 토너먼트 켜기"
            ProposalKind.LONG_KO_OFF -> "긴 토너먼트 끄기"
            ProposalKind.CL_DIRECT_UP, ProposalKind.CL_DIRECT_DOWN -> "챔피언스 리그 직행 ${from}개 리그 → ${to}개"
        }

    /** The 근거: what the season showed, with its real numbers, and what the change is for. */
    val evidence: String
        get() {
            fun a(i: Int) = args.getOrNull(i) ?: "?"
            return when (kind) {
                ProposalKind.SWAP_DOWN -> "1부로 올라온 ${a(0)}명 가운데 ${a(1)}명(${a(2)}%)이 한 시즌 만에 다시 내려갔어요. 승강 인원을 줄여 1부를 안정시켜요."
                ProposalKind.SWAP_UP -> (if (a(3) == "0.00") "2부 우승자들의 경기당 승점이 1부 중간 순위와 같았고, " else "2부 우승자들의 경기당 승점이 1부 중간 순위보다 ${a(3)}점 높았고, ") +
                    (if (a(1) == "0") "1부로 올라온 선수는 한 명도 다시 내려가지 않았어요." else "1부로 올라온 선수 가운데 ${a(2)}%만 다시 내려갔어요.") +
                    " 실력 있는 선수가 더 빨리 올라오게 해요."
                ProposalKind.TITLE_PO_OFF -> "${a(0)}개 리그 가운데 ${a(1)}곳에서 정규 리그 1위가 플레이오프에서 우승을 놓쳤어요. 시즌 내내 1위를 지킨 선수가 우승하게 해요."
                ProposalKind.TITLE_PO_ON -> "${a(0)}개 리그 가운데 ${a(1)}곳에서 1위가 2위를 승점 8점 이상 앞서 일찍 우승을 굳혔어요. 마지막까지 긴장감을 주려고 상위 4명 플레이오프를 열어요."
                ProposalKind.SWEEP_OFF -> "결판난 리그 경기의 ${a(2)}%가 2:0 완승이라 보너스가 순위를 거의 바꾸지 못했어요. 보너스를 없애 승점 계산을 단순하게 해요."
                ProposalKind.SWEEP_ON -> "결판난 리그 경기 가운데 2:0 완승이 ${a(2)}%뿐이에요. 완승에 승점 1을 더 줘서 확실한 승리를 가려요."
                ProposalKind.SIZE_DOWN -> "리그 경기만 ${a(0)}주나 걸려요. 부를 줄여 시즌을 짧게 해요."
                ProposalKind.SIZE_UP -> "리그 경기가 ${a(0)}주면 끝나요. 부를 키워 더 많은 상대와 겨루게 해요."
                ProposalKind.LONG_KO_ON -> "요트컵 8강부터 ${a(0)}경기 가운데 ${a(1)}경기(${a(2)}%)에서 레이팅이 100점 이상 낮은 쪽이 이겼어요. 시리즈를 늘려 실력이 드러나게 해요."
                ProposalKind.LONG_KO_OFF ->
                    (if (a(1) == "0") "요트컵 8강부터 ${a(0)}경기 동안 이변이 한 번도 없었어요." else "요트컵 8강부터 ${a(0)}경기 가운데 이변은 ${a(1)}경기(${a(2)}%)뿐이에요.") +
                        " 시리즈를 줄여 시즌을 가볍게 해요."
                ProposalKind.CL_DIRECT_UP -> "랭킹 상위 리그 우승자들이 대륙 챔피언스를 거쳐 온 선수들보다 챔피언스 리그에서 경기당 승점을 ${unsigned(a(0))}점 더 땄어요. 직행 리그를 늘려요."
                ProposalKind.CL_DIRECT_DOWN -> "대륙 챔피언스를 거쳐 온 선수들이 직행한 리그 우승자들보다 챔피언스 리그에서 경기당 승점을 ${unsigned(a(0))}점 더 땄어요. 직행 리그를 줄여 다른 리그 선수에게도 자리를 나눠요."
            }
        }

    companion object {
        /** No choice, not decided, nobody. */
        const val NONE = '-'

        /** 찬성 (a choice) or 가결 (a result). */
        const val YES = 'A'

        /** 반대 or 부결. */
        const val NO = 'R'

        /** 무효: adopted, but its rule had already changed when it came to apply ([Federation.applyTo]). */
        const val VOID = 'V'

        /** Decided by the user's choice, or by the committee's vote. */
        const val USER = 'U'
        const val COMMITTEE = 'C'

        private fun unsigned(s: String) = s.removePrefix("-")
    }
}

/**
 * What the 요트협회 reads from a closed season ([of]), as raw counts so its file keeps the
 * evidence: the elevator ([promoted] last season into a 1부, [bounced] straight back down), the
 * 2부 champions' edge ([champPpm]), title playoffs lost by the leader, runaway leaders, 2-0 sweeps,
 * the league weeks and fixtures, 요트컵 upsets from the last 8 on, and the 챔피언스 리그 points
 * per match of league champions against the 대륙 챔피언스' finalists; and [levers], the lever
 * values the season was played with (the "changed recently" rule of [Federation.assemble]).
 */
data class FederationStats(
    val season: Int,
    /** A season of national leagues. */
    val national: Boolean,
    /** Members promoted into a 1부 at the end of the season before who played in a 1부 this season. */
    val promoted: Int,
    /** Of those, the ones relegated from it at this season's end. */
    val bounced: Int,
    /**
     * Over the leagues with a 2부: its champion's league points per match minus the median of its
     * 1부's, averaged (3 decimals); null without such a league.
     */
    val champPpm: Double?,
    /** Leagues that held a title playoff, and those whose regular-season leader did not win it. */
    val playoffLeagues: Int,
    val leaderLost: Int,
    /** Leagues whose 1부 has the 4 a title playoff needs, and those whose leader finished [RUNAWAY]+ points clear. */
    val leagues: Int,
    val runaway: Int,
    /** Decided league matches of two games (판 승점제) and the 2-0 sweeps among them. */
    val decided: Int,
    val sweeps: Int,
    /** League weeks of the season (R). */
    val rounds: Int,
    /**
     * League fixtures national leagues of this season's sizes would have at the next rules'
     * divisionSize + 2, and the old regional pyramid's ([LeagueConfig] defaults) at as many members.
     */
    val projected: Int,
    val legacy: Int,
    /** 요트컵 ties from the last 8 on with both sides rated, and those won by the side rated [UPSET_GAP]+ lower. */
    val koTies: Int,
    val koUpsets: Int,
    /** 챔피언스 리그 league points (3/1/0 a group match, 3 a knockout tie won) and matches of the entrants who came as league champions. */
    val champPoints: Int,
    val champMatches: Int,
    /** The same for the entrants who came from the 대륙 챔피언스 (its champions and runners-up). */
    val cclPoints: Int,
    val cclMatches: Int,
    /** The [Lever] values the season was played with, in [Lever] order. */
    val levers: List<String>,
    /**
     * Members promoted into a 1부 at this season's end: the next season's [promoted] (kept as
     * federation.txt's `up` line, not in the `fs` line). None after a legacy season: the first
     * national 1부s are formed, nobody is promoted into them.
     */
    val up: List<String> = emptyList(),
) {
    /** Share of [promoted] that went straight back down. */
    val elevator: Double get() = share(bounced, promoted)
    val leaderLostShare: Double get() = share(leaderLost, playoffLeagues)
    val runawayShare: Double get() = share(runaway, leagues)
    val sweepShare: Double get() = share(sweeps, decided)
    val upsetShare: Double get() = share(koUpsets, koTies)

    /** League champions' 챔피언스 리그 points per match minus the 대륙 챔피언스 finalists'. */
    val clEdge: Double get() = ppm(champPoints, champMatches) - ppm(cclPoints, cclMatches)

    /** The `fs` line's fields: "elevator=31/56,champPpm=0.320,...,lv=2/1/1/20/1/7". */
    fun encode(): String = listOf(
        "national=${if (national) 1 else 0}", "elevator=$bounced/$promoted", "champPpm=${champPpm?.let { fmt3(it) } ?: "-"}",
        "leaderLost=$leaderLost/$playoffLeagues", "runaway=$runaway/$leagues", "sweep=$sweeps/$decided", "rounds=$rounds",
        "projected=$projected", "legacy=$legacy", "koUpsets=$koUpsets/$koTies", "clChamp=$champPoints/$champMatches",
        "clCcl=$cclPoints/$cclMatches", "lv=${levers.joinToString("/")}",
    ).joinToString(",")

    companion object {
        /** Points by which a 1부 leader is a runaway. */
        const val RUNAWAY = 8

        /** Rating gap of a cup upset. */
        const val UPSET_GAP = 100.0

        /** Members of a 1부 a title playoff needs. */
        const val PLAYOFF = 4

        private fun share(k: Int, n: Int) = if (n <= 0) 0.0 else k.toDouble() / n
        internal fun ppm(points: Int, matches: Int) = if (matches <= 0) 0.0 else points.toDouble() / matches
        private fun fmt3(x: Double) = String.format(Locale.ROOT, "%.3f", x)
        private fun round3(x: Double) = Math.round(x * 1000) / 1000.0

        /**
         * The statistics of closed season [plan] (its post-season played), read at the next open:
         * [honours] holds at least this season's and the season before's ([HonourBook.all]),
         * [federation] last season's promoted players (its `up` line), [config] the rules of the
         * season to come (for [projected]); [gone] are the players who left during the season (as
         * [Offseason.moves] takes them). Pure; O(league fixtures + cup ties).
         */
        fun of(plan: SeasonPlan, honours: List<Honour>, federation: Federation, config: LeagueConfig, gone: Set<String> = emptySet()): FederationStats {
            val n = plan.number
            val league = plan.league
            val leagues = league.leagues
            val moves = Offseason.moves(plan, gone)
            // The elevator: last season's promoted who played in a 1부, and those of them going down from it now.
            val down = moves.filter { it.from == 0 && it.to > 0 }.map { it.key }.toHashSet()
            val lastUp = federation.promoted(n - 1).orEmpty().filter { k -> league.divisionOf(k)?.let { leagues.tier(it) } == 0 }
            // A legacy season's top tier is no national 1부: the next season's are formed, not promoted into.
            val up = if (!leagues.national) emptyList() else moves.filter { it.to == 0 && it.from > 0 }.map { it.key }.distinct()
            // The 2부 champions' points per match over the 1부's median, per league with a 2부.
            val edges = ArrayList<Double>()
            for (lg in leagues.list) {
                if (lg.tiers.count < 2) continue
                val top = league.table(lg.top).filter { it.played > 0 }.map { it.points.toDouble() / it.played }
                val champions = lg.divisions.filter { leagues.tier(it) == 1 }.mapNotNull { d -> league.table(d).firstOrNull()?.takeIf { it.played > 0 } }
                if (top.isEmpty() || champions.isEmpty()) continue
                edges += champions.map { it.points.toDouble() / it.played }.average() - median(top)
            }
            // Title playoffs and runaway leaders, per league.
            val now = honours.filter { it.season == n }
            fun holder(kind: String) = now.firstOrNull { it.kind == kind }?.key
            var playoffs = 0
            var lost = 0
            var withTop = 0
            var runaway = 0
            for (lg in leagues.list) {
                if (holder(HonourKind.of("L0R", lg.id)) != null) {
                    playoffs++
                    val leader = holder(HonourKind.of("REG", lg.id))
                    val champion = holder(HonourKind.of("L0", lg.id))
                    if (leader != null && champion != null && leader != champion) lost++
                }
                val table = league.table(lg.top)
                if (table.size < PLAYOFF) continue
                withTop++
                if (table[0].points - table[1].points >= RUNAWAY) runaway++
            }
            // 2-0 sweeps among the decided league matches (two games on the same dice only).
            var decided = 0
            var sweeps = 0
            if (league.format.sweepBonus) {
                for (id in 0 until league.fixturesTotal) {
                    val g = league.result(id) ?: continue
                    val r = TieRule.match(g, league.format)
                    if (r == 0) continue
                    decided++
                    if (if (r > 0) TieRule.sweptByA(g) else TieRule.sweptByB(g)) sweeps++
                }
            }
            val (koTies, koUpsets) = cupUpsets(plan)
            val cl = clPoints(plan, honours)
            return FederationStats(
                n, leagues.national, lastUp.size, lastUp.count { it in down }, edges.takeIf { it.isNotEmpty() }?.let { round3(it.average()) },
                playoffs, lost, withTop, runaway, decided, sweeps, league.matchdays, projected(plan, config), legacy(league.divisions.sumOf { it.size }),
                koTies, koUpsets, cl[0], cl[1], cl[2], cl[3], Lever.entries.map { plan.config.valueOf(it.key) ?: "-" }, up,
            )
        }

        /** National league fixtures of [plan]'s league sizes at [config]'s divisionSize + 2 (its round robin). */
        private fun projected(plan: SeasonPlan, config: LeagueConfig): Int {
            val size = config.divisionSize + 2
            val legs = if (config.doubleRoundRobin) 2 else 1
            return plan.league.leagues.list.sumOf { lg ->
                val n = lg.divisions.sumOf { plan.league.divisions[it].size }
                NationalLeagues.sizes(n, NationalLeagues.tierCount(n, size)).sumOf { s -> legs * s * (s - 1) / 2 }
            }
        }

        /** The old league's fixtures for [n] members under the defaults: the regional pyramid, or its divisions, one round robin. */
        internal fun legacy(n: Int): Int {
            val c = LeagueConfig()
            val shape = Pyramid.shape(n, c)
            val sizes = if (shape != null) Pyramid.sizes(n, shape) else {
                val count = c.divisions.coerceIn(1, maxOf(1, n / 2))
                List(count) { d -> n / count + if (d < n % count) 1 else 0 }
            }
            val legs = if (c.doubleRoundRobin) 2 else 1
            return sizes.sumOf { s -> legs * s * (s - 1) / 2 }
        }

        /** The 요트컵's played ties from the last 8 on with both sides rated, and the upsets among them. */
        private fun cupUpsets(plan: SeasonPlan): Pair<Int, Int> {
            val cup = plan.competition(Comp.CUP) as? Cup ?: return 0 to 0
            // The stage holding the winners' 8강 ("8강", "승자조 8강" among its rounds); a bracket of 4 or 2 is all late.
            val last8 = Cup.label(8, 0)
            val labels = cup.stages().map { st -> st.label.split(" · ").map { it.removePrefix("승자조 ") } }
            val from = labels.indexOfFirst { last8 in it }.coerceAtLeast(0)
            val rating = cup.entries.associate { it.key to it.rating }
            var ties = 0
            var upsets = 0
            for (m in cup.seasonMatches()) {
                val s = m.score ?: continue
                if (m.stage < from) continue
                val a = rating[m.fixture.a] ?: Double.NaN
                val b = rating[m.fixture.b] ?: Double.NaN
                if (a.isNaN() || b.isNaN()) continue
                ties++
                val aWon = tieWinner(s) > 0
                val (w, l) = if (aWon) a to b else b to a
                if (w <= l - UPSET_GAP) upsets++
            }
            return ties to upsets
        }

        /** +1 when side A won a tie, -1 when B: the games, then the totals, then A (as [Knockout] decides a level one). */
        private fun tieWinner(s: TieScore): Int = when {
            s.winner != 0 -> s.winner
            s.totalA != s.totalB -> if (s.totalA > s.totalB) 1 else -1
            else -> 1
        }

        /**
         * The 챔피언스 리그's points and matches of [plan]'s entrants who came as league champions
         * (a national league's "L0.<id>" of the season before) and of those who came from the
         * 대륙 챔피언스 ("CCL.*", "CCL2.*"); last season's holder is neither.
         */
        private fun clPoints(plan: SeasonPlan, honours: List<Honour>): IntArray {
            val out = IntArray(4)
            val cl = plan.competition(Comp.CHAMPIONS) as? ChampionsLeague ?: return out
            val last = honours.filter { it.season == plan.number - 1 }
            val holder = last.firstOrNull { it.kind == "CL" }?.key
            val ccl = last.filter { (HonourKind.base(it.kind) == "CCL" || HonourKind.base(it.kind) == "CCL2") && it.key != holder }.map { it.key }.toHashSet()
            val champions = last.filter { HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null && it.key != holder && it.key !in ccl }.map { it.key }.toHashSet()
            val entrants = cl.entrantKeys().toHashSet()
            fun add(key: String, points: Int) {
                if (key !in entrants) return
                val at = if (key in champions) 0 else if (key in ccl) 2 else return
                out[at] += points
                out[at + 1]++
            }
            for (m in cl.seasonMatches()) {
                val s = m.score ?: continue
                val r = if (m.fixture.kind == JobKind.TIE) tieWinner(s) else s.winner
                add(m.fixture.a, TieRule.points(r))
                add(m.fixture.b, TieRule.points(-r))
            }
            return out
        }

        private fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** The fields of an `fs` line back ([encode]); null when they are unusable. */
        fun decode(season: Int, text: String): FederationStats? = runCatching {
            val f = text.split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun pair(k: String): Pair<Int, Int> = f.getValue(k).split('/').let { it[0].toInt() to it[1].toInt() }
            fun int(k: String) = f.getValue(k).toInt()
            val (bounced, promoted) = pair("elevator")
            val (lost, playoffs) = pair("leaderLost")
            val (runaway, leagues) = pair("runaway")
            val (sweeps, decided) = pair("sweep")
            val (upsets, ties) = pair("koUpsets")
            val (cp, cm) = pair("clChamp")
            val (kp, km) = pair("clCcl")
            val levers = f.getValue("lv").split('/')
            require(levers.size == Lever.entries.size)
            FederationStats(
                season, f["national"] == "1", promoted, bounced, f.getValue("champPpm").takeIf { it != "-" }?.toDouble(),
                playoffs, lost, leagues, runaway, decided, sweeps, int("rounds"), int("projected"), int("legacy"), ties, upsets, cp, cm, kp, km, levers,
            )
        }.getOrNull()
    }
}

/**
 * 요트협회 (yacht federation, docs/LEAGUE_SPEC.md): it keeps the league coefficients (a
 * [CoefficientStore]: `coef` lines of the newest [Coefficients.KEEP] seasons), the best-ranked
 * leagues of every national season (`top` lines, [isMajor]), and once a season holds its 정기 총회:
 * - **assemble** (at the open of season n + 1, against the rules season n + 1 really uses): from
 *   season n's [FederationStats] it tables 2 proposals, a third when it presses hard, at most one
 *   per [Lever] and none on a lever changed lately (P(n));
 * - during season n + 1 the user can vote on them ([choose]);
 * - **resolve** (at the close of season n + 1): the user's choice decides, the rest go to the
 *   선수 위원회's seeded vote ([committee]); adopted changes apply from season n + 2 ([applyTo]).
 * Both steps are idempotent, so a close or open replayed after a kill finds what it stored.
 * Not thread-safe: callers synchronise.
 */
class Federation : CoefficientStore {
    private val coefs = TreeMap<Int, Map<String, IntArray>>()
    private val tops = TreeMap<Int, List<String>>()
    private val stats = TreeMap<Int, FederationStats>()
    private var upSeason = 0
    private var upKeys: List<String> = emptyList()
    private val list = ArrayList<Proposal>()

    /** The last season whose assembly was held ([assemble]); 0 before any. */
    var assembled = 0
        private set

    /** The last season whose close decided the proposals ([resolve]); 0 before any. */
    var resolved = 0
        private set

    /** The newest [PROPOSALS] proposals (and any still pending), oldest first. */
    val proposals: List<Proposal> get() = list.toList()

    /** Proposals not decided yet (투표 중). */
    fun pending(): List<Proposal> = list.filter { it.pending }

    /** The proposals of the assembly after season [season] (P(season)), by number. */
    fun of(season: Int): List<Proposal> = list.filter { it.season == season }

    fun proposal(season: Int, number: Int): Proposal? = list.firstOrNull { it.season == season && it.number == number }

    /**
     * The user's vote on pending proposal [number] of season [season]: [approve] true 찬성, false
     * 반대, null clears it. False when there is no such pending proposal.
     */
    fun choose(season: Int, number: Int, approve: Boolean?): Boolean {
        val i = list.indexOfFirst { it.season == season && it.number == number && it.pending }
        if (i < 0) return false
        list[i] = list[i].copy(choice = when (approve) { true -> Proposal.YES; false -> Proposal.NO; null -> Proposal.NONE })
        return true
    }

    /**
     * Decides, at the close of season [season], the proposals of season [season] - 1: a user's
     * choice decides it; otherwise each of the [committee]'s seats votes yes with chance
     * `clamp(0.25 + 0.35 × pressure, 0.1, 0.9)` (seeded by the closing season's [seed], so a
     * replay votes the same) and a majority adopts it. With the federation off ([enabled] false)
     * they are void. Older ones still pending are void too. Returns the adopted ones; idempotent:
     * once [resolved] ≥ [season] it returns the stored adopted list and changes nothing.
     */
    fun resolve(season: Int, seed: Long, committee: List<String>, enabled: Boolean = true): List<Proposal> {
        if (resolved >= season) return adopted(season - 1)
        val seats = committee.size.takeIf { it > 0 } ?: COMMITTEE
        for ((i, p) in list.withIndex()) {
            if (!p.pending || p.season > season - 1) continue
            list[i] = when {
                !enabled || p.season < season - 1 -> p.copy(result = Proposal.VOID)
                p.choice == Proposal.YES || p.choice == Proposal.NO -> p.copy(result = p.choice, by = Proposal.USER)
                else -> {
                    val chance = (0.25 + 0.35 * p.pressure).coerceIn(0.1, 0.9)
                    val yes = (0 until seats).count { s -> Random(DiceStream.mix(seed, 0xFED0, p.season * 16L + p.number, s.toLong())).nextDouble() < chance }
                    p.copy(result = if (yes > seats / 2) Proposal.YES else Proposal.NO, by = Proposal.COMMITTEE, yes = yes, no = seats - yes)
                }
            }
        }
        resolved = season
        return adopted(season - 1)
    }

    private fun adopted(season: Int): List<Proposal> = list.filter { it.season == season && it.adopted }

    /**
     * Records adopted [proposals] as void (무효), as [applyTo] found them: their rule had already
     * changed. Idempotent.
     */
    fun markVoid(proposals: Collection<Proposal>) {
        for (p in proposals) {
            val i = list.indexOfFirst { it.season == p.season && it.number == p.number && it.adopted }
            if (i >= 0) list[i] = list[i].copy(result = Proposal.VOID)
        }
    }

    /**
     * The assembly after season [season] (at the next season's open): records [stats] (an `fs`
     * line, and its promoted players as the `up` line) and, with [LeagueConfig.federation] on and
     * national leagues to come ([LeagueConfig.national]: a legacy league's rules are never
     * proposed on), tables P(season) against [config], the rules of the season to come:
     * 1. every kind whose guard holds and whose statistic is past its floor ([candidates]; only
     *    the structure-free ones with [structureFreeOnly] or after a legacy season, the first
     *    assembly);
     * 2. none on a lever with a pending proposal, or whose value differs among the season to come
     *    ([config]), [season] and [season] - 1 (changed in either direction in the last 2 seasons,
     *    by the federation or by hand), or whose change [config] cannot take;
     * 3. one per lever (the higher pressure);
     * 4. by pressure, then kind order: the first [TABLED], and the next when its pressure is at
     *    least [THIRD].
     * Returns P(season) (empty: the rules stay as they are). Idempotent: once [assembled] ≥
     * [season], or P(season) is stored (a file whose `meta` line was lost), it returns the stored
     * proposals.
     */
    fun assemble(season: Int, stats: FederationStats, config: LeagueConfig, structureFreeOnly: Boolean): List<Proposal> {
        if (assembled >= season || list.any { it.season == season }) return of(season).also { assembled = maxOf(assembled, season) }
        require(stats.season == season) { "statistics of season ${stats.season} for the assembly after $season" }
        putStats(stats)
        upSeason = season
        upKeys = stats.up
        if (config.federation && config.national) {
            val busy = pending().map { it.lever }.toSet()
            val previous = this.stats[season - 1]?.levers
            fun changed(l: Lever): Boolean {
                val values = listOfNotNull(config.valueOf(l.key), stats.levers.getOrNull(l.ordinal), previous?.getOrNull(l.ordinal))
                return values.distinct().size > 1
            }
            val picked = candidates(stats, config, structureFreeOnly)
                .filter { it.lever !in busy && !changed(it.lever) && config.with(it.lever.key, it.to) != null }
                .groupBy { it.lever }.values.map { same -> same.minWith(ORDER) }
                .sortedWith(ORDER)
            val tabled = picked.take(TABLED) + picked.drop(TABLED).take(1).filter { it.pressure >= THIRD }
            for ((i, p) in tabled.withIndex()) list += p.copy(season = season, number = i + 1)
            trim()
        }
        assembled = season
        return of(season)
    }

    private fun putStats(s: FederationStats) {
        // As the `fs` line keeps it (the promoted are the `up` line), so a reload reads the same.
        stats[s.season] = s.copy(up = emptyList())
        while (stats.size > STATS) stats.remove(stats.firstKey())
    }

    /** The statistics of season [season], if kept (the newest [STATS]). */
    fun stats(season: Int): FederationStats? = stats[season]

    /** The members promoted into a 1부 at the end of season [season] (the `up` line); null when not kept. */
    fun promoted(season: Int): List<String>? = if (season == upSeason && season > 0) upKeys else null

    private fun trim() {
        val decided = list.filter { !it.pending }.sortedWith(compareBy({ it.season }, { it.number }))
        val drop = decided.take(maxOf(0, list.size - PROPOSALS)).toSet()
        list.removeAll { it in drop }
        list.sortWith(compareBy({ it.season }, { it.number }))
    }

    // ---- coefficients and the best-ranked leagues ---------------------------------------------

    override fun putCoefficients(season: Int, values: Map<String, IntArray>) {
        coefs[season] = LinkedHashMap(values.mapValues { it.value.copyOf() })
        while (coefs.size > Coefficients.KEEP) coefs.remove(coefs.firstKey())
    }

    override fun coefficient(league: String, season: Int): Double? = sums(league, season)?.let(Coefficients::value)

    /** Season [season]'s [points, entries] of league [league]; null without a line. */
    fun sums(league: String, season: Int): IntArray? = coefs[season]?.get(league)?.copyOf()

    /** The seasons with coefficients kept, oldest first. */
    val coefficientSeasons: List<Int> get() = coefs.keys.toList()

    /** The [TOP] best-ranked leagues national season [season] was played under, best first ([NationalLeagues.bestRanked]). */
    fun putTop(season: Int, ids: List<String>) {
        tops[season] = ids.take(TOP)
        while (tops.size > HonourBook.MAX_SEASONS) tops.remove(tops.firstKey())
    }

    /** The best-ranked leagues of season [season]; null when none were recorded. */
    fun top(season: Int): List<String>? = tops[season]

    /**
     * Whether league [league]'s title of season [season] is a major title ([HonourBook.majorTitles]):
     * it was one of that season's best-ranked leagues; true for a season with no `top` line.
     */
    fun isMajor(season: Int, league: String): Boolean = tops[season]?.contains(league) ?: true

    // ---- saving ---------------------------------------------------------------------------------

    /** federation.txt (`federation 1`). */
    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("meta $assembled $resolved\n")
        for ((s, values) in coefs) for ((id, v) in values) append("coef $s $id ${v[0]} ${v[1]}\n")
        for ((s, ids) in tops) append("top $s ${ids.joinToString(",").ifEmpty { "-" }}\n")
        if (upSeason > 0) append("up $upSeason ${upKeys.joinToString(",").ifEmpty { "-" }}\n")
        for ((s, st) in stats) append("fs $s ${st.encode()}\n")
        for (p in list) {
            append("prop ${p.season} ${p.number} ${p.kind.name} ${p.lever.key} ${Field.esc(p.from)} ${Field.esc(p.to)} ")
            append(String.format(Locale.ROOT, "%.2f", p.pressure)).append(' ')
            append("${p.choice} ${p.result} ${p.by} ${p.yes} ${p.no} ")
            append(p.args.joinToString("|") { Field.esc(it) }.ifEmpty { "-" }).append('\n')
        }
    }

    companion object {
        const val HEADER = "federation 1"

        /** Proposals tabled at an assembly, and the most (the last only when it presses hard). */
        const val TABLED = 2
        const val MOST = 3
        const val THIRD = 0.6

        /** Seats of the 선수 위원회. */
        const val COMMITTEE = 9

        /** Best-ranked leagues recorded per season ([putTop]). */
        const val TOP = 8

        /** Seasons of statistics kept, and proposals kept. */
        const val STATS = 10
        const val PROPOSALS = 30

        /** Samples the guards ask for: promoted players, title-playoff leagues, leagues, cup ties, 챔피언스 리그 matches a side, decided matches. */
        const val MIN_PROMOTED = 10
        const val MIN_PLAYOFFS = 4
        const val MIN_LEAGUES = 4
        const val MIN_TIES = 10
        const val MIN_CL_MATCHES = 12
        const val MIN_DECIDED = 30

        /** Pressure of a kind at statistic [x]: 0.3 at its floor, 1.0 at its threshold, at most 3; below 0.3 short of the floor. */
        fun pressure(kind: ProposalKind, x: Double): Double =
            minOf(3.0, 0.3 + 0.7 * (x - kind.floor) / (kind.threshold - kind.floor))

        private val CHOICES = setOf(Proposal.NONE, Proposal.YES, Proposal.NO)
        private val RESULTS = setOf(Proposal.NONE, Proposal.YES, Proposal.NO, Proposal.VOID)
        private val DECIDERS = setOf(Proposal.NONE, Proposal.USER, Proposal.COMMITTEE)

        // Higher pressure first, then kind order.
        private val ORDER = compareByDescending<Proposal> { it.pressure }.thenBy { it.kind.ordinal }

        /**
         * Every kind whose guard holds under [config] (the rules of the season to come) and whose
         * statistic in [stats] is past its floor, as a proposal of number 0 with its pressure (2
         * decimals) and evidence; only [ProposalKind.structureFree] ones with [structureFreeOnly]
         * and for a legacy season's [stats] (its elevator, tables and fixtures are no national
         * leagues'). No lever or recent-change rule here ([Federation.assemble] applies those).
         */
        fun candidates(stats: FederationStats, config: LeagueConfig, structureFreeOnly: Boolean = false): List<Proposal> {
            val out = ArrayList<Proposal>()
            fun pct(k: Int, n: Int) = if (n <= 0) "0" else (100.0 * k / n).roundToInt().toString()
            fun num2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
            val freeOnly = structureFreeOnly || !stats.national
            for (kind in ProposalKind.entries) {
                if (freeOnly && !kind.structureFree) continue
                val from = config.valueOf(kind.lever.key) ?: continue
                val swapArgs = listOf(stats.promoted.toString(), stats.bounced.toString(), pct(stats.bounced, stats.promoted), stats.champPpm?.let(::num2) ?: "-")
                val c: Triple<Double, String, List<String>>? = when (kind) {
                    ProposalKind.SWAP_DOWN -> if (config.autoSwap > 1 && stats.promoted >= MIN_PROMOTED) Triple(stats.elevator, "${config.autoSwap - 1}", swapArgs) else null
                    ProposalKind.SWAP_UP ->
                        if (config.autoSwap < 4 && stats.promoted >= MIN_PROMOTED && (stats.champPpm ?: -1.0) >= 0) Triple(stats.elevator, "${config.autoSwap + 1}", swapArgs) else null
                    ProposalKind.TITLE_PO_OFF -> if (config.titlePlayoff && stats.playoffLeagues >= MIN_PLAYOFFS) {
                        Triple(stats.leaderLostShare, "0", listOf("${stats.playoffLeagues}", "${stats.leaderLost}", pct(stats.leaderLost, stats.playoffLeagues)))
                    } else null
                    ProposalKind.TITLE_PO_ON -> if (!config.titlePlayoff && stats.leagues >= MIN_LEAGUES) {
                        Triple(stats.runawayShare, "1", listOf("${stats.leagues}", "${stats.runaway}", pct(stats.runaway, stats.leagues)))
                    } else null
                    ProposalKind.SWEEP_OFF, ProposalKind.SWEEP_ON -> {
                        val on = kind == ProposalKind.SWEEP_OFF
                        // Only where the format has the bonus to give or take (판 승점제), and the season showed enough matches.
                        if (config.sweepBonus == on && config.matchFormat.sweepBonus && stats.decided >= MIN_DECIDED) {
                            Triple(stats.sweepShare, if (on) "0" else "1", listOf("${stats.decided}", "${stats.sweeps}", pct(stats.sweeps, stats.decided)))
                        } else null
                    }
                    ProposalKind.SIZE_DOWN -> if (config.divisionSize > 16 && stats.rounds > 0) {
                        Triple(stats.rounds.toDouble(), "${config.divisionSize - 2}", listOf("${stats.rounds}", "${stats.projected}", "${stats.legacy}"))
                    } else null
                    ProposalKind.SIZE_UP -> if (config.divisionSize < 24 && stats.rounds > 0 && stats.projected <= stats.legacy) {
                        Triple(stats.rounds.toDouble(), "${config.divisionSize + 2}", listOf("${stats.rounds}", "${stats.projected}", "${stats.legacy}"))
                    } else null
                    ProposalKind.LONG_KO_ON, ProposalKind.LONG_KO_OFF -> {
                        val on = kind == ProposalKind.LONG_KO_ON
                        if (config.longKnockouts != on && stats.koTies >= MIN_TIES) {
                            Triple(stats.upsetShare, if (on) "1" else "0", listOf("${stats.koTies}", "${stats.koUpsets}", pct(stats.koUpsets, stats.koTies)))
                        } else null
                    }
                    ProposalKind.CL_DIRECT_UP, ProposalKind.CL_DIRECT_DOWN -> {
                        val up = kind == ProposalKind.CL_DIRECT_UP
                        val room = if (up) config.clDirect < 12 else config.clDirect > 0
                        if (room && stats.champMatches >= MIN_CL_MATCHES && stats.cclMatches >= MIN_CL_MATCHES) {
                            val args = listOf(
                                num2(stats.clEdge), num2(FederationStats.ppm(stats.champPoints, stats.champMatches)), num2(FederationStats.ppm(stats.cclPoints, stats.cclMatches)),
                            )
                            Triple(stats.clEdge, "${config.clDirect + if (up) 1 else -1}", args)
                        } else null
                    }
                }
                val (x, to, args) = c ?: continue
                // Past the floor: on the threshold's side of it (or on it).
                if ((x - kind.floor) * (kind.threshold - kind.floor) < 0) continue
                val p = Math.round(pressure(kind, x) * 100) / 100.0
                out += Proposal(stats.season, 0, kind, from, to, p, args)
            }
            return out
        }

        /**
         * Applies [adopted] proposals to [config] in proposal order, only on their [Lever] keys:
         * one whose rule already has its new value was applied before (a replayed close) and
         * changes nothing; one whose rule is no longer its old value (changed by hand since the
         * assembly), or that the rules cannot take, is void. Returns the rules and the void ones
         * (for [markVoid]). Proposals not adopted are left out.
         */
        fun applyTo(config: LeagueConfig, adopted: List<Proposal>): Pair<LeagueConfig, List<Proposal>> {
            var c = config
            val void = ArrayList<Proposal>()
            for (p in adopted.filter { it.adopted }.sortedWith(compareBy({ it.season }, { it.number }))) {
                val key = p.lever.key
                val now = c.valueOf(key)
                if (now == p.to) continue
                val next = if (now == p.from) c.with(key, p.to) else null
                if (next == null) void += p else c = next
            }
            return c to void
        }

        /**
         * The 선수 위원회 after closed season [plan]: the best-rated member of each of the [COMMITTEE]
         * best-ranked leagues by [ranking] (unranked ones by strength), then the best-rated other
         * members up to [COMMITTEE] (a legacy season: its [COMMITTEE] best-rated members).
         */
        fun committee(plan: SeasonPlan, ranking: LeagueRanking, rating: (String) -> Double): List<String> {
            val season = plan.league
            fun members(lg: LeagueDef) = lg.divisions.flatMap { season.divisions[it] }.filter { !rating(it).isNaN() }
            val byLeague = season.leagues.list.filter { it.national }.associate { it.id to members(it).sortedByDescending { k -> rating(k) } }
            val order = Coefficients.order(byLeague.keys, ranking) { id -> Coefficients.strength(byLeague.getValue(id), rating) }
            val out = LinkedHashSet<String>()
            for (id in order) {
                if (out.size >= COMMITTEE) break
                byLeague.getValue(id).firstOrNull()?.let { out += it }
            }
            val everyone = season.divisions.flatten().filter { !rating(it).isNaN() }.sortedWith(compareByDescending<String> { rating(it) }.thenBy { it })
            for (k in everyone) {
                if (out.size >= COMMITTEE) break
                out += k
            }
            return out.toList()
        }

        /** Reads federation.txt; throws when it is not one. Bad lines are skipped. */
        fun decode(text: String): Federation {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a federation file" }
            val f = Federation()
            val coefs = TreeMap<Int, LinkedHashMap<String, IntArray>>()
            for (line in lines) {
                runCatching {
                    val p = line.trim().split(' ')
                    when (p[0]) {
                        "meta" -> {
                            f.assembled = p[1].toInt()
                            f.resolved = p[2].toInt()
                        }
                        "coef" -> coefs.getOrPut(p[1].toInt()) { LinkedHashMap() }[p[2]] = intArrayOf(p[3].toInt(), p[4].toInt())
                        "top" -> f.tops[p[1].toInt()] = if (p[2] == "-") emptyList() else p[2].split(',')
                        "up" -> {
                            f.upSeason = p[1].toInt()
                            f.upKeys = if (p[2] == "-") emptyList() else p[2].split(',')
                        }
                        "fs" -> {
                            val s = p[1].toInt()
                            FederationStats.decode(s, p[2])?.let { f.stats[s] = it }
                        }
                        "prop" -> {
                            val kind = ProposalKind.valueOf(p[3])
                            require(kind.lever.key == p[4])
                            val args = if (p[13] == "-") emptyList() else p[13].split('|').map { Field.unesc(it) }
                            val choice = p[8].single()
                            val result = p[9].single()
                            val by = p[10].single()
                            require(choice in CHOICES && result in RESULTS && by in DECIDERS)
                            val pressure = p[7].toDouble()
                            require(pressure.isFinite() && p[2].toInt() >= 1)
                            f.list += Proposal(p[1].toInt(), p[2].toInt(), kind, Field.unesc(p[5]), Field.unesc(p[6]), pressure, args, choice, result, by, p[11].toInt(), p[12].toInt())
                        }
                    }
                }
            }
            for ((s, v) in coefs) f.putCoefficients(s, v)
            while (f.tops.size > HonourBook.MAX_SEASONS) f.tops.remove(f.tops.firstKey())
            while (f.stats.size > STATS) f.stats.remove(f.stats.firstKey())
            val seen = HashSet<Pair<Int, Int>>()
            f.list.retainAll { seen.add(it.season to it.number) }
            f.trim()
            return f
        }
    }
}
