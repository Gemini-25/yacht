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
        // The club world's (design ENG 11.2): money and club life, coaches, the market, history and academies.
        "club_accounts", "club_trouble", "forced_sale", "administration", "rescue", "club_folded", "takeover", "owner_exit",
        "sponsor_deal", "sponsor_exit", "stadium_project", "stadium_open", "fan_protest", "fan_party", "club_ranking", "club_world_digest",
        "coach_sacked", "coach_hired", "coach_poached", "coach_retired", "player_to_coach", "coach_digest",
        "market_week", "deal_collapsed", "record_fee", "rival_move", "release_clause", "free_agent", "contract_renewal",
        "winter_window", "deadline_day", "loan_digest", "wonderkid", "academy_graduates", "one_club_man", "club_legend", "captain",
        "derby_result", "rivalry_born",
        // The meta, the media and the world events.
        "era_begins", "era_ends", "counter_style", "imitation", "power_ranking", "predictions", "prediction_scorecard",
        "press_award", "press_awards", "wc_bids", "wc_host_vote", "wc_draw_ceremony",
        "superleague_rumour", "superleague_announce", "superleague_showdown", "superleague_outcome",
        "investor_rumour", "investor_talks", "investor_takeover", "investor_verdict", "crisis_begins", "crisis_ends",
        "merger_proposed", "league_merged", "merger_failed", "league_split", "boom_begins", "boom_ends",
    )

    /** Titles of cups held every few seasons: a run of them is counted in editions (대회). */
    private val EDITION_KINDS = setOf("WC", "WCW", "CC", "CCW", "FAMILY")

    /** Headline of the plain article for a kind without templates (or an event missing its keys). */
    const val PLAIN_HEADLINE = "리그 소식"

    /** The last argument that marks an event of a national season (its awards, the lower tiers' digest). */
    const val NATIONAL = "national"

    /** The last argument of a transfer abroad of a league champion or award winner (bigger news). */
    const val STAR = "star"

    /** A winter window's `transfer` ([WinterReport.WINTER_FLAG], its third argument). */
    private const val WINTER = WinterReport.WINTER_FLAG

    /** A `federation_decision` by a world event's 긴급 안건 ([Proposal.EMERGENCY]). */
    private val EMERGENCY = Proposal.EMERGENCY.toString()

    /** A `record_fee`'s scope for the world record (else a league's id). */
    private const val WORLD_RECORD = "W"

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
     * team id's (family teams and all-star sides); [coach] a coach id's (the club world's coaches,
     * the `coachName` of design ENG 11.2). With [rating], a knockout win by an AI rated 100 or
     * more below its opponent is written as a giant killing. The article links the event's match
     * ([LeagueEvent.ref]) and takes its [scope] (a league, a confederation, ""). [money]: the
     * event comes from a club book with a club world, whose fees are [Money] units: the
     * off-season's `transfer`, `transfer_abroad` and `market_digest` then write them with
     * [Money.text] (a book without one keeps today's plain numbers).
     */
    fun article(
        e: LeagueEvent,
        name: (String) -> String,
        club: (String) -> String = { it },
        team: (String) -> String = Competitions::teamLabel,
        rating: ((String) -> Double)? = null,
        scope: (LeagueEvent) -> String = { "" },
        coach: (String) -> String = { it },
        money: Boolean = false,
    ): Article = write(e, name, club, team, rating, null, scope, coach, money)

    /** The importance (1-5) of [e]'s article, whatever the names (what [NewsBudget] ranks by). */
    fun importance(e: LeagueEvent): Int = write(e, { it }, { it }, { it }, null, null, { "" }, { it }, true).importance

    /**
     * What a key of an event of kind [kind] stands for, by its place among the keys (the last
     * letter for every place after): P a player, C a club, K a coach, T a team. Kinds without an
     * entry read their keys as before (club ids in the club competitions, teams in the team ones,
     * players otherwise).
     */
    private val ROLES: Map<String, String> = mapOf(
        "coach_sacked" to "CKK", "coach_hired" to "CKC", "coach_poached" to "CKC", "coach_retired" to "K", "player_to_coach" to "PK",
        "fan_protest" to "CK", "market_week" to "PCCCK", "derby_result" to "PPCC", "press_awards" to "CP",
        "superleague_announce" to "C", "superleague_showdown" to "C", "superleague_outcome" to "C",
        "investor_rumour" to "C", "investor_talks" to "C", "investor_takeover" to "C", "investor_verdict" to "C",
    )

    /** What the club world's and the media's templates read besides the slots: the event's keys by display name, and the resolvers. */
    private class Ctx(
        val names: List<String>,
        val name: (String) -> String,
        val club: (String) -> String,
        val coach: (String) -> String,
        val money: Boolean,
    )

    /** [article], with the rating gap of a knockout win already known from its `upset` event ([upsetGap]). */
    private fun write(
        e: LeagueEvent,
        name: (String) -> String,
        club: (String) -> String,
        team: (String) -> String,
        rating: ((String) -> Double)?,
        upsetGap: Double?,
        scope: (LeagueEvent) -> String,
        coach: (String) -> String,
        money: Boolean,
    ): Article {
        val v = HashMap<String, String>()
        v["season"] = e.season.toString()
        v["comp"] = e.comp?.label ?: Comp.LEAGUE.label
        // A continental cup's events name their tournament ("@아시아컵", the last argument).
        e.args.lastOrNull()?.takeIf { it.startsWith("@") }?.let { v["comp"] = it.drop(1) }
        // Keys are AIs, or club and team ids in the club and team competitions (and the clubs of a
        // transfer or draft): an id the club or team names do not know is an AI's. The club world's
        // kinds say what each of their keys is ([ROLES]).
        val clubKeys = e.comp == Comp.CLUB || e.comp == Comp.CLUB_CUP
        val teamKeys = e.comp == Comp.FAMILY || e.comp == Comp.ALL_STAR ||
            (e.comp == Comp.WORLD_CUP || e.comp == Comp.CONTINENTAL) && e.kind != "nation_squad" && e.kind != "nation_awards"
        val roles = ROLES[e.kind]
        fun who(i: Int, k: String): String {
            if (k == "-") return ""
            when (roles?.let { it.getOrElse(i) { _ -> it.last() } }) {
                'P' -> return name(k)
                'C' -> return club(k)
                'K' -> return coach(k)
                'T' -> return team(k)
            }
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

        val (list, importance) = templates(e, v, arg, club, team, rating, upsetGap, name, Ctx(names, name, club, coach, money))
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
        coach: (String) -> String = { it },
        money: Boolean = false,
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
            out += write(e, name, club, team, rating, gap, scope, coach, money)
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
        x: Ctx,
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
            fun shown(s: String): String = kind?.let { k -> s.toDoubleOrNull()?.let { recordValue(k, it) } } ?: number(s)
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
            arg(1)?.let { scores(v, it) }
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
                        T("{comp}의 이변: 강호 {b}, 패자조로", "강호 {b|이} {round}에서 {a}에게[[ {lscore|으로}]] 졌어요. 패자조에서 다시 올라올 수 있을지 관심이 모여요."),
                    ) to if (LAST_EIGHT.containsMatchIn(arg(0).orEmpty())) 3 else 2
                }
                drop -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾어", "{a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 물리쳤어요. {b|은} 패자조에서 한 번 더 기회를 노려요."),
                    T("{a}, {round} 관문 통과", "{a|이} {b|과} 맞붙은 {comp} {round}에서[[ {score|으로}]] 이겼어요. {b|은} 패자조로 내려갔어요."),
                ) to 2
                e.comp == Comp.WORLD_CUP || e.comp == Comp.CONTINENTAL -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾고 진출", "{a|이} {comp} {round}에서 {b|을}[[ 보드 {score|으로}]] 물리쳤어요. 대표팀의 도전이 다음 라운드로 이어져요."),
                    T("{b}, {comp} {round}에서 탈락", "{b|은} {a}에[[ 보드 {lscore|으로}]] 져 이번 {comp|을} 마쳤어요. {a|은} 다음 라운드로 향해요."),
                ) to if (e.comp == Comp.WORLD_CUP) 3 else 2
                e.comp == Comp.PROMOTION_PLAYOFF -> listOf(
                    T("{round}: {a}의 승리", "{a|이} {round}에서 {b|을}[[ {score|으로}]] 꺾었어요. 다음 시즌 어느 부에서 뛸지가 이 승부로 갈렸어요."),
                    T("{round}, 웃은 쪽은 {a}", "{a|과} {b|이} 맞붙은 {round}에서 {a|이} 이겼어요. {b|은} 아쉬움을 삼켰어요."),
                ) to 3
                gap != null -> {
                    v["gap"] = Math.round(gap).toString()
                    listOf(
                        T("자이언트 킬링! {a}, {b} 잡고 {round} 통과", "레이팅이 {gap}점 낮은 {a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 꺾었어요. 컵 대회다운 반전이에요."),
                        T("{comp}의 이변: 강호 {b} 탈락", "강호 {b|이} {round}에서 {a}에게[[ {lscore|으로}]] 졌어요. {a|은} 레이팅 {gap}점 차를 뛰어넘었어요."),
                    ) to if (LAST_EIGHT.containsMatchIn(arg(0).orEmpty())) 3 else 2
                }
                else -> listOf(
                    T("{comp} {round}: {a}, {b} 꺾고 진출", "{a|이} {comp} {round}에서 {b|을}[[ {score|으로}]] 물리쳤어요. 다음 라운드에서 또 한 번 승리를 노려요."),
                    T("{a}, {round} 관문 통과", "{a|이} {b|과} 맞붙은 {comp} {round}에서[[ {score|으로}]] 이겼어요. 우승까지 한 걸음 더 다가갔어요."),
                    T("{b}, {comp} {round}에서 멈춰", "{b|은} {a}에게[[ {lscore|으로}]] 져 {comp} 여정을 마쳤어요. {a|은} 다음 라운드로 향해요."),
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
            arg(0)?.let { scores(v, it) }
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
                    T("{b}, 결승에서 아쉬운 준우승", "{b|은} {comp} 결승에서 {a}에게[[ {lscore|으로}]] 졌어요. 우승은 {a}의 몫이 됐어요."),
                ) to 4
            }
        }
        "transfer" -> {
            // [ai, fromClub, toClub] (fee); a free agent has "-" (or no) selling club. A club world's
            // (fee in money, "1" when talks came true) and its winter window's (fee, talked 0/1, "W").
            val free = e.keys.size < 3 || e.keys[1] == "-" || e.keys[1].isEmpty()
            v.remove("b")
            v.remove("c")
            val clubs = e.keys.drop(1).filter { it != "-" && it.isNotEmpty() }
            if (free) clubs.lastOrNull()?.let { v["to"] = club(it) } else {
                v["from"] = club(e.keys[1])
                v["to"] = club(e.keys[2])
            }
            arg(0)?.let { v["fee"] = if (x.money) moneyOf(it.takeIf { f -> f != "0" }) ?: "" else number(it) }
            val talked = arg(1) == "1"
            val winter = arg(2) == WINTER
            if (!free && (talked || winter)) {
                v["after"] = if (talked) "소문이 사실이 됐어요." else "후반기부터 새 유니폼을 입고 뛰어요."
                if (winter) listOf(
                    T("{a}, 겨울 이적시장에서 {to|으로}", "{a|이} 겨울 이적시장에서 {from|을} 떠나 {to|으로} 옮겨요.[[ 이적료는 {fee|이에요}.]] {after}"),
                ) to 2 else listOf(
                    T("{a}, {to|으로} 이적", "{a|이} {from}에서 {to|으로} 옮겨요.[[ 이적료는 {fee|이에요}.]] {after}"),
                    T("{to}, {from}의 {a} 영입", "{to|이} {from}에서 뛰던 {a|을} 데려왔어요.[[ 이적료 {fee|을} 들인 승부수예요.]] {after}"),
                ) to 2
            } else if (free) {
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
                // (3, as every title race: design_ux 5.2's scale; a title is 4 and up.)
                arg(1) == "lead" -> listOf(
                    T("선두 교체! {a}, 1위로", "{a|이} {b|을} 끌어내리고 {top} 선두에 올랐어요. 두 선수의 승점 차는 {gap}점이에요."),
                    T("{a}, {top} 선두 등극", "{a|이} 1위 자리를 차지했어요. 2위 {b|과} 승점 {gap}점 차라 아직 안심할 수 없어요."),
                ) to 3
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
        else -> worldTemplates(e, v, arg, club, name, x)
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
        x: Ctx,
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
                // A world event's 긴급 안건 (by E), and a pending proposal it set aside.
                result == "A" && by == EMERGENCY -> listOf(
                    T("‘{title}’ 긴급 가결", "슈퍼리그 사태로 요트협회가 ‘{title}’ 안건을 긴급 결정했어요. 시즌 {from}부터 적용돼요."),
                ) to 4
                result == "V" && by == EMERGENCY -> listOf(
                    T("‘{title}’ 무효", "‘{title}’ 안건은 긴급 결정으로 무효가 됐어요. 같은 규칙이 슈퍼리그 사태로 이미 바뀌었어요."),
                ) to 2
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
                // The assembly only tables proposals: rules adopted at this close still apply.
                T("요트협회 총회, 새 안건 없음", "이번 요트협회 총회에는 새로 올린 안건이 없어요. 다음 총회는 시즌 {vote|이} 끝난 뒤에 열려요."),
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
            arg(2)?.takeIf { it != "0" }?.let { v["fee"] = if (x.money) moneyOf(it) ?: number(it) else number(it) }
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
            arg(5)?.takeIf { it != "0" }?.let { v["fee"] = if (x.money) moneyOf(it) ?: number(it) else number(it) }
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
        else -> deskTemplates(e, v, arg, name, x)
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
        x: Ctx,
    ): Pair<List<T>, Int> = when (e.kind) {
        "league_upset" -> {
            // [winner, loser] (gap, league or "-", tier, score, the league's rank or "-").
            arg(0)?.let { v["gap"] = number(it) }
            val id = arg(1) ?: LeagueIds.LEGACY
            val tier = arg(2)?.toIntOrNull() ?: 0
            v["div"] = LeagueIds.division(id, tier)
            v["lg"] = LeagueIds.label(id)
            arg(3)?.let { scores(v, it) }
            val rank = arg(4)?.toIntOrNull() ?: Int.MAX_VALUE
            listOf(
                T("이변! {a}, {b} 격파", "{div}에서 레이팅이 {gap}점 낮은 {a|이} {b|을} {score|으로} 꺾었어요. 이번 주 손꼽히는 이변이에요."),
                T("{b}, {a}에게 덜미", "{b|은} {div}에서 한참 아래로 본 {a}에게 {lscore|으로} 졌어요. 레이팅 차는 {gap}점이었어요."),
                T("약자의 반란: {a}", "{a|이} 레이팅 {gap}점 차를 뒤집고 {b|을} 눌렀어요. {lg} 순위 싸움이 더 뜨거워졌어요."),
            ) to if (tier == 0 && (id == LeagueIds.LEGACY || rank < LeagueWorld.LEAGUE_TITLES)) 3 else 2
        }
        "club_race" -> {
            // [leader club, second club] (league, gap x 10, "lead" | "close").
            arg(0)?.let { v["lg"] = LeagueIds.label(it) }
            arg(1)?.toIntOrNull()?.let { v["gap"] = String.format(Locale.ROOT, "%.1f", it / 10.0) }
            // Level on points: the order is the tie-break's.
            val level = arg(1)?.toIntOrNull() == 0
            when {
                arg(2) == "lead" && level -> listOf(
                    T("{a}, {lg} 클럽 순위 1위로", "{a|이} {b|과} 점수는 같지만 순위에서 앞서 {lg} 클럽 순위 맨 위에 올랐어요."),
                )
                arg(2) == "lead" -> listOf(
                    T("{a}, {lg} 클럽 순위 1위로", "{a|이} {b|을} 제치고 {lg} 클럽 순위 맨 위에 올랐어요. 두 클럽의 차이는 {gap}점이에요."),
                )
                level -> listOf(
                    T("클럽 순위 접전: {a}·{b}", "{lg} 클럽 순위에서 {a|와} {b}의 점수가 같아졌어요. 선수들의 남은 경기에 클럽 우승이 걸렸어요."),
                )
                else -> listOf(
                    T("클럽 순위 접전: {a}·{b}", "{lg} 클럽 순위에서 {a|와} {b}의 차이가 {gap}점으로 줄었어요. 선수들의 남은 경기에 클럽 우승이 걸렸어요."),
                )
            } to 3
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
            // [≤ 8 giant killers not told alone, or a top seed's] (stage, ties, upsets, the top seed out or "-", next week or "-").
            arg(0)?.let { v["stage"] = it }
            arg(1)?.let { v["n"] = number(it) }
            val upsets = arg(2)?.toIntOrNull() ?: 0
            val out = arg(3)?.let { name(it) }
            // The winners it names beat players rated well above them (or a top seed).
            val who = shortList(e.keys, name, 2)?.let { if (e.keys.size > 2) "$it 등" else it }
            val gone = out?.let { "톱시드 ${it}도 짐을 쌌어요" }
            v["surprise"] = when {
                upsets > 0 && who != null -> "${who + Josa.after(who, "이")} 강호를 꺾으며 이변이 ${upsets}번 나왔" + (gone?.let { "고, $it." } ?: "어요.")
                upsets > 0 -> "이변이 ${upsets}번 나왔" + (gone?.let { "고, $it." } ?: "어요.")
                who != null -> "${who + Josa.after(who, "이")} 강호를 꺾었" + (gone?.let { "고, $it." } ?: "어요.")
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
            // [≤ 8, group winners first] (stage, qualified, eliminated, top seeds among them, how many of the keys won their group).
            arg(0)?.let { v["stage"] = it }
            arg(1)?.let { v["q"] = it }
            arg(2)?.let { v["out"] = it }
            arg(3)?.takeIf { it != "0" }?.let { v["fallen"] = it }
            val firsts = e.keys.take(arg(4)?.toIntOrNull() ?: e.keys.size)
            shortList(firsts, name, DIGEST_SHOWN)?.let { v["firsts"] = if (firsts.size > DIGEST_SHOWN) "$it 등" else it }
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
        else -> clubTemplates(e, v, arg, x)
    }

    /**
     * The club world's kinds (design ENG 11.2): money and club life, coaches, the transfer market,
     * club history and the academies. Clubs, players and coaches come as keys ([ROLES] where the
     * competition does not say), money as [Money] units ([moneyOf]: every amount ends in 억, so the
     * particle after one is always 을 / 이에요).
     */
    private fun clubTemplates(e: LeagueEvent, v: MutableMap<String, String>, arg: (Int) -> String?, x: Ctx): Pair<List<T>, Int> = when (e.kind) {
        "club_accounts" -> {
            // [richest, biggest loss?] (its income, the season's biggest loss, clubs whose balance ended below 0).
            // The red count is of balances (빚), the loss of the season's accounts: a club can lose money and keep a balance.
            moneyOf(arg(0))?.let { v["inc"] = it }
            moneyOf(arg(1)?.takeIf { it != "0" })?.let { v["loss"] = it }
            val red = arg(2)?.toIntOrNull() ?: 0
            v["red"] = if (red > 0) "빚을 안고 시즌을 마친 클럽은 ${red}곳이에요." else "빚을 안고 시즌을 마친 클럽은 한 곳도 없어요."
            listOf(
                T("클럽 재정 결산: 수입 1위 {a}", "시즌 {season}에 가장 많이 번 클럽은 수입 {inc|을} 올린 {a|이에요}. {red}[[ 이번 시즌 손실이 가장 큰 클럽은 {loss|을} 잃은 {b|이에요}.]]"),
            ) to 2
        }
        "club_trouble" -> {
            // [club] (balance).
            val balance = arg(0)?.toIntOrNull()
            if (balance != null && balance < 0) v["debt"] = Money.text(-balance) else balance?.let { v["bal"] = Money.text(it) }
            listOf(
                when {
                    v["debt"] != null -> T("{a}, 재정 위기", "{a}의 빚이 {debt|으로} 불어났어요. 이적시장에서 선수를 사기 어려워졌고, 주축 선수를 팔아야 할지도 몰라요.")
                    v["bal"] != null -> T("{a}, 재정 위기", "{a}의 자금이 {bal}까지 떨어졌어요. 이적시장에서 선수를 사기 어려워졌고, 주축 선수를 팔아야 할지도 몰라요.")
                    else -> T("{a}, 재정 위기", "{a|이} 재정 위기에 빠졌어요. 이적시장에서 선수를 사기 어려워졌고, 주축 선수를 팔아야 할지도 몰라요.")
                },
            ) to 3
        }
        "forced_sale" -> {
            // [ai, seller, buyer] (fee).
            moneyOf(arg(0)?.takeIf { it != "0" })?.let { v["fee"] = it }
            listOf(
                T("재정난 {b}, {a|을} {c}에 넘겨요", "빚에 시달리던 {b|이} 간판 선수 {a|을}[[ {fee}에]] {c|으로} 보냈어요. 팬들은 아쉬움을 감추지 못했어요."),
            ) to 3
        }
        "administration" -> listOf(
            T("{a}, 법정관리에 들어가요", "적자를 견디지 못한 {a|이} 법정관리에 들어갔어요. 새 시즌 클럽 순위표에서 승점 10%가 깎여요."),
        ) to 4
        "rescue" -> {
            // [club] (the new owner).
            arg(0)?.let { v["owner"] = it }
            listOf(
                T("{a}, 극적인 구조", "파산 위기의 {a|을} {owner|이} 인수했어요. 새 주인이 빚을 덜어 주면서 클럽은 살아남았어요."),
            ) to 4
        }
        "club_folded" -> {
            // [club] (members released).
            arg(0)?.takeIf { it != "0" }?.let { v["n"] = it }
            listOf(
                T("{a}, 끝내 해체돼요", "재정 위기를 넘지 못한 {a|이} 문을 닫아요.[[ 선수 {n}명은 새 클럽을 찾아 떠나요.]] 클럽의 기록은 역사로 남아요."),
            ) to 5
        }
        "takeover" -> {
            // [club] (the new owner, its type's code).
            arg(0)?.let { v["owner"] = it }
            val type = ownerType(arg(1))
            ownerWord(type)?.let { v["type"] = it }
            v["promise"] = when (type) {
                OwnerType.TYCOON, OwnerType.STATE -> "첫 시즌부터 과감한 투자를 약속했어요."
                OwnerType.FUND -> "클럽 가치를 키우겠다고 밝혔어요."
                OwnerType.LOCAL -> "지역과 함께 클럽을 키우겠다고 했어요."
                OwnerType.FANS -> "팬들과 함께 클럽을 꾸려 가겠다고 했어요."
                null -> "새 시즌 계획은 곧 밝히기로 했어요."
            }
            listOf(
                T("{a}, 새 주인 맞아요", "[[{type} ]]{owner|이} {a|을} 인수했어요. {promise}"),
            ) to if (type == OwnerType.TYCOON || type == OwnerType.STATE) 4 else 3
        }
        "owner_exit" -> {
            // [club] (the owner leaving).
            arg(0)?.let { v["owner"] = it }
            listOf(
                T("{owner}, {a}에서 손 떼요", "{a}의 구단주 {owner|이} 클럽을 떠나요. 지원이 끊긴 {a|은} 허리띠를 졸라매야 해요."),
            ) to 3
        }
        "sponsor_deal" -> {
            // [club] (sponsor, a season's value, seasons, record 0/1).
            arg(0)?.let { v["sponsor"] = it }
            moneyOf(arg(1))?.let { v["value"] = it }
            arg(2)?.let { v["seasons"] = it }
            val record = arg(3) == "1"
            if (record) v["record"] = "스폰서 계약 역대 최고 금액이에요."
            listOf(
                T("{a}, {sponsor|과} 스폰서 계약", "{a|이} {sponsor|과}[[ {seasons}시즌 동안]] 한 시즌 {value}의 메인 스폰서 계약을 맺었어요.[[ {record}]] 다음 시즌부터 유니폼에 {sponsor}의 이름이 새겨져요."),
            ) to if (record) 3 else 2
        }
        "sponsor_exit" -> {
            // [club] (sponsor, mid 0/1: a walk-out before the deal's end).
            arg(0)?.let { v["sponsor"] = it }
            listOf(
                if (arg(1) == "1") {
                    T("{sponsor}, {a|과} 결별", "메인 스폰서 {sponsor|이} 계약 기간 중에 {a}에서 손을 뗐어요. {a|은} 새 스폰서를 찾아야 해요.")
                } else {
                    T("{sponsor}, {a|과} 결별", "계약이 끝난 메인 스폰서 {sponsor|이} {a|과} 재계약하지 않기로 했어요. {a|은} 새 스폰서를 찾아야 해요.")
                },
            ) to 2
        }
        "stadium_project" -> {
            // [club] (seats now, seats after, seasons of building, cost, E expansion / N new stadium).
            arg(0)?.let { v["from"] = number(it) }
            arg(1)?.let { v["to"] = number(it) }
            arg(2)?.takeIf { (it.toIntOrNull() ?: 0) > 0 }?.let { v["seasons"] = it }
            moneyOf(arg(3))?.let { v["cost"] = it }
            listOf(
                if (arg(4) == "N") {
                    T("{a}, 새 홈구장 건설", "{a|이} {cost|을} 들여 {to}석 규모의 새 홈구장을 지어요.[[ 공사는 {seasons}시즌 걸려요.]] 완공되면 입장 수입이 크게 늘어날 거예요.")
                } else {
                    T("{a}, 경기장 증축", "{a|이} {cost|을} 들여 홈구장을 {from}석에서 {to}석으로 넓혀요.[[ 공사는 {seasons}시즌 걸려요.]] 완공되면 입장 수입이 늘어날 거예요.")
                },
            ) to 2
        }
        "stadium_open" -> {
            // [club] (the stadium's name, its seats, E an expansion finished / N a new stadium).
            arg(0)?.let { v["stadium"] = it }
            arg(1)?.let { v["cap"] = number(it) }
            val named = v["stadium"] != null
            listOf(
                when {
                    arg(2) == "N" && named -> T("{a}, 새 홈구장 개장", "{a}의 새 홈구장 {stadium|이} 문을 열었어요. {cap}석 규모예요.")
                    arg(2) == "N" -> T("{a}, 새 홈구장 개장", "{a}의 새 홈구장이 문을 열었어요. {cap}석 규모예요.")
                    named -> T("{a}, 홈구장 증축 완료", "{a}의 홈구장 {stadium|이} 증축을 마쳤어요. 이제 {cap}석 규모예요.")
                    else -> T("{a}, 홈구장 증축 완료", "{a}의 홈구장이 증축을 마쳤어요. 이제 {cap}석 규모예요.")
                },
            ) to 3
        }
        "fan_protest" -> listOf(
            // [club, its coach?] (mood).
            if (v["b"] != null) {
                T("{a} 팬들, 항의 시위", "성적 부진에 화가 난 {a} 팬들이 경기장 앞에 모였어요. {b} 감독을 향한 압박이 커지고 있어요.")
            } else {
                T("{a} 팬들, 항의 시위", "성적 부진에 화가 난 {a} 팬들이 경기장 앞에 모였어요. 구단을 향한 불만이 커지고 있어요.")
            },
        ) to 2
        "fan_party" -> listOf(
            T("{a} 팬들, 축제 분위기", "클럽 순위 1위에 오른 {a}의 팬들이 거리로 나와 밤새 축하했어요. 홈구장 앞은 새벽까지 응원가로 가득했어요."),
        ) to 2
        "club_ranking" -> {
            // [the most valuable club, the best by results] (its value).
            moneyOf(arg(0)?.takeIf { it != "0" })?.let { v["value"] = it }
            val then = when {
                v["b"] == null -> "클럽 가치는 선수단과 경기장, 시설, 팬, 재정을 함께 따져 매겨요."
                e.keys[1] == e.keys[0] -> "최근 다섯 시즌 성적에서도 1위예요."
                else -> "최근 다섯 시즌 성적으로는 {b|이} 1위예요."
            }
            listOf(
                T("세계 클럽 랭킹: {a} 1위", "[[클럽 가치 {value}의 ]]{a|이} 세계 클럽 랭킹 1위에 올랐어요. $then"),
            ) to 2
        }
        "club_world_digest" -> {
            // [≤ 6 clubs] (sponsor deals, building projects, facility investments).
            val parts = listOfNotNull(
                arg(0)?.toIntOrNull()?.takeIf { it > 0 }?.let { "스폰서 계약 ${it}건" },
                arg(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { "경기장 공사 ${it}곳" },
                arg(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { "시설 투자 ${it}곳" },
            )
            if (parts.isNotEmpty()) v["what"] = parts.joinToString(", ")
            listOf(
                T("오프시즌 클럽 소식", "이번 오프시즌에 {what|이} 있었어요.[[ 눈에 띄는 곳은 {seeds|이에요}.]] 클럽마다 새 시즌 준비가 한창이에요."),
                T("오프시즌 클럽 소식", "클럽마다 새 시즌 준비가 한창이에요.[[ 눈에 띄는 곳은 {seeds|이에요}.]]"),
            ).let { if (v["what"] != null) it.take(1) else it.drop(1) } to 2
        }

        // ---- coaches ----------------------------------------------------------------------------
        "coach_sacked" -> {
            // [club, coach, the coach hired at once?] (club-table place, the board's target, far 0/1) and,
            // at a winter checkpoint, (…, protest 0/1).
            arg(0)?.let { v["place"] = it }
            arg(1)?.let { v["exp"] = it }
            if (arg(2) == "1") v["far"] = "한참"
            val winter = e.args.size >= 4
            val below = (arg(0)?.toIntOrNull() ?: 0) > (arg(1)?.toIntOrNull() ?: Int.MAX_VALUE)
            val first = when {
                winter && arg(3) == "1" -> "성적 부진에 팬들의 항의까지 겹치자 {a|이} 시즌 도중 {b} 감독을 경질했어요."
                winter -> "{a|이} 성적 부진을 이유로 시즌 도중 {b} 감독을 경질했어요."
                else -> "{a|이} 성적 부진을 이유로 {b} 감독과 결별했어요."
            }
            val second = if (below) "클럽 순위 {place}위에 그쳐 목표였던 {exp}위에[[ {far}]] 못 미쳤어요." else "클럽 순위는 {place}위였지만 이사회의 인내심이 바닥났어요."
            listOf(
                T("{a}, {b} 감독 경질", "$first $second[[ 후임으로 {c} 감독이 곧바로 지휘봉을 잡았어요.]]"),
            ) to 3
        }
        "coach_hired" -> {
            // [club, coach, the club it led last?] (its philosophy's code).
            val phil = arg(0)?.singleOrNull()?.let { Philosophy.of(it) }
            phil?.let {
                v["phil"] = it.label
                v["desc"] = "새 감독은 " + firstSentence(it.desc)
            }
            listOf(
                if (phil != null) {
                    T("{a}, 새 감독에 {b}", "{a|이} {phil} 감독 {b|을} 새 사령탑으로 선임했어요.[[ {c}에서 지휘봉을 잡았던 감독이에요.]] {desc}")
                } else {
                    T("{a}, 새 감독에 {b}", "{a|이} {b|을} 새 감독으로 선임했어요.[[ {c}에서 지휘봉을 잡았던 감독이에요.]] 새 감독과 함께 새 시즌을 준비해요.")
                },
            ) to if (v["c"] != null) 3 else 2
        }
        "coach_poached" -> {
            // [club, coach, the club it left] (the compensation).
            moneyOf(arg(0)?.takeIf { it != "0" })?.let { v["fee"] = it }
            listOf(
                T("{a}, {c}의 {b} 감독 영입", "{a|이}[[ 위약금 {fee|을} 내고]] {c}의 {b} 감독을 데려왔어요. 감독을 잃은 {c|은} 서둘러 후임을 구했어요."),
            ) to 3
        }
        "coach_retired" -> {
            // [coach] (seasons in charge, titles).
            arg(0)?.takeIf { it != "0" }?.let { v["seasons"] = it }
            arg(1)?.takeIf { it != "0" }?.let { v["titles"] = it }
            listOf(
                if (v["titles"] != null) {
                    T("{a} 감독, 은퇴", "[[{seasons}시즌 동안 ]]지휘봉을 잡은 {a} 감독이 은퇴해요. 우승 {titles}회를 이끈 명장이에요.")
                } else {
                    T("{a} 감독, 은퇴", "[[{seasons}시즌 동안 ]]지휘봉을 잡은 {a} 감독이 은퇴해요. 오랜 시간 벤치를 지킨 지도자가 이제 쉬어 가요.")
                },
            ) to 2
        }
        "player_to_coach" -> {
            // [player, coach] (its style family's code or "-", its philosophy's code).
            arg(0)?.singleOrNull()?.let { StyleFamily.ofCode(it) }?.let { v["style"] = it.label }
            arg(1)?.singleOrNull()?.let { Philosophy.of(it) }?.let { v["phil"] = it.label }
            val then = when {
                v["style"] != null && v["phil"] != null -> "선수 시절의 {style} 스타일을 살려 {phil} 지도자로 첫발을 내디뎌요."
                v["phil"] != null -> "{phil} 지도자로 첫발을 내디뎌요."
                v["style"] != null -> "선수 시절의 {style} 스타일이 지도 철학이 될 거예요."
                else -> "새 클럽을 찾아 지도자 생활을 시작해요."
            }
            listOf(T("{a}, 지도자로 새 출발", "은퇴한 {a|이} 감독으로 돌아와요. $then")) to 2
        }
        "coach_digest" -> {
            // [≤ 6 clubs] (sacked, hired, poached, clubs with a new coach); the winter checkpoint's has a week.
            val sacked = arg(0)?.toIntOrNull() ?: 0
            arg(0)?.let { v["ns"] = it }
            arg(1)?.let { v["nh"] = it }
            arg(3)?.let { v["n"] = it }
            arg(2)?.takeIf { it != "0" }?.let { v["np"] = it }
            val winter = NewsBudget.inSeasonWeek(e.week)
            val head = if (sacked > 0) "감독 이동: 경질 {ns}명·선임 {nh}명" else "감독 이동: 새 감독 {nh}명"
            val first = if (winter) "시즌 도중 감독이 바뀐 클럽은 {n}곳이에요." else "이번에 감독이 바뀐 클럽은 {n}곳이에요."
            listOf(
                T(head, "$first[[ 눈에 띄는 곳은 {seeds|이에요}.]] [[그중 {np}곳은 다른 클럽의 감독을 데려왔어요.]]"),
                T(head, "$first[[ 눈에 띄는 곳은 {seeds|이에요}.]] 새 감독들이 클럽을 어떻게 바꿀지 지켜봐요."),
            ).let { if (v["np"] != null) it.take(1) else it.drop(1) } to 2
        }

        // ---- the transfer market ----------------------------------------------------------------
        "market_week" -> {
            // [ai, buyer, seller, a club that sacked its coach?, that coach?] (week, the asked fee, the week's new talks).
            (arg(0) ?: e.week.takeIf { it > 0 }?.toString())?.let { v["week"] = it }
            moneyOf(arg(1)?.takeIf { it != "0" })?.let { v["fee"] = it }
            arg(2)?.toIntOrNull()?.takeIf { it > 1 }?.let { v["talks"] = it.toString() }
            val sacked = e.keys.size >= 5 && x.names.getOrNull(3).orEmpty().isNotEmpty() && x.names.getOrNull(4).orEmpty().isNotEmpty()
            if (sacked) v["e"] = x.names[4]
            val more = when {
                sacked -> " {d|은} {e} 감독과 결별했어요."
                v["talks"] != null -> " 이번 주에 새로 시작된 이적 협상은 모두 {talks}건이에요."
                else -> ""
            }
            val fee = if (v["fee"] != null) "제시한 이적료는 {fee|이에요}." else "협상 결과는 오프시즌에 나와요."
            listOf(
                T(if (sacked) "{week}주차 이적·감독 소식" else "{week}주차 이적 소식: {b}, {a} 영입 협상", "{b|이} {c}에서 뛰는 {a}의 영입을 놓고 협상에 들어갔어요. $fee$more"),
            ) to 2
        }
        "deal_collapsed" -> {
            // [ai, buyer, seller, hijacker?] (reason: P price, S the player stays, H hijacked, M a new coach, F money, R the rules).
            val reason = arg(0)?.singleOrNull()
            if (reason == 'H' && v["d"] != null) {
                listOf(T("{d}, {a} 가로채기", "{b|이} 공들이던 {a} 영입을 마지막 순간 {d|이} 가로챘어요. {a|은} {d} 유니폼을 입어요.")) to 2
            } else {
                v["why"] = when (reason) {
                    'P' -> "가격이 맞지 않았어요."
                    'S' -> "선수가 잔류를 택했어요."
                    'H' -> "다른 클럽이 먼저 데려갔어요."
                    'M' -> "감독이 바뀌면서 없던 일이 됐어요."
                    'F' -> "구단 재정이 발목을 잡았어요."
                    'R' -> "규정에 막혔어요."
                    else -> "끝내 합의에 이르지 못했어요."
                }
                listOf(T("{a} 이적, 막판 무산", "{b|과} {c}의 {a} 이적 협상이 깨졌어요. {why}")) to 2
            }
        }
        "record_fee" -> {
            // [ai, seller, buyer] (fee, the record before, its scope: W the world, else a league's id).
            moneyOf(arg(0))?.let { v["fee"] = it }
            moneyOf(arg(1)?.takeIf { it != "0" })?.let { v["prev"] = it }
            val world = arg(2) == WORLD_RECORD
            val league = arg(2)?.takeIf { !world }?.let { LeagueIds.label(it) }
            v["where"] = if (world) "요트 역사상" else league?.let { "$it 역대" } ?: "역대"
            v["top"] = if (league != null) "$league 최고 이적료" else "역대 최고 이적료"
            listOf(
                T("{top}! {a}, {fee}", "{c|이} {b}의 {a|을} {fee}에 데려왔어요. [[종전 기록 {prev|을} 넘어선 ]]{where} 최고 이적료예요."),
            ) to if (world) 5 else 4
        }
        "rival_move" -> {
            // [ai, from, to] (fee).
            moneyOf(arg(0)?.takeIf { it != "0" })?.let { v["fee"] = it }
            listOf(
                T("배신인가 도전인가: {a}, 라이벌 {c|으로}", "{b}의 {a|이} 라이벌 {c|으로} 옮겨요.[[ 이적료는 {fee|이에요}.]] {b} 팬들이 크게 반발하고 있어요."),
            ) to 3
        }
        "release_clause" -> {
            // [ai, from, to] (the clause paid).
            moneyOf(arg(0))?.let { v["fee"] = it }
            listOf(
                T("{c}, {a}의 바이아웃 발동", "{c|이} 바이아웃 금액 {fee|을} 내고 {b}의 {a|을} 데려갔어요. {b|은} 막을 방법이 없었어요."),
            ) to 3
        }
        "free_agent" -> {
            // [ai, the club it left, the club it signs for] (wage).
            moneyOf(arg(0)?.takeIf { it != "0" })?.let { v["wage"] = it }
            listOf(
                if (v["wage"] != null) {
                    T("{a}, 자유계약으로 {c}행", "계약이 끝난 {a|이} 이적료 없이[[ {b|을} 떠나]] {c|으로} 옮겨요. 새 클럽에서 받는 연봉은 {wage|이에요}.")
                } else {
                    T("{a}, 자유계약으로 {c}행", "계약이 끝난 {a|이} 이적료 없이[[ {b|을} 떠나]] {c|으로} 옮겨요. 새 팀에서 다시 출발해요.")
                },
            ) to 2
        }
        "contract_renewal" -> {
            // [ai, club] (seasons, wage).
            arg(0)?.let { v["seasons"] = it }
            moneyOf(arg(1)?.takeIf { it != "0" })?.let { v["wage"] = it }
            listOf(
                T("{a}, {b|과} 재계약", "{a|이} {b|과}[[ {seasons}시즌]] 재계약했어요.[[ 연봉은 {wage|이에요}.]] {b|은} 핵심 선수를 붙잡았어요."),
            ) to 2
        }
        "winter_window" -> {
            // [≤ 6 movers, biggest fee first] (deals, the biggest one's ai, its from club, its to club, its fee).
            arg(0)?.let { v["n"] = it }
            arg(1)?.let { v["big"] = x.name(it) }
            arg(2)?.let { v["bfrom"] = x.club(it) }
            arg(3)?.let { v["bto"] = x.club(it) }
            moneyOf(arg(4)?.takeIf { it != "0" })?.let { v["fee"] = it }
            val big = if (v["fee"] != null) "[[ 가장 큰 거래는 이적료 {fee}에 {bfrom}에서 {bto|으로} 옮긴 {big|이에요}.]]" else "[[ 가장 큰 화제는 {bfrom}에서 {bto|으로} 옮긴 {big|이에요}.]]"
            listOf(
                T("겨울 이적시장 마감: {n}건", "겨울 이적시장이 이적 {n}건과 함께 문을 닫았어요.$big 후반기에는 새 얼굴들이 순위 싸움에 뛰어들어요."),
            ) to 3
        }
        "deadline_day" -> {
            // [≤ 6 movers] (late signings).
            arg(0)?.let { v["n"] = it }
            v["seeds"]?.let { names -> v["who"] = if (everyone(e, arg(0))) names else "$names 등" }
            listOf(
                if (v["who"] != null) {
                    T("이적시장 마감일, 막판 영입 {n}건", "마감을 앞두고 클럽들이 서둘러 선수를 데려왔어요. {who|이} 마지막 날 새 유니폼을 입었어요.")
                } else {
                    T("이적시장 마감일, 막판 영입 {n}건", "마감을 앞두고 클럽들이 서둘러 선수를 데려왔어요. 마지막 날에만 {n}건이 성사됐어요.")
                },
            ) to 2
        }
        "loan_digest" -> {
            // [≤ 6 loaned] (loans).
            arg(0)?.let { v["n"] = it }
            v["seeds"]?.let { names -> v["who"] = if (everyone(e, arg(0))) names else "$names 등" }
            listOf(
                T("임대 이적 {n}건", "선수 {n}명이 경기 경험을 쌓으러 임대를 떠나요.[[ {who|은} 다음 시즌 다른 클럽에서 뛰어요.]] 임대 선수는 대부분 시즌이 끝나면 원래 클럽으로 돌아와요."),
            ) to 1
        }

        // ---- academies and club history ---------------------------------------------------------
        "wonderkid" -> {
            // [ai, club] (potential, age).
            arg(0)?.let { v["pot"] = it }
            arg(1)?.takeIf { it != "0" }?.let { v["age"] = it }
            listOf(
                T("원더키드 등장: {a}", "{b} 유스 출신[[ {age}세]] {a|이} 데뷔를 앞두고 있어요. 잠재력 {pot}의 대형 유망주예요."),
            ) to 3
        }
        "academy_graduates" -> {
            // [≤ 3 best graduates, then their ≤ 3 clubs] (graduates, how many of the keys are graduates).
            arg(0)?.let { v["n"] = it }
            val named = (arg(1)?.toIntOrNull() ?: 0).coerceIn(0, x.names.size)
            x.names.take(named).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { v["best"] = it.joinToString("·") }
            x.names.drop(named).filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let { v["clubs"] = it.joinToString("·") }
            listOf(
                if (v["best"] != null || v["clubs"] != null) {
                    T("유스 졸업생 {n}명 데뷔", "클럽 유스에서 키운 선수 {n}명이 다음 시즌 데뷔해요.[[ 눈여겨볼 선수는 {best|이에요}.]][[ {clubs} 유스가 특히 돋보였어요.]]")
                } else {
                    T("유스 졸업생 {n}명 데뷔", "클럽 유스에서 키운 선수 {n}명이 다음 시즌 데뷔해요. 아카데미의 수준이 클럽의 미래를 바꿔요.")
                },
            ) to 1
        }
        "one_club_man" -> {
            // [ai, club] (seasons, the number retired or "-").
            arg(0)?.let { v["seasons"] = it }
            arg(1)?.let { v["no"] = it }
            listOf(
                T("원클럽맨 {a}, 은퇴", "{a|은} {seasons}시즌 동안 {b}에서만 뛰었어요.[[ 클럽은 등번호 {no}번을 영구결번으로 남겨요.]] {b} 팬들이 기립 박수로 작별 인사를 건넸어요."),
            ) to 3
        }
        "club_legend" -> listOf(
            // [ai, club].
            T("{a}, {b}의 전설로", "{b|이} {a|을} 클럽 명예의 전당에 올렸어요. {a|이} {b}에서 남긴 발자취는 오래 기억될 거예요."),
        ) to 2
        "captain" -> listOf(
            // [ai, club].
            T("{b}의 새 주장은 {a}", "{b|이} {a}에게 주장 완장을 맡겼어요. 다음 시즌부터 {a|이} 선수단을 이끌어요."),
        ) to 1
        "derby_result" -> {
            // [winner, loser, winner's club, loser's club] (score winner first, the winner's club's all-time wins, draws, losses, the rivalry's name).
            arg(0)?.let { v["score"] = it }
            arg(1)?.let { v["w"] = it }
            arg(2)?.let { v["dr"] = it }
            arg(3)?.let { v["l"] = it }
            arg(4)?.let { v["name"] = it }
            listOf(
                T("{name}: {a}, {b} 꺾어요", "{name}에서 {c}의 {a|이} {d}의 {b|을} 이겼어요[[({score})]]. 두 클럽의 통산 전적은 {c} 기준 {w}승 {dr}무 {l}패예요."),
            ) to 2
        }
        "rivalry_born" -> {
            // [club, club] (why: P two seasons' title race, F a 클럽 대항전 final, X a record fee, K a captain's move).
            v["why"] = when (arg(0)) {
                "P" -> "두 시즌 우승을 다툰"
                "F" -> "클럽 대항전 결승에서 맞붙은"
                "X" -> "기록적인 이적료가 오간"
                "K" -> "주장이 옮겨 간"
                else -> ""
            }
            listOf(
                T("새 라이벌의 탄생: {a} vs {b}", "[[{why} ]]{a|과} {b|이} 리그의 새 라이벌로 떠올랐어요. 다음 시즌부터 두 클럽의 맞대결은 라이벌전이 돼요."),
            ) to 2
        }
        else -> mediaTemplates(e, v, arg, x)
    }

    /**
     * The meta's, the media's and the world events' kinds (design ENG 11.2): eras, the counter
     * style and imitation; the power ranking, the predictions and their scorecard, the press votes,
     * the World Cup's bids, vote and draw ceremony; the super league, the investor, the crisis, the
     * merger and the boom.
     */
    private fun mediaTemplates(e: LeagueEvent, v: MutableMap<String, String>, arg: (Int) -> String?, x: Ctx): Pair<List<T>, Int> = when (e.kind) {
        "era_begins" -> {
            // [] (family code or "-" for 춘추전국, its big titles, all big titles of the last three seasons, the era's name).
            val family = arg(0)?.singleOrNull()?.let { StyleFamily.ofCode(it) }
            family?.let { v["family"] = it.label }
            arg(1)?.let { v["titles"] = it }
            arg(2)?.let { v["total"] = it }
            arg(3)?.let {
                v["era"] = it
                v["eraq"] = "‘$it’"
            }
            listOf(
                if (family != null) {
                    T("{eraq|이} 열렸어요", "최근 세 시즌 큰 대회 우승 {total}번 가운데 {titles}번을 {family|이} 가져갔어요. 바야흐로 {era|이에요}.")
                } else {
                    T("{eraq|이} 열렸어요", "최근 세 시즌 동안 어느 스타일도 큰 대회를 휩쓸지 못했어요. 바야흐로 {era|이에요}.")
                },
            ) to 4
        }
        "era_ends" -> {
            // [] (family code or "-", the era's name, its seasons).
            arg(1)?.let { v["era"] = it }
            arg(2)?.let { v["seasons"] = it }
            listOf(
                T("{era|이} 저물어요", "[[{seasons}시즌 이어진 ]]{era|이} 막을 내렸어요. 다음 시대는 누가 열지 지켜봐요."),
            ) to 3
        }
        "counter_style" -> {
            // [] (the counter's family code, the dominant's, its points a match above expectation).
            arg(0)?.singleOrNull()?.let { StyleFamily.ofCode(it) }?.let { v["counter"] = it.label }
            arg(1)?.singleOrNull()?.let { StyleFamily.ofCode(it) }?.let { v["dominant"] = it.label }
            arg(2)?.let { v["x"] = it }
            listOf(
                T("{counter}의 반격", "{dominant|이} 판치는 사이 {counter|이} {dominant|을} 상대로 기대보다 경기당 승점을 {x}점 더 땄어요. 신인들 사이에서 {counter|이} 유행이에요."),
            ) to 3
        }
        "imitation" -> {
            // [≤ 3 imitators of the family most taken up] (their count, the family's code, every imitator's count).
            arg(0)?.let { v["n"] = it }
            arg(1)?.singleOrNull()?.let { StyleFamily.ofCode(it) }?.let { v["family"] = it.label }
            arg(2)?.takeIf { (it.toIntOrNull() ?: 0) > (arg(0)?.toIntOrNull() ?: 0) }?.let { v["all"] = it }
            v["seeds"]?.let { names -> v["who"] = if (everyone(e, arg(0))) names else "$names 등" }
            listOf(
                if (v["who"] != null || v["all"] != null) {
                    T("따라 하기 열풍: {family}", "젊은 선수 {n}명이 {family} 스타일을 새로 몸에 익혔어요.[[ {who|이} 대표적이에요.]][[ 다른 스타일까지 합치면 모두 {all}명이 누군가를 따라 했어요.]]")
                } else {
                    T("따라 하기 열풍: {family}", "젊은 선수 {n}명이 {family} 스타일을 새로 몸에 익혔어요. 잘나가는 스타일은 금세 퍼져요.")
                },
            ) to 1
        }
        "power_ranking" -> {
            // [the top 10] (week, the biggest climber or "-", its places up, the best newcomer or "-", "1" when #1 changed).
            (arg(0) ?: e.week.takeIf { it > 0 }?.toString())?.let { v["week"] = it }
            arg(1)?.let { v["climber"] = x.name(it) }
            arg(2)?.let { v["up"] = it }
            arg(3)?.let { v["newcomer"] = x.name(it) }
            val changed = arg(4) == "1"
            val first = if (changed) "새 1위는 {a|이에요}." else "이번 주 파워 랭킹 1위는 {a|이에요}."
            val climb = if (v["climber"] != null && v["up"] != null) " {climber|은} {up}계단 뛰어올랐어요." else ""
            val fresh = if (v["newcomer"] != null) " 새로 이름을 올린 선수는 {newcomer|이에요}." else ""
            val rest = when {
                climb.isNotEmpty() || fresh.isNotEmpty() -> climb + fresh
                v["b"] != null && v["c"] != null -> " 2위 {b|과} 3위 {c|이} 뒤를 이어요."
                else -> " 다음 주 순위를 지켜봐요."
            }
            listOf(T("{week}주차 파워 랭킹: 1위 {a}", first + rest)) to if (changed) 3 else 2
        }
        "predictions" -> {
            if (arg(0) == "*") {
                // The digest: [the other leagues' favourites] ("*", then "league:title %" each, in the same order).
                val list = e.keys.zip(e.args.drop(1)).mapIndexedNotNull { i, (_, p) ->
                    val n = x.names.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    val id = p.substringBefore(':')
                    n to "$n(${LeagueIds.short(id)} ${p.substringAfter(':', "")}%)"
                }.take(PREDICTIONS_SHOWN)
                if (list.isNotEmpty()) v["list"] = list.joinToString(", ") { it.second } + Josa.after(list.last().first, "이")
                v["more"] = if (e.keys.size > list.size) "그 밖에도 리그마다 우승 후보가 정해졌어요." else "시즌이 끝나면 예측 성적표로 다시 따져 봐요."
                listOf(T("다른 리그 우승 후보", "{list} 각 리그의 우승 후보 1순위로 꼽혔어요. {more}")) to 2
            } else {
                // [favourite, dark horse or "-", relegation picks…] (league, the favourite's title chance in %).
                arg(0)?.let { v["lg"] = LeagueIds.label(it) }
                arg(1)?.let { v["p"] = it }
                x.names.drop(2).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { v["rel"] = it.joinToString("·") }
                val more = when {
                    v["b"] != null || v["rel"] != null -> "[[ 다크호스로는 {b|이} 꼽혀요.]][[ 강등 후보로는 {rel|이} 거론돼요.]]"
                    else -> " 개막 전 예상이 맞을지 지켜봐요."
                }
                listOf(
                    T("시즌 {season} 전망: {lg} 우승 후보 {a}", "기자단이 {a|을} 시즌 {season} {lg} 우승 후보 1순위로 꼽았어요[[(우승 확률 {p}%)]].$more"),
                ) to 3
            }
        }
        "prediction_scorecard" -> {
            // [overachiever or "-", flop or "-"] (favourites who won, leagues, the overachiever's predicted and final
            // place, the flop's, champions among the favourites, relegation hits, relegation picks, ρ × 100).
            val hits = arg(0)?.toIntOrNull() ?: 0
            val leagues = arg(1)?.toIntOrNull() ?: 0
            v["n"] = leagues.toString()
            v["hit"] = hits.toString()
            arg(2)?.let { v["op"] = it }
            arg(3)?.let { v["of"] = it }
            arg(4)?.let { v["fp"] = it }
            arg(5)?.let { v["ff"] = it }
            val first = when {
                leagues <= 1 && hits > 0 -> "시즌 전 우승 후보 1순위가 실제로 우승했어요."
                leagues <= 1 -> "시즌 전 우승 후보 1순위는 우승하지 못했어요."
                hits > 0 -> "시즌 전 우승 후보 1순위 {n}명 가운데 {hit}명이 실제로 우승했어요."
                else -> "시즌 전 우승 후보 1순위 {n}명 가운데 실제로 우승한 선수는 없어요."
            }
            val over = v["a"] != null && v["op"] != null && v["of"] != null
            val flop = v["b"] != null && v["fp"] != null && v["ff"] != null
            val rest = buildString {
                if (over) append(" 가장 크게 예상을 뒤엎은 선수는 예상 {op}위에서 {of}위에 오른 {a|이에요}.")
                if (flop) append(" 가장 아쉬운 선수는 예상 {fp}위에서 {ff}위에 그친 {b|이에요}.")
                if (!over && !flop) {
                    val picks = arg(8)?.toIntOrNull() ?: 0
                    append(if (picks > 0) " 강등 예측은 ${picks}명 가운데 ${arg(7) ?: "0"}명이 맞았어요." else " 예측과 결과는 기자단 페이지에서 비교해 볼 수 있어요.")
                }
            }
            listOf(T("시즌 {season} 기자단 예측 성적표", first + rest)) to 3
        }
        "press_award" -> {
            // [the golden dice's winner, 2nd, 3rd] (points, first-place votes, jurors, the 2nd's points, the 3rd's).
            arg(0)?.let { v["pts"] = it }
            arg(1)?.let { v["firsts"] = it }
            arg(2)?.let { v["voters"] = it }
            arg(3)?.let { v["pts2"] = it }
            arg(4)?.let { v["pts3"] = it }
            val rest = if (v["b"] != null && v["pts2"] != null) "[[ 2위는 {pts2}점을 받은 {b|이에요}.]][[ 3위는 {pts3}점의 {c|이에요}.]]" else " 기자단 투표로 뽑는 시즌 최고의 영예예요."
            listOf(
                T("시즌 {season} 골든 다이스는 {a}", "기자단[[ {voters}명]]의 투표에서 {a|이} {pts}점[[(1위 표 {firsts}표)]]으로 골든 다이스를 차지했어요.$rest"),
            ) to 5
        }
        "press_awards" -> {
            // [올해의 감독's club or "-", 올해의 영입's winner or "-"] (the coach's id or "-").
            arg(0)?.let { v["coach"] = x.coach(it) }
            val coach = v["coach"] != null
            listOf(
                when {
                    coach && v["b"] != null -> T("기자단 선정 올해의 감독·영입", "기자단이 뽑은 올해의 감독은 [[{a}의 ]]{coach} 감독이에요. 올해의 영입으로는 {b|이} 뽑혔어요.")
                    coach -> T("올해의 감독: {coach}", "기자단이 뽑은 올해의 감독은 [[{a}의 ]]{coach} 감독이에요. 시즌 내내 팀을 잘 이끈 공을 인정받았어요.")
                    else -> T("올해의 영입: {b}", "기자단이 올해의 영입으로 {b|을} 뽑았어요. 새 팀에 오자마자 제 몫을 해냈어요.")
                },
            ) to 3
        }
        "wc_bids" -> {
            // [bidding nations] (the World Cup's season).
            v["wc"] = arg(0) ?: (e.season + 1).toString()
            listOf(
                T("시즌 {wc} 요트 월드컵 유치전", "{seeds|이} 개최지 유치에 나섰어요. 투표는 이번 시즌이 끝나면 열려요."),
            ) to 2
        }
        "wc_host_vote" -> {
            // [host, runner-up or "-"] (rounds, the host's votes in the last round, the runner-up's or "-"); the vote is for the next season's World Cup.
            v["wc"] = (e.season + 1).toString()
            val rounds = arg(0)?.toIntOrNull() ?: 0
            arg(0)?.let { v["rounds"] = it }
            arg(1)?.let { v["votes"] = it }
            arg(2)?.let { v["rv"] = it }
            val first = when {
                rounds == 0 -> "{a|이} 단독 후보로 나서 개최권을 따냈어요."
                rounds == 1 -> "1차 투표에서 {a|이}[[ {votes}표로]] 과반을 얻어 개최권을 따냈어요."
                else -> "{rounds}차 투표 끝에 {a|이}[[ {votes}표로]] 개최권을 따냈어요."
            }
            listOf(
                T("시즌 {wc} 월드컵 개최지는 {a}", "$first[[ {b|은} {rv}표에 그쳤어요.]] 시즌 {wc} 요트 월드컵은 {a}에서 열려요."),
            ) to 4
        }
        "wc_draw_ceremony" -> {
            // [presenter or "-", the group of death's nations] (its letter).
            arg(0)?.let { v["grp"] = it }
            x.names.drop(1).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { v["teams"] = it.joinToString("·") }
            listOf(
                if (v["a"] != null) {
                    T("월드컵 조 추첨식: 죽음의 조는 {grp}조", "{a|이} 시즌 {season} 요트 월드컵 조 추첨자로 나섰어요. {grp}조에 {teams|이} 모이며 죽음의 조가 됐어요.")
                } else {
                    T("월드컵 조 추첨식: 죽음의 조는 {grp}조", "시즌 {season} 요트 월드컵 조 추첨이 끝났어요. {grp}조에 {teams|이} 모이며 죽음의 조가 됐어요.")
                },
            ) to 4
        }

        // ---- world events -----------------------------------------------------------------------
        "superleague_rumour" -> listOf(
            T("빅클럽 슈퍼리그 추진설", "몇몇 빅클럽이 따로 리그를 만들려 한다는 소문이 돌아요. 요트협회는 강하게 반대하고 있어요."),
        ) to 4
        "superleague_announce" -> {
            // [≤ 12 founders] (their count).
            arg(0)?.let { v["n"] = it }
            firstNames(x.names, ARC_CLUBS)?.let { v["who"] = if (x.names.count { it.isNotEmpty() } > ARC_CLUBS) "$it 등" else it }
            listOf(
                T("빅클럽 {n}곳, 슈퍼리그 창설 발표", "{who|이} 새 리그 창설을 발표했어요. 요트협회는 참가 클럽 선수들을 국제 대회에서 빼겠다고 경고했어요."),
            ) to 5
        }
        "superleague_showdown" -> {
            // [the clubs that stayed] (their count, the founders' count).
            val stayers = arg(0)?.toIntOrNull() ?: 0
            v["n"] = stayers.toString()
            arg(1)?.let { v["f"] = it }
            firstNames(x.names, ARC_CLUBS)?.let { v["who"] = if (x.names.count { it.isNotEmpty() } > ARC_CLUBS) "$it 등" else it }
            listOf(
                if (stayers > 0) {
                    T("슈퍼리그 사태, 운명의 결정", "팬들의 반발 속에[[ 창설을 선언한 {f}곳 가운데]] {n}곳이 끝까지 남았어요.[[ {who|이} 마지막까지 버텼어요.]] 결말은 다음 시즌 개막에 맞춰 나와요.")
                } else {
                    T("슈퍼리그 사태, 운명의 결정", "팬들의 거센 반발에 창설을 선언한 클럽들이 모두 물러섰어요. 결말은 다음 시즌 개막에 맞춰 나와요.")
                },
            ) to 4
        }
        "superleague_outcome" -> {
            // [the clubs that stayed] (C collapse / P compromise, the 챔피언스 리그 직행's change "7>8" or "-", the prize factor × 100).
            val reform = arg(1)?.split('>')?.takeIf { it.size == 2 }
            reform?.let {
                v["rf"] = it[0]
                v["rt"] = it[1]
            }
            arg(2)?.toIntOrNull()?.takeIf { it > 100 }?.let { v["up"] = (it - 100).toString() }
            firstNames(x.names, ARC_CLUBS)?.let { v["who"] = if (x.names.count { it.isNotEmpty() } > ARC_CLUBS) "$it 등" else it }
            val fined = if (v["who"] != null) "끝까지 버틴 {who|은} 벌금을 내요." else "끝까지 버틴 클럽들은 벌금을 내요."
            if (arg(0) == "P") {
                val change = when {
                    reform != null && v["up"] != null -> "챔피언스 리그 직행 리그가 {rf}개에서 {rt}개로 늘고, 상금도 {up}% 올라요."
                    reform != null -> "챔피언스 리그 직행 리그가 {rf}개에서 {rt}개로 늘어요."
                    v["up"] != null -> "챔피언스 리그 상금이 {up}% 올라요."
                    else -> "챔피언스 리그는 지금처럼 열려요."
                }
                listOf(
                    T(if (reform != null) "타협: 챔피언스 리그 개혁" else "슈퍼리그 사태, 타협으로 일단락", "요트협회와 빅클럽이 합의했어요. $change $fined"),
                ) to 5
            } else {
                listOf(
                    T("슈퍼리그 무산", "빅클럽들이 결국 손을 들었어요. $fined"),
                ) to 5
            }
        }
        "investor_rumour" -> listOf(
            // [club].
            T("{a} 매각설", "해외 투자자가 {a} 인수를 노린다는 소식이에요. 구단은 아직 말을 아끼고 있어요."),
        ) to 3
        "investor_talks" -> {
            // [club] (the investor's name, its owner type's code).
            arg(0)?.let { v["owner"] = it }
            ownerWord(ownerType(arg(1)))?.let { v["type"] = it }
            listOf(
                T("{a} 인수 협상", "[[{type} ]]{owner|이} {a} 인수 협상에 들어갔어요. 결론은 시즌이 끝나면 나와요."),
            ) to 3
        }
        "investor_takeover" -> {
            // [club] (the investor's name, its owner type's code).
            arg(0)?.let { v["owner"] = it }
            ownerWord(ownerType(arg(1)))?.let { v["type"] = it }
            listOf(
                T("큰손 등장: {owner}, {a} 인수", "[[{type} ]]{owner|이} {a|을} 사들였어요. 앞으로 세 시즌 동안 막대한 투자가 이어질 거예요."),
            ) to 4
        }
        "investor_verdict" -> {
            // [club] (S success, E the investor leaves, F a failure it stays through; its name, its type's code).
            arg(1)?.let { v["owner"] = it }
            listOf(
                when (arg(0)) {
                    "S" -> T("투자의 결실: {a}", "{owner}의 투자를 받은 {a|이} 마침내 정상에 올랐어요. 세 시즌 동안 쏟아부은 돈이 결실을 맺었어요.")
                    "E" -> T("투자 실패: {owner}, {a}에서 철수", "기대한 성과를 내지 못하자 {owner|이} {a}에서 손을 떼요. {a|은} 새 주인과 다시 시작해요.")
                    else -> T("투자 실패: {a}", "{owner|이} 돈을 쏟아부었지만 {a|은} 우승하지 못했어요. 투자자는 남았지만 분위기는 무거워요.")
                },
            ) to 3
        }
        "crisis_begins" -> {
            // [] (its scope: a confederation's name or WORLD).
            v["scope"] = scopeLabel(arg(0))
            listOf(
                T("{scope}에 금융 위기", "{scope} 경제가 얼어붙었어요. 스폰서 계약이 줄고 중계권 수입도 떨어질 전망이에요. 빚 많은 클럽이 걱정이에요."),
            ) to 5
        }
        "crisis_ends" -> {
            // [] (seasons, clubs that went into administration, its scope).
            arg(0)?.let { v["seasons"] = it }
            val n = arg(1)?.toIntOrNull() ?: 0
            v["n"] = n.toString()
            v["scope"] = scopeLabel(arg(2))
            listOf(
                if (n > 0) {
                    T("{scope} 금융 위기 끝나요", "[[{seasons}시즌 이어진 ]]위기가 지나갔어요. 그동안 클럽 {n}곳이 법정관리에 들어갔어요.")
                } else {
                    T("{scope} 금융 위기 끝나요", "[[{seasons}시즌 이어진 ]]위기가 지나갔어요. 다행히 그동안 법정관리에 들어간 클럽은 없었어요.")
                },
            ) to 3
        }
        "merger_proposed", "league_merged", "league_split", "merger_failed" -> {
            // [] (nation A, nation B); a failed vote's (…, A's clubs for, A's clubs, B's for, B's).
            arg(0)?.let { v["na"] = nationName(it) }
            arg(1)?.let { v["nb"] = nationName(it) }
            arg(2)?.let { v["ya"] = it }
            arg(3)?.let { v["ca"] = it }
            arg(4)?.let { v["yb"] = it }
            arg(5)?.let { v["cb"] = it }
            when (e.kind) {
                "merger_proposed" -> listOf(
                    T("{na}·{nb} 리그 통합 논의", "리그 랭킹이 낮아 고민하던 {na|과} {nb|이} 리그를 합치는 방안을 논의해요. 두 나라 클럽들이 시즌이 끝나면 투표로 정해요."),
                ) to 4
                "league_merged" -> listOf(
                    T("{na}·{nb} 통합 리그 출범", "{na|과} {nb}의 클럽들이 투표로 리그 통합을 결정했어요. 다음 시즌부터 두 나라 선수들이 한 리그에서 겨뤄요."),
                ) to 4
                "league_split" -> listOf(
                    T("{na}·{nb} 리그, 다시 따로", "통합 리그에서 함께 뛰던 {na|과} {nb|이} 다시 각자의 리그로 돌아가요. 선수가 늘어 따로 리그를 꾸릴 수 있게 됐어요."),
                ) to 4
                else -> listOf(
                    T("{na}·{nb} 리그 통합 무산", "투표에서 {na} 클럽 {ca}곳 가운데 {ya}곳, {nb} 클럽 {cb}곳 가운데 {yb}곳이 찬성했어요. 두 나라 모두 과반이 찬성해야 해서 통합은 없던 일이 됐어요."),
                    T("{na}·{nb} 리그 통합 무산", "{na|과} {nb}의 리그 통합이 클럽 투표에서 부결됐어요. 두 나라 모두 과반이 찬성해야 해서 통합은 없던 일이 됐어요."),
                ).let { if (v["ca"] != null && v["cb"] != null && v["ya"] != null && v["yb"] != null) it.take(1) else it.drop(1) } to 3
            }
        }
        "boom_begins" -> {
            // [] (nation, its members).
            arg(0)?.let {
                v["nat"] = nationName(it)
                v["natLg"] = LeagueIds.label(it)
            }
            listOf(
                T("{nat}에 요트 붐", "{nat}에서 요트 인기가 치솟고 있어요. 앞으로 몇 시즌 동안 신인이 크게 늘어나요. 선수가 충분히 모이면 {natLg|이} 새로 생겨요."),
            ) to 4
        }
        "boom_ends" -> {
            // [] (nation, N its own league / L the boom faded).
            arg(0)?.let {
                v["nat"] = nationName(it)
                v["natLg"] = LeagueIds.label(it)
            }
            listOf(
                if (arg(1) == "N") {
                    T("새 리그 창설: {natLg}", "요트 붐 덕분에 {nat|이} 연합 리그를 떠나 자기 리그를 열어요. 다음 시즌부터 {nat} 선수들은 {natLg}에서 뛰어요.")
                } else {
                    T("{nat}의 요트 붐, 식었어요", "{nat}의 요트 붐이 잦아들었어요. 신인은 늘었지만 리그를 따로 열 만큼 모이지는 않았어요.")
                },
            ) to 3
        }
        else -> emptyList<T>() to 1
    }

    /** Club and owner names an arc's article lists before "등". */
    private const val ARC_CLUBS = 4

    /** Leagues the predictions digest names. */
    private const val PREDICTIONS_SHOWN = 4

    /** The first [n] non-empty [names] joined by "·"; null without any. */
    private fun firstNames(names: List<String>, n: Int): String? = names.filter { it.isNotEmpty() }.take(n).takeIf { it.isNotEmpty() }?.joinToString("·")

    /** Money units [s] as [Money.text] ("12.3억"); null for none or for what is no amount. */
    private fun moneyOf(s: String?): String? = s?.toIntOrNull()?.let { Money.text(it) }

    /** An owner type by its code, null when unknown. */
    private fun ownerType(code: String?): OwnerType? = code?.singleOrNull()?.let { OwnerType.of(it) }

    /** The word an article puts before an owner's name ("재벌 김민준 회장", "투자 펀드 벨로렌 파트너스"); none for a fans' club. */
    private fun ownerWord(t: OwnerType?): String? = when (t) {
        OwnerType.TYCOON -> "재벌"
        OwnerType.LOCAL -> "지역 기업"
        OwnerType.FUND -> "투자 펀드"
        OwnerType.STATE -> "국부 펀드"
        OwnerType.FANS, null -> null
    }

    /** A nation's Korean name ("캐나다", "일본") from its code; the code when unknown. */
    private fun nationName(code: String): String = Nations.of(code)?.short ?: code

    /** A world event's scope: "세계" for [WorldEvents.WORLD] (or none), else the confederation's name ("아시아"). */
    private fun scopeLabel(scope: String?): String = Confed.entries.firstOrNull { it.name == scope }?.label ?: "세계"

    /** The first sentence of [text] ("…해요. …해요." → "…해요."). */
    private fun firstSentence(text: String): String = text.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." }

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

    /**
     * A winner-first score ([TieScore.text], "2-0", "2.5-1.5", "합계 450-430") as `score`, and as
     * `lscore` the loser's side first, for the sentences whose subject lost ("0-2로 졌어요").
     */
    private fun scores(v: MutableMap<String, String>, score: String) {
        v["score"] = score
        v["lscore"] = loserFirst(score)
    }

    /** [score] with its two sides swapped ("2-0" → "0-2", "합계 450-430" → "합계 430-450"); as it is when it is no "x-y". */
    internal fun loserFirst(score: String): String {
        val pre = if (score.startsWith("합계 ")) "합계 " else ""
        val p = score.removePrefix(pre).split('-')
        return if (p.size == 2 && p.all { it.isNotEmpty() }) "$pre${p[1]}-${p[0]}" else score
    }

    /** Whole numbers with thousands separators, others as they are. */
    private fun number(s: String): String = s.toLongOrNull()?.let { String.format(Locale.ROOT, "%,d", it) } ?: s

    /**
     * A record's value [v] with its unit ([RecordKind.withUnit]), without the noun its label
     * already ends on or names: "통산 최다 요트" reads "122개", not "요트 122개", and "한 시즌 최고
     * 평균 점수" "231.4점"; a unit the label does not name stays ("최대 이변" → "레이팅 차 514").
     */
    fun recordValue(kind: RecordKind, v: Double): String {
        val unit = kind.withUnit(v)
        val words = unit.split(' ')
        val at = words.indexOfFirst { w -> w.firstOrNull()?.let { it.isDigit() || it == '+' || it == '-' } == true }
        if (at <= 0) return unit
        val label = kind.label.split(' ', '(', ')').filter { it.isNotEmpty() }.toSet()
        return if (words.take(at).all { it in label }) words.drop(at).joinToString(" ") else unit
    }

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

    /**
     * Adds [a] as the newest (an article equal to one of the last few is not added twice); its lane
     * drops its oldest beyond its size. A column's article ([COLUMNS], design ENG 5.1) takes the
     * place of the one before it: the book keeps only the newest of a column.
     */
    fun add(a: Article) {
        if (list.take(RECENT).any { it == a }) return
        if (a.kind in COLUMNS) dropColumn(a.kind)
        list.addFirst(a)
        if (a.major) majors++
        trim(a.major)
    }

    /** Drops every article of column [kind]. */
    private fun dropColumn(kind: String) {
        val it = list.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (x.kind != kind) continue
            it.remove()
            if (x.major) majors--
        }
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

        /** Columns (연재): kinds of which only the newest article is kept (the weekly power ranking's). */
        val COLUMNS: Set<String> = setOf("power_ranking")

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
                    // Oldest first in the file: each lane keeps its newest (and a column its newest article only).
                    if (a.kind in COLUMNS) book.dropColumn(a.kind)
                    book.list.addFirst(a)
                    if (a.major) book.majors++
                    book.trim(a.major)
                }
            }
            return book
        }
    }
}

