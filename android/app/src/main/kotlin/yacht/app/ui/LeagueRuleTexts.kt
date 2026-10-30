package yacht.app.ui

import yacht.engine.league.KnockoutLength
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat

/**
 * The league's rules in words, shared by the rules dialog, the season tab and the 대회 tab.
 * Built from the engine's own numbers ([KnockoutLength]) so the text follows the rules.
 */

/** How a match is decided under [f] (one sentence or two, 해요체). */
fun matchRuleText(f: MatchFormat): String = when (f) {
    MatchFormat.GAMES ->
        "같은 주사위로 선공·후공 두 판을 둬요. 2-0이나 1승 1무는 승(3점), 1승 1패나 두 판 모두 무승부면 무(1점), 나머지는 패예요. " +
            "2-0 완승이면 보너스 1점을 더 받아요(리그·조별 리그). 합계 점수는 승점이 같을 때 순위만 가려요."
    MatchFormat.MIRRORED ->
        "같은 주사위로 선공·후공 두 판을 둬요. 더 많은 판을 이기면 승(3점), 한 판씩 나눠 가지면 두 판 합계 점수로 가리고, 합계도 같으면 무(1점)예요."
    MatchFormat.BEST_OF_3 -> "같은 주사위로 두 판을 두고, 한 판씩 나눠 가지면 새 주사위로 세 번째 판을 둬요. 먼저 두 판을 이기면 승이에요."
    MatchFormat.SINGLE -> "한 판으로 승부를 가려요."
}

/** The match rule's short name ("판 승점제", "두 판 합계", ...). */
fun matchRuleName(f: MatchFormat): String = when (f) {
    MatchFormat.GAMES -> "판 승점제"
    MatchFormat.MIRRORED -> "두 판 합계"
    MatchFormat.BEST_OF_3 -> "3판 2선승"
    MatchFormat.SINGLE -> "단판"
}

/** How the knockouts of [c] are played: series lengths, the losers' bracket, the final. */
fun knockoutRuleText(c: LeagueConfig): String {
    val long = c.longKnockouts
    val parts = ArrayList<String>()
    if (c.seriesKnockouts) {
        parts += if (long) {
            "토너먼트는 선승제예요. 같은 주사위로 선공을 번갈아 두며 먼저 N판을 이기면 올라가요(무승부 판은 0.5승). " +
                "16강까지는 ${KnockoutLength.wins(16, true)}선승, 8강·4강은 ${KnockoutLength.wins(8, true)}선승, 결승은 ${KnockoutLength.grandWins(true)}선승이에요. " +
                "슈퍼컵과 승강전은 3선승이에요."
        } else {
            "토너먼트는 모두 ${KnockoutLength.wins(2, false)}선승이에요. 같은 주사위로 선공을 번갈아 두며 먼저 두 판을 이기면 올라가요(무승부 판은 0.5승)."
        }
    } else {
        parts += if (long) "토너먼트는 경기 수로 겨뤄요. 16강·8강은 2경기(4판), 4강은 3경기(6판), 결승은 4경기(8판)이고, 한 경기는 같은 주사위로 두 판이에요."
        else "토너먼트는 한 경기(두 판)로 끝나요. 챔스 8강·4강과 우승 플레이오프 4강만 2경기예요."
    }
    if (c.doubleElimination) {
        val wf = KnockoutLength.wins(2, long, grandAfter = true)
        parts += "패자부활: 4명(4팀) 이상 토너먼트는 승자조에서 지면 패자조로 내려가고, 패자조에서 지면 탈락해요. " +
            (if (c.seriesKnockouts) "승자조 결승은 ${wf}선승, 패자조는 ${KnockoutLength.losersWins(false, long)}선승(패자조 결승 ${KnockoutLength.losersWins(true, long)}선승)이에요. " else "") +
            "최종 결승은 승자조 우승과 패자조 우승이 한 시리즈로 겨뤄요(어드밴티지·재경기 없음). 국가대항전·계열 대항전은 한 경기(보드 대결)로 정하되 패자부활은 같아요."
    } else {
        parts += "한 번 지면 탈락해요."
    }
    return parts.joinToString(" ")
}

/** The rules in a few words: "판 승점제 · 선승제 · 패자부활". */
fun rulesSummary(c: LeagueConfig): String = listOf(
    matchRuleName(c.matchFormat),
    if (c.seriesKnockouts) "선승제" else "경기 수제",
    if (c.doubleElimination) "패자부활" else "한 번 지면 탈락",
).joinToString(" · ")

/**
 * A stage label for tight places: "승자조 4강 · 패자조 1R" → "4강 · 패자 1R" (the winners'
 * final "승자 결승", not to be read as the final); the grand final keeps its name.
 */
fun shortStage(label: String): String = label.split(" · ").joinToString(" · ") { part ->
    when {
        part == "승자조 결승" -> "승자 결승"
        part.startsWith("승자조 ") -> part.removePrefix("승자조 ")
        part.startsWith("패자조 ") -> "패자 " + part.removePrefix("패자조 ")
        else -> part
    }
}
