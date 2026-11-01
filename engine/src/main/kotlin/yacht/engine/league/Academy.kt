package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

/** How a member without a club found one in the academy and draft step ([Academy.draft]). [code] names it for the moves the caller logs. */
enum class Intake(val code: Char, val label: String) {
    /** An evolved rookie at its mentor's club (유스 입단, as before). */
    MENTOR('Y', "유스 입단"),

    /** A rookie at its hometown's club, whose academy is good enough. */
    HOMETOWN('H', "고향 클럽 유스"),

    /** A rookie drawn by an academy in the lottery. */
    LOTTERY('A', "유스 아카데미"),

    /** Everyone else (released players, members of dissolved clubs, rookies no academy had room for): the smallest club first, as before. */
    DRAFT('D', "드래프트"),
    ;

    /** An academy graduate ([Terms.academy] is the club). */
    val graduate: Boolean get() = this != DRAFT
}

/** A member placed by [Academy.draft]: [key] at [club] by [intake]; [pick] its number in its nation's draft (0 for a graduate). */
data class AcademyPick(val key: String, val club: String, val intake: Intake, val pick: Int)

/** What the academy reads about the players besides the off-season's input: each one's hometown and potential (null: not known). */
class AcademyInput(
    val hometown: (String) -> String? = { null },
    val potential: (String) -> Int? = { null },
)

/**
 * What the academy and draft step of the off-season after [season] did (design ENG 1b.3): every
 * placement in order ([placed]; empty when [Academy.report] rebuilt it from the book), the
 * academy graduates (best potential first) with their clubs, and the wonderkids among them.
 */
data class AcademyReport(
    val season: Int,
    val placed: List<AcademyPick>,
    val graduates: List<Pair<String, String>>,
    val wonderkids: List<String>,
    /** Each wonderkid's potential and age, for the news. */
    val potential: Map<String, Int> = emptyMap(),
    val age: Map<String, Int> = emptyMap(),
) {
    /**
     * The news of ENG 11.2 (comp [Comp.CLUB]): one `academy_graduates` [≤ 3 best graduates, then
     * their ≤ 3 clubs] (count, best named), and `wonderkid` [ai, club] (potential, age) ≤
     * [Academy.WONDERKIDS], the highest potentials.
     */
    fun events(): List<LeagueEvent> {
        if (graduates.isEmpty()) return emptyList()
        val out = ArrayList<LeagueEvent>()
        val best = graduates.take(Academy.NAMED)
        out += LeagueEvent(
            "academy_graduates", season, best.map { it.first } + best.map { it.second }.distinct(),
            listOf(graduates.size.toString(), best.size.toString()), Comp.CLUB,
        )
        val clubOf = graduates.toMap()
        for (k in wonderkids.take(Academy.WONDERKIDS)) {
            out += LeagueEvent("wonderkid", season, listOf(k, clubOf.getValue(k)), listOf((potential[k] ?: 0).toString(), (age[k] ?: 0).toString()), Comp.CLUB)
        }
        return out
    }
}

/**
 * Youth academies (유스 아카데미, design ENG 1b.3): rookies are generated as before, and the
 * off-season's draft step decides where they start. Per nation, with `q = ⌈R / C⌉` its R rookies
 * (newcomers to the league) over its C clubs: pupils at their mentor's club; then a rookie at its
 * hometown's club when that club's academy is at least the nation's median, it took fewer than q
 * this off-season and it is below the size ceiling; then the academy lottery, best potential first,
 * each rookie to a club drawn by its academy level, without replacement within a round (a round
 * ends when every eligible club drew once): round r among the clubs below the ceiling that took
 * fewer than r rookies (and fewer than q + 1), so a club that took pupils or hometown rookies waits
 * until the others have as many; then everyone left through the smallest-club draft. No club takes
 * more than q + 1 rookies while another has room. Graduates' terms name the academy and the season; they get its growth
 * term for their first [GROWTH_SEASONS] seasons ([growth]). Nothing without a club world.
 */
object Academy {
    /** A wonderkid (원더키드): a graduate of potential [WONDERKID_POTENTIAL] or more, aged [WONDERKID_AGE] or less. News: ≤ [WONDERKIDS] a season. */
    const val WONDERKID_POTENTIAL = 88
    const val WONDERKID_AGE = 19
    const val WONDERKIDS = 2

    /** Graduates an `academy_graduates` article names. */
    const val NAMED = 3