/**
 * How much of the club world's news a rollover and a season tell (design ENG 11.2, 3.1): pure
 * functions of the events, so a rollover or a week told again (after a kill) tells the same.
 * Kinds of today's news pass through untouched.
 */
object NewsBudget {
    /** The club world's off-season kinds the rollover budget counts, in the order of ENG 11.2's table (its tie-break). */
    val ROLLOVER: List<String> = listOf(
        "club_accounts", "club_trouble", "forced_sale", "administration", "rescue", "club_folded", "takeover", "owner_exit",
        "sponsor_deal", "sponsor_exit", "stadium_project", "stadium_open", "fan_protest", "fan_party", "club_ranking", "club_world_digest",
        "coach_sacked", "coach_hired", "coach_poached", "coach_retired", "player_to_coach", "coach_digest",
        "deal_collapsed", "record_fee", "rival_move", "release_clause", "free_agent", "contract_renewal", "deadline_day", "loan_digest",
        "wonderkid", "academy_graduates", "one_club_man", "club_legend", "captain", "rivalry_born",
    )

    /** The digests the rest is folded into: always kept (and counted). */
    const val CLUB_DIGEST = "club_world_digest"
    const val COACH_DIGEST = "coach_digest"

    /** Coach moves folded into [COACH_DIGEST] (a digest is made for them when the off-season had none). */
    private val COACH_MOVES = setOf("coach_sacked", "coach_hired", "coach_poached")

