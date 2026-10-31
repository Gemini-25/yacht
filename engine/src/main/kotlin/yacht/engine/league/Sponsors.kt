package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import kotlin.math.ln

/**
 * Main sponsors (메인 스폰서, design ENG 1b.4): coined company names, so no generated name is a real
 * company, the deal's value from the league, the fans and recent titles, and renewals. A Korean club's
 * sponsor reads "휘담전자" ([KR_PREFIXES] + [KR_SECTORS]), another nation's "벨로렌 텔레콤"
 * ([FOREIGN_PREFIXES] + " " + [FOREIGN_SECTORS]). A [Sponsor.sector] is an index of [SECTORS]: the
 * Korean sectors first, then the foreign ones. The fund owners' names take the foreign prefixes too
 * ([fund], [state]).
 */
object Sponsors {
    val KR_PREFIXES = listOf(
        "휘담", "솔채", "다님", "온담", "해람", "별채", "누빔", "결담", "가윤", "서담",
        "라빈", "하솜", "도담", "미솔", "아람채", "빛나래", "수담", "연솔", "채밀", "담솔",
    )
    val KR_SECTORS = listOf("전자", "통신", "해운", "건설", "제약", "식품", "에너지", "항공", "은행", "보험", "자동차", "게임즈", "화학", "물산", "유통")

    /**
     * Thirty prefixes (the design's twenty and ten more): a world of 377 clubs has up to about 360
     * clubs outside Korea, more than twenty prefixes' 300 names.
     */
    val FOREIGN_PREFIXES = listOf(
        "벨로렌", "카디온", "루메아", "세오라", "알데인", "비렌토", "몬테바", "오렐리", "티에노", "펠라르",
        "카스펜", "누아렐", "에스벨", "로비안", "실바렌", "테오라", "마렌시", "아스틴", "벨키아", "도레안",
        "아르벨", "칼레노", "미레나", "솔데안", "테르넬", "오비렌", "라세온", "페리안", "노르벨", "이스벤",
    )
    val FOREIGN_SECTORS = listOf("텔레콤", "에너지", "항공", "뱅크", "모터스", "푸드", "파마", "로지스틱스", "테크", "게이밍", "인슈어런스", "일렉트릭", "시스템즈", "홀딩스", "리테일")

    /** Every sector, Korean then foreign: [Sponsor.sector] indexes it. */
    val SECTORS: List<String> = KR_SECTORS + FOREIGN_SECTORS

    /** The deal's sector factor: banks 1.10, telecoms 1.05, games 0.95. */
    private val SECTOR_FACTORS = mapOf("은행" to 1.10, "뱅크" to 1.10, "통신" to 1.05, "텔레콤" to 1.05, "게임즈" to 0.95, "게이밍" to 0.95)

    /** Draws of a name before the first free one in list order is taken. */
    const val DRAWS = 20

    /** A walk-out's replacement and a club without a sponsor sign at this share of the formula. */
    const val REPLACEMENT = 0.85

    /** Renewal: p 0.8 when the club's mean place score over the deal is at least −0.2; else the sponsor walks with p 0.5. */
    const val RENEW = 0.8
    const val RENEW_FLOOR = -0.2
    const val WALK_POOR = 0.5

    /** A sponsor walks mid-deal with p 0.15 in a crisis or when the fans' mood is below 20. */
    const val WALK_MID = 0.15
    const val MOOD_FLOOR = 20

    private const val SALT = 0x5B0AL
    private const val NAMING_SALT = 0x5B0BL
    private const val LOCAL_SALT = 0x5B0CL

    /** Whether a club of [nation] takes the Korean forms. */
    fun korean(nation: String?): Boolean = nation == Nations.KR.code

    /** The name of [prefix] (an index of the nation's prefixes) and [sector] (an index of [SECTORS]). */
    fun name(prefix: Int, sector: Int): String =
        if (sector < KR_SECTORS.size) KR_PREFIXES[prefix] + KR_SECTORS[sector] else FOREIGN_PREFIXES[prefix] + " " + SECTORS[sector]

    /** Sector [sector]'s factor on a deal (1.0 for most). */
    fun sectorFactor(sector: Int): Double = SECTORS.getOrNull(sector)?.let { SECTOR_FACTORS[it] } ?: 1.0

