package yacht.engine

import yacht.engine.league.Accounts
import yacht.engine.league.ArcKind
import yacht.engine.league.ArcStage
import yacht.engine.league.Archive
import yacht.engine.league.Awards
import yacht.engine.league.BigStory
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubHistoryBook
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubLegend
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubRecord
import yacht.engine.league.ClubRecordKind
import yacht.engine.league.ClubWorld
import yacht.engine.league.ClubYear
import yacht.engine.league.Coach
import yacht.engine.league.DerbyRecord
import yacht.engine.league.EncFinal
import yacht.engine.league.EndedArc
import yacht.engine.league.Era
import yacht.engine.league.EraCandidate
import yacht.engine.league.Evolution
import yacht.engine.league.FamilyStats
import yacht.engine.league.FeeRecord
import yacht.engine.league.Field
import yacht.engine.league.Gender
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueHistory
import yacht.engine.league.LeagueScorecard
import yacht.engine.league.Leagues
import yacht.engine.league.MarketStory
import yacht.engine.league.Matchup
import yacht.engine.league.NotableMove
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Philosophy
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PredictionScorecard
import yacht.engine.league.PressAwards
import yacht.engine.league.PressTally
import yacht.engine.league.PressVoteRow
import yacht.engine.league.RankLine
import yacht.engine.league.RetiredCoach
import yacht.engine.league.RetiredNumber
import yacht.engine.league.ReviewCoaches
import yacht.engine.league.ReviewEra
import yacht.engine.league.ReviewMarket
import yacht.engine.league.ReviewMedia
import yacht.engine.league.Rival
import yacht.engine.league.ScoreSwing
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonReview
import yacht.engine.league.SeasonStories
import yacht.engine.league.Sponsor
import yacht.engine.league.Stadium
import yacht.engine.league.StadiumProject
import yacht.engine.league.StatsBook
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.StyleShares
import yacht.engine.league.Tenure
import yacht.engine.league.Terms
import yacht.engine.league.WcCeremony
import yacht.engine.league.WcVote
import yacht.engine.league.WinterMove
import yacht.engine.league.WorldArc
import yacht.engine.league.WorldBook
import yacht.engine.league.WorldEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phase-2 codecs (design ENG 1.6, 8.1): every club-world line kind of clubs.txt after its `cw`
 * marker, the world book (world.txt), the stats' `fm`/`pv` lines and the review's story sub-lines
 * round-trip; a copy of 3ce4b93's club-book decoder still reads a file with every new line kind;
 * a book without the marker has no club world and writes exactly what it read.
 */
class ClubWorldCodecTest {
    /** A national book as 3ce4b93 wrote it: three clubs, a dissolved one, places, a digest and moves (one of a later move type). */
    private val oldText = buildString {
        append("clubs 2\nmeta 77 3 991 6\n")
        append("club c01 KR 서울 호랑이 서울%20호랑이 0 ffc62828 ffffffff 호 120 2 1 g1,g2,g3\n")
        append("club c02 KR 부산 돌고래 부산%20돌고래 2 ff1565c0 ffffc107 돌 250 1 1 g4,g5\n")
        append("club c03 JP 도쿄 여우 도쿄%20여우 3 ff2e7d32 ffcfd8dc 여 80 0 2 j1,j2\n")
        append("gone c04 KR 대구%20늑대 1 ff6a1b9a ffffffff 늑 1 1 2\n")
        append("cs 3 KR:c01,c02:812;JP:c03:401\n")
        append("od 3 4 2 1 0 g4 c01 c02 33 g2 g4 j1:JP:KR - 0\n")
        append("move 3 T g4 c01 c02 33\nmove 3 D g2 - c01 1\nmove 3 F j9 c03 - 40\nmove 3 H j1 - c03 0\nmove 3 Y g3 - c01 0\n")
        append("move 3 L g5 c01 c02 0\n")
    }