    /** The club life's singles whose club joins [CLUB_DIGEST]'s names when they are left out. */
    private val CLUB_LIFE = setOf(
        "club_trouble", "administration", "rescue", "club_folded", "takeover", "owner_exit", "sponsor_deal", "sponsor_exit",
        "stadium_project", "stadium_open", "fan_protest", "fan_party",
    )

    /** The market's singles that tell a deal the off-season's `transfer` news may name too. */
    private val DEALS = setOf("record_fee", "rival_move", "release_clause", "forced_sale")

    /** Clubs a digest names (ENG 11.2's "≤ 6 clubs"). */
    const val DIGEST_CLUBS = 6

    /** The rollover budget: [BASE] + members / [PER] articles of the [ROLLOVER] kinds (10 at 200 players, 12 at 1000, 16 at 3000). */
    const val BASE = 10
    const val PER = 500

    /** In-season caps: one `market_week` a week and [MARKET_WEEKS] a season, one `derby_result` a week and [DERBIES] a season, [WINTER] articles of the winter window's week. */
    const val MARKET_WEEKS = 12
    const val DERBIES = 6
    const val WINTER = 5

    /** The winter checkpoint's kinds ([WinterReport.events]) that its week's cap counts. */
    val WINTER_KINDS: Set<String> = setOf(
        "winter_window", "coach_sacked", "coach_digest", "record_fee", "rival_move", "release_clause", "transfer", "deal_collapsed", "fan_protest",
    )