    /** Every name of the forms of [korean] in list order, with its sector: the fallback order of [draw]. */
    fun forms(korean: Boolean): List<Pair<String, Int>> =
        if (korean) KR_PREFIXES.indices.flatMap { p -> KR_SECTORS.indices.map { s -> name(p, s) to s } }
        else FOREIGN_PREFIXES.indices.flatMap { p -> FOREIGN_SECTORS.indices.map { s -> name(p, KR_SECTORS.size + s) to KR_SECTORS.size + s } }

    /**
     * A coined name for club number [club] of [nation] and its sector, not in [taken]: up to [DRAWS]
     * draws of `Random(mix(seed, 0x5B0A, club, deal))` from the nation's forms, then the first free
     * name in list order (the nation's forms, then the other forms: a world with more clubs than
     * names of one kind). [deal] tells a club's deals apart (the season it signs).
     */
    fun draw(nation: String?, seed: Long, club: Int, deal: Int, taken: Set<String>, salt: Long = SALT): Pair<String, Int> {
        val kr = korean(nation)
        val rng = Random(DiceStream.mix(seed, salt, club.toLong(), deal.toLong()))
        val prefixes = if (kr) KR_PREFIXES.size else FOREIGN_PREFIXES.size
        val sectors = if (kr) KR_SECTORS.size else FOREIGN_SECTORS.size
        repeat(DRAWS) {
            val p = rng.nextInt(prefixes)
            val s = rng.nextInt(sectors) + if (kr) 0 else KR_SECTORS.size
            val n = name(p, s)
            if (n !in taken) return n to s
        }
        return (forms(kr) + forms(!kr)).firstOrNull { it.first !in taken } ?: forms(kr).first()
    }

    /** A naming-rights sponsor's name for club number [club] (another stream than the main deals'). */
    fun namingName(nation: String?, seed: Long, club: Int, season: Int, taken: Set<String>): String = draw(nation, seed, club, season, taken, NAMING_SALT).first

    /** A 지역 기업 owner's name, a company of the club's nation (not checked against the sponsors: an owner may own one). */
    fun localName(nation: String?, seed: Long, club: Int, season: Int): String = draw(nation, seed, club, season, emptySet(), LOCAL_SALT).first

    /** A 투자 펀드's name: "{prefix} 파트너스". */
    fun fund(prefix: Int): String = FOREIGN_PREFIXES[Math.floorMod(prefix, FOREIGN_PREFIXES.size)] + " 파트너스"

    /** A 국부 펀드's name: "{prefix} 인베스트먼트". */
    fun state(prefix: Int): String = FOREIGN_PREFIXES[Math.floorMod(prefix, FOREIGN_PREFIXES.size)] + " 인베스트먼트"

    /**
     * A deal's value a season: `λ × (1.2 + 0.7 × ln(1 + fans / 5,000)) × (1 + 0.08 × min(5, titles))
     * × sector factor × [factor]` 억 ([titles]: club-table titles in the last 5 seasons; [factor]: a
     * crisis's 0.7 / 0.85, [REPLACEMENT] for a walk-out's replacement).
     */
    fun perSeason(lambda: Double, fans: Int, titles: Int, sector: Int, factor: Double = 1.0): Int =
        Money.of(lambda * (1.2 + 0.7 * ln(1 + maxOf(0, fans) / 5_000.0)) * (1 + 0.08 * titles.coerceIn(0, 5)) * sectorFactor(sector) * factor)

    /** A deal's length: 2 + (hash % 3) seasons. */
    fun length(seed: Long, club: Int, season: Int): Int = 2 + Math.floorMod(DiceStream.mix(seed, SALT, 7L, club.toLong(), season.toLong()), 3L).toInt()

    /** The naming rights of a new stadium a season: `0.5 × λ × capacity / 20,000` 억. */
    fun naming(lambda: Double, capacity: Int): Int = Money.of(0.5 * lambda * capacity / 20_000.0)

    /**
     * Whether a sponsor at the end of its deal renews: with [RENEW] when the club's mean place score
     * over the deal ([perf], `((C + 1) / 2 − place) / C`) is at least [RENEW_FLOOR], else with
     * 1 − [WALK_POOR]; [walkout] (a crisis's factor) multiplies the chance of walking.
     */
    fun renews(perf: Double, walkout: Double, rng: Random): Boolean {
        val walk = ((if (perf >= RENEW_FLOOR) 1 - RENEW else WALK_POOR) * walkout).coerceIn(0.0, 1.0)
        return rng.nextDouble() >= walk
    }
}