    /** [oldText]'s book with every club-world part filled. */
    private fun worldBook(): ClubBook {
        val book = ClubBook.decode(oldText)
        val w = book.startWorld()
        w.wageIndex["KR"] = 105
        w.wageIndex["JP"] = 92
        w.worldRecord = FeeRecord("g1", "c02", "c01", 450, 3)
        w.checkpoint = 3
        w.marketSeason = 3
        w.marketWeek = 27
        w.nextCoach = 9
        w.nextStory = 14
        w.dissolvedBy = 2
        w.anchor = 1491.5
        w.state("c01").apply {
            foundedYear = 1923; nickname = "파랑 군단: 100% 진심, 영원히"; balance = -45; fans = 25_000; mood = 70; pressure = 1.3
            coach = "k1"; captain = "g1"; trouble = 1; admin = 0; deduction = 10; training = 3; academy = 2
        }
        w.state("c02").apply { foundedYear = 1950; nickname = "부산의 자존심"; balance = 250; fans = 18_000; mood = 55; training = 2; academy = 4 }
        val e = w.economy
        e.owners["c01"] = Owner(OwnerType.LOCAL, "휘담전자", 3, 3, 1, 0)
        e.owners["c02"] = Owner(OwnerType.TYCOON, "김철수 회장", 5, 1, 2, 812)
        e.sponsors["c01"] = Sponsor("벨로렌 텔레콤", 1, 46, 2, 4, true)
        e.stadiums["c01"] = Stadium("서울 스타디움", 12_000, StadiumProject('E', 15_000, 5, 60, 30), null)
        e.stadiums["c02"] = Stadium("부산 파크", 9_000, null, "솔채 아레나")
        e.accounts["c01"] = Accounts(3, 69, 46, 84, 47, 73, 7, -3, 12, 140, 27, 22, 0, 30, -45)
        e.accounts["c02"] = Accounts(3, 50, 30, 0, 30, 40, -5, 4, 0, 90, 20, 18, 12, 0, 250)
        e.years["c01"] = listOf(ClubYear(3, -45, 335, 25_000, 1), ClubYear(2, 20, 300, 24_000, 2))
        e.ranks[3] = RankLine(3, listOf("c02", "c01"), listOf("c01", "c02", "c03"), linkedMapOf("KR" to 450, "JP" to 120))
        e.ranks[2] = RankLine(2, listOf("c01"), listOf("c01"), emptyMap())
        w.terms["g1"] = Terms(5, 25, 600, 10, 1, 150, null, "c01", 2)
        w.terms["g4"] = Terms(4, 12, 0, 7, 3, 30, "c01", null, 0)
        w.terms["j1"] = Terms(4, 9, 0, 9, 3, 0, null, null, 0)
        w.terms["gone-key"] = Terms(4, 9, 0, 9, 3, 0, null, null, 0)
        val h = w.history
        h.rivals["c01"] = listOf(Rival("c02", 'N', 1))
        h.rivals["c02"] = listOf(Rival("c01", 'N', 1))
        h.derbies[ClubHistoryBook.pairKey("c02", "c01")] = DerbyRecord("c01", "c02", 5, 2, 3, 10, 1, 3)
        h.legends["c01"] = listOf(ClubLegend("g9", 140, 11, 3, true), ClubLegend("custom-4", 95, 8, 1, false))
        h.legends["c04"] = listOf(ClubLegend("x7", 90, 7, 1, false))
        h.retired["c01"] = listOf(RetiredNumber(10, "g9", 2))
        h.records["c01"] = listOf(ClubRecord(ClubRecordKind.APPS, "g9", 420, 2), ClubRecord(ClubRecordKind.SIGNING, "g1", 450, 3), ClubRecord(ClubRecordKind.SALE, null, 0, 0))
        h.records["c04"] = listOf(ClubRecord(ClubRecordKind.TITLES, "x7", 3, 1))
        val tenures = listOf(
            Tenure("c01", 3, 0, 1, 1, '-'), Tenure("c02", 2, 3, 2, 0, 'P'), Tenure("c03", 1, 2, 4, 0, 'S'),
            Tenure("c02", 1, 1, 5, 0, 'L'), Tenure("c01", 1, 1, 3, 0, 'S'),
        )
        w.coaches.active["k1"] = Coach("k1", "이 감독", "KR", Gender.FEMALE, -45, Philosophy.YOUTH, 5, 63, "c01", 3, 5, 31, null, 9, 2, 1, 2, tenures, 1)
        w.coaches.active["k2"] = Coach("k2", "Tanaka Ken", "JP", Gender.MALE, -38, Philosophy.STAR, 2, 40, null, 0, 0, 0, "g8", 0, 0, 0, 0, emptyList(), 0)
        w.coaches.active["k10"] = Coach("k10", "박 감독", "KR", Gender.MALE, -50, Philosophy.STABLE, 3, 55, "c02", 2, 4, 25, null, 2, 0, 0, 0, listOf(Tenure("c02", 2, 0, 2, 0, '-')), 0)
        w.coaches.retiredList += RetiredCoach("k3", "최 감독", "KR", Gender.MALE, -60, 3, Philosophy.ATTACK, 70, "g7", "CT:3,L0:5,COY:1,clubs:4")
        w.market.stories += MarketStory(12, 3, StoryStage.TALKS, "g4", "c01", "c02", 140, 26, 28, '-', false, null)
        w.market.stories += MarketStory(13, 3, StoryStage.HIJACKED, "j1", "c02", "c03", 90, 27, 0, 'H', true, "c01")
        w.market.stories += MarketStory(5, 2, StoryStage.COLLAPSED, "custom-4", "c02", "c01", 60, 30, 32, 'P', false, null)
        w.market.notable += NotableMove(3, 'T', "g1", "c02", "c01", 450, "WCV")
        w.market.notable += NotableMove(2, 'F', "g8", "c01", null, 33, "")
        w.winter.moves += WinterMove(3, "g2", "c02", "c01", 22, 31, 0)
        return book
    }