    /** The contract of a draft or academy signing, in seasons. */
    const val CONTRACT = 3

    /** The academy's growth term `0.02 × (level − 3)` on its own graduates' first [GROWTH_SEASONS] seasons (design ENG 1b.2, 1b.11). */
    const val GROWTH = 0.02
    const val GROWTH_SEASONS = 3

    private const val SALT = 0xAC4DL

    /** The fair intake `⌈rookies / clubs⌉` (0 without clubs). */
    fun fair(rookies: Int, clubs: Int): Int = if (clubs <= 0) 0 else (rookies + clubs - 1) / clubs

    fun isWonderkid(potential: Int?, age: Int?): Boolean = potential != null && age != null && potential >= WONDERKID_POTENTIAL && age <= WONDERKID_AGE

    /**
     * The academy and draft step of the off-season after [season] (design ENG 1b.10 step 12): every
     * member of [input] without a club is placed in a club of its nation, newcomers to the league
     * (not in [played], the closing season's members) as the academies take them, the others by the
     * draft ([Academy]'s order). [place] is each club's place in its club table (the draft's order:
     * the clubs that did not play, then the lowest placed, pick first). Every member placed gets
     * terms from the next season for [CONTRACT] seasons at [wage] (member, club) with a shirt number
     * ([ClubHistory.join]); a graduate's name its club's academy. Members of a nation without clubs
     * stay without one. Asked again it places only who is still without a club.
     */
    fun draft(
        book: ClubBook, season: Int, input: ClubInput, academy: AcademyInput, played: Set<String>,
        place: Map<String, Int> = emptyMap(), wage: (String, String) -> Int = { _, _ -> 0 },
    ): AcademyReport {
        val w = book.world ?: return AcademyReport(season, emptyList(), emptyList(), emptyList())
        val alive = input.members.toHashSet()
        val order = ClubBook.ratingOrder(input.rating)
        val free = alive.filter { book.clubOf(it) == null }.groupBy { ClubBook.homeOf(input.nation(it)) }
        val potential = { k: String -> academy.potential(k) ?: Int.MIN_VALUE }
        val byPotential = compareByDescending<String> { potential(it) }.then(order)
        val placed = ArrayList<AcademyPick>()
        for ((ni, nation) in Nations.ALL.withIndex()) {
            val pool = free[nation.code]?.sortedWith(order) ?: continue
            val clubs = book.clubsOf(nation.code)
            if (clubs.isEmpty()) continue
            val size = clubs.associateTo(HashMap()) { it.id to it.squad.size }
            // The shirt numbers' stars: the nation's best by rating, its players without a club among them.
            val stars = (clubs.flatMap { it.squad } + pool).sortedWith(order).take(ClubHistory.STAR_RANK).toHashSet()
            val members = clubs.sumOf { it.squad.size } + pool.size
            val max = ClubBook.maxSize(members, clubs.size)
            val rookies = pool.filter { it !in played }
            val q = fair(rookies.size, clubs.size)
            val intake = HashMap<String, Int>()
            val level = clubs.associate { it.id to (w.clubs[it.id]?.academy ?: 1).coerceAtLeast(1) }
            fun put(k: String, club: String, how: Intake, pick: Int = 0) {
                book.place(k, club)
                size.merge(club, 1, Int::plus)
                if (how != Intake.DRAFT || k !in played) intake.merge(club, 1, Int::plus)
                ClubHistory.join(book, k, club, season + 1, season + CONTRACT, wage(k, club), k in stars, academy = if (how.graduate) club else null)
                placed += AcademyPick(k, club, how, pick)
            }
            fun room(club: String) = (size[club] ?: 0) < max
            val left = ArrayList<String>()
            // 1. Pupils at their mentor's club (counted toward the intake).
            for (k in rookies) {
                val mentor = if (input.origin(k) == Origin.EVOLVED) input.mentor(k) else null
                val club = mentor?.let { book.clubOf(it) }?.takeIf { c -> clubs.any { it.id == c } && room(c) && (intake[c] ?: 0) < q + 1 }
                if (club == null) left += k else put(k, club, Intake.MENTOR)
            }
            // 2. Hometown clubs whose academy is at least the nation's median.
            val levels = clubs.map { level.getValue(it.id) }.sorted()
            val median = if (levels.size % 2 == 1) levels[levels.size / 2].toDouble() else (levels[levels.size / 2 - 1] + levels[levels.size / 2]) / 2.0
            val lottery = ArrayList<String>()
            for (k in left.sortedWith(byPotential)) {
                val town = academy.hometown(k)
                val club = town?.let { t -> clubs.filter { it.city == t }.minWithOrNull(compareBy(ClubBook.ID_ORDER) { it.id }) }?.id
                    ?.takeIf { level.getValue(it) >= median && (intake[it] ?: 0) < q && room(it) }
                if (club == null) lottery += k else put(k, club, Intake.HOMETOWN)
            }
            // 3. The lottery by academy level, without replacement within a round; round r is for the
            // clubs that took fewer than r (a club with pupils or hometown rookies waits for the others).
            val rng = Random(DiceStream.mix(book.seed, SALT, season.toLong(), ni.toLong()))
            val drafted = ArrayList<String>()
            var r = 0
            var round = ArrayList<String>()
            for (k in lottery) {
                while (round.isEmpty()) {
                    r++
                    round = clubs.map { it.id }.filterTo(ArrayList()) { room(it) && (intake[it] ?: 0) < minOf(r, q + 1) }
                    if (round.isEmpty() && r > q) break
                }
                if (round.isEmpty()) {
                    drafted += k
                    continue
                }
                var x = rng.nextInt(round.sumOf { level.getValue(it) })
                var pick = round.last()
                for (c in round) {
                    x -= level.getValue(c)
                    if (x < 0) {
                        pick = c
                        break
                    }
                }
                round.remove(pick)
                put(k, pick, Intake.LOTTERY)
            }
            // 4. Everyone left: the smallest club first, then the clubs that did not play and the lowest placed (as before).
            val rest = (pool.filter { it in played } + drafted).sortedWith(order)
            val turn = compareBy<String> { size[it] ?: 0 }.thenByDescending { place[it] ?: Int.MAX_VALUE }.thenComparing(ClubBook.ID_ORDER)
            for ((i, k) in rest.withIndex()) put(k, clubs.map { it.id }.minWith(turn), Intake.DRAFT, i + 1)
        }
        if (placed.isNotEmpty()) book.touch()
        return reportOf(book, season, input, academy, placed)
    }

