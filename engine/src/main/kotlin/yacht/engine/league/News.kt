package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Locale

/** A news article (뉴스 기사) written by [News] from a [LeagueEvent]. */
data class Article(
    val season: Int,
    val kind: String,
    val headline: String,
    /** Two or three sentences (해요체). */
    val body: String,
    /** What the article is about, as the event's keys (AIs, or team and club ids): for the 응원 AI filter. */
    val keys: List<String>,
    val comp: Comp?,
    /** 1 (a line in the feed) to 5 (the front page). */
    val importance: Int,
    /** The week of the season it happened in ([SeasonCalendar]); [SeasonCalendar.NONE] for older news. */
    val week: Int = SeasonCalendar.NONE,
    /** The match it is about ([MatchRef.encode], a board or game of it at most), for the match report; null when none. */
    val ref: String? = null,
    /**
     * Where it happened: a league's id (a national league's news), a [Confed]'s name (its 대륙
     * 챔피언스), or "" for the world (the international stage, awards, a legacy season's league).
     */
    val scope: String = "",
) {
    /** Its lane in the [NewsBook]: [NewsBook.MAJOR] from importance 3 up. */
    val major: Boolean get() = importance >= NewsBook.MAJOR_FROM
}

/**
 * Korean particles (조사) chosen by the sound the word before ends in: the last Hangul syllable's
 * final consonant, a digit as it is read (0 영/십/백, 1 일, 3 삼, ...), a Latin letter roughly.
 */
internal object Josa {
    /** 0: a vowel, 1: a final consonant, 2: a final ㄹ (which takes 로, not 으로). */
    fun ending(word: String): Int {
        for (i in word.indices.reversed()) {
            val c = word[i]
            when {
                c in '가'..'힣' -> {
                    val jong = (c - '가') % 28
                    return if (jong == 0) 0 else if (jong == 8) 2 else 1
                }
                c in 'ㄱ'..'ㅎ' -> return 1
                c in '0'..'9' -> return when (c) {
                    '2', '4', '5', '9' -> 0
                    '1', '7', '8' -> 2
                    else -> 1
                }
                c in 'a'..'z' || c in 'A'..'Z' -> return when (c.lowercaseChar()) {
                    'l' -> 2
                    'm', 'n' -> 1
                    else -> 0
                }
                c == '%' -> return 0
            }
        }
        return 0
    }

    /** The particle [josa] (either form, e.g. "이" or "가") as it goes after [word]. */
    fun after(word: String, josa: String): String {
        val e = ending(word)
        return when (josa) {
            "이", "가" -> if (e == 0) "가" else "이"
            "은", "는" -> if (e == 0) "는" else "은"
            "을", "를" -> if (e == 0) "를" else "을"
            "과", "와" -> if (e == 0) "와" else "과"
            "으로", "로" -> if (e == 1) "으로" else "로"
            "이에요", "예요" -> if (e == 0) "예요" else "이에요"
            "이었어요", "였어요" -> if (e == 0) "였어요" else "이었어요"
            "이라는", "라는" -> if (e == 0) "라는" else "이라는"
            else -> josa
        }
    }
}

/**
 * Korean articles from league events (docs/LEAGUE_SPEC.md sections 4 and 9). Each kind has a few
 * templates; the event's hash picks one, so the same event always reads the same and different
 * events vary. Templates use `{name}` slots, `{name|이}` for a slot with its particle and
 * `[[ ... ]]` for a part dropped when a slot inside it has no value; a template whose other
 * slots cannot all be filled is not used.
 */
object News {
    private class T(val headline: String, val body: String)

    private val SEGMENT = Regex("""\[\[(.*?)]]""")

    /** A knockout round from the last eight on ("8강", "승자조 4강", "결승"; not "128강"). */
    private val LAST_EIGHT = Regex("(^| )(8강|4강|결승)")
    private val SLOT = Regex("""\{(\w+)(?:\|([가-힣]+))?}""")
    private val SPACES = Regex(" {2,}")

    /** Every event kind with its own templates (others get a plain article). */
    val KINDS: List<String> = listOf(
        "upset", "comeback", "record", "milestone", "title", "promoted", "relegated", "cup_round", "eliminated",
        "qualified", "final", "transfer", "draft", "born", "retired", "award", "callup", "dropped", "title_race",
        "retirements", "rookies", "debut", "breakout", "decline", "learned_rule", "league_reform", "founders_farewell", "lower_tiers",
        "wc_open", "cc_open", "draw_wc", "draw_cc", "nation_squad", "ko_set", "rivalry", "nation_awards", "style_trend", "draw_legend", "player_of_week",
        "hall_of_fame", "dynasty", "legend_match", "draw_cup", "draw_cl", "draw_super", "draw_allstar",
        "draw_family", "season_review",
        // National leagues, clubs, the international stage and the 요트협회.
        "league_title", "league_titles_digest", "league_moves", "national_reform", "league_formed", "league_ranking",
        "federation_decision", "federation_assembly", "club_title", "club_champions", "club_reform", "club_founded",
        "club_dissolved", "club_digest", "transfer_abroad", "return_home", "transfer_window", "youth", "market_digest",
        "draw_ccl", "draw_clubcup", "milestones", "dynasties", "pupils", "learned_rules",
        // The news desk's ([NewsDesk]).
        "league_upset", "club_race", "intl_week", "cup_digest", "group_digest", "milestones_week", "record_chase",
    )

    /** Titles of cups held every few seasons: a run of them is counted in editions (대회). */
    private val EDITION_KINDS = setOf("WC", "WCW", "CC", "CCW", "FAMILY")

    /** Headline of the plain article for a kind without templates (or an event missing its keys). */
    const val PLAIN_HEADLINE = "리그 소식"

    /** The last argument that marks an event of a national season (its awards, the lower tiers' digest). */
    const val NATIONAL = "national"

    /** The last argument of a transfer abroad of a league champion or award winner (bigger news). */
    const val STAR = "star"

    /** Korean names of the record codes (other record names are shown as they are). */
    val RECORD_LABELS: Map<String, String> = mapOf(
        "best_game" to "한 경기 최고 점수",
        "margin" to "최다 점수 차 승리",
        "comeback" to "최대 역전승",
        "streak" to "최다 연승",
        "yachts" to "한 경기 최다 요트",
        "season_points" to "시즌 최다 득점",
        "season_rating" to "시즌 최고 평균 평점",
        "titles" to "최다 우승",
    )

    /** Korean names of the award kinds (Honour kinds). */
    val AWARD_LABELS: Map<String, String> = mapOf(
        "MVP" to "MVP", "ROOKIE" to "신인왕", "MIP" to "기량발전상", "YACHT" to "최다 요트상", "SCORER" to "득점왕",
    )

    /**
     * The article of [e]. [name] gives an AI's display name; [club] a club id's and [team] a
     * team id's (family teams and all-star sides). With [rating], a knockout win by an AI rated
     * 100 or more below its opponent is written as a giant killing. The article links the
     * event's match ([LeagueEvent.ref]) and takes its [scope] (a league, a confederation, "").
     */
    fun article(
        e: LeagueEvent,
        name: (String) -> String,
        club: (String) -> String = { it },
        team: (String) -> String = Competitions::teamLabel,
        rating: ((String) -> Double)? = null,
        scope: (LeagueEvent) -> String = { "" },
    ): Article = write(e, name, club, team, rating, null, scope)

    /** [article], with the rating gap of a knockout win already known from its `upset` event ([upsetGap]). */
    private fun write(
        e: LeagueEvent,
        name: (String) -> String,
        club: (String) -> String,
        team: (String) -> String,
        rating: ((String) -> Double)?,
        upsetGap: Double?,
        scope: (LeagueEvent) -> String,
    ): Article {
        val v = HashMap<String, String>()
        v["season"] = e.season.toString()
        v["comp"] = e.comp?.label ?: Comp.LEAGUE.label
        // A continental cup's events name their tournament ("@아시아컵", the last argument).
        e.args.lastOrNull()?.takeIf { it.startsWith("@") }?.let { v["comp"] = it.drop(1) }
        // Keys are AIs, or club and team ids in the club and team competitions (and the clubs of a
        // transfer or draft): an id the club or team names do not know is an AI's.
        val clubKeys = e.comp == Comp.CLUB || e.comp == Comp.CLUB_CUP
        val teamKeys = e.comp == Comp.FAMILY || e.comp == Comp.ALL_STAR ||
            (e.comp == Comp.WORLD_CUP || e.comp == Comp.CONTINENTAL) && e.kind != "nation_squad" && e.kind != "nation_awards"
        fun who(i: Int, k: String): String {
            if (k == "-") return ""
            if (clubKeys || (i > 0 && (e.kind == "transfer" || e.kind == "draft"))) club(k).takeIf { it != k }?.let { return it }
            if (teamKeys) team(k).takeIf { it != k }?.let { return it }
            return name(k)
        }
        val names = e.keys.mapIndexed { i, k -> who(i, k) }
        for ((i, n) in names.withIndex().take(4)) if (n.isNotEmpty()) v["abcd"[i].toString()] = n
        val listed = names.filter { it.isNotEmpty() }
        if (listed.isNotEmpty()) v["seeds"] = listed.joinToString("·")
        v["count"] = listed.size.toString()
        val arg = { i: Int -> e.args.getOrNull(i)?.takeIf { it.isNotBlank() && it != "-" } }

        val (list, importance) = templates(e, v, arg, club, team, rating, upsetGap, name)
        val chosen = pick(list, v, e)
        val t = chosen ?: T(PLAIN_HEADLINE, "[[{seeds}의 ]]새 소식이 들어왔어요. 자세한 내용은 피드에서 볼 수 있어요.")
        return Article(
            e.season, e.kind, fill(t.headline, v), fill(t.body, v), e.keys, e.comp, (if (chosen == null) 1 else importance).coerceIn(1, 5), e.week,
            e.ref, runCatching { scope(e) }.getOrDefault(""),
        )
    }

    /**
     * Articles of [events] in order (see [article]). A knockout tie reports its `cup_round` and,
     * besides, an `upset` [winner, loser] and an `eliminated` [loser] (round) of the same tie: those
     * are one story, so they make one article (the round's, a giant killing when it was an upset).
     */
    fun articles(
        events: List<LeagueEvent>,
        name: (String) -> String,
        club: (String) -> String = { it },
        team: (String) -> String = Competitions::teamLabel,
        rating: ((String) -> Double)? = null,
        scope: (LeagueEvent) -> String = { "" },
    ): List<Article> {
        val rounds = events.filter { it.kind == "cup_round" && it.keys.size >= 2 }
        fun sameTie(r: LeagueEvent, e: LeagueEvent) =
            r.season == e.season && r.comp == e.comp && e.keys.size >= 2 && r.keys[0] == e.keys[0] && r.keys[1] == e.keys[1]
        fun roundOf(e: LeagueEvent): LeagueEvent? = when (e.kind) {
            "upset" -> rounds.firstOrNull { sameTie(it, e) }
            "eliminated" -> rounds.firstOrNull { r ->
                r.season == e.season && r.comp == e.comp && r.keys[1] == e.keys.firstOrNull() && r.args.firstOrNull() == e.args.firstOrNull()
            }
            else -> null
        }
        val out = ArrayList<Article>(events.size)
        for (e in events) {
            if (roundOf(e) != null) continue
            val gap = if (e.kind == "cup_round") {
                events.firstOrNull { it.kind == "upset" && sameTie(e, it) }?.args?.firstOrNull()?.toDoubleOrNull()
            } else {
                null
            }
            out += write(e, name, club, team, rating, gap, scope)
        }
        return out
    }

    /**
     * A `title_race` event [leader, second] (gap, "lead" or "close") when the 1st division's
     * leader changed between [before] and [after] (its table at two matchdays, (key, points) best
     * first) with at least 25% of the league played, or when the gap closed to one point or less
     * late in the season (70%); null otherwise. Two leaders level on points both times only swap
     * places on the tie-break: that is not news (it could repeat every matchday).
     */
    fun titleRace(season: Int, before: List<Pair<String, Int>>, after: List<Pair<String, Int>>, progress: Double): LeagueEvent? {
        if (before.size < 2 || after.size < 2) return null
        val (leader, lp) = after[0]
        val (second, sp) = after[1]
        val gap = lp - sp
        val oldGap = before[0].second - before[1].second
        val reason = when {
            leader != before[0].first && progress >= LEAD_FROM && (gap > 0 || oldGap > 0) -> "lead"
            gap <= 1 && oldGap > 1 && progress >= CLOSE_FROM -> "close"
            else -> return null
        }
        return LeagueEvent("title_race", season, listOf(leader, second), listOf(gap.toString(), reason), Comp.LEAGUE)
    }

    const val LEAD_FROM = 0.25
    const val CLOSE_FROM = 0.7

    // ---- templates -----------------------------------------------------------------------------

