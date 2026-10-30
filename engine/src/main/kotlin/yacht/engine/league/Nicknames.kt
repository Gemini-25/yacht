package yacht.engine.league

/**
 * 별명 (docs/LEAGUE_SPEC.md 1.5): what fans call a player, from its honours first (a world
 * champion, a serial winner), then a striking ability, its age, and else its style. The same
 * player gets the same nickname until its story changes.
 */
object Nicknames {
    /** Nicknames by style: one is picked by the player's key. */
    private val STYLE: Map<String, List<String>> = mapOf(
        "ev" to listOf("정석의 교과서", "걸어 다니는 계산기"),
        "hunter" to listOf("요트 헌터", "{town}의 요트왕"),
        "collector" to listOf("같은 눈 수집가", "주사위 수집가"),
        "fourkind" to listOf("포카드 마스터", "네 개의 마법사"),
        "straight" to listOf("스트레이트 장인", "직선의 예술가"),
        "purist" to listOf("보너스 장인", "윗칸의 지배자"),
        "lower" to listOf("족보 마술사", "아랫칸의 지배자"),
        "noscratch" to listOf("철벽 수비수", "0점은 없다"),
        "scratcher" to listOf("과감한 결단가", "버릴 줄 아는 선수"),
        "bigchoice" to listOf("초이스 저격수"),
        "fullhouse" to listOf("풀하우스 마니아", "풀하우스 사냥꾼"),
        "hoarder" to listOf("초이스 금고지기", "보험왕"),
        "dumper" to listOf("초이스 해결사"),
        "timid" to listOf("신중파", "돌다리도 두들기는 선수"),
        "reroller" to listOf("끝장 승부사", "세 번은 굴려야지"),
        "small" to listOf("알뜰살뜰", "작은 눈의 수호자"),
        "copycat" to listOf("카피캣", "따라쟁이"),
        "contrarian" to listOf("반골 기질", "엇박자의 달인"),
        "ss3" to listOf("엉뚱한 천재"),
        "fh2pair" to listOf("엉뚱한 천재"),
        "high" to listOf("큰 눈 마니아"),
        "topdown" to listOf("순서의 수호자"),
        "bottomup" to listOf("거꾸로 전략가"),
        "allornothing" to listOf("올인 승부사"),
        "runonly" to listOf("직진 본능"),
    )

    /**
     * The nickname of [r] in [season] with its [honours] (all seasons); null for a player with
     * nothing to be known for yet. "리그의 제왕" takes three major league titles: the old single
     * league's, or a national league's when [major] (season, league id) counts it
     * ([HonourBook.majorTitles]).
     */
    fun of(r: PlayerRecord, honours: List<Honour>, season: Int, major: (season: Int, league: String) -> Boolean = { _, _ -> true }): String? {
        val id = r.identity
        val nation = id?.let { Nations.of(it.nation) }
        fun fill(s: String) = s.replace("{town}", r.hometown).replace("{nat}", nation?.short ?: "")
        val count = honours.groupingBy { it.kind }.eachCount()
        val titles = honours.count { HonourBook.isTitle(it.kind) }
        val kingdoms = honours.count { HonourKind.isTopTitle(it.kind) && HonourBook.isMajor(it, major) }
        val age = id?.age(season, r.born) ?: 0
        val a = id?.abilities
        val pick = Profiles.hash(r.key, 50, 2)
        return when {
            (count["WC"] ?: 0) > 0 || (count["WCW"] ?: 0) > 0 -> fill("{nat}의 월드컵 영웅")
            kingdoms >= 3 -> "리그의 제왕"
            titles >= 5 -> "우승 청부사"
            (count["MVP"] ?: 0) >= 2 -> "MVP 단골"
            (count["WCMVP"] ?: 0) > 0 -> "월드컵의 별"
            a != null && a.judgement >= 95 -> "무결점"
            a != null && a.mental >= 95 -> "강철 멘탈"
            a != null && a.sense >= 90 -> "승부사"
            a != null && a.foresight >= 95 -> "선견지명"
            id != null && age <= 21 && id.potential >= 85 -> if (pick == 0) fill("{nat}의 미래") else "괴물 신인"
            age >= 37 -> if (pick == 0) "백전노장" else "살아 있는 전설"
            (count["POTW"] ?: 0) >= 5 -> "이 주의 단골"
            else -> r.spec.play?.let { g -> STYLE[g.primary]?.let { xs -> fill(xs[Profiles.hash(r.key, 51, xs.size)]) } }
        }
    }
}
