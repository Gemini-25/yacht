package yacht.app.ui

import yacht.app.core.LeagueController
import yacht.engine.league.Awards
import yacht.engine.league.Confed
import yacht.engine.league.ContinentalChampions
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueIds

/** An honour in Korean ("1부 우승", "한국 리그 우승", "아시아 챔피언스 우승", "요트컵 준우승", "신인왕", ...). */
fun honourLabel(kind: String): String {
    nationalLabel(kind)?.let { return it }
    division(kind, "L")?.let { return "${it + 1}부 우승" }
    division(kind, "UP")?.let { return "${it + 1}부 승격" }
    division(kind, "DOWN")?.let { return "${it + 1}부 강등" }
    division(kind, "STAY")?.let { return "${it + 1}부 잔류" }
    return when (kind) {
        "REG" -> "정규리그 1위"
        "L0R" -> "우승 플레이오프 준우승"
        "CUP" -> "요트컵 우승"
        "CUP2" -> "요트컵 준우승"
        "CL" -> "챔피언스 리그 우승"
        "CL2" -> "챔피언스 리그 준우승"
        "SUPER" -> "슈퍼컵 우승"
        "ALLSTAR" -> "올스타전 승리"
        "FAMILY" -> "계열 대항전 우승"
        "FSQ" -> "계열 대표 선발"
        "CLUB" -> "클럽 리그 우승"
        "CLUB2" -> "클럽 리그 준우승"
        "MVP" -> "MVP"
        "ROOKIE" -> "신인왕"
        "MIP" -> "기량발전상"
        "YACHT" -> "최다 요트상"
        "SCORER" -> "득점왕"
        "WC" -> "요트 월드컵 우승"
        "WC2" -> "요트 월드컵 준우승"
        "WC4" -> "요트 월드컵 4강"
        "WCW" -> "요트 월드컵 우승 멤버"
        "WCMVP" -> "월드컵 MVP"
        "WCTOP" -> "월드컵 득점왕"
        "WCH" -> "요트 월드컵 개최"
        "CC" -> "대륙컵 우승"
        "CC2" -> "대륙컵 준우승"
        "CCW" -> "대륙컵 우승 멤버"
        "NSQ" -> "국가대표 선발"
        "POTW" -> "이 주의 선수"
        else -> kind
    }
}

internal fun division(kind: String, prefix: String): Int? =
    if (kind.startsWith(prefix)) kind.removePrefix(prefix).toIntOrNull() else null

/**
 * The honours of national seasons ([HonourKind]): a league's titles ("한국 리그 우승", "한국 2부
 * 우승", "한국 정규리그 1위", "한국 리그 MVP"), a confederation's 대륙 챔피언스 and the 클럽 대항전;
 * null for the others.
 */
private fun nationalLabel(kind: String): String? {
    val base = HonourKind.base(kind)
    HonourKind.leagueOf(kind)?.let { id ->
        return when {
            HonourKind.isDivision(kind) -> if (HonourKind.tier(kind) == 0) "${LeagueIds.label(id)} 우승" else "${LeagueIds.division(id, HonourKind.tier(kind)!!)} 우승"
            base == "REG" -> "${LeagueIds.short(id)} 정규리그 1위"
            base == "L0R" -> "${LeagueIds.label(id)} 우승 플레이오프 준우승"
            base == Awards.LMVP -> "${LeagueIds.label(id)} MVP"
            else -> null
        }
    }
    if (base == "CCL" || base == "CCL2") {
        val title = HonourKind.qualifier(kind)?.let { q -> Confed.entries.firstOrNull { it.name == q } }?.let(ContinentalChampions::title) ?: "대륙 챔피언스"
        return if (base == "CCL") "$title 우승" else "$title 준우승"
    }
    return when (kind) {
        "CLUBCUP" -> "클럽 대항전 우승"
        "CLUBCUP2" -> "클럽 대항전 준우승"
        "CLUBCUPW" -> "클럽 대항전 우승 멤버"
        else -> null
    }
}

/** Honours worth a trophy icon (titles and awards, not moves or call-ups). */
internal fun trophy(kind: String): String? = when {
    kind.startsWith("UP") || kind.startsWith("DOWN") || kind.startsWith("STAY") || kind == "FSQ" || kind == "NSQ" || kind == "WCH" -> null
    // A national season's: a league's title and its lower tiers', the regular season and the league MVP, the 대륙 챔피언스, the 클럽 대항전.
    HonourKind.leagueOf(kind) != null -> when (HonourKind.base(kind)) {
        "L0" -> "👑"
        "REG", Awards.LMVP -> "🏅"
        "L0R" -> "🥈"
        else -> if (HonourKind.isDivision(kind)) "🥇" else null
    }
    HonourKind.base(kind) == "CCL" || kind == "CLUBCUP" || kind == "CLUBCUPW" -> "🏆"
    HonourKind.base(kind) == "CCL2" || kind == "CLUBCUP2" -> "🥈"
    kind == "L0" -> "👑"
    division(kind, "L") != null -> "🥇"
    kind in setOf("CUP", "CL", "SUPER", "FAMILY", "CLUB", "WC", "WCW", "CC", "CCW") -> "🏆"
    kind in setOf("MVP", "ROOKIE", "MIP", "YACHT", "SCORER", "REG", "WCMVP", "WCTOP") -> "🏅"
    kind == "WC4" -> "🥉"
    kind.endsWith("2") || kind == "L0R" -> "🥈"
    else -> null
}

/** A milestone event's args (kind, value) in Korean. */
fun milestoneText(args: List<String>): String {
    val v = args.getOrNull(1)?.toIntOrNull()?.let { "%,d".format(it) } ?: args.getOrNull(1).orEmpty()
    return when (args.firstOrNull()) {
        "games" -> "통산 ${v}판"
        "wins" -> "통산 ${v}승"
        "yachts" -> "통산 요트 ${v}번"
        "300" -> if (v == "1") "첫 300점" else "통산 ${v}번째 300점 경기"
        "title" -> if (v == "1") "첫 우승" else "통산 ${v}번째 우승"
        else -> args.joinToString(" ")
    }
}

/** An honour's winner: a family or all-star side by its label, a club or AI by name. */
fun honourWinner(lg: LeagueController, kind: String, key: String): String = when (kind) {
    "FAMILY", "ALLSTAR" -> yacht.engine.league.Competitions.teamLabel(key)
    else -> lg.name(key)
}