    @Test
    fun everyLineKindRoundTrips() {
        val book = worldBook()
        val text = book.encode()
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        val lines = text.lines()
        for (p in ClubWorld.PREFIXES) assertTrue(lines.any { it.startsWith("$p ") }, "a $p line")
        assertEquals(19, ClubWorld.PREFIXES.size)
        assertEquals(
            setOf("cw", "cl", "co", "cp", "cd", "ac", "ah", "sq", "rv", "dr", "lg", "rn", "cr", "k", "kr", "ms", "tx", "ws", "rk"),
            ClubWorld.PREFIXES,
        )
        // After today's lines, from the marker on; a member without a club keeps no terms.
        val marker = lines.indexOfFirst { it.startsWith("cw ") }
        assertTrue(marker > lines.indexOfLast { it.startsWith("move ") })
        assertTrue(lines.drop(marker).none { it.startsWith("club ") || it.startsWith("move ") })
        assertTrue(text.contains("\ncw 1 JP:92,KR:105 g1:c02:c01:450:3 3 3:27 9 2 1491.5 14\n"), text.lines().first { it.startsWith("cw ") })
        assertNull(back.terms("gone-key"))
        // Escaped names come back as they were.
        val w = assertNotNull(back.world)
        assertEquals("파랑 군단: 100% 진심, 영원히", back.life("c01")!!.nickname)
        assertEquals("CT:3,L0:5,COY:1,clubs:4", back.retiredCoaches.single().summary)
        assertEquals(1491.5, w.anchor)
        assertEquals(1.05, w.omega("KR"))
        assertEquals(1.0, w.omega("CN"))
        // Decoding twice gives the same book; a cut file keeps what came before the cut.
        assertEquals(text, ClubBook.decode(back.encode()).encode())
        val cut = text.substring(0, text.indexOf("\nk k10 ") + 1)
        val partial = ClubBook.decode(cut)
        assertNotNull(partial.world)
        assertEquals(listOf("k1", "k2"), partial.coaches.map { it.id })
        assertTrue(partial.notable.isEmpty())
    }

    @Test
    fun theAccessorsReadTheClubWorld() {
        val book = ClubBook.decode(worldBook().encode())
        val life = assertNotNull(book.life("c01"))
        assertEquals(1923, life.foundedYear)
        assertEquals("빨강·흰색", life.colours)
        assertEquals("파랑·금색", book.life("c02")!!.colours)
        assertEquals(-45, life.balance)
        assertEquals(OwnerType.LOCAL, life.owner.type)
        assertEquals(46, life.sponsor!!.perSeason)
        assertTrue(life.sponsor!!.record)
        assertEquals(StadiumProject('E', 15_000, 5, 60, 30), life.stadium.project)
        assertEquals("솔채 아레나", book.life("c02")!!.stadium.naming)
        assertEquals(listOf(3, 2, 25_000, 70, 1, 0, 10), listOf(life.training, life.academy, life.fans, life.mood, life.trouble, life.admin, life.deduction))
        assertEquals(1.3, life.pressure)
        assertEquals("k1" to "g1", life.coach to life.captain)
        assertEquals(335, life.last!!.income)
        assertEquals(219, life.last!!.expenses)
        assertEquals(116, life.last!!.net)
        assertEquals(-5, book.life("c02")!!.last!!.owner)
        assertEquals(book.life("c02")!!.last!!.let { it.wages + it.coach + it.upkeep + it.building + it.buys + 5 }, book.life("c02")!!.last!!.expenses)
        assertEquals(listOf(3, 2), life.history.map { it.season })
        assertEquals(listOf(Rival("c02", 'N', 1)), life.rivals)
        assertEquals(listOf("g9", "custom-4"), life.legends.map { it.key })
        assertEquals(listOf(RetiredNumber(10, "g9", 2)), life.retired)
        assertEquals(3, life.records.size)
        assertNull(life.records.last().key)
        assertNull(book.life("c03"), "no state, no life")
        assertNull(book.life("c04"))
        assertEquals(listOf("x7"), book.world!!.history.legends["c04"]!!.map { it.key }, "a gone club keeps its legends")
        assertEquals(Owner.NONE, ClubBook.decode(worldBook().encode().lines().filter { !it.startsWith("co ") }.joinToString("\n")).life("c01")!!.owner)
        // Money: a club world's balance; the budget the screens read is max(0, balance), and the club line writes it.
        assertEquals(0 to -45, book.club("c01")!!.let { it.budget to it.balance })
        assertEquals(250 to 250, book.club("c02")!!.let { it.budget to it.balance })
        assertEquals(80 to 80, book.club("c03")!!.let { it.budget to it.balance }, "no state: the old budget")
        assertEquals("0", book.encode().lines().first { it.startsWith("club c01 ") }.split(' ')[10])
        assertEquals(book.clubs, book.clubsOf("KR") + book.clubsOf("JP"))
        // Contracts, rivals and derbies.
        assertEquals(Terms(4, 12, 0, 7, 3, 30, "c01", null, 0), book.terms("g4"))
        assertNull(book.terms("g2"))
        assertTrue(book.isRival("c01", "c02") && book.isRival("c02", "c01"))
        assertFalse(book.isRival("c01", "c03"))
        assertEquals(book.derby("c01", "c02"), book.derby("c02", "c01"))
        assertEquals(Triple(3, 2, 5), book.derby("c01", "c02")!!.of("c02"))
        assertNull(book.derby("c01", "c03"))
        // Coaches: by id (k10 after k2), the club's own (its cl line), the free pool, the retired, the spells at a club.
        assertEquals(listOf("k1", "k2", "k10"), book.coaches.map { it.id })
        assertEquals("k1", book.coachOf("c01")!!.id)
        assertNull(book.coachOf("c02"), "c02's cl line names no coach")
        assertEquals(listOf("k2"), book.freeCoaches.map { it.id })
        assertEquals(listOf("k3"), book.retiredCoaches.map { it.id })
        assertEquals(Gender.FEMALE, book.coach("k1")!!.gender)
        assertEquals(5, book.coach("k1")!!.tenures.size)
        assertEquals(47, book.coach("k1")!!.age(2))
        // Newest first, then by coach id (k1 before k10).
        assertEquals(listOf("k1" to 2, "k10" to 2, "k1" to 1), book.tenuresAt("c02").map { it.first to it.second.from })
        assertEquals(listOf(3, 1), book.tenuresAt("c01").map { it.second.from })
        assertTrue(book.tenuresAt("c09").isEmpty())
        // The market, the winter, the records and rankings.
        assertEquals(listOf(12, 13), book.stories(3).map { it.id })
        assertEquals("c01", book.stories(3).last().by)
        assertEquals(listOf(5), book.stories(2).map { it.id })
        assertEquals(listOf(WinterMove(3, "g2", "c02", "c01", 22, 31, 0)), book.winterMoves(3))
        assertTrue(book.winterMoves(2).isEmpty())
        assertEquals(listOf("WCV", ""), book.notable.map { it.flags })
        assertNull(book.notable.last().to)
        assertEquals(FeeRecord("g1", "c02", "c01", 450, 3), book.worldRecord)
        assertEquals(listOf(2, 3), book.rankLines.map { it.season })
        assertEquals(mapOf("KR" to 450, "JP" to 120), book.rankLines.last().leagueRecords)
    }