    private fun templates(
        e: LeagueEvent,
        v: MutableMap<String, String>,
        arg: (Int) -> String?,
        club: (String) -> String,
        team: (String) -> String,
        rating: ((String) -> Double)?,
        upsetGap: Double?,
        name: (String) -> String,
    ): Pair<List<T>, Int> = when (e.kind) {
        "upset" -> {
            arg(0)?.let { v["gap"] = it }
            listOf(
                T("이변! {a}, {b} 격파", "[[레이팅이 {gap}점 낮은 ]]{a|이} {b|을} 꺾었어요. {comp}에서 나온 이번 시즌 손꼽히는 이변이에요."),
                T("{a}의 반란, {b} 침몰", "{a|은} 레이팅이 {b}보다 {gap}점이나 낮았지만 경기를 가져갔어요. 예상을 뒤집은 결과에 리그가 술렁여요."),
                T("약자의 반격: {a}, {b}에 승리", "{a}의 승리를 점친 이는 많지 않았어요. 레이팅 {gap}점 차를 뒤집고 {b|을} 넘어섰어요."),
            ) to 3
        }
        "comeback" -> {
            // (p, then the desk's: the competition's code, the fixture's label, the league, the tier).
            arg(0)?.let { v["p"] = percent(it) }
            where(arg(1), arg(2), arg(3), arg(4))?.let { v["where"] = it }
            listOf(
                T("대역전극! {a}, {b}에 뒤집기", "[[{where}에서 ]]{a|은} 승리 확률이 {p}%까지 떨어졌지만 끝내 경기를 뒤집었어요. {b|은} 다 잡은 경기를 놓쳤어요."),
                T("승리 확률 {p}%에서 살아난 {a}", "[[{where}에서 ]]한때 승리 확률이 {p}%에 그쳐 모두가 끝났다고 생각했어요. {a|이} 끝까지 버텨 {b|을} 꺾었어요."),
                T("{b}, 다 잡은 경기 놓쳐", "[[{where}에서 ]]{b|은} 승리를 눈앞에 두고 {a}에게 역전을 허용했어요. {a}의 승리 확률은 한때 {p}%에 불과했어요."),
                T("역전 드라마: {a}, {b} 제압", "[[{where}에서 ]]{a|이} 끌려가던 경기를 뒤집고 {b|을} 꺾었어요. 끝까지 포기하지 않은 결과예요."),
            ).let { all -> if (v["p"] != null) all.dropLast(1) else all } to 3
        }
        "record" -> {
            // The stats module names records by RecordKind (its values with their unit: "87점 차",
            // "레이팅 차 514"); older codes are lower case, their values plain numbers.
            val kind = arg(0)?.let { runCatching { RecordKind.valueOf(it) }.getOrNull() }
            arg(0)?.let { v["rec"] = kind?.label ?: RECORD_LABELS[it.lowercase(Locale.ROOT)] ?: it.replace('_', ' ') }
            fun shown(s: String): String = kind?.let { k -> s.toDoubleOrNull()?.let { k.withUnit(it) } } ?: number(s)
            arg(1)?.let { v["value"] = shown(it) }
            arg(2)?.let { v["prev"] = shown(it) }
            listOf(
                T("신기록! {a}, {rec} 경신", "{a|이} {rec} 기록을 {value|으로} 새로 썼어요.[[ 종전 기록은 {prev|이었어요}.]] 리그 역사에 이름을 남겼어요."),
                T("{rec} 새 역사: {a}", "{rec} 기록이 {value|으로} 바뀌었어요. 새 주인공은 {a|이에요}.[[ 종전 기록은 {prev|이었어요}.]]"),
                T("기록 경신: {rec} {value}", "{a|이} {rec}에서 {value|을} 찍었어요.[[ 종전 기록 {prev|을} 넘어섰어요.]] 오래 회자될 기록이에요."),
            ) to 4
        }
        "milestone" -> milestone(arg(0).orEmpty(), arg(1), v)
        "title" -> {
            val a0 = arg(0)
            // A division index, an honour kind ("L0", "CUP"), a competition ("CHAMPIONS") or its name.
            val division = a0?.toIntOrNull()?.takeIf { it in 0..98 } ?: a0?.takeIf { Regex("L\\d").matches(it) }?.drop(1)?.toInt()
            // A pyramid's group ("A조") after the division.
            val group = arg(1)?.takeIf { it.endsWith("조") }
            v["t"] = when {
                division != null && group != null -> "${division + 1}부 $group"
                division != null -> "${division + 1}부 리그"
                // A competition's code (the club league's "K").
                a0 != null && a0.length == 1 && Comp.of(a0[0]) != null -> Comp.of(a0[0])!!.label
                a0 != null -> honourLabel(a0) ?: Comp.entries.firstOrNull { it.name == a0 }?.label ?: a0
                else -> e.comp?.label ?: Comp.LEAGUE.label
            }
            val importance = when {
                e.comp == Comp.LEAGUE || e.comp == Comp.TITLE_PLAYOFF -> if (division == 0) 5 else 3
                e.comp == Comp.CHAMPIONS || e.comp == Comp.WORLD_CUP -> 5
                e.comp == Comp.CONTINENTAL || e.comp == Comp.CONT_CHAMPIONS -> 4
                e.comp == Comp.CUP || e.comp == Comp.FAMILY || e.comp == Comp.CLUB || e.comp == Comp.CLUB_CUP -> 4
                e.comp == Comp.ALL_STAR -> 2
                else -> 3
            }
            if (arg(1) == "first") listOf(
                T("{a}, 사상 첫 {t} 우승!", "{a|이} 사상 처음으로 {t} 정상에 올랐어요. 대표 5명 모두 나라의 역사를 새로 썼어요."),
                T("{a}의 첫 {t} 트로피", "{a|이} 처음으로 {t} 우승을 차지했어요. {a}에게 시즌 {season|은} 잊지 못할 시즌으로 남아요."),
            ) to importance else listOf(
                T("{a}, {t} 우승!", "{a|이} 시즌 {season}의 {t} 정상에 올랐어요. 시즌 내내 이어 온 노력이 결실을 맺었어요."),
                T("{t}의 주인은 {a}", "시즌 {season}의 {t} 우승 트로피는 {a}에게 돌아갔어요. 축하 인사가 쏟아지고 있어요."),
                T("정상 등극: {a}, {t} 제패", "{a|이} {t|을} 제패했어요. 이번 우승으로 리그 역사에 이름을 한 번 더 새겼어요."),
            ) to importance
        }
        "promoted", "relegated" -> {
            // Divisions are 0-based in the event: (to) or (from, to).
            val to = e.args.lastOrNull()?.toIntOrNull()
            val from = if (e.args.size >= 2) e.args[0].toIntOrNull() else null
            to?.let { v["to"] = "${it + 1}" }
            from?.let { v["from"] = "${it + 1}" }
            if (e.kind == "promoted") {
                listOf(
                    T("{a}, {to}부 승격", "{a|이} 다음 시즌 {to}부 무대를 밟아요. 한 단계 높은 곳에서 새 도전을 시작해요."),
                    T("올라간다! {a}, {to}부행 확정", "{a|이} 승격을 확정했어요. 다음 시즌에는 {to}부의 강자들과 맞붙어요."),
                    T("{a}의 승격 축포", "{a|이} {from}부를 떠나 {to}부로 올라가요. 꾸준한 성적이 만든 승격이에요."),
                    T("{a}, 승격 확정", "{a|이} 다음 시즌 한 단계 위에서 뛰어요. 꾸준한 성적이 만든 승격이에요."),
                ).let { if (to != null) it.dropLast(1) else it } to 3
            } else {
                listOf(
                    T("{a}, {to}부로 강등", "{a|이} 다음 시즌 {to}부에서 뛰어요. 아쉬운 시즌을 뒤로하고 재도약을 노려요."),
                    T("눈물의 강등: {a}", "{a|이} 끝내 강등을 피하지 못했어요. 다음 시즌은 {to}부에서 다시 시작해요."),
                    T("{a}, 강등 확정", "{a}의 {to}부 강등이 확정됐어요. 한 시즌 만에 돌아올 수 있을지 관심이 모여요."),
                    T("{a}, 강등의 쓴맛", "{a|이} 다음 시즌 한 단계 아래에서 뛰어요. 한 시즌 만에 돌아올 수 있을지 관심이 모여요."),
                ).let { if (to != null) it.dropLast(1) else it } to 3
            }
        }
        "cup_round" -> {
            arg(0)?.let { v["round"] = it }
            arg(1)?.let { v["score"] = it }
            val gap = upsetGap?.takeIf { !it.isNaN() && it >= Drama.UPSET_GAP } ?: rating?.let { r ->
                val w = e.keys.getOrNull(0)
                val l = e.keys.getOrNull(1)
                if (w == null || l == null) null else (r(l) - r(w)).takeIf { !it.isNaN() && it >= Drama.UPSET_GAP }
            }
            // 패자부활: a winners'-side loser drops to the losers' side, it is not out.
            val drop = arg(0)?.contains("승자조") == true
            when {
                drop && (e.comp == Comp.WORLD_CUP || e.comp == Comp.CONTINENTAL) -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾어", "{a|이} {comp} {round}에서 {b|을}[[ 보드 {score|으로}]] 물리쳤어요. {b|은} 패자조로 내려가 다시 도전해요."),
                    T("{a}, {comp} {round} 승리", "{a|이} {b|과} 맞붙은 {round}에서[[ 보드 {score|으로}]] 이겼어요. 진 {b|은} 패자조에서 한 번 더 기회를 노려요."),
                ) to if (e.comp == Comp.WORLD_CUP) 3 else 2
                drop && gap != null -> {
                    v["gap"] = Math.round(gap).toString()
                    listOf(
                        T("자이언트 킬링! {a}, {b} 잡아", "레이팅이 {gap}점 낮은 {a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 꺾었어요. {b|은} 패자조로 떨어졌어요."),
                        T("{comp}의 이변: 강호 {b}, 패자조로", "강호 {b|이} {round}에서 {a}에게[[ {score|으로}]] 졌어요. 패자조에서 다시 올라올 수 있을지 관심이 모여요."),
                    ) to 3
                }
                drop -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾어", "{a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 물리쳤어요. {b|은} 패자조에서 한 번 더 기회를 노려요."),
                    T("{a}, {round} 관문 통과", "{a|이} {b|과} 맞붙은 {comp} {round}에서[[ {score|으로}]] 이겼어요. {b|은} 패자조로 내려갔어요."),
                ) to 2
                e.comp == Comp.WORLD_CUP || e.comp == Comp.CONTINENTAL -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾고 진출", "{a|이} {comp} {round}에서 {b|을}[[ 보드 {score|으로}]] 물리쳤어요. 대표팀의 도전이 다음 라운드로 이어져요."),
                    T("{b}, {comp} {round}에서 탈락", "{b|은} {a}에[[ 보드 {score|으로}]] 져 이번 {comp|을} 마쳤어요. {a|은} 다음 라운드로 향해요."),
                ) to if (e.comp == Comp.WORLD_CUP) 3 else 2
                e.comp == Comp.PROMOTION_PLAYOFF -> listOf(
                    T("{round}: {a}의 승리", "{a|이} {round}에서 {b|을}[[ {score|으로}]] 꺾었어요. 다음 시즌 어느 부에서 뛸지가 이 승부로 갈렸어요."),
                    T("{round}, 웃은 쪽은 {a}", "{a|과} {b|이} 맞붙은 {round}에서 {a|이} 이겼어요. {b|은} 아쉬움을 삼켰어요."),
                ) to 3
                gap != null -> {
                    v["gap"] = Math.round(gap).toString()
                    listOf(
                        T("자이언트 킬링! {a}, {b} 잡고 {round} 통과", "레이팅이 {gap}점 낮은 {a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 꺾었어요. 컵 대회다운 반전이에요."),
                        T("{comp}의 이변: 강호 {b} 탈락", "강호 {b|이} {round}에서 {a}에게[[ {score|으로}]] 졌어요. {a|은} 레이팅 {gap}점 차를 뛰어넘었어요."),
                    ) to if (LAST_EIGHT.containsMatchIn(arg(0).orEmpty())) 3 else 2
                }
                else -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾고 진출", "{a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 물리쳤어요. 다음 라운드에서 또 한 번 승리를 노려요."),
                    T("{a}, {round} 관문 통과", "{a|이} {b|과} 맞붙은 {comp} {round}에서[[ {score|으로}]] 이겼어요. 우승까지 한 걸음 더 다가갔어요."),
                    T("{b}, {comp} {round}에서 멈춰", "{b|은} {a}에게[[ {score|으로}]] 져 {comp} 여정을 마쳤어요. {a|은} 다음 라운드로 향해요."),
                ) to 2
            }
        }
        "eliminated" -> {
            arg(0)?.let { v["round"] = it }
            listOf(
                T("{a}, {comp} {round}에서 탈락", "우승 후보로 꼽히던 {a|이} {round}에서 짐을 쌌어요. 이번 시즌 {comp} 도전은 여기까지예요."),
                T("충격의 탈락: {a}", "{a|이} {comp} {round}에서 고배를 마셨어요. 높은 시드가 무색한 결과예요."),
            ) to 2
        }
        "qualified" -> {
            arg(0)?.let { v["stage"] = it }
            arg(1)?.let { v["place"] = it }
            listOf(
                if (arg(0)?.contains("승자조") == true) {
                    T("{a}, {comp} {stage} 진출", "{a|이}[[ {place|으로}]] {comp} {stage}에 올랐어요. 이제부터는 두 번 지면 끝나는 패자부활 토너먼트예요.")
                } else {
                    T("{a}, {comp} {stage} 진출", "{a|이}[[ {place|으로}]] {comp} {stage}에 올랐어요. 이제부터는 지면 끝나는 토너먼트예요.")
                },
                T("{stage} 티켓을 손에 넣은 {a}", "{a|이} {comp} {stage} 진출을 확정했어요.[[ 조별 리그를 {place|으로} 마쳤어요.]] 우승을 향한 도전이 이어져요."),
            ) to 2
        }
        "final" -> {
            arg(0)?.let { v["score"] = it }
            when (e.comp) {
                Comp.SUPER -> listOf(
                    T("{a}, 슈퍼컵 들어 올려", "{a|이} 슈퍼컵에서 {b|을}[[ {score|으로}]] 꺾었어요. 새 시즌 첫 트로피의 주인공이에요."),
                    T("슈퍼컵 승자는 {a}", "{a|과} {b}의 슈퍼컵 맞대결에서 {a|이} 이겼어요. 기분 좋게 새 시즌을 시작해요."),
                ) to 3
                Comp.WORLD_CUP -> listOf(
                    T("{a}, 요트 월드컵 정상!", "{a|이} 결승에서 {b|을}[[ 보드 {score|으로}]] 꺾고 시즌 {season} 요트 월드컵 챔피언이 됐어요. 대표 5명 모두 우승 멤버로 이름을 남겨요."),
                    T("월드컵 결승: {a}, {b} 제압", "결승에서 {a|과} {b|이} 맞붙었고, {a|이} 웃었어요.[[ 보드 스코어는 {score|이에요}.]] {b|은} 준우승에 만족해야 했어요."),
                ) to 5
                Comp.ALL_STAR -> listOf(
                    T("올스타전: {a}, {b}에 승리", "{a|이} 올스타전에서 {b|을}[[ {score|으로}]] 꺾었어요. 리그 반환점을 수놓은 축제였어요."),
                    T("별들의 잔치, 웃은 쪽은 {a}", "올스타전에서 {a|이} {b|을} 눌렀어요. 승패를 떠나 팬들에게는 즐거운 무대였어요."),
                ) to 2
                else -> listOf(
                    T("{comp} 결승: {a}, {b} 꺾고 정상", "{a|이} {comp} 결승에서 {b|을}[[ {score|으로}]] 꺾었어요. 시즌 {season} {comp} 트로피를 들어 올렸어요."),
                    T("{a}, {comp} 제패", "결승에서 {a|과} {b|이} 맞붙었고, {a|이} 웃었어요.[[ 최종 스코어는 {score|이에요}.]] {b|은} 준우승에 만족해야 했어요."),
                    T("{b}, 결승에서 아쉬운 준우승", "{b|은} {comp} 결승에서 {a}에게[[ {score|으로}]] 졌어요. 우승은 {a}의 몫이 됐어요."),
                ) to 4
            }
        }
        "transfer" -> {
            // [ai, fromClub, toClub]; a free agent has "-" (or no) selling club.
            val free = e.keys.size < 3 || e.keys[1] == "-" || e.keys[1].isEmpty()
            v.remove("b")
            v.remove("c")
            val clubs = e.keys.drop(1).filter { it != "-" && it.isNotEmpty() }
            if (free) clubs.lastOrNull()?.let { v["to"] = club(it) } else {
                v["from"] = club(e.keys[1])
                v["to"] = club(e.keys[2])
            }
            arg(0)?.let { v["fee"] = number(it) }
            if (free) {
                listOf(
                    T("{to}, 자유계약 선수 {a} 영입", "자유계약 선수였던 {a|이} {to}의 유니폼을 입어요.[[ 몸값은 {fee|이에요}.]] 새 팀에서 기회를 잡을지 지켜봐요."),
                    T("{a}, {to|으로} 새 출발", "소속 클럽이 없던 {a|이} {to|과} 손을 잡았어요.[[ 몸값은 {fee|이에요}.]] 다음 경기부터 활약이 기대돼요."),
                ) to 2
            } else {
                listOf(
                    T("{a}, {to|으로} 이적", "{a|이} {from}에서 {to|으로} 옮겨요.[[ 이적료는 {fee|이에요}.]] 새 팀에서 주전 경쟁에 나서요."),
                    T("{to}, {from}의 {a} 영입", "{to|이} {from}에서 뛰던 {a|을} 데려왔어요.[[ 이적료 {fee|을} 들인 승부수예요.]] 전력 보강에 속도를 내요."),
                ) to 2
            }
        }
        "draft" -> {
            v.remove("b")
            e.keys.getOrNull(1)?.takeIf { it != "-" }?.let { v["club"] = club(it) }
            arg(0)?.let { v["pick"] = it }
            listOf(
                T("{club}, 드래프트로 {a} 지명", "{club|이} 드래프트[[ {pick}순위]]로 {a|을} 뽑았어요. {a|은} 다음 시즌 {club} 유니폼을 입고 뛰어요."),
                T("{a}, {club}의 부름 받아", "{club|이} 드래프트에서 {a|을} 선택했어요. 빈자리를 채울 새 얼굴이에요."),
            ) to 1
        }
        "born" -> when {
            v["b"] != null && v["c"] != null && v["b"] != v["c"] -> listOf(
                T("{b}·{c}의 제자 {a} 데뷔", "{b|과} {c}에게 배운 {a|이} 리그에 데뷔해요. 스승의 장점을 얼마나 물려받았을지 지켜봐요."),
                T("{b}의 제자 {a}, 첫발", "{b|이} 직접 가르친 {a|이} 가장 낮은 부에서 데뷔 시즌을 시작해요. {c}의 가르침도 받았어요."),
            )
            v["b"] != null -> listOf(
                T("{b}의 후계자 {a} 데뷔", "{b}의 스타일을 이어받은 {a|이} 리그에 합류해요. 가장 낮은 부에서 데뷔 시즌을 시작해요."),
            )
            else -> listOf(
                T("새 얼굴 {a} 데뷔", "새 선수 {a|이} 리그에 합류해요. 가장 낮은 부에서 데뷔 시즌을 시작해요."),
            )
        } to 1
        "retired" -> {
            arg(0)?.let { v["seasons"] = it }
            arg(2)?.takeIf { it != "0" }?.let { v["age"] = it }
            val titles = arg(1)?.toIntOrNull() ?: 0
            if (titles > 0) {
                v["titles"] = titles.toString()
                listOf(
                    T("{a}, {age}세로 은퇴 선언", "{a|이}[[ {seasons}시즌의]] 선수 생활을 마쳐요. 우승 {titles}회의 기록은 레전드 보관소에 남아요."),
                    T("굿바이 {a}", "우승 {titles}회에 빛나는 {a|이}[[ {age}세에]] 유니폼을 벗어요. 그동안 보여 준 경기는 오래 기억될 거예요."),
                ) to 3
            } else {
                listOf(
                    T("{a}, {age}세로 은퇴", "{a|이}[[ {seasons}시즌의]] 선수 생활을 마쳐요. 우승과는 인연이 없었지만 끝까지 최선을 다했어요."),
                    T("{a}, 리그와 작별", "{a|이} 은퇴해요. 그 자리는 새 얼굴이 채워요."),
                ) to 2
            }
        }
        "retirements" -> {
            arg(0)?.let { v["count"] = it }
            listOf(
                T("시즌 {season} 은퇴 선수 {count}명", "이번 시즌을 끝으로 {count}명이 선수 생활을 마쳐요. {seeds} 등이 정든 무대를 떠나요."),
                T("베테랑 {count}명, 유니폼을 벗어요", "시즌 {season|을} 끝으로 {count}명이 리그를 떠나요. {a|도} 그중 한 명이에요. 빈자리는 신인과 제자들이 채워요."),
            ) to 2
        }
        "rookies" -> {
            arg(0)?.let { v["count"] = it }
            listOf(
                T("시즌 {season} 신인 {count}명 합류", "이번 시즌에도 새 얼굴 {count}명이 리그에 들어와요. {seeds|이} 가장 주목받는 신인이에요."),
                T("신인 {count}명, 데뷔 준비 끝", "{a|을} 비롯한 신인 {count}명이 첫 시즌을 맞아요. 모두 가장 낮은 부에서 데뷔해요."),
            ) to 2
        }
        "debut" -> {
            val nation = Nations.of(arg(0))
            nation?.let { v["nat"] = it.short }
            arg(1)?.let { v["age"] = it }
            arg(2)?.let { v["town"] = it }
            val potential = arg(3)?.toIntOrNull() ?: 0
            when {
                potential >= 85 && (nation == null || nation == Nations.KR) -> listOf(
                    T("대형 신인 {a}, 리그 합류", "{town} 출신의 {age}세 {a|이} 데뷔해요. 스카우트들이 일찌감치 주목한 유망주예요."),
                )
                potential >= 85 -> listOf(
                    T("대형 신인 {a}, 리그 합류", "{nat} {town} 출신의 {age}세 {a|이} 데뷔해요. 스카우트들이 일찌감치 주목한 유망주예요."),
                    T("{nat}의 보석 {a}", "{age}세 {a|이} 리그에 첫발을 내디뎌요. {town}에서 온 이 신인을 두고 벌써 기대가 커요."),
                )
                nation == null || nation == Nations.KR -> listOf(
                    T("{town} 출신 신예 {a} 데뷔", "{age}세 {a|이} 리그에 첫발을 내디뎌요. 가장 낮은 부에서 데뷔 시즌을 시작해요."),
                )
                else -> listOf(
                    T("{nat}에서 온 신예 {a} 데뷔", "{age}세 {a|이} 리그에 첫발을 내디뎌요. {town} 출신으로, 가장 낮은 부에서 데뷔 시즌을 시작해요."),
                )
            } to if (potential >= 85) 3 else 2
        }
        "breakout", "decline" -> {
            arg(0)?.let { v["age"] = it }
            arg(1)?.toIntOrNull()?.let { i -> Abilities.LABELS.getOrNull(i)?.let { v["skill"] = it } }
            arg(2)?.let { v["from"] = it }
            arg(3)?.let { v["to"] = it }
            if (e.kind == "breakout") {
                listOf(
                    T("{a}, 한 시즌 만에 몰라보게 성장", "{age}세 {a}의 {skill|이} {from}에서 {to|으로} 올랐어요. 무서운 성장세예요."),
                    T("성장하는 {a}", "{a}의 실력이 한 시즌 사이 부쩍 늘었어요. {skill|이} {from}에서 {to|으로} 뛰었어요. 다음 시즌이 더 기대돼요."),
                ) to 2
            } else {
                listOf(
                    T("{a}, 세월은 못 이겨", "{age}세 {a}의 {skill|이} {from}에서 {to|으로} 떨어졌어요. 노련함으로 버틸 수 있을지 지켜봐요."),
                    T("{a}에게 찾아온 내리막", "{a}의 {skill|이} 한 시즌 만에 {from}에서 {to|으로} 내려갔어요. 은퇴 시기를 고민할 나이예요."),
                ) to 2
            }
        }
        "learned_rule" -> {
            arg(0)?.let { k -> PlayStyles.recipe(k)?.let { v["belief"] = it.name } }
            listOf(
                T("{a}, 규칙 착각에서 벗어나", "{a|이} 그동안 잘못 알고 있던 규칙('{belief}')을 바로잡았어요. 다음 시즌 진짜 실력이 나올지 지켜봐요."),
            ) to 2
        }
        "league_reform" -> listOf(
            T("리그 개편: 생성 선수들의 시대", "시즌 {season|을} 끝으로 리그가 새로 태어나요. 선수마다 나라·나이·능력치가 생기고, 나이가 들면 은퇴하고 신인과 제자가 그 자리를 채워요."),
        ) to 5
        "founders_farewell" -> {
            arg(0)?.let { v["count"] = it }
            listOf(
                T("창립 멤버 {count}명, 리그와 작별", "기본 AI {count}명이 시즌 {season|을} 끝으로 리그를 떠나요. {seeds}의 우승 기록과 통산 성적은 그대로 남아요. 다음 시즌부터는 생성 선수들만의 리그예요."),
                T("고마웠어요, 창립 멤버", "{seeds} 등 리그의 첫 시즌부터 함께한 기본 AI {count}명이 유니폼을 벗었어요. 이제 랭크 게임과 AI 연구소에서 다시 만날 수 있어요."),
            ) to 5
        }
        "lower_tiers" -> {
            // A pyramid's lower tiers in one article: (group champions, players promoted, first tier, last tier);
            // national leagues' (champions of every lower tier, players promoted, 1, deepest tier, "national").
            arg(0)?.let { v["count"] = it }
            arg(1)?.let { v["up"] = it }
            arg(2)?.toIntOrNull()?.let { v["from"] = "${it + 1}부" }
            arg(3)?.toIntOrNull()?.let { v["to"] = "${it + 1}부" }
            if (arg(4) == NATIONAL) {
                v["range"] = if (v["from"] == v["to"]) v["from"].orEmpty() else "${v["from"].orEmpty()}~${v["to"].orEmpty()}"
                listOf(
                    T("각 리그 하위 부 결산", "각 리그의 {range}에서 우승자 {count}명이 가려졌어요. {seeds|이} 그중 돋보였고, 모두 {up}명이 한 단계 위로 올라가요."),
                    T("{seeds}, 하위 부 정상", "시즌 {season} 각 리그의 {range}에서 {count}명이 우승을 차지했어요. 다음 시즌에는 {up}명이 한 단계 위에서 새 도전을 시작해요."),
                ) to 2
            } else {
                listOf(
                    T("지역 리그 결산: {from}~{to}", "{from}부터 {to}까지 지역 리그 {count}개 조의 우승자가 가려졌어요. {seeds|이} 조 1위에 올랐고, 모두 {up}명이 한 단계 위로 올라가요."),
                    T("{seeds}, 지역 리그 정상", "시즌 {season} 지역 리그({from}~{to})에서 {count}명이 조 우승을 차지했어요. 다음 시즌에는 {up}명이 윗 리그에서 새 도전을 시작해요."),
                ) to 2
            }
        }
        "award" -> {
            val kind = arg(0)
            // A national season's awards are the 요트협회's, and its MVP is the player of the year.
            val national = arg(1) == NATIONAL
            kind?.let { v["award"] = if (national && it == "MVP") "올해의 선수" else AWARD_LABELS[it] ?: it }
            if (national) listOf(
                T("{a}, 시즌 {season} {award} 수상", "요트협회가 시즌 {season} {award|으로} {a|을} 뽑았어요. 한 시즌 동안 가장 빛난 이름이에요."),
                T("요트협회 시상: {award} {a}", "요트협회가 시즌 {season} {award} 수상자를 발표했어요. 주인공은 {a|이에요}."),
            ) to (if (kind == "MVP") 5 else 4) else listOf(
                T("{a}, 시즌 {season} {award} 수상", "{a|이} 시즌 {season} {award}의 주인공이 됐어요. 한 시즌 동안 가장 빛난 이름이에요."),
                T("{award|은} {a}", "시즌 {season} {award|은} {a}에게 돌아갔어요. 시상식의 주인공이 된 소감이 궁금해요."),
            ) to if (kind == "MVP") 5 else 4
        }
        "callup", "dropped" -> {
            v["fam"] = arg(0)?.let { a -> Family.entries.firstOrNull { it.name == a }?.let { team(it.name) } ?: a } ?: "계열"
            if (e.kind == "callup") {
                listOf(
                    T("{a}, {fam} 대표 발탁", "{a|이} 계열 대항전 {fam} 대표로 뽑혔어요. 지난 대회에는 없던 이름이에요."),
                    T("{fam}, 새 얼굴 {a} 승선", "{fam} 대표 명단에 {a|이} 새로 이름을 올렸어요. 계열의 자존심을 걸고 뛰어요."),
                ) to 1
            } else {
                listOf(
                    T("{a}, {fam} 대표 탈락", "지난 대회 {fam} 대표였던 {a|이} 이번에는 명단에서 빠졌어요. 레이팅 순으로 뽑는 자동 선발의 냉정한 결과예요."),
                    T("{fam} 명단 변화: {a} 제외", "{a|이} {fam} 대표 명단에서 제외됐어요. 다음 대회 복귀를 노려요."),
                ) to 1
            }
        }
        "title_race" -> {
            val gap = arg(0)
            gap?.let { v["gap"] = it }
            // A national league's race names its league (the third argument).
            v["top"] = arg(2)?.let { "${LeagueIds.label(it)} 1부" } ?: "1부 리그"
            when {
                gap == "0" -> listOf(
                    T("선두 다툼 안갯속: {a}·{b} 승점 동률", "{a|과} {b}의 승점이 같아요. 남은 경기에서 한 번만 미끄러져도 순위가 바뀌어요."),
                    T("{a}·{b}, 승점 같은 채 선두 경쟁", "{top} 1위와 2위의 승점이 같아요. 우승의 향방은 마지막까지 알 수 없어요."),
                ) to 3
                arg(1) == "lead" -> listOf(
                    T("선두 교체! {a}, 1위로", "{a|이} {b|을} 끌어내리고 {top} 선두에 올랐어요. 두 선수의 승점 차는 {gap}점이에요."),
                    T("{a}, {top} 선두 등극", "{a|이} 1위 자리를 차지했어요. 2위 {b|과} 승점 {gap}점 차라 아직 안심할 수 없어요."),
                ) to 4
                else -> listOf(
                    T("우승 경쟁 점입가경: {a}·{b} {gap}점 차", "시즌 막바지, 선두 {a|과} 2위 {b}의 승점 차가 {gap}점으로 좁혀졌어요. 마지막까지 우승의 향방을 알 수 없어요."),
                    T("{b}, 선두 {a} 턱밑 추격", "{b|이} 선두 {a|을} 승점 {gap}점 차로 쫓고 있어요. 남은 라운드가 우승을 가를 거예요."),
                ) to 3
            }
        }
        "hall_of_fame" -> listOf(
            T("{a}, 명예의 전당 헌액", "{a|이} 명예의 전당에 이름을 올렸어요. 리그 역사에 길이 남을 선수예요."),
            T("전설이 된 {a}", "{a|이} 명예의 전당에 들어갔어요. 쌓아 온 기록이 그 자격을 증명해요."),
        ) to 4
        "dynasty" -> {
            // The stats module sends (RUN, count, title kind) for runs of titles.
            val kind = arg(0).orEmpty().let { if (it == "RUN") arg(2).orEmpty() else it }
            arg(1)?.let { v["count"] = it }
            // A national league's double, treble or unbeaten title names its league (the title kind, "L0.KR").
            val league = arg(2)?.takeIf { HonourKind.leagueOf(it) != null }?.let { honourLabel(it) }
            league?.let { v["lg"] = it }
            when (kind.lowercase(Locale.ROOT)) {
                "double" -> if (league != null) {
                    listOf(T("{a}, {lg}·요트컵 더블 달성", "{a|이} 한 시즌에 {lg|과} 요트컵을 모두 차지했어요. 흔치 않은 더블이에요."))
                } else {
                    listOf(T("{a}, 리그·컵 더블 달성", "{a|이} 한 시즌에 리그와 요트컵을 모두 차지했어요. 흔치 않은 더블이에요."))
                }
                "treble" -> if (league != null) {
                    listOf(T("{a}, 꿈의 트레블", "{a|이} {lg}, 요트컵에 국제 대회 우승까지 한 시즌에 모두 이뤘어요. 리그 역사에 남을 시즌이에요."))
                } else {
                    listOf(T("{a}, 꿈의 트레블", "{a|이} 리그, 요트컵, 챔피언스 리그를 모두 들어 올렸어요. 리그 역사에 남을 시즌이에요."))
                }
                "unbeaten" -> if (league != null) {
                    listOf(T("{a}, {lg} 무패 우승", "{a|이} 한 경기도 지지 않고 {lg} 정상에 섰어요. 완벽한 시즌이에요."))
                } else {
                    listOf(T("{a}, 무패 우승", "{a|이} 한 경기도 지지 않고 리그 정상에 섰어요. 완벽한 시즌이에요."))
                }
                else -> {
                    v["dyn"] = honourLabel(kind) ?: e.comp?.label ?: Comp.LEAGUE.label
                    // Cups held every few seasons count editions, not seasons.
                    v["unit"] = if (kind.uppercase(Locale.ROOT) in EDITION_KINDS) "대회" else "시즌"
                    listOf(
                        T("{a}, {dyn} {count}연속 우승", "{a|이} {dyn}에서 {count}{unit} 연속 정상에 올랐어요. 왕조가 시작된 걸까요?"),
                        T("{a} 왕조, {dyn} {count}연속 제패", "{dyn|은} 이번에도 {a}의 차지였어요. 벌써 {count}{unit} 연속이에요."),
                    )
                }
            } to 4
        }
        "legend_match" -> {
            val result = arg(0)
            val score = result?.split('-')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }
            val won = when {
                score != null -> score[0].compareTo(score[1])
                result in setOf("win", "W", "1") -> 1
                result in setOf("loss", "L", "-1") -> -1
                else -> 0
            }
            if (score != null) v["score"] = if (won < 0) result!!.split('-').reversed().joinToString("-") else result!!
            when {
                won > 0 -> listOf(
                    T("레전드 {a}, 건재 과시", "레전드 매치에서 {a|이} 현역 강자 {b|을}[[ {score|으로}]] 꺾었어요. 세월이 흘러도 실력은 그대로예요."),
                    T("돌아온 전설 {a}, {b}에 승리", "{a|이} 레전드 매치에서 {b|을} 눌렀어요. 왜 레전드인지 보여 준 경기였어요."),
                )
                won < 0 -> listOf(
                    T("{b}, 레전드 {a} 넘어", "{b|이} 레전드 매치에서 {a|을}[[ {score|으로}]] 이겼어요. 새 시대가 왔음을 알렸어요."),
                    T("세대교체? {b}, 레전드 {a} 제압", "현역 강자 {b|이} 레전드 {a|을} 꺾었어요. 레전드도 박수로 승자를 축하했어요."),
                )
                else -> listOf(
                    T("레전드 매치: {a} 대 {b}", "레전드 {a|과} 현역 강자 {b|이} 맞붙었어요.[[ 결과는 {score|이에요}.]] 팬들에게는 즐거운 선물이었어요."),
                )
            } to 3
        }
        "draw_cup" -> {
            arg(0)?.let { v["n"] = it }
            arg(1)?.let { v["round"] = it }
            arg(2)?.takeIf { it != "0" }?.let { v["byes"] = it }
            listOf(
                T("시즌 {season} 요트컵 대진 확정", "[[{n}명이 나서는 ]]요트컵 대진이 나왔어요. {seeds|이} 우승 후보로 꼽혀요.[[ 상위 시드 {byes}명은 부전승으로 출발해요.]]"),
                T("요트컵 개막, 1번 시드는 {a}", "시즌 {season} 요트컵 대진 추첨이 끝났어요.[[ 첫 경기는 {round}부터예요.]] 1번 시드 {a|이} 정상을 노려요."),
            ) to 2
        }
        "draw_cl" -> {
            v["group"] = arg(0) ?: "조"
            listOf(
                T("챔피언스 리그 {group} 편성 완료", "{group}에는 {seeds|이} 모였어요. 두 장의 8강 티켓을 두고 겨뤄요."),
                T("챔스 {group}: {seeds}", "챔피언스 리그 {group}에서 {seeds|이} 맞붙어요. 조 2위 안에 들어야 8강에 올라요."),
            ) to 2
        }
        "draw_super" -> listOf(
            T("슈퍼컵 대진: {a} 대 {b}", "지난 시즌 챔피언 {a|과} 컵 강자 {b|이} 슈퍼컵에서 맞붙어요. 새 시즌의 첫 트로피가 걸려 있어요."),
            T("새 시즌 서막, 슈퍼컵 {a}·{b} 격돌", "{a|과} {b|이} 시즌 첫 트로피를 두고 다퉈요. 승자는 기분 좋게 시즌을 시작해요."),
        ) to 2
        "draw_allstar" -> {
            arg(0)?.let { v["team"] = team(it) }
            listOf(
                T("{team} 명단 발표", "{team}에 {seeds|이} 뽑혔어요. 올스타전은 리그 반환점에서 열려요."),
                T("올스타전 {team}: {a} 등 {count}명", "{team} 명단이 나왔어요. {seeds|이} 한 팀으로 뛰어요."),
            ) to 2
        }
        "draw_family" -> {
            v["group"] = arg(0) ?: "조"
            listOf(
                T("계열 대항전 {group} 편성", "{group}에서 {seeds|이} 맞붙어요. 각 계열은 레이팅 상위 5명으로 대표팀을 꾸려요."),
                T("계열 대항전 개막, {group}에 {seeds}", "계열의 자존심을 건 대항전이 시작돼요. {group}에서는 {seeds|이} 경쟁해요."),
            ) to 2
        }
        "wc_open" -> {
            arg(0)?.let { v["n"] = it }
            listOf(
                T("시즌 {season} 요트 월드컵, {n}개국 출전", "{n}개국 대표팀이 시즌 {season} 요트 월드컵에 나서요.[[ 개최국은 {a|이에요}.]] 나라마다 레이팅 상위 5명이 대표로 뽑혀요."),
                T("요트 월드컵 시즌이 돌아왔어요", "4시즌마다 열리는 요트 월드컵이 시즌 {season}에 돌아와요.[[ 개최국 {a|을} 포함해 ]]{n}개국이 우승컵을 다퉈요."),
            ) to 3
        }
        "cc_open" -> {
            arg(0)?.let { v["cups"] = it }
            listOf(
                T("시즌 {season} 대륙컵 개막", "{cups|이} 한꺼번에 열려요. 대륙마다 최강의 대표팀을 가려요."),
                T("대륙 챔피언을 가리는 시즌", "시즌 {season}에는 {cups|이} 열려요. 2시즌 뒤에는 요트 월드컵이 기다려요."),
            ) to 2
        }
        "draw_wc" -> {
            arg(0)?.let { v["group"] = it }
            arg(1)?.let { v["ko"] = it }
            e.args.getOrNull(2)?.let { team(it).takeIf { t -> t != it }?.let { t -> v["host"] = t } }
            listOf(
                T("요트 월드컵 {group} 편성", "{group}에서 {seeds|이} {ko} 티켓을 다퉈요.[[ 개최국 {host|은} {group} 1번 시드로 나서요.]]"),
                T("월드컵 {group}: {seeds}", "시즌 {season} 요트 월드컵 {group} 추첨이 끝났어요. {a|이} 1번 시드로 조 1위를 노려요."),
            ) to 2
        }
        "draw_cc" -> {
            arg(0)?.let { v["group"] = it }
            listOf(
                T("{comp} {group} 편성", "{comp} {group}에서 {seeds|이} 맞붙어요. 대륙 챔피언을 향한 첫걸음이에요."),
                T("{comp} 대진: {seeds}", "{comp} {group} 추첨이 끝났어요. 1번 시드 {a|이} 정상을 노려요."),
            ) to 2
        }
        "nation_squad" -> {
            // [new players..., dropped players...] (nation, cup, new count)
            val nNew = arg(2)?.toIntOrNull() ?: 0
            arg(0)?.let { v["nat"] = team(it) }
            arg(1)?.let { v["cup"] = it }
            val all = e.keys.map { name(it) }
            all.take(nNew).joinToString("·").takeIf { it.isNotEmpty() }?.let { v["new"] = it }
            all.drop(nNew).joinToString("·").takeIf { it.isNotEmpty() }?.let { v["out"] = it }
            listOf(
                T("{nat} 대표팀 명단 발표", "{cup}에 나설 {nat} 대표 5명이 정해졌어요.[[ 새로 뽑힌 선수는 {new|이에요}.]][[ {out|은} 이번에는 명단에서 빠졌어요.]]"),
                T("{nat}, {cup} 명단에 변화", "[[{new|이} 새로 {nat} 유니폼을 입어요. ]][[{out|은} 레이팅 순 자동 선발에서 밀려났어요.]]"),
            ) to 1
        }
        "ko_set" -> {
            arg(0)?.let { v["round"] = it }
            listOf(
                T("{comp} {round} 대진 확정", "조별 리그가 끝나고 {count}개국이 {round}에 올랐어요. 1번 시드 {a|과} {b}·{c} 등이 우승 후보로 꼽혀요."),
                if (arg(0)?.contains("승자조") == true) {
                    T("{comp}, 이제부터 토너먼트", "{count}개국이 {comp} {round}에 진출했어요. 두 번 지면 끝나는 패자부활 토너먼트가 시작돼요.")
                } else {
                    T("{comp}, 이제부터 토너먼트", "{count}개국이 {comp} {round}에 진출했어요. 지면 끝나는 승부가 시작돼요.")
                },
            ) to 3
        }
        "rivalry" -> {
            arg(0)?.let { v["label"] = it }
            arg(1)?.let { v["score"] = it }
            arg(2)?.let { v["stage"] = it }
            listOf(
                T("{label}: {a}, {b}에 승리", "{comp} {stage} {label}에서 {a|이} {b|을}[[ 보드 {score|으로}]] 꺾었어요. 자존심이 걸린 맞대결이었어요."),
                T("{label}의 승자는 {a}", "{a|과} {b}의 {label|은} {a}의 승리로 끝났어요.[[ 보드 스코어는 {score|이에요}.]] 다음 맞대결이 벌써 기다려져요."),
            ) to 3
        }
        "player_of_week" -> {
            arg(0)?.let { v["week"] = it }
            arg(1)?.let { v["avg"] = it }
            arg(2)?.let { v["best"] = it }
            // The tier within its league; a national league's pick names the league ("한국 1부").
            arg(3)?.toIntOrNull()?.let { t -> v["div"] = arg(4)?.let { LeagueIds.division(it, t) } ?: "${t + 1}부" }
            listOf(
                T("{week}주차 이 주의 선수: {a}", "{a|이} {week}주차 경기에서 {b|을} 꺾으며 평균 {avg}점을 기록했어요. 이번 주 리그에서 가장 빛난 선수예요."),
                T("이 주의 선수 {a}, 평균 {avg}점", "[[{div} ]]{a|이} {b|을} 상대로 최고 {best}점까지 올렸어요. {week}주차 이 주의 선수로 뽑혔어요."),
            ) to 2
        }
        "draw_legend" -> listOf(
            T("레전드 매치: {a} vs {b}", "은퇴한 레전드 {a|이} 전성기의 모습으로 돌아와 현역 {b|과} 맞붙어요. 순위와 상관없는 이벤트 경기예요."),
            T("돌아온 {a}, {b}에 도전장", "{a|이} 레전드 매치로 다시 무대에 서요. 상대는 지금 리그를 이끄는 {b|이에요}."),
        ) to 3
        "style_trend" -> {
            arg(0)?.let { k -> PlayStyles.recipe(k)?.let { v["style"] = it.name } }
            listOf(
                T("월드컵 우승 효과: '{style}' 유행", "{a|이} 요트 월드컵을 들어 올리자 '{style}' 스타일이 유행이에요. 앞으로 몇 시즌 동안 이 스타일로 두는 신인이 늘어날 거예요."),
                T("{a}처럼 두고 싶어요", "월드컵 챔피언 {a}의 주무기 '{style}'에 신인들이 빠져들었어요. 리그의 흐름이 바뀔지 지켜봐요."),
            ) to 3
        }
        "nation_awards" -> {
            // [mvp, top scorer] (cup, mvp's board points, top scorer's average)
            arg(0)?.let { v["cup"] = it }
            arg(1)?.let { v["pts"] = it }
            arg(2)?.let { v["avg"] = it }
            if (e.keys.size >= 2 && e.keys[0] == e.keys[1]) listOf(
                T("{a}, {cup} MVP·득점왕 석권", "{a|이} 보드 승점 {pts}점, 평균 {avg}점으로 MVP와 득점왕을 모두 차지했어요. 대회 내내 가장 빛난 선수예요."),
            ) to 3 else listOf(
                T("{cup} MVP는 {a}", "{a|이} 보드 승점 {pts}점으로 {cup} MVP에 뽑혔어요.[[ 득점왕은 평균 {avg}점의 {b|이에요}.]]"),
                T("{cup} 개인상: MVP {a}, 득점왕 {b}", "대회 최우수 선수는 {a}, 평균 점수가 가장 높은 득점왕은 {b|이에요}. 두 선수 모두 대회 내내 빛났어요."),
            ) to 3
        }
        "season_review" -> {
            // [league champion, cup winner] (either may be "-"); national leagues' (the best-ranked league).
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            when {
                v["a"] != null && v["lg"] != null -> listOf(
                    T("시즌 {season} 결산: {a}의 해", "시즌 {season|이} 막을 내렸어요. {a|이} {lg} 정상에 올랐어요.[[ 요트컵은 {b|이} 가져갔어요.]]"),
                    T("시즌 {season} 마무리, {lg} 챔피언은 {a}", "치열했던 시즌 {season|이} 끝났어요. {lg} 우승 트로피는 {a}에게 돌아갔고, 다음 시즌 준비가 시작돼요."),
                )
                v["a"] != null -> listOf(
                    T("시즌 {season} 결산: {a}의 해", "시즌 {season|이} 막을 내렸어요. {a|이} 1부 리그 정상에 올랐어요.[[ 요트컵은 {b|이} 가져갔어요.]]"),
                    T("시즌 {season} 마무리, 챔피언은 {a}", "치열했던 시즌 {season|이} 끝났어요. 우승 트로피는 {a}에게 돌아갔고, 다음 시즌 준비가 시작돼요."),
                )
                else -> listOf(T("시즌 {season} 결산", "시즌 {season|이} 막을 내렸어요. 시상식과 기록은 기록 탭에서 볼 수 있어요."))
            } to 5
        }
        else -> worldTemplates(e, v, arg, club, name)
    }

    /**
     * The national leagues', clubs', international stage's and 요트협회's kinds (design ENG 1.12, 2,
     * 3, 5.2, 6.8): league ids and the 요트협회's texts come in the arguments, clubs as keys or by
     * [club], players by [name].
     */
    private fun worldTemplates(
        e: LeagueEvent,
        v: MutableMap<String, String>,
        arg: (Int) -> String?,
        club: (String) -> String,
        name: (String) -> String,
    ): Pair<List<T>, Int> = when (e.kind) {
        "league_title" -> {
            // [champion] (league, "0", "po" when its title playoff decided it, rank among the best-ranked).
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            val rank = arg(3)?.toIntOrNull() ?: 0
            if (arg(2) == "po") listOf(
                T("{a}, 플레이오프 끝에 {lg} 우승", "{a|이} {lg} 우승 플레이오프를 이겨 내고 시즌 {season} 챔피언이 됐어요. 마지막 고비를 넘은 끝에 들어 올린 트로피예요."),
                T("{lg} 플레이오프의 승자는 {a}", "시즌 {season} {lg} 우승은 플레이오프 결승에서 웃은 {a}에게 돌아갔어요. 다음 시즌 국제 무대에서도 활약이 기대돼요."),
            ) to (if (rank < 4) 4 else 3) else listOf(
                T("{a}, {lg} 우승!", "{a|이} 시즌 {season} {lg} 정상에 올랐어요. 1부 1위로 시즌을 마치며 우승을 확정했어요."),
                T("{lg}의 주인은 {a}", "시즌 {season} {lg} 우승 트로피는 {a}에게 돌아갔어요. 다음 시즌 국제 무대에서도 활약이 기대돼요."),
            ) to if (rank < 4) 4 else 3
        }
        "league_titles_digest" -> {
            // [champions] (count, then "league:key" each).
            arg(0)?.let { v["count"] = it }
            val pairs = e.args.drop(1)
            val named = pairs.take(3).mapNotNull { p ->
                val id = p.substringBefore(':', "")
                val k = p.substringAfter(':', "")
                if (id.isEmpty() || k.isEmpty()) null else "${name(k)}(${LeagueIds.short(id)})"
            }
            if (named.isNotEmpty()) v["list"] = named.joinToString("·")
            // A world of few leagues: every other champion is named.
            if (named.isNotEmpty() && arg(0)?.toIntOrNull() == named.size) listOf(
                T("다른 리그 챔피언: {list}", "시즌 {season} 다른 리그에서는 {list|이} 정상에 올랐어요. 다음 시즌 국제 무대에서 이들의 활약을 지켜봐요."),
            ) to 2 else listOf(
                T("{count}개 리그 챔피언 확정", "{list} 등 {count}개 리그에서 시즌 {season} 챔피언이 가려졌어요. 다음 시즌 국제 무대에서 이들의 활약을 지켜봐요."),
                T("리그 챔피언 소식: {a} 외", "시즌 {season} 리그 챔피언이 모두 가려졌어요. 각 리그 정상에 오른 선수는 {list} 등 {count}명이에요."),
            ) to 2
        }
        "league_moves" -> {
            // [promoted…, relegated…] (league, ups, downs): a league's 1부 moves in one article.
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            val ups = arg(1)?.toIntOrNull() ?: 0
            val shown = e.keys.map { name(it) }
            shown.take(ups).joinToString("·").takeIf { it.isNotEmpty() }?.let { v["ups"] = it }
            shown.drop(ups).joinToString("·").takeIf { it.isNotEmpty() }?.let { v["downs"] = it }
            when {
                v["ups"] != null && v["downs"] != null -> listOf(
                    T("{lg} 1부 승강 확정", "{ups} 선수가 1부로 올라오고, {downs} 선수가 2부로 내려가요. 다음 시즌 {lg} 1부의 얼굴이 바뀌어요."),
                )
                v["ups"] != null -> listOf(
                    T("{lg} 1부 승격 확정", "{ups} 선수가 1부로 올라와요. 다음 시즌 {lg} 1부에서 새 도전을 시작해요."),
                )
                else -> listOf(
                    T("{lg} 1부 강등 확정", "{downs} 선수가 2부로 내려가요. 한 시즌 만에 돌아올 수 있을지 관심이 모여요."),
                )
            } to 3
        }
        "national_reform" -> {
            // [] (national leagues, regional leagues, every id).
            val own = arg(0)?.toIntOrNull() ?: 0
            val regional = arg(1)?.toIntOrNull() ?: 0
            listOfNotNull(if (own > 0) "국가 리그 ${own}개" else null, if (regional > 0) "연합 리그 ${regional}개" else null)
                .takeIf { it.isNotEmpty() }?.let { v["formed"] = it.joinToString("와 ") }
            // Where the players play: their club's nation's league; nations with few players
            // share a 연합 리그 (a small world has only those, 강자만 one 국제 연합 리그).
            when {
                own == 0 && e.args.drop(2) == listOf(LeagueIds.WORLD) -> listOf(
                    T("리그 개편: 국제 연합 리그 출범", "시즌 {season}부터 모든 선수가 국제 연합 리그 하나에서 뛰어요. 1부부터 차례로 승강이 이어지고, 리그 챔피언은 국제 무대에 나가요."),
                )
                own == 0 -> listOf(
                    T("리그 개편: 연합 리그 시대 개막", "시즌 {season}부터 선수들은 소속 클럽의 나라가 속한 연합 리그에서 뛰어요. {formed|이} 새로 출범했어요. 리그마다 1부부터 차례로 승강이 이어지고, 각 리그 챔피언은 국제 무대에 나가요."),
                )
                regional > 0 -> listOf(
                    T("리그 개편: 국가 리그 시대 개막", "시즌 {season}부터 선수들은 소속 클럽이 있는 나라의 리그에서 뛰고, 선수가 적은 나라는 연합 리그에 모여요. {formed|이} 새로 출범했어요. 리그마다 1부부터 차례로 승강이 이어지고, 각 리그 챔피언은 국제 무대에 나가요."),
                )
                else -> listOf(
                    T("리그 개편: 국가 리그 시대 개막", "시즌 {season}부터 선수들은 소속 클럽이 있는 나라의 리그에서 뛰어요. {formed|이} 새로 출범했어요. 리그마다 1부부터 차례로 승강이 이어지고, 각 리그 챔피언은 국제 무대에 나가요."),
                )
            } to 5
        }
        "league_formed" -> {
            // [] (league, tiers, members).
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            arg(1)?.let { v["tiers"] = it }
            arg(2)?.let { v["n"] = it }
            listOf(
                T("{lg} 출범", "{lg|이} 시즌 {season}에 새로 출범해요. 선수 {n}명이 {tiers}개 부로 나뉘어 첫 시즌을 치러요."),
            ) to 3
        }
        "league_ranking" -> {
            // [] (the 3 best leagues, the biggest climber or "-", its climb).
            val top = (0..2).mapNotNull { arg(it) }.map { LeagueIds.label(it) }
            top.firstOrNull()?.let { v["first"] = it }
            if (top.isNotEmpty()) v["top3"] = top.joinToString("·")
            top.drop(1).takeIf { it.isNotEmpty() }?.let { v["next"] = it.joinToString("·") }
            arg(3)?.let { v["up"] = LeagueIds.label(it) }
            arg(4)?.takeIf { it != "0" }?.let { v["d"] = it }
            // The biggest climber climbed to the top.
            if (v["d"] != null && arg(3) != null && arg(3) == arg(0)) listOf(
                T("리그 랭킹 발표: {first}, {d}계단 올라 1위", "최근 5시즌 국제 대회 성적으로 매긴 리그 랭킹에서 {first|이} {d}계단 올라 1위가 됐어요. 이 순위로 다음 시즌 대륙 챔피언스 출전권과 챔피언스 리그 직행이 정해져요."),
                T("시즌 {season} 리그 랭킹: {first} 1위", "요트협회가 리그 랭킹을 발표했어요. 한 시즌 만에 {d}계단을 뛰어오른 {first|이} 1위예요.[[ 그 뒤를 {next|이} 이어요.]]"),
            ) to 3 else listOf(
                T("리그 랭킹 발표: 1위 {first}", "최근 5시즌 국제 대회 성적으로 매긴 리그 랭킹에서 {first|이} 1위예요.[[ {up|은} {d}계단 올랐어요.]] 이 순위로 다음 시즌 대륙 챔피언스 출전권과 챔피언스 리그 직행이 정해져요."),
                T("시즌 {season} 리그 랭킹: {first} 1위", "요트협회가 리그 랭킹을 발표했어요. 1위부터 {top3} 순이에요.[[ {up|은} 한 시즌 만에 {d}계단 뛰었어요.]]"),
            ) to 3
        }
        "federation_decision" -> {
            // [] (title, result A/R/V, by U/C/-, yes, no, season it applies from).
            arg(0)?.let { v["title"] = it }
            arg(5)?.let { v["from"] = it }
            val yes = arg(3)?.toIntOrNull() ?: 0
            val seats = yes + (arg(4)?.toIntOrNull() ?: 0)
            v["seats"] = seats.toString()
            v["yes"] = yes.toString()
            val result = arg(1)
            val by = arg(2)
            when {
                result == "V" && by == null -> listOf(
                    T("‘{title}’ 무효", "요트협회가 쉬고 있어 ‘{title}’ 안건은 투표 없이 무효가 됐어요. 규칙은 그대로예요."),
                ) to 2
                result == "V" -> listOf(
                    T("‘{title}’ 무효", "‘{title}’ 안건은 무효가 됐어요. 규칙이 이미 바뀌어 이 안건은 적용하지 않았어요."),
                ) to 2
                result == "A" && by == "U" -> listOf(
                    T("‘{title}’ 가결", "‘{title}’ 안건이 가결됐어요. 직접 찬성한 안건이에요. 시즌 {from}부터 적용돼요."),
                ) to 4
                result == "A" && yes == seats -> listOf(
                    T("‘{title}’ 가결", "‘{title}’ 안건이 가결됐어요. 선수 위원회 {seats}명 전원이 찬성했어요. 시즌 {from}부터 적용돼요."),
                ) to 4
                result == "A" -> listOf(
                    T("‘{title}’ 가결", "‘{title}’ 안건이 가결됐어요. 선수 위원회 {seats}명 가운데 {yes}명이 찬성했어요. 시즌 {from}부터 적용돼요."),
                ) to 4
                by == "U" -> listOf(
                    T("‘{title}’ 부결", "‘{title}’ 안건이 부결됐어요. 직접 반대한 안건이에요. 규칙은 그대로예요."),
                ) to 3
                yes == 0 -> listOf(
                    T("‘{title}’ 부결", "‘{title}’ 안건이 부결됐어요. 선수 위원회 {seats}명 가운데 아무도 찬성하지 않았어요."),
                ) to 3
                else -> listOf(
                    T("‘{title}’ 부결", "‘{title}’ 안건이 부결됐어요. 선수 위원회 {seats}명 가운데 {yes}명만 찬성했어요. 규칙은 그대로예요."),
                ) to 3
            }
        }
        "federation_assembly" -> {
            // [] (count, the season voting, the season they apply from, each proposal's title).
            arg(0)?.let { v["n"] = it }
            arg(1)?.let { v["vote"] = it }
            arg(2)?.let { v["apply"] = it }
            val titles = e.args.drop(3).filter { it.isNotBlank() }
            if (titles.isNotEmpty()) v["items"] = titles.joinToString(", ") { "‘$it’" }
            if (titles.isEmpty()) listOf(
                T("요트협회 총회, 규칙은 그대로", "요트협회 총회는 이번에 규칙을 그대로 두기로 했어요. 다음 총회는 시즌 {vote|이} 끝난 뒤에 열려요."),
            ) to 2 else listOf(
                T("요트협회 총회, 안건 {n}개 상정", "요트협회 정기 총회가 안건 {n}개를 올렸어요. 안건은 {items|이에요}. 시즌 {vote} 동안 투표를 받아 시즌 {apply}부터 적용해요."),
                T("요트협회, 규칙 변경 안건 {n}개 투표", "요트협회가 {items|을} 투표에 부쳤어요. 시즌 {vote} 동안 찬반을 모아 가결되면 시즌 {apply}부터 적용해요."),
            ) to 3
        }
        "club_title" -> {
            // [club] (league or "-", the club's points).
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            arg(1)?.let { v["pts"] = it }
            listOf(
                T("{a}, [[{lg} ]]클럽 순위 1위", "{a|이} 시즌 {season}[[ {lg}]] 클럽 순위 1위에 올랐어요. 소속 선수들이 모은 클럽 점수는 {pts}점이에요."),
                T("[[{lg} ]]최강 클럽은 {a}", "시즌 {season}[[ {lg}]] 클럽 순위 맨 위에 {a|이} 이름을 올렸어요. 상위 선수들의 성적과 우승을 더한 점수가 {pts}점이에요."),
            ) to 3
        }
        "club_champions" -> {
            // [clubs of the next leagues] (leagues beyond those told).
            arg(0)?.let { v["n"] = it }
            if (everyone(e, arg(0))) listOf(
                T("{n}개 리그 클럽 1위 확정", "다른 리그에서는 {seeds|이} 클럽 1위에 올랐어요. 클럽 순위는 소속 선수들의 리그 성적과 우승으로 매겨요."),
            ) to 2 else listOf(
                T("{n}개 리그 클럽 1위 확정", "{seeds} 등 {n}개 리그에서도 클럽 1위가 가려졌어요. 클럽 순위는 소속 선수들의 리그 성적과 우승으로 매겨요."),
            ) to 2
        }
        "club_reform" -> {
            // [] (clubs, nations).
            arg(0)?.let { v["clubs"] = it }
            arg(1)?.let { v["nations"] = it }
            listOf(
                T("클럽 개편: {nations}개국 {clubs}개 클럽", "클럽이 {nations}개국 {clubs}개 클럽으로 새로 태어났어요. 이제 모든 선수가 자기 나라 클럽에 속하고, 클럽이 속한 리그에서 뛰어요."),
            ) to 4
        }
        "club_founded" -> {
            // [club] (nation).
            Nations.of(arg(0))?.let { v["nat"] = it.short }
            listOf(
                T("{a} 창단", "[[{nat}에 ]]새 클럽 {a|이} 생겼어요. 드래프트로 선수들을 모아 다음 시즌 첫발을 내디뎌요."),
            ) to 2
        }
        "club_dissolved" -> {
            // [club] (members released).
            arg(0)?.takeIf { it != "0" }?.let { v["n"] = it }
            listOf(
                T("{a} 해체", "{a|이} 클럽 수 조정으로 해체됐어요.[[ 소속 선수 {n}명은 드래프트로 새 클럽을 찾아요.]] 클럽의 기록은 역사로 남아요."),
            ) to 2
        }
        "club_digest" -> {
            // [≤ 6 clubs, founded first] (founded, dissolved).
            val founded = arg(0)?.toIntOrNull() ?: 0
            val dissolved = arg(1)?.toIntOrNull() ?: 0
            v["change"] = when {
                founded > 0 && dissolved > 0 -> "클럽 ${founded}곳이 새로 생기고 ${dissolved}곳이 해체됐어요"
                founded > 0 -> "클럽 ${founded}곳이 새로 생겼어요"
                else -> "클럽 ${dissolved}곳이 해체됐어요"
            }
            v["nf"] = founded.toString()
            v["nd"] = dissolved.toString()
            if (everyone(e, (founded + dissolved).toString())) listOf(
                T("클럽 재편: 창단 {nf}곳·해체 {nd}곳", "이번 오프시즌에 {change}. 나라마다 선수 수에 맞춰 클럽 수를 조정했어요.[[ 바뀐 클럽은 {seeds|이에요}.]]"),
            ) to 2 else listOf(
                T("클럽 재편: 창단 {nf}곳·해체 {nd}곳", "이번 오프시즌에 {change}. 나라마다 선수 수에 맞춰 클럽 수를 조정했어요.[[ 바뀐 클럽에는 {seeds} 등이 있어요.]]"),
            ) to 2
        }
        "transfer_abroad" -> {
            // [ai, from club, to club] (from league, to league, fee[, "star"]).
            arg(0)?.let { v["fromLg"] = LeagueIds.label(it) }
            arg(1)?.let { v["toLg"] = LeagueIds.label(it) }
            arg(2)?.takeIf { it != "0" }?.let { v["fee"] = number(it) }
            listOf(
                T("{a}, {toLg|으로} 해외 이적", "{a|이}[[ {b|을} 떠나]] {toLg} {c|으로} 옮겨요.[[ 이적료는 {fee|이에요}.]] 다음 시즌부터 {toLg} 1부에서 뛰어요."),
                T("{c}, {fromLg}의 {a} 영입", "{c|이}[[ {fromLg}]] {b}에서 뛰던 {a|을} 데려왔어요.[[ 이적료 {fee|을} 들인 승부수예요.]] {a|은} 다음 시즌 {toLg} 1부에서 뛰어요."),
            ) to if (arg(3) == STAR) 4 else 3
        }
        "return_home" -> {
            // [ai, from club, to club or "-"] (from league, to league).
            arg(0)?.let { v["fromLg"] = LeagueIds.label(it) }
            arg(1)?.let { v["toLg"] = LeagueIds.label(it) }
            listOf(
                T("{a}, 고국으로 돌아와요", "{a|이}[[ {fromLg}]] {b} 생활을 마치고[[ {c|으로}]] 돌아와요. 다음 시즌 {toLg}에서 다시 정상을 노려요."),
                T("돌아온 {a}", "해외 무대를 누빈 {a|이} 고국으로 돌아와요.[[ 새 클럽은 {c|이에요}.]] 우승 경험이 고국 리그에 힘을 보탤 거예요."),
            ) to 2
        }
        "transfer_window" -> {
            // [≤ 6 of them] (moved abroad, came home).
            val abroad = arg(0)?.toIntOrNull() ?: 0
            val home = arg(1)?.toIntOrNull() ?: 0
            v["moves"] = when {
                abroad > 0 && home > 0 -> "${abroad}명이 해외 리그로 옮기고 ${home}명이 고국으로 돌아왔어요"
                abroad > 0 -> "${abroad}명이 해외 리그로 옮겼어요"
                else -> "${home}명이 고국으로 돌아왔어요"
            }
            v["n"] = (abroad + home).toString()
            if (everyone(e, v["n"])) listOf(
                T("해외 이적 시장 결산: {n}명 이동", "이번 오프시즌에 {moves}. 리그를 옮긴 선수는 다음 시즌 새 리그에서 뛰어요.[[ 주인공은 {seeds|이에요}.]]"),
            ) to 2 else listOf(
                T("해외 이적 시장 결산: {n}명 이동", "이번 오프시즌에 {moves}. 리그를 옮긴 선수는 다음 시즌 새 리그에서 뛰어요.[[ {seeds} 등이 새 무대에 서요.]]"),
            ) to 2
        }
        "youth" -> {
            // [ai, club]: a pupil joins its mentor's club.
            v.remove("b")
            e.keys.getOrNull(1)?.takeIf { it != "-" }?.let { v["club"] = club(it) }
            listOf(
                T("{a}, {club} 유스 입단", "스승이 뛰는 {club|이} {a|을} 유스로 받아들였어요. {a|은} 다음 시즌 {club} 유니폼을 입고 데뷔해요."),
            ) to 1
        }
        "market_digest" -> {
            // [drafted and bought] (domestic deals, rookies, biggest-fee ai, from club, to club, fee).
            val deals = arg(0)?.toIntOrNull() ?: 0
            val rookies = arg(1)?.toIntOrNull() ?: 0
            v["market"] = when {
                deals > 0 && rookies > 0 -> "국내 이적 ${deals}건이 성사되고 신인 ${rookies}명이 드래프트로 새 클럽을 찾았어요"
                deals > 0 -> "국내 이적 ${deals}건이 성사됐어요"
                else -> "신인 ${rookies}명이 드래프트로 새 클럽을 찾았어요"
            }
            arg(2)?.let { v["big"] = name(it) }
            arg(3)?.let { v["bfrom"] = club(it) }
            arg(4)?.let { v["bto"] = club(it) }
            arg(5)?.takeIf { it != "0" }?.let { v["fee"] = number(it) }
            // The players it names when there are any, else the market alone.
            if (v["seeds"] != null) listOf(
                T("오프시즌 이적 시장 결산", "이번 오프시즌에 {market}.[[ 가장 큰 이적은 이적료 {fee}에 {bfrom}에서 {bto|으로} 옮긴 {big|이에요}.]] {seeds} 등이 주목받는 이름이에요."),
            ) to 2 else listOf(
                T("이적 시장 마감", "이번 오프시즌에 {market}.[[ 가장 큰 이적은 {bfrom}에서 {bto|으로} 옮긴 {big|이에요}.]] 새 시즌 클럽들의 판도가 궁금해요."),
            ) to 2
        }
        "draw_ccl" -> {
            // [the section's entrants, by seed] (section title).
            v["title"] = arg(0) ?: v["comp"].orEmpty()
            listOf(
                T("{title} 대진 확정", "시즌 {season} {title}에 {count}명이 나서요. 1번 시드 {a|과} {b}·{c} 등이 우승을 노려요."),
                T("{title} 개막, 1번 시드는 {a}", "각 리그 챔피언과 상위권 선수 {count}명이 {title} 정상을 다퉈요. 조 2위 안에 들어야 토너먼트에 올라요."),
            ) to 2
        }
        "milestones" -> {
            // [≤ 6 players] (count, first titles): a national close's title milestones beyond the best-rated.
            arg(0)?.let { v["n"] = it }
            arg(1)?.takeIf { it != "0" }?.let { v["firsts"] = it }
            // "등" only when there are more than it names.
            v["seeds"]?.let { names -> v["who"] = if (everyone(e, arg(0))) names else "$names 등" }
            if (arg(1) == arg(0)) listOf(
                T("첫 우승의 기쁨, {n}명", "이번 시즌 {n}명이 데뷔 첫 우승을 차지했어요. {who|이} 처음으로 트로피를 들어 올렸어요."),
            ) to 2 else listOf(
                T("우승 이정표 {n}건", "이번 시즌 {n}명이 우승으로 새 이정표를 세웠어요.[[ 그중 {firsts}명은 데뷔 첫 우승이에요.]] {who|이} 이름을 올렸어요."),
            ) to 2
        }
        "dynasties" -> {
            // [≤ 6 players] (count): a national close's runs and unbeaten seasons in titles that are not major.
            arg(0)?.let { v["n"] = it }
            if (everyone(e, arg(0))) listOf(
                T("리그 곳곳의 왕조, {n}명", "{seeds|이} 각자 리그에서 연속 우승이나 무패 우승을 이어 갔어요. 리그마다 새 강자가 자리를 굳히고 있어요."),
            ) to 2 else listOf(
                T("리그 곳곳의 왕조, {n}명", "{seeds} 등 {n}명이 각 리그에서 연속 우승이나 무패 우승을 이어 갔어요. 리그마다 새 강자가 자리를 굳히고 있어요."),
            ) to 2
        }
        "pupils" -> {
            // [≤ 6 pupils] (count): a national close's debuts of pupils beyond the best-rated.
            arg(0)?.let { v["n"] = it }
            if (everyone(e, arg(0))) listOf(
                T("제자 {n}명 데뷔", "스승에게 배운 제자 {seeds|이} 리그에 데뷔해요. 모두 자기 나라 리그의 가장 낮은 부에서 첫 시즌을 시작해요."),
            ) to 1 else listOf(
                T("제자 {n}명 데뷔", "{seeds} 등 스승에게 배운 제자 {n}명이 리그에 데뷔해요. 모두 자기 나라 리그의 가장 낮은 부에서 첫 시즌을 시작해요."),
            ) to 1
        }
        "learned_rules" -> {
            // [≤ 6 players] (count): a national close's players who stopped misreading a rule, beyond the best-rated.
            arg(0)?.let { v["n"] = it }
            if (everyone(e, arg(0))) listOf(
                T("규칙 착각에서 벗어난 {n}명", "{seeds|이} 잘못 알던 규칙을 바로잡았어요. 다음 시즌 진짜 실력이 나올지 지켜봐요."),
            ) to 1 else listOf(
                T("규칙 착각에서 벗어난 {n}명", "{seeds} 등 {n}명이 잘못 알던 규칙을 바로잡았어요. 다음 시즌 진짜 실력이 나올지 지켜봐요."),
            ) to 1
        }
        "draw_clubcup" -> {
            // [clubs] (group).
            v["group"] = arg(0) ?: "조"
            listOf(
                T("클럽 대항전 {group} 편성", "{group}에서 {seeds|이} 맞붙어요. 각 클럽은 소속 선수 가운데 레이팅 상위 5명으로 팀을 꾸려요."),
                T("클럽 대항전 개막, {group}에 {seeds}", "각 리그 클럽 1위들이 맞붙는 클럽 대항전이 시작돼요. {group}에서는 {seeds|이} 경쟁해요."),
            ) to 2
        }
        else -> deskTemplates(e, v, arg, name)
    }

    /**
     * The news desk's kinds ([NewsDesk], design_ux 5.1): the week's league upsets, the club race,
     * the international stage's points, and the digests of a cup stage, a group stage and the
     * week's milestones; the players near a record.
     */
    private fun deskTemplates(
        e: LeagueEvent,
        v: MutableMap<String, String>,
        arg: (Int) -> String?,
        name: (String) -> String,
    ): Pair<List<T>, Int> = when (e.kind) {
        "league_upset" -> {
            // [winner, loser] (gap, league or "-", tier, score, the league's rank or "-").
            arg(0)?.let { v["gap"] = number(it) }
            val id = arg(1) ?: LeagueIds.LEGACY
            val tier = arg(2)?.toIntOrNull() ?: 0
            v["div"] = LeagueIds.division(id, tier)
            v["lg"] = LeagueIds.label(id)
            arg(3)?.let { v["score"] = it }
            val rank = arg(4)?.toIntOrNull() ?: Int.MAX_VALUE
            listOf(
                T("이변! {a}, {b} 격파", "{div}에서 레이팅이 {gap}점 낮은 {a|이} {b|을} {score|으로} 꺾었어요. 이번 주 손꼽히는 이변이에요."),
                T("{b}, {a}에게 덜미", "{b|은} {div}에서 한참 아래로 본 {a}에게 {score|으로} 졌어요. 레이팅 차는 {gap}점이었어요."),
                T("약자의 반란: {a}", "{a|이} 레이팅 {gap}점 차를 뒤집고 {b|을} 눌렀어요. {lg} 순위 싸움이 더 뜨거워졌어요."),
            ) to if (tier == 0 && (id == LeagueIds.LEGACY || rank < LeagueWorld.LEAGUE_TITLES)) 3 else 2
        }
        "club_race" -> {
            // [leader club, second club] (league, gap x 10, "lead" | "close").
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            arg(1)?.toIntOrNull()?.let { v["gap"] = String.format(Locale.ROOT, "%.1f", it / 10.0) }
            if (arg(2) == "lead") listOf(
                T("{a}, {lg} 클럽 순위 1위로", "{a|이} {b|을} 제치고 {lg} 클럽 순위 맨 위에 올랐어요. 두 클럽의 차이는 {gap}점이에요."),
            ) to 3 else listOf(
                T("클럽 순위 접전: {a}·{b}", "{lg} 클럽 순위에서 {a|와} {b}의 차이가 {gap}점으로 줄었어요. 선수들의 남은 경기에 클럽 우승이 걸렸어요."),
            ) to 3
        }
        "intl_week" -> {
            // [] (competition code, stage, "league:points" for the 1 to 3 leagues that earned most, then "lead" when the first leads the season).
            arg(1)?.let { v["stage"] = it }
            val gains = e.args.drop(2).filter { ':' in it }.mapNotNull { p ->
                val id = p.substringBefore(':')
                p.substringAfter(':').takeIf { it.isNotEmpty() && id.isNotEmpty() }?.let { LeagueIds.label(id) to it }
            }
            gains.firstOrNull()?.let { (lg, p) ->
                v["l1"] = lg
                v["p1"] = p
            }
            // The others with the particle after the last league's name, not after its points.
            gains.drop(1).takeIf { it.isNotEmpty() }?.let { rest ->
                v["rest"] = rest.joinToString(", ") { (lg, p) -> "$lg(${p}점)" } + Josa.after(rest.last().first, "이")
            }
            if (e.args.lastOrNull() == "lead") listOf(
                T("{l1}, 국제 무대에서 웃었어요", "{comp} {stage} 결과로 {l1|이} 이번 시즌 국제 성적 점수 레이스 선두에 섰어요. 이번 단계에서만 {p1}점을 보탰어요."),
                T("국제 무대 결산: {l1} 강세", "{comp} {stage}에서 {l1} 선수들이 국제 성적 점수 {p1}점을 벌었어요.[[ {rest} 뒤를 이었어요.]] {l1|은} 이번 시즌 레이스 선두예요."),
            ) to 2 else listOf(
                T("국제 무대 결산: {l1} 강세", "{comp} {stage}에서 {l1} 선수들이 국제 성적 점수 {p1}점을 벌었어요.[[ {rest} 뒤를 이었어요.]]"),
            ).let { all -> if (v["rest"] == null) listOf(T("국제 무대 결산: {l1} 강세", "{comp} {stage}에서 {l1} 선수들이 국제 성적 점수 {p1}점을 벌었어요. 다른 리그는 이번 단계에서 점수를 보태지 못했어요.")) else all } to 2
        }
        "cup_digest" -> {
            // [≤ 8 notable winners] (stage, ties, upsets, the top seed out or "-", next week or "-").
            arg(0)?.let { v["stage"] = it }
            arg(1)?.let { v["n"] = number(it) }
            val upsets = arg(2)?.toIntOrNull() ?: 0
            val out = arg(3)?.let { name(it) }
            // The winners it names beat better seeds (the stage's other notable ties).
            val who = shortList(e.keys, name, 2)?.let { if (e.keys.size > 2) "$it 등" else it }
            val gone = out?.let { "톱시드 ${it}도 짐을 쌌어요" }
            v["surprise"] = when {
                upsets > 0 && who != null -> "${who + Josa.after(who, "이")} 높은 시드를 꺾는 등 이변이 ${upsets}번 나왔" + (gone?.let { "고, $it." } ?: "어요.")
                upsets > 0 -> "이변이 ${upsets}번 나왔" + (gone?.let { "고, $it." } ?: "어요.")
                who != null -> "${who + Josa.after(who, "이")} 높은 시드를 꺾었" + (gone?.let { "고, $it." } ?: "어요.")
                out != null -> "톱시드 ${out + Josa.after(out, "이")} 탈락했어요."
                else -> "큰 이변 없이 시드 순서대로 흘러갔어요."
            }
            arg(4)?.let { v["next"] = it }
            if (upsets > 0) v["u"] = upsets.toString()
            listOf(
                T("{comp} {stage} 결과[[: 이변 {u}번]]", "{stage} {n}경기가 모두 끝났어요. {surprise} 다음 라운드는 {next}주차예요."),
                T("{comp} {stage} 결과[[: 이변 {u}번]]", "{stage} {n}경기가 모두 끝났어요. {surprise} 다음 라운드를 기다려요."),
            ).let { all -> if (v["next"] != null) all.take(1) else all.drop(1) } to 2
        }
        "group_digest" -> {
            // [≤ 8 group winners] (stage, qualified, eliminated, top seeds among them).
            arg(0)?.let { v["stage"] = it }
            arg(1)?.let { v["q"] = it }
            arg(2)?.let { v["out"] = it }
            arg(3)?.takeIf { it != "0" }?.let { v["fallen"] = it }
            shortList(e.keys, name, DIGEST_SHOWN)?.let { v["firsts"] = if (e.keys.size > DIGEST_SHOWN) "$it 등" else it }
            listOf(
                T("{comp} {stage} 결과", "{q}명이 토너먼트에 올랐[[고, {out}명은 여기서 멈췄]]어요.[[ 톱시드 {fallen}명도 짐을 쌌어요.]][[ {firsts|이} 조 1위로 다음 단계에 나서요.]]"),
            ) to 2
        }
        "milestones_week" -> {
            // [≤ 20 players] ("kind:value:count" each).
            val counts = e.args.mapNotNull { a ->
                val f = a.split(':')
                val n = f.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                milestoneLabel(f[0], f.getOrNull(1)) to n
            }
            val total = counts.sumOf { it.second }
            if (total > 0) v["n"] = total.toString()
            if (counts.isNotEmpty()) v["list"] = counts.joinToString(", ") { (label, n) -> "$label ${n}명" }
            // A few names; "등" when there are more milestones than it names.
            shortList(e.keys, name, DIGEST_SHOWN)?.let { v["who"] = if (total <= minOf(e.keys.size, DIGEST_SHOWN)) it else "$it 등" }
            listOf(
                T("이번 주의 이정표 {n}개", "이번 주에 이정표 {n}개가 나왔어요. {list|이에요}.[[ {who|이} 이름을 올렸어요.]]"),
            ) to 1
        }
        "record_chase" -> {
            // [player, holder] (record kind, how far in Korean, value, record).
            val kind = arg(0)?.let { runCatching { RecordKind.valueOf(it) }.getOrNull() }
            kind?.let { v["rec"] = it.label }
            arg(1)?.let { v["chase"] = it }
            // The record's holder chasing its own season record is no second name.
            if (e.keys.getOrNull(1) == e.keys.firstOrNull()) v.remove("b")
            listOf(
                T("{a}, {rec} 기록에 다가서요", "{a|이} {rec} 기록에 다가섰어요. {chase|이에요}.[[ 기록의 주인은 {b|이에요}.]]"),
                T("기록 추격: {a}", "{a|이} {rec} 기록을 바짝 쫓고 있어요. {chase|이에요}.[[ 지금 기록은 {b}의 것이에요.]]"),
            ) to if (kind != null && kind in RecordChase.CAREER) 3 else 2
        }
        else -> emptyList<T>() to 1
    }

    /** Names shown in a digest's sentence (its keys are many more). */
    private const val DIGEST_SHOWN = 3

    /** The first [n] of [keys] by [name], joined by "·"; null without any. */
    private fun shortList(keys: List<String>, name: (String) -> String, n: Int): String? =
        keys.filter { it != "-" }.take(n).map(name).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString("·")

    /** "한국 1부 경기" (a league match of league [id], tier [tier]) or "요트컵 16강" (a competition's [label]), from a desk event's arguments; null when unknown. */
    private fun where(code: String?, label: String?, id: String?, tier: String?): String? {
        val comp = code?.singleOrNull()?.let { Comp.of(it) } ?: return null
        if (comp == Comp.LEAGUE) return "${LeagueIds.division(id ?: LeagueIds.LEGACY, tier?.toIntOrNull() ?: 0)} 경기"
        return listOfNotNull(comp.label, label).joinToString(" ")
    }

    /** "통산 요트 100개", "통산 1,000경기", "300점 경기 10번" for a milestone of [kind] at [value]. */
    private fun milestoneLabel(kind: String, value: String?): String {
        val n = value?.let { number(it) }.orEmpty()
        return when (kind) {
            "games" -> "통산 ${n}경기"
            "wins" -> "통산 ${n}승"
            "yachts" -> "통산 요트 ${n}개"
            "300" -> "300점 경기 ${n}번"
            else -> "$kind $n".trim()
        }
    }

    /**
     * Whether digest [e] names everyone its [count] counts (a small world, a quiet off-season):
     * its article then lists them without "등".
     */
    private fun everyone(e: LeagueEvent, count: String?): Boolean = count?.toIntOrNull()?.let { it in 1..e.keys.count { k -> k != "-" } } == true

    private fun milestone(kind: String, value: String?, v: MutableMap<String, String>): Pair<List<T>, Int> {
        val n = value?.toIntOrNull()
        value?.let { v["v"] = number(it) }
        if (kind == "title" || kind == "titles") {
            return if (n == null || n <= 1) {
                listOf(
                    T("{a}, 데뷔 첫 우승", "{a|이} 데뷔 후 첫 우승을 차지했어요. 오래 기다린 순간이에요."),
                    T("마침내 정상, {a}의 첫 트로피", "{a|이} 생애 첫 트로피를 들어 올렸어요. 앞으로가 더 기대돼요."),
                ) to 3
            } else {
                listOf(
                    T("{a}, 통산 {v}번째 우승", "{a|이} 통산 {v}번째 우승을 달성했어요. 우승 경험이 쌓일수록 더 무서워져요."),
                    T("우승 {v}회 클럽 가입: {a}", "{a|이} 우승 횟수를 {v}회로 늘렸어요. 리그에서 손꼽히는 우승 수집가예요."),
                ) to 3
            }
        }
        if (kind == "300" || kind == "300+" || kind == "big") {
            return if (n == null || n <= 1) {
                listOf(
                    T("{a}, 생애 첫 300점", "{a|이} 처음으로 한 경기 300점을 넘겼어요. 리그에서도 보기 드문 점수예요."),
                    T("300점 클럽에 들어온 {a}", "{a|이} 한 경기에서 300점을 넘기는 대기록을 세웠어요. 주사위와 판단이 모두 맞아떨어졌어요."),
                ) to 3
            } else {
                listOf(
                    T("{a}, 통산 {v}번째 300점 경기", "{a|이} 또 한 번 300점을 넘겼어요. 벌써 통산 {v}번째예요."),
                ) to 2
            }
        }
        v["mile"] = when (kind) {
            "games" -> "통산 ${v["v"] ?: ""}경기"
            "wins" -> "통산 ${v["v"] ?: ""}승"
            "yachts" -> "통산 요트 ${v["v"] ?: ""}개"
            else -> listOfNotNull(kind.takeIf { it.isNotEmpty() }, v["v"]).joinToString(" ").ifEmpty { "새 이정표" }
        }.replace(SPACES, " ").trim()
        return listOf(
            T("{a}, {mile} 달성", "{a|이} {mile|을} 달성했어요. 꾸준함이 만든 기록이에요."),
            T("{mile} 고지에 오른 {a}", "{a|이} 마침내 {mile} 고지를 밟았어요. 다음 목표를 향해 다시 달려요."),
            T("축하해요, {a}! {mile}", "{a|이} {mile} 기록을 세웠어요. 리그에서 손꼽히는 발자취예요."),
        ) to 2
    }

    /**
     * The Korean name of a competition honour kind ("L0" 1부 리그, "CUP" 요트컵, ...), a national
     * league's by its league ("L0.KR" 한국 리그, "L1.KR" 한국 2부, [HonourKind.leagueOf]) and a
     * confederation's by its section ("CCL.ASIA" 아시아 챔피언스); null when unknown.
     */
    private fun honourLabel(kind: String): String? {
        val base = HonourKind.base(kind)
        val league = HonourKind.leagueOf(kind)
        if (league != null) return when {
            HonourKind.tier(kind) == 0 -> LeagueIds.label(league)
            HonourKind.isDivision(kind) -> LeagueIds.division(league, HonourKind.tier(kind)!!)
            base == "REG" -> "${LeagueIds.short(league)} 정규리그 1위"
            base == "L0R" -> "${LeagueIds.label(league)} 준우승"
            base == Awards.LMVP -> "${LeagueIds.label(league)} MVP"
            else -> null
        }
        if (base == "CCL" || base == "CCL2") {
            val title = HonourKind.qualifier(kind)?.let { q -> Confed.entries.firstOrNull { it.name == q } }?.let { ContinentalChampions.title(it) } ?: Comp.CONT_CHAMPIONS.label
            return if (base == "CCL") title else "$title 준우승"
        }
        if (base == "CLUBCUP" || base == "CLUBCUPW") return Comp.CLUB_CUP.label
        return legacyLabel(kind)
    }

    private fun legacyLabel(kind: String): String? = when {
        Regex("L\\d").matches(kind) -> "${kind.drop(1).toInt() + 1}부 리그"
        kind == "REG" -> "정규리그 1위"
        kind == "CUP" -> Comp.CUP.label
        kind == "CL" -> Comp.CHAMPIONS.label
        kind == "SUPER" -> Comp.SUPER.label
        kind == "FAMILY" -> Comp.FAMILY.label
        kind == "CLUB" -> Comp.CLUB.label
        kind == "ALLSTAR" -> Comp.ALL_STAR.label
        kind == "WC" || kind == "WCW" -> Comp.WORLD_CUP.label
        kind == "CC" || kind == "CCW" -> Comp.CONTINENTAL.label
        kind.isNotEmpty() && kind.any { it in '가'..'힣' } -> kind
        else -> null
    }

    /** "0.08" (a share) or "8" as a percent number. */
    private fun percent(s: String): String {
        val x = s.toDoubleOrNull() ?: return s
        val p = if (s.contains('.') && x < 1.0) x * 100 else x
        return Math.round(p).toString()
    }

    /** Whole numbers with thousands separators, others as they are. */
    private fun number(s: String): String = s.toLongOrNull()?.let { String.format(Locale.ROOT, "%,d", it) } ?: s

    // ---- filling -------------------------------------------------------------------------------

    /** The first usable template by the event's hash: every slot outside a `[[ ]]` part must have a value. */
    private fun pick(templates: List<T>, v: Map<String, String>, e: LeagueEvent): T? {
        val usable = templates.filter { usable(it.headline, v) && usable(it.body, v) }
        if (usable.isEmpty()) return null
        val h = DiceStream.mix(
            e.kind.hashCode().toLong(), e.season.toLong(), e.keys.hashCode().toLong(), e.args.hashCode().toLong(),
            (e.comp?.code?.code ?: 0).toLong(),
        )
        return usable[Math.floorMod(h, usable.size.toLong()).toInt()]
    }

    private fun usable(template: String, v: Map<String, String>): Boolean =
        SLOT.findAll(SEGMENT.replace(template, "")).all { !v[it.groupValues[1]].isNullOrEmpty() }

    /** Fills [template] from [v] (see [News]); a slot without a value is left as it is. */
    internal fun fill(template: String, v: Map<String, String>): String {
        val kept = SEGMENT.replace(template) { m ->
            val inner = m.groupValues[1]
            if (SLOT.findAll(inner).all { !v[it.groupValues[1]].isNullOrEmpty() }) inner else ""
        }
        val out = SLOT.replace(kept) { m ->
            val value = v[m.groupValues[1]]
            when {
                value == null -> m.value
                m.groupValues[2].isEmpty() -> value
                else -> value + Josa.after(value, m.groupValues[2])
            }
        }
        return out.replace(SPACES, " ").trim()
    }
}

