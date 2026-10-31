package yacht.engine.league

/**
 * A leaderboard's statistic (리더보드, design UX 4.1): what the board ranks, its name and the
 * one-line rule its page opens with. [career]: whether it has a career board (완승왕 is per
 * season only); [lowFirst]: smaller is better (꾸준함). 행운아 and 불운 are [LUCK] read both ways.
 */
enum class LeaderStat(val label: String, val rule: String, val career: Boolean = true, val lowFirst: Boolean = false) {
    SCORER("득점왕", "리그 경기 평균 점수예요. 가장 많이 뛴 선수의 절반 이상 뛰어야 올라가요."),
    YACHTS("요트왕", "리그 경기에서 만든 요트 수예요."),
    BONUS("보너스왕", "공식 경기에서 윗칸 보너스를 받은 판의 비율이에요."),
    SWEEPS("완승왕", "리그에서 2:0으로 이긴 경기 수예요.", career = false),
    MVP("평점왕", "공식 경기 평균 평점이에요. 리그 경기의 60% 이상 뛰어야 MVP 후보예요."),
    LUCK("행운아", "실제 승점에서 같은 주사위로 비교한 운 보정 승점을 뺀 값이에요. 0보다 크면 운이 따랐어요."),
    CLUTCH("클러치", "결정전과 5점 차 이내 접전의 승률이에요."),
    COMEBACK("역전의 명수", "승리 확률이 20% 아래로 떨어졌다가 이긴 판 수예요."),
    GIANT("거인 사냥꾼", "레이팅이 100점 이상 높은 상대를 이긴 경기 수예요."),
    STEADY("꾸준함", "판 점수의 표준편차예요. 작을수록 꾸준해요.", lowFirst = true),
    OVER300("300점 클럽", "300점 이상을 낸 판 수예요."),
}

/**
 * One line of a leaderboard: [key]'s [value] over [games] (the board's sample: league matches,
 * official games or matches, decider and close games for [LeaderStat.CLUTCH]) and an [extra]
 * where the board shows one (요트왕's Yachts per game played, 요트율; 보너스왕's bonus games;
 * 행운아's 운 보정 승점; 클러치's wins; 역전의 명수's share of its wins; NaN without one).
 */
data class LeaderEntry(val key: String, val value: Double, val games: Int, val extra: Double = Double.NaN)

/**
 * The leaderboards of a season (from its standings and [StatsBook.season]) and of careers
 * ([StatsBook.career]), over the players in [keys] (a league's members, everyone, a club's):
 * one pass per board, O(keys log keys). Every board is best first ([LeaderStat.lowFirst] or
 * `ascending` turned around: 불운 is [LeaderStat.LUCK] ascending); level lines by the larger
 * sample, then by key, so a board reads the same whenever it is asked. A player below a board's
 * eligibility is not on it.
 */
object Leaders {
    /** Official games a season's 보너스왕 needs, and a career's. */
    const val BONUS_GAMES = 20
    const val CAREER_BONUS_GAMES = 100

    /** Decider and close games a 클러치 needs (season, career). */
    const val CLUTCH_GAMES = 8
    const val CAREER_CLUTCH_GAMES = 30

    /** Games for 꾸준함 (season, career). */
    const val STEADY_GAMES = 40
    const val CAREER_STEADY_GAMES = 200

    /** Matches for a career 평점왕, and a season's for a player outside the season's league. */
    const val CAREER_RATING_MATCHES = 100
    const val OUTSIDE_RATING_MATCHES = 10

    /** Games for a career 득점왕. */
    const val CAREER_SCORER_GAMES = 200

    /** A season's board of [stat] over [keys] (players not in [plan]'s league count only on the boards of [StatsBook.season]). */
    fun season(stat: LeaderStat, plan: SeasonPlan, stats: StatsBook, keys: Collection<String>, ascending: Boolean = false): List<LeaderEntry> =
        season(stat, plan.league, { stats.season(it) }, keys, ascending)

    /** [season] with the season's stats as a lookup (what [StatsBook.endSeason] has while it closes a season). */
    fun season(stat: LeaderStat, league: Season, stats: (String) -> AiStats?, keys: Collection<String>, ascending: Boolean = false): List<LeaderEntry> {
        val ks = keys.distinct()
        val out = ArrayList<LeaderEntry>(ks.size)
        when (stat) {
            LeaderStat.SCORER, LeaderStat.LUCK -> {
                val table = ks.mapNotNull { league.standings[it] }.filter { it.played > 0 }
                val min = (table.maxOfOrNull { it.played } ?: 0) / 2
                for (s in table) {
                    if (s.played < min) continue
                    out += if (stat == LeaderStat.SCORER) LeaderEntry(s.key, s.avgScore, s.played)
                    // Points as the matches went (without the sweep bonus) against the same dice's totals.
                    else LeaderEntry(s.key, (s.points - s.sweeps - s.aggPoints).toDouble(), s.played, s.aggPoints.toDouble())
                }
            }
            LeaderStat.YACHTS -> for (k in ks) {
                val s = league.standings[k] ?: continue
                if (s.played == 0) continue
                out += LeaderEntry(k, s.yachts.toDouble(), s.played, s.yachts.toDouble() / maxOf(1, s.games))
            }
            LeaderStat.SWEEPS -> {
                val sweeps = if (league.bonus) null else sweepsOf(league, ks.toHashSet())
                for (k in ks) {
                    val s = league.standings[k] ?: continue
                    if (s.played == 0) continue
                    out += LeaderEntry(k, (sweeps?.get(k) ?: s.sweeps).toDouble(), s.played)
                }
            }
            LeaderStat.MVP -> for (k in ks) {
                val st = stats(k) ?: continue
                if (st.avgRating.isNaN()) continue
                val s = league.standings[k]
                val ok = if (s != null) s.played > 0 && s.played >= Awards.MVP_SHARE * league.fixturesOf(k) else st.matches >= OUTSIDE_RATING_MATCHES
                if (ok) out += LeaderEntry(k, st.avgRating, st.matches)
            }
            else -> for (k in ks) entry(stat, k, stats(k) ?: continue, career = false)?.let { out += it }
        }
        return sorted(stat, out, ascending)
    }

