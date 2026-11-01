package yacht.engine.league

import yacht.engine.DiceStream

/** A region of a nation's club cities (design ENG 1b.6): the two biggest clubs of its [cities] play the "{name} 더비". */
data class Region(val name: String, val cities: List<String>)

/**
 * A club's identity in the club world (design ENG 1b.6): its founding year (창단 연도) and nickname
 * (애칭), drawn from the book's seed and the club's id, and the region tables of the regional
 * derbies. The colours are the badge's ([ClubNames.colours]). Set once, when the club world starts
 * ([start]) or the club is founded later ([newClub]); saved on the `cl` line.
 */
object ClubIdentity {
    /** Founding years of the clubs a club world starts with: [FIRST_YEAR] + up to [YEAR_SPAN] − 1, older for a bigger city. */
    const val FIRST_YEAR = 1880
    const val YEAR_SPAN = 130

    /** The calendar year of season 0: a club founded for season n was founded in [SEASON_YEAR] + n ("시즌 12 창단 (2037년)"). */
    const val SEASON_YEAR = 2025

    /** Club-table titles a club needs to be "{city} 명가". */
    const val NAME_TITLES = 3

    private const val SALT = 0x1D3AL

    /**
     * The regions of the regional derbies (지역 더비), nations that have a table: the clubs of a
     * region's cities, two biggest first, are rivals. Other nations may follow; a region of fewer
     * than two clubs has no derby.
     */
    val REGIONS: Map<String, List<Region>> = mapOf(
        "KR" to listOf(
            Region("수도권", listOf("서울", "인천", "수원", "성남", "고양", "용인", "안양")),
            Region("영남", listOf("부산", "대구", "울산", "창원", "포항", "김해")),
            Region("호남", listOf("광주", "전주", "여수", "목포")),
            Region("충청", listOf("대전", "청주", "천안")),
        ),
        "JP" to listOf(
            Region("간토", listOf("도쿄", "요코하마", "가와사키", "사이타마", "지바")),
            Region("간사이", listOf("오사카", "고베", "교토")),
            Region("주부", listOf("나고야", "시즈오카", "하마마쓰", "니가타", "가나자와")),
            Region("규슈", listOf("후쿠오카", "구마모토", "가고시마", "나가사키")),
            Region("주고쿠", listOf("히로시마", "오카야마")),
        ),
    )

    /** The region of [city] in [nation]'s table; null without one. */
    fun region(nation: String, city: String): Region? = REGIONS[nation]?.firstOrNull { city in it.cities }

    /**
     * Club [club]'s founding year in a book seeded [seed]: a club the club world starts with gets
     * `1880 + (h % 130) × (0.6 + 0.4 × i / 10)` (h a hash of the seed and the id, i its city's index
     * among its nation's club cities, at most 10: a bigger city's club is older); a club founded
     * [later] the year of its first season ([SEASON_YEAR] + [Club.founded]).
     */
    fun foundedYear(seed: Long, club: Club, later: Boolean = false): Int {
        if (later) return SEASON_YEAR + maxOf(0, club.founded)
        val h = Math.floorMod(DiceStream.mix(seed, SALT, 1, ClubEconomy.number(club.id).toLong()), YEAR_SPAN.toLong())
        val i = ClubEconomy.cityIndex(club).coerceIn(0, 10)
        return FIRST_YEAR + Math.round(h * (0.6 + 0.4 * i / 10.0)).toInt()
    }

    /**
     * Club [club]'s nickname (애칭) in a book seeded [seed]: one of "{colour} 군단", "{city}의 자존심",
     * "{mascot} 군단", "{city} 명가" (with [NAME_TITLES] club-table titles), "{colour} {mascot}" and
     * "{city}의 {mascot}", by a hash of the id; one that cannot be made (no title, a badge colour
     * without a name) gives the next.
     */
    fun nickname(seed: Long, club: Club): String {
        val colour = ClubNames.colours(club.emblem.copy(colour2 = 0)).ifEmpty { null }
        val city = club.city.ifEmpty { club.name }
        val mascot = club.mascot.ifEmpty { null }
        val first = Math.floorMod(DiceStream.mix(seed, SALT, 2, ClubEconomy.number(club.id).toLong()), NICKNAMES.toLong()).toInt()
        for (i in 0 until NICKNAMES) {
            val text = when ((first + i) % NICKNAMES) {
                0 -> colour?.let { "$it 군단" }
                1 -> "${city}의 자존심"
                2 -> mascot?.let { "$it 군단" }
                3 -> if (club.honours >= NAME_TITLES) "$city 명가" else null
                4 -> if (colour != null && mascot != null) "$colour $mascot" else null
                else -> mascot?.let { "${city}의 $it" }
            }
            if (text != null) return text
        }
        return club.name
    }

    private const val NICKNAMES = 6

    /**
     * The identity of every club of [book] that has none yet (founding year 0): the start of its club
     * world (its migration or founding, design ENG 8.2). Clubs given one.
     */
    fun start(book: ClubBook): Int {
        val w = book.world ?: return 0
        var n = 0
        for (c in book.clubs) {
            val st = w.state(c.id)
            if (st.foundedYear != 0) continue
            st.foundedYear = foundedYear(book.seed, c)
            st.nickname = nickname(book.seed, c)
            n++
        }
        if (n > 0) book.touch()
        return n
    }

    /** The identity of club [id], founded after the club world started (design ENG 8.2): the year of its first season, a nickname. False without a club world or the club. */
    fun newClub(book: ClubBook, id: String): Boolean {
        val w = book.world ?: return false
        val c = book.club(id) ?: return false
        val st = w.state(id)
        st.foundedYear = foundedYear(book.seed, c, later = true)
        st.nickname = nickname(book.seed, c)
        book.touch()
        return true
    }
}