/**
 * Watches 1st divisions for [News.titleRace]: [check] after every recorded league fixture
 * compares a division's table at each newly completed round with the one at the round before.
 * Memory only: after a restart the first completed round is the new baseline.
 */
class TitleRaceWatch {
    private var season = -1

    /** Per division watched: its rounds completed when last checked, and its table then. */
    private val done = HashMap<Int, Int>()
    private val last = HashMap<Int, List<Pair<String, Int>>>()

    /** The 1st division of a legacy league. */
    fun check(league: Season): LeagueEvent? = race(league, 0, league.progress, null)

    /**
     * The 1부 of each national league of [ids] (the best-ranked, in a national season): its race
     * as far as its own rounds have gone; the event's arguments end with the league's id.
     */
    fun check(league: Season, ids: List<String>): List<LeagueEvent> = ids.mapNotNull { id ->
        val d = league.leagues.byId(id)?.top ?: return@mapNotNull null
        val rounds = league.roundsOf(d)
        val played = league.positions.getOrNull(d)?.size ?: 0
        race(league, d, if (rounds > 0) played.toDouble() / rounds else 0.0, id)
    }

    private fun race(league: Season, d: Int, progress: Double, id: String?): LeagueEvent? {
        if (league.number != season) {
            season = league.number
            done.clear()
            last.clear()
        }
        val rounds = league.positions.getOrNull(d)?.size ?: return null
        if (done[d] == rounds) return null
        done[d] = rounds
        val now = league.table(d).map { it.key to it.points }
        val before = last.put(d, now) ?: return null
        val e = News.titleRace(league.number, before, now, progress) ?: return null
        return if (id == null) e else e.copy(args = e.args + id)
    }
}