    @Test
    fun aBookWithoutTheMarkerHasNoClubWorldAndWritesWhatItRead() {
        val book = ClubBook.decode(oldText)
        assertNull(book.world)
        assertEquals(oldText.lines().filter { !it.startsWith("move 3 L ") }.joinToString("\n"), book.encode())
        assertNull(book.life("c01"))
        assertNull(book.terms("g1"))
        assertTrue(book.rivals("c01").isEmpty() && book.coaches.isEmpty() && book.freeCoaches.isEmpty() && book.retiredCoaches.isEmpty())
        assertTrue(book.stories(3).isEmpty() && book.winterMoves(3).isEmpty() && book.notable.isEmpty() && book.rankLines.isEmpty())
        assertNull(book.coachOf("c01"))
        assertNull(book.worldRecord)
        assertEquals(120 to 120, book.club("c01")!!.let { it.budget to it.balance })
        // The checkpoint does nothing without a club world (and nothing yet with one).
        val s = Season.start(3, 1L, listOf(listOf("g1", "g2", "g3", "g4")), false, Leagues.national(listOf("KR"), listOf(1)))
        val input = ClubInput(listOf("g1", "g2", "g3", "g4"), { 1500.0 }, { "KR" }, LeagueConfig.DEFAULT)
        assertEquals(emptyList(), book.checkpoint(SeasonPlan(s, LeagueConfig.DEFAULT), input, 22))
        assertEquals(book.encode(), ClubBook.decode(oldText).encode())
        val world = worldBook()
        val before = world.encode()
        assertEquals(emptyList(), world.checkpoint(SeasonPlan(s, LeagueConfig.DEFAULT), input, 22))
        assertEquals(before, world.encode())
        // A gone club's reason is written only when it has one.
        assertTrue(oldText.lines().first { it.startsWith("gone ") } in book.encode().lines())
        assertEquals('-', book.gone("c04")!!.reason)
        val folded = ClubBook.decode(oldText.replace("gone c04 KR 대구%20늑대 1 ff6a1b9a ffffffff 늑 1 1 2\n", "gone c04 KR 대구%20늑대 1 ff6a1b9a ffffffff 늑 1 1 2 F\n"))
        assertEquals('F', folded.gone("c04")!!.reason)
        assertTrue(folded.encode().contains("\ngone c04 KR 대구%20늑대 1 ff6a1b9a ffffffff 늑 1 1 2 F\n"))
    }

