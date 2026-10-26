package yacht.engine.league

/** How many games make a match. */
enum class MatchFormat(val label: String) {
    /** One game: quick, but the deal decides more. */
    SINGLE("단판"),

    /** Two games on the same dice with the seats swapped (the default). */
    MIRRORED("같은 주사위 두 판"),

    /** Mirrored, plus a decider game when level. */
    BEST_OF_3("3판 2선승"),
}

/**
 * The league's rules, chosen by the player (규칙 설정) or a preset. Structural changes apply
 * from the next season. See docs/LEAGUE_SPEC.md section 7.
 */
data class LeagueConfig(
    val divisions: Int = 4,
    val doubleRoundRobin: Boolean = false,
    val matchFormat: MatchFormat = MatchFormat.MIRRORED,
    /** Teams moving automatically between neighbouring divisions. */
    val autoSwap: Int = 2,
    /** The next place on each side plays a tie for the higher division. */
    val promotionPlayoff: Boolean = true,
    /** The top 4 of the 1st division play semis and a final for the title. */
    val titlePlayoff: Boolean = true,
    /**
     * A league of more than [Pyramid.MAX_LINEAR] groups of [groupSize] plays as a regional
     * pyramid (지역 리그 피라미드) instead of [divisions] divisions; off, always [divisions].
     */
    val pyramid: Boolean = true,
    /** Players per group of the pyramid (조 인원). */
    val groupSize: Int = 50,
    /** Players moving automatically from each lower group of the pyramid. */
    val groupSwap: Int = 3,
    /** Generated and evolved players in the league (the book is kept at this size). */
    val generated: Int = DEFAULT_PLAYERS,
    /** Players age, grow, decline and retire, and new ones come in (나이와 은퇴); off, nobody changes. */
    val aging: Boolean = true,
    /** Percent of the free places that go to pupils of the best players (the rest to rookies). */
    val childShare: Int = 40,
    /** The 103 hand-made AIs play in the league too (off: generated players only; they stay for ranked games and the lab). */
    val baseAis: Boolean = false,
    /** National teams play the 요트 월드컵 and the continental cups (국가대항전). */
    val nationCup: Boolean = true,
    /** Clubs in the club league (0 = no club league). */
    val clubs: Int = 16,
    val squad: Int = 6,
    val cup: Boolean = true,
    val champions: Boolean = true,
    val superCup: Boolean = true,
    val allStar: Boolean = true,
    val familyCup: Boolean = true,
    /** Only AIs with mistakes (the "chaos" preset). */
    val onlyFlawed: Boolean = false,
    /** Only the strongest AIs by rating, 0 = everyone (the "elite" preset). */
    val onlyTop: Int = 0,
) {
    init {
        require(divisions in 1..6) { "divisions $divisions" }
        require(autoSwap in 0..5)
        require(groupSize in MIN_GROUP..MAX_GROUP) { "groupSize $groupSize" }
        require(groupSwap in 1..8)
        require(generated in 0..MAX_PLAYERS)
        require(childShare in 0..100)
        require(clubs == 0 || clubs in 4..24)
        require(squad in 5..8)
        require(onlyTop >= 0)
    }

    fun encode(): String = buildString {
        append("config 1\n")
        append("divisions $divisions\n")
        append("double ${flag(doubleRoundRobin)}\n")
        append("format ${matchFormat.name}\n")
        append("autoSwap $autoSwap\n")
        append("promotionPlayoff ${flag(promotionPlayoff)}\n")
        append("titlePlayoff ${flag(titlePlayoff)}\n")
        append("pyramid ${flag(pyramid)}\n")
        append("groupSize $groupSize\n")
        append("groupSwap $groupSwap\n")
        append("generated $generated\n")
        append("aging ${flag(aging)}\n")
        append("childShare $childShare\n")
        append("baseAis ${flag(baseAis)}\n")
        append("nationCup ${flag(nationCup)}\n")
        append("clubs $clubs\n")
        append("squad $squad\n")
        append("cup ${flag(cup)}\n")
        append("champions ${flag(champions)}\n")
        append("superCup ${flag(superCup)}\n")
        append("allStar ${flag(allStar)}\n")
        append("familyCup ${flag(familyCup)}\n")
        append("onlyFlawed ${flag(onlyFlawed)}\n")
        append("onlyTop $onlyTop\n")
    }

    companion object {
        private fun flag(b: Boolean) = if (b) "1" else "0"

        /** Players in a new league, and the most a league can have. */
        const val DEFAULT_PLAYERS = 1000
        const val MAX_PLAYERS = 3000

        /** Smallest and largest groups of the pyramid. */
        const val MIN_GROUP = 30
        const val MAX_GROUP = 80

        /** Reads a saved config; missing or bad values keep the defaults. */
        fun decode(text: String): LeagueConfig {
            var c = LeagueConfig()
            for (line in text.lines()) {
                val f = line.trim().split(' ')
                if (f.size < 2) continue
                val v = f[1]
                c = runCatching {
                    when (f[0]) {
                        "divisions" -> c.copy(divisions = v.toInt())
                        "double" -> c.copy(doubleRoundRobin = v == "1")
                        "format" -> c.copy(matchFormat = MatchFormat.valueOf(v))
                        "autoSwap" -> c.copy(autoSwap = v.toInt())
                        "promotionPlayoff" -> c.copy(promotionPlayoff = v == "1")
                        "titlePlayoff" -> c.copy(titlePlayoff = v == "1")
                        "pyramid" -> c.copy(pyramid = v == "1")
                        "groupSize" -> c.copy(groupSize = v.toInt())
                        "groupSwap" -> c.copy(groupSwap = v.toInt())
                        "generated" -> c.copy(generated = v.toInt())
                        // Before ages: the number replaced each season, 0 = no evolution.
                        "evolution" -> c.copy(aging = v != "0")
                        "aging" -> c.copy(aging = v == "1")
                        "childShare" -> c.copy(childShare = v.toInt())
                        "baseAis" -> c.copy(baseAis = v == "1")
                        "nationCup" -> c.copy(nationCup = v == "1")
                        "clubs" -> c.copy(clubs = v.toInt())
                        "squad" -> c.copy(squad = v.toInt())
                        "cup" -> c.copy(cup = v == "1")
                        "champions" -> c.copy(champions = v == "1")
                        "superCup" -> c.copy(superCup = v == "1")
                        "allStar" -> c.copy(allStar = v == "1")
                        "familyCup" -> c.copy(familyCup = v == "1")
                        "onlyFlawed" -> c.copy(onlyFlawed = v == "1")
                        "onlyTop" -> c.copy(onlyTop = v.toInt())
                        else -> c
                    }
                }.getOrDefault(c)
            }
            return c
        }
    }
}