    /** The academy step's report of the off-season after [season], rebuilt from the book: the graduates of that intake who are still at their academy's club. */
    fun report(book: ClubBook, season: Int, input: ClubInput, academy: AcademyInput): AcademyReport = reportOf(book, season, input, academy, emptyList())

    private fun reportOf(book: ClubBook, season: Int, input: ClubInput, academy: AcademyInput, placed: List<AcademyPick>): AcademyReport {
        val w = book.world ?: return AcademyReport(season, placed, emptyList(), emptyList())
        val grads = book.clubs.flatMap { c -> c.squad.filter { k -> w.terms[k]?.let { it.academy == c.id && it.gradSeason == season + 1 && it.since == season + 1 } == true }.map { it to c.id } }
            .sortedWith(compareByDescending<Pair<String, String>> { academy.potential(it.first) ?: Int.MIN_VALUE }.thenBy { it.first })
        val kids = grads.map { it.first }.filter { isWonderkid(academy.potential(it), input.age(it)) }
        return AcademyReport(
            season, placed, grads, kids,
            kids.associateWith { academy.potential(it) ?: 0 }, kids.associateWith { input.age(it) ?: 0 },
        )
    }

    /** The club whose academy [key] came out of (its terms'); null without one. */
    fun academyOf(book: ClubBook, key: String): String? = book.terms(key)?.academy

    /** Club [club]'s graduates playing now, at any club, by key. */
    fun graduates(book: ClubBook, club: String): List<String> {
        val w = book.world ?: return emptyList()
        return book.clubs.flatMap { c -> c.squad.filter { w.terms[it]?.academy == club } }.sorted()
    }

    /**
     * Member [key]'s academy growth term at the close of [season] (design ENG 1b.2, 1b.11:
     * `0.02 × (level − 3)`, the level of its academy's club): only for a graduate playing for that
     * club in its first [GROWTH_SEASONS] seasons; 0 otherwise.
     */
    fun growth(book: ClubBook, key: String, season: Int): Double {
        val t = book.terms(key) ?: return 0.0
        val club = t.academy ?: return 0.0
        if (book.clubOf(key) != club || season !in t.gradSeason until t.gradSeason + GROWTH_SEASONS) return 0.0
        val level = book.world?.clubs?.get(club)?.academy ?: return 0.0
        return GROWTH * (level - 3)
    }
}