    @Test
    fun theMutationsOfTheClubWorldStepsAndTheRollback() {
        val book = worldBook()
        val saved = book.encode()
        val v0 = book.version
        assertTrue(book.place("g3", "c02"))
        assertEquals("c02", book.clubOf("g3"))
        assertEquals(listOf("g1", "g2"), book.club("c01")!!.squad)
        assertEquals(listOf("g4", "g5", "g3"), book.club("c02")!!.squad)
        assertFalse(book.place("g3", "c99"), "an unknown club: nothing changes")
        assertEquals("c02", book.clubOf("g3"))
        assertTrue(book.place("g3", null))
        assertNull(book.clubOf("g3"))
        book.logMove(ClubMove(4, ClubMoveType.TRANSFER, "g3", "c02", null, 0))
        book.world!!.state("c01").balance = 999
        assertNotEquals(v0, book.version)
        val v1 = book.version
        book.touch()
        assertNotEquals(v1, book.version)
        // Back to the saved book, whole.
        book.restore(saved)
        assertEquals(saved, book.encode())
        assertEquals("c01", book.clubOf("g3"))
        assertEquals(-45, book.life("c01")!!.balance)
        assertTrue(book.world!!.book === book)
        // A book started afresh: an empty world, the version moved.
        val plain = ClubBook.decode(oldText)
        val v = plain.version
        val w = plain.startWorld()
        assertTrue(plain.startWorld() === w)
        assertNotEquals(v, plain.version)
        assertTrue(plain.encode().lines().any { it == "cw 1 - - 0 0:0 1 0 1482 1" })
        assertNotNull(ClubBook.decode(plain.encode()).world)
    }

    /** What 3ce4b93's `ClubBook.decode` kept of a national book (a copy of its line handling). */
    private class OldBook(
        val clubs: List<Triple<String, String, List<String>>>,
        val budgets: Map<String, Int>,
        val gone: List<String>,
        val places: Map<Int, String>,
        val digest: String?,
        val moves: List<String>,
    )

    private fun oldDecode(text: String): OldBook {
        val lines = text.lines()
        require(lines.first().trim() == "clubs 2")
        requireNotNull(lines.firstOrNull { it.trim().split(' ')[0] == "meta" })
        val clubs = ArrayList<Triple<String, String, List<String>>>()
        val budgets = HashMap<String, Int>()
        val gone = ArrayList<String>()
        val places = HashMap<Int, String>()
        var digest: String? = null
        val moves = ArrayList<String>()
        val seen = HashSet<String>()
        // 3ce4b93's move types: an unknown code is skipped (its moveOf returns null).
        val types = "TDRXFHY"
        for (l in lines) {
            runCatching {
                val f = l.trim().split(' ')
                when (f[0]) {
                    "club" -> {
                        require(f.size >= 14 && f[2] in setOf("KR", "JP"))
                        if (clubs.any { it.first == f[1] }) return@runCatching
                        Field.unesc(f[3])
                        f[6].toInt()
                        f[7].toLong(16)
                        f[8].toLong(16)
                        val squad = f[13].split(',').filter { it.isNotEmpty() && it != "-" && seen.add(it) }
                        clubs += Triple(f[1], f[2], squad)
                        budgets[f[1]] = maxOf(0, f[10].toInt())
                        f[11].toInt()
                        f[12].toInt()
                    }
                    "gone" -> {
                        require(f.size >= 11)
                        f[8].toInt()
                        f[9].toInt()
                        f[10].toInt()
                        gone += f[1]
                    }
                    "cs" -> places[f[1].toInt()] = f[2]
                    "od" -> digest = f.subList(1, 15).joinToString(" ")
                    "move" -> {
                        if (f[2].single() !in types) return@runCatching
                        f[1].toInt()
                        f[6].toInt()
                        moves += f.subList(1, 7).joinToString(" ")
                    }
                }
            }
        }
        return OldBook(clubs, budgets, gone, places, digest, moves)
    }

    @Test
    fun anOlderBuildReadsAFileWithEveryNewLineKind() {
        val text = worldBook().encode()
        val old = oldDecode(text)
        val was = oldDecode(oldText)
        assertEquals(was.clubs, old.clubs, "clubs, nations, members")
        assertEquals(was.gone, old.gone)
        assertEquals(was.places, old.places)
        assertEquals(was.digest, old.digest)
        assertEquals(was.moves, old.moves, "every move of an old type, no other")
        assertEquals(5, old.moves.size)
        assertTrue(old.moves.none { it.startsWith("3 L ") }, "a move of a later type is skipped")
        // A sane budget for the club in the red.
        assertEquals(mapOf("c01" to 0, "c02" to 250, "c03" to 80), old.budgets)
        // The new book reads the same moves (a later type skipped here too) and the same digest.
        val book = ClubBook.decode(text)
        assertEquals(old.moves, book.history.map { "${it.season} ${it.type.code} ${it.ai} ${it.from ?: "-"} ${it.to ?: "-"} ${it.amount}" })
        assertEquals(listOf("c01", "c02"), book.places(3).first().clubs)
        assertEquals(4, book.digest!!.rookies)
    }