/** Ready-made rule sets (리그 프리셋). */
enum class LeaguePreset(val label: String, val desc: String, val config: LeagueConfig) {
    REAL(
        "현실 축구형", "선수 1000명의 지역 리그 피라미드(조당 50명), 같은 주사위 두 판, 승강·우승 플레이오프, 컵·챔스·클럽 리그·국가대항전, 나이와 은퇴 모두",
        LeagueConfig(),
    ),
    FAST(
        "빠른 시즌", "2부까지, 단판 경기, 클럽 리그와 국가대항전 없이 시즌이 빨리 돌아요",
        LeagueConfig(divisions = 2, matchFormat = MatchFormat.SINGLE, clubs = 0, familyCup = false, nationCup = false, titlePlayoff = false, promotionPlayoff = false),
    ),
    CHAOS(
        "대혼돈", "실수하는 선수만 남기고 제자 비율을 높여요: 이변이 쏟아져요",
        LeagueConfig(onlyFlawed = true, childShare = 70),
    ),
    ELITE(
        "강자만", "레이팅 상위 48명만 2개 부에서, 정상급 싸움만",
        // The same players season after season: rookies would never get the rating to be picked.
        LeagueConfig(divisions = 2, onlyTop = 48, clubs = 8, familyCup = false, nationCup = false, aging = false),
    ),
}