/**
 * The newest articles in two lanes, newest first (league/news.txt): [MAJOR] of importance 3 and
 * up, [MINOR] of the rest, each dropping its own oldest, so a week of small news cannot push a
 * season's champions, awards and review out of the book. Not thread-safe: callers synchronise.
 */
class NewsBook {
    private val list = ArrayDeque<Article>()

    /** Articles of the [MAJOR] lane in [list]. */
    private var majors = 0

    /** Every article kept, newest first. */
    val all: List<Article> get() = list.toList()

    val size: Int get() = list.size

    /** Adds [a] as the newest (an article equal to one of the last few is not added twice); its lane drops its oldest beyond its size. */
    fun add(a: Article) {
        if (list.take(RECENT).any { it == a }) return
        list.addFirst(a)
        if (a.major) majors++
        trim(a.major)
    }

    /** Drops the oldest articles of one lane down to its size. */
    private fun trim(major: Boolean) {
        val cap = if (major) MAJOR else MINOR
        var n = if (major) majors else list.size - majors
        if (n <= cap) return
        val it = list.listIterator(list.size)
        while (n > cap && it.hasPrevious()) {
            if (it.previous().major != major) continue
            it.remove()
            n--
            if (major) majors--
        }
    }

    /** Adds [articles] in order (the last one ends up newest). */
    fun add(articles: Collection<Article>) {
        for (a in articles) add(a)
    }