    /** Whether [week] is a week of the season in play (not [SeasonCalendar.NONE], the preseason or the close). */
    fun inSeasonWeek(week: Int): Boolean = week > SeasonCalendar.PRESEASON && week < SeasonCalendar.CLOSING

    /** The rollover's articles of the club world's kinds for a league of [members] members. */
    fun rolloverCap(members: Int): Int = BASE + maxOf(0, members) / PER

    /**
     * The rollover's [events] within the budget: at most [rolloverCap] of the [ROLLOVER] kinds, the
     * digests first, then the singles by importance ([News.importance]), then the table's order,
     * then their own; the ones left out are folded into the digests: a coach move's into
     * `coach_digest` (one is made from the told moves when there is none), a club life single's
     * club among `club_world_digest`'s names (after its own first [DIGEST_CLUBS] / 2, ≤
     * [DIGEST_CLUBS] in all). Before that, the life step's `takeover` of a club that an
     * `investor_takeover` of the rollover (in [events] or in [told], the close's) tells is dropped,
     * and of two market singles (`record_fee`, `rival_move`, `release_clause`, `forced_sale`) telling
     * one deal (a record fee or a release clause paid by a rival) the better ranked is the one kept.
     * An off-season `transfer` of a deal such a single tells is dropped when that single is kept.
     * Every other event stays as it is, in its place; the kept ones keep their order. Applied again
     * to its result it changes nothing.
     */
    fun rollover(events: List<LeagueEvent>, members: Int, told: List<LeagueEvent> = emptyList()): List<LeagueEvent> {
        val invested = (events + told).filter { it.kind == "investor_takeover" }.mapNotNullTo(HashSet()) { it.keys.firstOrNull() }
        val list = events.filterNot { e -> e.kind == "takeover" && e.keys.firstOrNull() in invested }
        val order = ROLLOVER.withIndex().associate { it.value to it.index }
        val budgeted = list.withIndex().filter { it.value.kind in order }
        val digests = budgeted.filter { it.value.kind == CLUB_DIGEST || it.value.kind == COACH_DIGEST }
        val all = budgeted.filter { it.value.kind != CLUB_DIGEST && it.value.kind != COACH_DIGEST }
        val importance = all.associate { it.index to News.importance(it.value) }
        val byRank = all.sortedWith(
            compareByDescending<IndexedValue<LeagueEvent>> { importance.getValue(it.index) }.thenBy { order.getValue(it.value.kind) }.thenBy { it.index },
        )
        fun deal(e: LeagueEvent): List<String>? = e.keys.takeIf { e.kind in DEALS && it.size >= 3 }?.take(3)
        // A deal two market singles tell (a record fee or a clause paid by a rival): the better ranked tells it.
        val seen = HashSet<List<String>>()
        val twice = byRank.filter { x -> deal(x.value)?.let { !seen.add(it) } == true }.mapTo(HashSet()) { it.index }
        val singles = all.filter { it.index !in twice }
        val ranked = byRank.filter { it.index !in twice }
        val cap = rolloverCap(members)
        var room = maxOf(0, cap - digests.size)
        var kept = ranked.take(room).mapTo(HashSet()) { it.index }
        val hasCoachDigest = digests.any { it.value.kind == COACH_DIGEST }
        val coachMoves = singles.filter { it.value.kind in COACH_MOVES }
        // A coach move left out with no digest to hold it: one is made, and it takes a place.
        val makeDigest = !hasCoachDigest && coachMoves.any { it.index !in kept }
        if (makeDigest && room > 0) {
            room--
            kept = ranked.take(room).mapTo(HashSet()) { it.index }
        }
        // An off-season transfer of a deal a kept market single tells is not told again (a dropped one's is).
        val toldDeals = singles.filter { it.index in kept }.mapNotNullTo(HashSet()) { deal(it.value) }
        fun retold(e: LeagueEvent) = e.kind == "transfer" && !inSeasonWeek(e.week) && e.keys.size >= 3 && e.keys.take(3) in toldDeals
        if (kept.size == singles.size && twice.isEmpty()) return list.filterNot(::retold)
        val dropped = singles.filter { it.index !in kept }
        val lifeClubs = dropped.filter { it.value.kind in CLUB_LIFE }.mapNotNull { it.value.keys.firstOrNull()?.takeIf { k -> k != "-" } }
        val out = ArrayList<LeagueEvent>(list.size)
        val firstCoach = coachMoves.firstOrNull()?.index
        for ((i, e) in list.withIndex()) {
            if (makeDigest && i == firstCoach) out += coachDigest(coachMoves.map { it.value })
            when {
                // Its own first clubs stay first; the clubs left out come next, so they are named even when it names six already.
                e.kind == CLUB_DIGEST && lifeClubs.isNotEmpty() -> {
                    val own = e.keys.filter { it != "-" }
                    out += e.copy(keys = (own.take(DIGEST_CLUBS / 2) + lifeClubs + own.drop(DIGEST_CLUBS / 2)).distinct().take(DIGEST_CLUBS))
                }
                e.kind in order && e.kind != COACH_DIGEST && e.kind != CLUB_DIGEST && i !in kept -> {}
                retold(e) -> {}
                else -> out += e
            }
        }
        return out
    }