    @Test
    fun theWorldBookRoundTrips() {
        val empty = WorldBook()
        assertEquals("world 1\nguards 0 0 0 0\n", empty.encode())
        assertEquals(empty.encode(), WorldBook.decode(empty.encode()).encode())
        assertEquals(1.0, empty.meta.weight(StyleFamily.COMBO))
        assertNull(empty.meta.era)
        assertEquals(yacht.engine.league.ArcEffects.NONE, WorldEvents.effects(empty, 3))
        assertNull(WorldEvents.forceTrigger)
        val w = WorldBook()
        w.closeSeason = 6
        w.metaSeason = 6
        w.arcClose = 6
        w.arcWinter = 6
        w.meta.weights = StyleFamily.entries.associateWith { if (it == StyleFamily.BONUS) 1.35 else 0.95 }
        w.meta.shareLines[5] = StyleShares(5, listOf(300, 200, 150, 120, 100, 70, 40, 20), listOf(280, 210, 160, 110, 100, 80, 40, 20))
        w.meta.shareLines[6] = StyleShares(6, listOf(310, 190, 150, 130, 100, 60, 40, 20), listOf(285, 205, 160, 115, 100, 75, 40, 20))
        w.meta.eraList += Era(1, StyleFamily.BONUS, 2, 4, 230)
        w.meta.eraList += Era(2, null, 5, 5, 0)
        w.meta.eraList += Era(3, StyleFamily.BONUS, 6, 0, 205)
        w.meta.candidate = EraCandidate(StyleFamily.YACHT, 1)
        w.media.weeksTop["g12"] = 14
        w.media.weeksTop["custom-3"] = 2
        w.media.scorecards[5] = PredictionScorecard(5, 3, 8, 5, 9, 24, 41, ScoreSwing("g3", 9, 2), null)
        w.media.scorecards[6] = PredictionScorecard(6, 4, 8, 6, 10, 24, 52, ScoreSwing("g3", 9, 2), ScoreSwing("g7", 1, 11), listOf(LeagueScorecard("KR", true, true, 2, 60), LeagueScorecard("XAS", false, true, 1, -12)))
        w.media.votes[7] = WcVote(7, "KR", listOf(listOf("KR" to 9, "JP" to 8, "US" to 5), listOf("KR" to 12, "JP" to 10)))
        w.media.votes[3] = WcVote(3, "BR", listOf(listOf("BR" to 22)))
        w.media.ceremonies[3] = WcCeremony(3, "g44", "C", listOf("A" to listOf("BR", "KR", "SN", "NZ"), "C" to listOf("FR", "DE", "AR", "JP")), listOf(listOf("BR", "FR"), listOf("KR", "DE")))
        w.media.ceremonies[7] = WcCeremony(7, null, "A", emptyList(), emptyList())
        w.events.active += WorldArc(4, ArcKind.SUPER_LEAGUE, ArcStage.ANNOUNCED, 6, 7, true, listOf("c01", "c02"), listOf("12", "슈퍼 리그|분리", ""), '-')
        w.events.active += WorldArc(3, ArcKind.INVESTOR, ArcStage.RUMOUR, 6, 7, false, listOf("c09"), emptyList())
        w.events.endedList += EndedArc(1, ArcKind.CRISIS, 2, 5, 'D', listOf("EUROPE"))
        w.events.cooldowns[ArcKind.CRISIS] = 17
        w.events.cooldowns[ArcKind.BOOM] = 9
        w.events.bottom[6] = listOf("XAF", "NZ")
        w.events.bottom[5] = emptyList()
        w.enc.finals += EncFinal(6, "CL", "g12", "g44", "3:1")
        w.enc.finals += EncFinal(6, "CCL.ASIA", "custom-3", "g2", "4:3 (연장)")
        w.enc.leagues["KR"] = LeagueHistory("KR", 2, listOf("formed" to 2))
        w.enc.leagues["XAS"] = LeagueHistory("XAS", 2, emptyList())
        val text = w.encode()
        val back = WorldBook.decode(text)
        assertEquals(text, back.encode())
        for (p in WorldBook.PARTS.flatMap { it.prefixes }) assertTrue(text.lines().any { it.startsWith("$p ") }, "a $p line")
        assertEquals(listOf(6, 6, 6, 6), listOf(back.closeSeason, back.metaSeason, back.arcClose, back.arcWinter))
        assertEquals(1.35, back.meta.weight(StyleFamily.BONUS))
        assertEquals(listOf(5, 6), back.meta.shares.map { it.season })
        assertEquals(3, back.meta.era!!.n)
        assertEquals(listOf("보너스파의 시대", "춘추전국 시대", "두 번째 보너스파의 시대"), back.meta.eras.map { back.meta.name(it) })
        assertEquals(EraCandidate(StyleFamily.YACHT, 1), back.meta.candidate)
        assertEquals(mapOf("custom-3" to 2, "g12" to 14), back.media.careerTop)
        assertEquals(2, back.media.scorecard(6)!!.perLeague.size)
        assertEquals(-12, back.media.scorecard(6)!!.perLeague.last().rho)
        assertNull(back.media.scorecard(5)!!.flop)
        assertEquals(listOf(3, 2), back.media.vote(7)!!.rounds.map { it.size })
        assertEquals("KR" to 12, back.media.vote(7)!!.rounds[1][0])
        assertEquals(listOf("BR", "KR", "SN", "NZ"), back.media.ceremony(3)!!.groups[0].second)
        assertNull(back.media.ceremony(7)!!.presenter)
        assertEquals(listOf(3, 4), back.events.arcs.map { it.id })
        assertEquals(listOf("12", "슈퍼 리그|분리", ""), back.events.arcs.last().args)
        assertTrue(back.events.arcs.last().atWinter)
        assertEquals(17, back.events.cooldown(ArcKind.CRISIS))
        assertEquals(0, back.events.cooldown(ArcKind.MERGER))
        assertEquals(listOf("XAF", "NZ"), back.events.bottom(6))
        assertEquals(listOf("4:3 (연장)"), back.enc.finals(6).filter { it.comp == "CCL.ASIA" }.map { it.score })
        assertEquals(listOf("formed" to 2), back.enc.league("KR")!!.events)
        // A line that cannot be read is skipped; the rest is kept.
        val torn = WorldBook.decode("world 1\nev x\nguards 1 2 3 4\nmeta 1,2\nfx 3 CL a\n")
        assertEquals(listOf(1, 2, 3, 4), listOf(torn.closeSeason, torn.metaSeason, torn.arcClose, torn.arcWinter))
        assertTrue(torn.events.arcs.isEmpty() && torn.enc.allFinals.isEmpty())
        assertNull(torn.meta.weights)
    }