    /** The articles about [key] (an AI, team or club), newest first. */
    fun about(key: String): List<Article> = list.filter { key in it.keys }

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        // Oldest first, so reading back adds them in order.
        for (a in list.reversed()) {
            append("a ").append(a.season).append(' ').append(MediaText.esc(a.kind)).append(' ').append(a.comp?.code ?: '-').append(' ')
            append(MediaText.list(a.keys)).append(' ').append(MediaText.esc(a.headline)).append(' ').append(MediaText.esc(a.body)).append(' ')
            append(a.importance)
            // The week, then the match ("@ref") and the scope ("#KR"): fields older versions do not read.
            if (a.week != SeasonCalendar.NONE) append(' ').append(a.week)
            a.ref?.takeIf { it.isNotEmpty() && ' ' !in it }?.let { append(" @").append(it) }
            if (a.scope.isNotEmpty() && ' ' !in a.scope) append(" #").append(a.scope)
            append('\n')
        }
    }

    companion object {
        const val FILE = "news.txt"
        const val HEADER = "news 1"

        /** Articles of importance [MAJOR_FROM] and up kept, and of the others: 200 in all, as before the lanes. */
        const val MAJOR = 120
        const val MINOR = 80
        const val MAX = MAJOR + MINOR
        const val MAJOR_FROM = 3
        private const val RECENT = 10

        /**
         * Reads news.txt; throws when it is not one (the caller starts fresh), skips bad or cut
         * lines and lines of other kinds (the [NewsDesk]'s).
         */
        fun decode(text: String): NewsBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a news file" }
            val book = NewsBook()
            for (line in lines.drop(1)) {
                runCatching {
                    val f = line.trim().split(' ')
                    if (f[0] != "a" || f.size < 8) return@runCatching
                    val importance = f[7].toInt()
                    require(importance in 1..5)
                    val comp = if (f[3] == "-") null else Comp.of(f[3].single())
                    // Optional fields after the importance: the week (a number), "@ref", "#scope".
                    val extra = f.drop(8)
                    val week = extra.firstOrNull()?.toIntOrNull() ?: SeasonCalendar.NONE
                    val ref = extra.firstOrNull { it.startsWith('@') && it.length > 1 }?.drop(1)
                    val scope = extra.firstOrNull { it.startsWith('#') }?.drop(1).orEmpty()
                    val a = Article(
                        f[1].toInt(), MediaText.unesc(f[2]), MediaText.unesc(f[5]), MediaText.unesc(f[6]), MediaText.unlist(f[4]), comp, importance, week, ref, scope,
                    )
                    // Oldest first in the file: each lane keeps its newest.
                    book.list.addFirst(a)
                    if (a.major) book.majors++
                    book.trim(a.major)
                }
            }
            return book
        }
    }
}
