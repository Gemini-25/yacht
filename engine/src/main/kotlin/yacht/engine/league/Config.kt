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
    /** Generated players in the league (besides the 103 hand-made AIs). */
    val generated: Int = 100,
    /** Generated/evolved players replaced by children each season (0 = no evolution). */
    val evolution: Int = 8,
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
        require(generated in 0..300)
        require(evolution in 0..30)
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
        append("generated $generated\n")
        append("evolution $evolution\n")
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
                        "generated" -> c.copy(generated = v.toInt())
                        "evolution" -> c.copy(evolution = v.toInt())
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
        "현실 축구형", "4부 리그, 같은 주사위 두 판, 승강 플레이오프와 우승 플레이오프, 컵·챔스·클럽 리그·진화 모두",
        LeagueConfig(),
    ),
    FAST(
        "빠른 시즌", "2부까지, 단판 경기, 클럽 리그와 계열 대항전 없이 시즌이 빨리 돌아요",
        LeagueConfig(divisions = 2, matchFormat = MatchFormat.SINGLE, clubs = 0, familyCup = false, titlePlayoff = false, promotionPlayoff = false),
    ),
    CHAOS(
        "대혼돈", "실수하는 AI만 남기고 진화를 세게: 이변이 쏟아져요",
        LeagueConfig(onlyFlawed = true, evolution = 20, generated = 150),
    ),
    ELITE(
        "강자만", "레이팅 상위 48개 AI만 2부로, 생성 선수 없이 정상급 싸움만",
        LeagueConfig(divisions = 2, onlyTop = 48, generated = 0, evolution = 0, clubs = 8, familyCup = false),
    ),
}