    @Test
    fun aStaleWorldBookDropsItsArcsAndVotesAndKeepsItsHistory() {
        val w = WorldBook()
        assertFalse(w.stale(40), "a book that never closed a season is never stale")
        w.closeSeason = 5
        w.events.active += WorldArc(1, ArcKind.BOOM, ArcStage.ACTIVE, 4, 6, false, listOf("NZ"), emptyList())
        w.events.bottom[5] = listOf("XAF")
        w.media.votes[6] = WcVote(6, "KR", listOf(listOf("KR" to 9)))
        w.media.votes[2] = WcVote(2, "BR", listOf(listOf("BR" to 9)))
        w.meta.eraList += Era(1, StyleFamily.EV, 3, 0, 190)
        w.media.weeksTop["g1"] = 3
        w.enc.finals += EncFinal(5, "CL", "g1", "g2", "3:0")
        assertFalse(w.stale(6))
        assertFalse(w.dropStale(6))
        assertEquals(1, w.events.arcs.size)
        assertTrue(w.stale(7))
        assertTrue(w.dropStale(7))
        assertTrue(w.events.arcs.isEmpty() && w.events.bottom(5).isEmpty())
        assertNull(w.media.vote(6))
        assertNotNull(w.media.vote(2))
        assertEquals(1, w.meta.eras.size)
        assertEquals(3, w.media.careerTop["g1"])
        assertEquals(1, w.enc.allFinals.size)
        assertFalse(w.dropStale(7), "nothing left to drop")
    }

    @Test
    fun theStatsFamilyAndPressVoteLinesRoundTrip() {
        val s = StatsBook()
        val fs = FamilyStats(
            3, listOf(50, 40, 30, 20, 10, 9, 6, 5), listOf(280, 210, 160, 110, 100, 80, 40, 20), listOf(StyleFamily.BONUS, null, StyleFamily.COMBO),
            listOf(1.2, -3.4, 0.0, 12.5, -0.1, 0.0, 0.0, 0.0),
            listOf(Matchup(StyleFamily.YACHT, StyleFamily.COMBO, 120, 40, 0.15), Matchup(StyleFamily.COMBO, StyleFamily.YACHT, 98, 40, -0.15)),
            linkedMapOf("KR" to StyleFamily.BONUS, "XAS" to StyleFamily.EV),
        )
        s.putFamilies(fs, 10)
        s.putFamilies(fs.copy(season = 2, bigTitles = emptyList(), matchups = emptyList(), leagues = emptyMap()), 10)
        val votes = listOf(
            PressTally(3, PressAwards.GOLDEN_DICE, 40, listOf(PressVoteRow("g12", 150, 22), PressVoteRow("custom-3", 99, 10))),
            PressTally(3, PressAwards.COACH, 40, listOf(PressVoteRow("k1", 120, 20))),
            PressTally(3, PressAwards.SIGNING, 40, emptyList()),
        )
        s.putPressVotes(3, votes, 5)
        val text = s.encode()
        val back = StatsBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(fs, back.families(3))
        assertEquals(listOf(2, 3), back.families.keys.toList())
        assertEquals(votes, back.pressVotes(3))
        assertTrue(back.pressVotes(2).isEmpty())
        // Kept for the newest seasons only.
        back.putFamilies(fs.copy(season = 4), 2)
        assertEquals(listOf(3, 4), back.families.keys.toList())
        back.putPressVotes(4, votes.map { it.copy(season = 4) }, 1)
        assertEquals(listOf(4), back.pressVotes.keys.toList())
        // A book without them writes no such lines.
        assertTrue(StatsBook().encode().lines().none { it.startsWith("fm ") || it.startsWith("pv ") })
    }