    /** A career board of [stat] over [keys] (empty for a board without one, [LeaderStat.career]). */
    fun career(stat: LeaderStat, stats: StatsBook, keys: Collection<String>, ascending: Boolean = false): List<LeaderEntry> {
        if (!stat.career) return emptyList()
        val out = ArrayList<LeaderEntry>()
        for (k in keys.distinct()) {
            val c = stats.career(k) ?: continue
            when (stat) {
                LeaderStat.SCORER -> if (c.games >= CAREER_SCORER_GAMES) out += LeaderEntry(k, c.avgScore, c.games)
                LeaderStat.YACHTS -> if (c.games > 0) out += LeaderEntry(k, c.yachts.toDouble(), c.games, c.yachts.toDouble() / c.games)
                LeaderStat.MVP -> if (c.matches >= CAREER_RATING_MATCHES && !c.avgRating.isNaN()) out += LeaderEntry(k, c.avgRating, c.matches)
                LeaderStat.LUCK -> if (c.matches > 0) out += LeaderEntry(k, (3 * c.matchWins + c.matchDraws - c.aggPoints).toDouble(), c.matches, c.aggPoints.toDouble())
                else -> entry(stat, k, c, career = true)?.let { out += it }
            }
        }
        return sorted(stat, out, ascending)
    }

    /** The boards both scopes read from [AiStats] alone. */
    private fun entry(stat: LeaderStat, k: String, s: AiStats, career: Boolean): LeaderEntry? = when (stat) {
        LeaderStat.BONUS -> if (s.games < (if (career) CAREER_BONUS_GAMES else BONUS_GAMES)) null else LeaderEntry(k, s.bonuses.toDouble() / s.games, s.games, s.bonuses.toDouble())
        LeaderStat.CLUTCH -> {
            val wins = s.deciderWins + s.closeWins
            val n = wins + s.deciderLosses + s.closeLosses
            if (n < (if (career) CAREER_CLUTCH_GAMES else CLUTCH_GAMES)) null else LeaderEntry(k, (wins + 1.0) / (n + 2.0), n, wins.toDouble())
        }
        LeaderStat.COMEBACK -> if (s.comebacks == 0) null else LeaderEntry(k, s.comebacks.toDouble(), s.games, s.comebacks.toDouble() / maxOf(1, s.gameWins))
        LeaderStat.GIANT -> if (s.giantKills == 0) null else LeaderEntry(k, s.giantKills.toDouble(), s.matches)
        LeaderStat.STEADY -> if (s.games < (if (career) CAREER_STEADY_GAMES else STEADY_GAMES)) null else LeaderEntry(k, s.scoreSd, s.games)
        LeaderStat.OVER300 -> if (s.over300 == 0) null else LeaderEntry(k, s.over300.toDouble(), s.games)
        else -> null
    }

    /**
     * Matches won 2-0 by [keys] in [league] (a season without the sweep bonus, whose standings do
     * not count them): one pass over its fixtures.
     */
    fun sweepsOf(league: Season, keys: Set<String>? = null): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (id in 0 until league.fixturesTotal) {
            val games = league.result(id) ?: continue
            if (games.size != 2) continue
            val f = league.fixture(id)
            val w = when {
                games.all { it.a > it.b } -> f.a
                games.all { it.b > it.a } -> f.b
                else -> continue
            }
            if (keys == null || w in keys) out.merge(w, 1, Int::plus)
        }
        return out
    }

    private fun sorted(stat: LeaderStat, list: List<LeaderEntry>, ascending: Boolean): List<LeaderEntry> {
        val low = stat.lowFirst != ascending
        val byValue = if (low) compareBy<LeaderEntry> { it.value } else compareByDescending { it.value }
        // Level: the comeback board by its share of wins, then the larger sample (fewer games for Yachts), then the key.
        val tie = when (stat) {
            LeaderStat.COMEBACK -> byValue.thenByDescending { it.extra }
            LeaderStat.YACHTS -> byValue.thenBy { it.games }
            else -> byValue.thenByDescending { it.games }
        }
        return list.filter { !it.value.isNaN() }.sortedWith(tie.thenBy { it.key })
    }
}