    /** A `coach_digest` [≤ 6 clubs] (sacked, hired, poached, clubs) of the off-season's coach moves [moves] (`coach_sacked`, `coach_hired`, `coach_poached`). */
    private fun coachDigest(moves: List<LeagueEvent>): LeagueEvent {
        val first = moves.first()
        val clubs = moves.mapNotNull { it.keys.firstOrNull()?.takeIf { k -> k != "-" } }.distinct()
        val sacked = moves.count { it.kind == "coach_sacked" }
        val poached = moves.count { it.kind == "coach_poached" }
        val hired = moves.count { it.kind == "coach_hired" } + poached
        return LeagueEvent(
            COACH_DIGEST, first.season, clubs.take(DIGEST_CLUBS), listOf(sacked.toString(), hired.toString(), poached.toString(), clubs.size.toString()),
            first.comp, first.week,
        )
    }

    /**
     * The in-season [events] (in the order they happened) within the season's caps: one
     * `market_week` a week and [MARKET_WEEKS] a season, one `derby_result` a week and [DERBIES] a
     * season (and only those [topLeague] passes: the best-ranked leagues'), and at most [WINTER] of
     * the winter checkpoint's kinds ([WINTER_KINDS], club news) in week [winterWeek]. [told] is
     * what the season told already, counted
     * against the caps by season and week ([toldOf] reads them from articles); everything else passes.
     */
    fun inSeason(
        events: List<LeagueEvent>,
        winterWeek: Int,
        told: List<LeagueEvent> = emptyList(),
        topLeague: (LeagueEvent) -> Boolean = { true },
    ): List<LeagueEvent> {
        val market = HashMap<Int, HashSet<Int>>()
        val derbies = HashMap<Int, HashSet<Int>>()
        val derbyCount = HashMap<Int, Int>()
        val winter = HashMap<Int, Int>()
        fun weekOf(e: LeagueEvent): Int = if (e.kind == "market_week" && e.week == SeasonCalendar.NONE) e.args.firstOrNull()?.toIntOrNull() ?: e.week else e.week
        fun winterNews(e: LeagueEvent) = e.week == winterWeek && e.kind in WINTER_KINDS && e.comp == Comp.CLUB
        fun note(e: LeagueEvent) {
            when (e.kind) {
                "market_week" -> market.getOrPut(e.season) { HashSet() } += weekOf(e)
                "derby_result" -> {
                    derbies.getOrPut(e.season) { HashSet() } += e.week
                    derbyCount[e.season] = (derbyCount[e.season] ?: 0) + 1
                }
            }
            if (winterNews(e)) winter[e.season] = (winter[e.season] ?: 0) + 1
        }
        told.forEach(::note)
        val out = ArrayList<LeagueEvent>(events.size)
        for (e in events) {
            val keep = when {
                e.kind == "market_week" -> market[e.season].orEmpty().let { weekOf(e) !in it && it.size < MARKET_WEEKS }
                e.kind == "derby_result" -> topLeague(e) && e.week !in derbies[e.season].orEmpty() && (derbyCount[e.season] ?: 0) < DERBIES
                winterNews(e) -> (winter[e.season] ?: 0) < WINTER
                else -> true
            }
            if (keep) {
                out += e
                note(e)
            }
        }
        return out
    }

    /** Articles told already as the events [inSeason] counts (kind, season, keys, competition and week). */
    fun toldOf(articles: Collection<Article>): List<LeagueEvent> = articles.map { LeagueEvent(it.kind, it.season, it.keys, emptyList(), it.comp, it.week) }
}