    @Test
    fun theReviewsStorySubLinesRoundTripAndAnOlderReviewHasNone() {
        fun review(n: Int) = SeasonReview(n, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf("시즌 $n 결산이에요."))
        val stories = SeasonStories(
            big = listOf(BigStory("arc", listOf("c01", "c02"), listOf("SUPER_LEAGUE", "P")), BigStory("era", emptyList(), listOf("B", "두 번째 보너스파의 시대"))),
            media = ReviewMedia(3, 8, 41, listOf(PressVoteRow("g12", 150, 22), PressVoteRow("g7", 80, 4))),
            market = ReviewMarket(FeeRecord("g1", "c02", "c01", 450, 3), 17),
            coaches = ReviewCoaches(4, "k1", "c01"),
            era = ReviewEra(StyleFamily.BONUS, 2, 'B'),
        )
        val small = SeasonStories(market = ReviewMarket(null, 3), era = ReviewEra(null, 1, 'E'))
        val a = Archive()
        for (n in 2..4) a.putReview(review(n))
        assertNull(a.stories(3))
        assertTrue(a.amendReview(2, stories = small))
        assertTrue(a.amendReview(3, stories = stories))
        assertFalse(a.amendReview(9, stories = stories), "no review, no stories")
        val text = a.encode()
        val back = Archive.decode(text)
        assertEquals(text, back.encode())
        assertEquals(stories, back.stories(3))
        assertEquals(small, back.stories(2))
        assertNull(back.stories(4))
        assertEquals(a.review(3), back.review(3), "the review itself is the value it was")
        assertTrue(text.lines().none { it.startsWith("rv big") }, "the newest review has none")
        assertTrue(text.lines().any { it.startsWith("rw big arc ") })
        // Amended after the close; again with the same changes nothing; other parts stay as they were.
        assertTrue(back.amendReview(4, transfers = 3))
        assertTrue(back.amendReview(4, stories = stories))
        val amended = back.encode()
        assertTrue(back.amendReview(4, stories = stories))
        assertEquals(amended, back.encode())
        val again = Archive.decode(amended)
        assertEquals(stories, again.stories(4))
        assertEquals(3, again.review(4)!!.transfers)
        assertTrue(amended.lines().any { it.startsWith("rv coa 4 k1 c01") })
        // The review reads its own sub-lines only (as an older version does); the stories' are skipped there.
        assertEquals(review(4).copy(transfers = 3), again.review(4))
        // A review put again (a close replayed) starts without them; a review that goes takes its stories along.
        again.putReview(review(4))
        assertNull(again.stories(4))
        for (n in 5..10) again.putReview(review(n))
        assertNull(again.stories(3))
        assertTrue(again.encode().lines().none { it.startsWith("rw big") })
        assertTrue(SeasonStories().isEmpty)
    }

    @Test
    fun theFamiliesThePressAwardsAndTheCodesArePinned() {
        // Every recipe in exactly one family (the 25 of 3ce4b93).
        assertEquals(PlayStyles.RECIPES.map { it.key }.toSet(), StyleFamily.RECIPES.keys)
        assertEquals(25, StyleFamily.RECIPES.size)
        assertEquals(StyleFamily.entries.toSet(), StyleFamily.RECIPES.values.toSet())
        assertEquals(StyleFamily.ODD, StyleFamily.of("someday"))
        assertNull(StyleFamily.of(null as yacht.engine.league.PlayGene?))
        assertEquals("보너스파의 시대", StyleFamily.BONUS.era)
        // The neutral shares: pinned, summing to 1, and what the generator draws (100,000 recruits; 4 standard errors).
        assertEquals(1.0, StyleFamily.NEUTRAL.values.sum(), 1e-9)
        assertEquals(StyleFamily.entries, StyleFamily.NEUTRAL.keys.toList())
        assertEquals(0.284906, StyleFamily.NEUTRAL.getValue(StyleFamily.COMBO))
        val n = 100_000
        val counts = IntArray(StyleFamily.entries.size)
        for (i in 1..n) counts[StyleFamily.of(PlayerGenerator.recruit(i, 1, false, emptySet()).spec.play)!!.ordinal]++
        for (f in StyleFamily.entries) assertEquals(StyleFamily.NEUTRAL.getValue(f), counts[f.ordinal].toDouble() / n, 0.006, f.name)
        // The press's honours apart from the season's awards; 올해의 영입 makes nobody a legend.
        assertTrue(PressAwards.KINDS.intersect(Awards.KINDS).isEmpty())
        assertEquals(setOf("GD", "SIGN"), PressAwards.KINDS)
        assertFalse(Evolution.isHonour("SIGN"))
        assertTrue(Evolution.isHonour("GD"))
        assertTrue(Evolution.isHonour("MVP"))
        assertEquals("-", Evolution.summary(listOf(yacht.engine.league.Honour(3, "SIGN", "g1"))))
        // Saved codes are unique.
        for (codes in listOf(
            OwnerType.entries.map { it.code }, Philosophy.entries.map { it.code }, StoryStage.entries.map { it.code }, ArcStage.entries.map { it.code },
            StyleFamily.entries.map { it.code }, ClubRecordKind.entries.map { it.code },
        )) assertEquals(codes.size, codes.toSet().size, codes.toString())
        assertEquals(listOf(StyleFamily.YACHT, StyleFamily.BONUS, null, StyleFamily.CAUTION, StyleFamily.COMBO, StyleFamily.EV, null), Philosophy.entries.map { it.family })
    }
}
