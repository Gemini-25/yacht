package yacht.engine

import yacht.engine.league.AiStats
import yacht.engine.league.Archive
import yacht.engine.league.Awards
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Dynasty
import yacht.engine.league.Family
import yacht.engine.league.Field
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameScore
import yacht.engine.league.HallOfFameEntry
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MetaReport
import yacht.engine.league.Offseason
import yacht.engine.league.RatingMove
import yacht.engine.league.RecordBreak
import yacht.engine.league.RecordKind
import yacht.engine.league.Season
import yacht.engine.league.SeasonEnd
import yacht.engine.league.SeasonLine
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonReview
import yacht.engine.league.SeasonSummary
import yacht.engine.league.SideGame
import yacht.engine.league.StatsBook
import yacht.engine.league.StatsContext
import yacht.engine.league.TieRule
import yacht.engine.league.TraitShare
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val rules = RuleSet()

    private fun card(vararg s: Int): Board = Board.of(rules, s.toList())

    /** A card with its whole [total] in Choice. */
    private fun flat(total: Int): Board = Board.of(rules, Category.ALL.map { if (it == Category.CHOICE) total else 0 })

    /** The facts of a game whose first player's P(win) after half-turn h is [curve] (percent). */
    private fun facts(first: Board, second: Board, curve: (Int) -> Int): GameFacts {
        val totals = ShortArray(48)
        totals[46] = first.total.toShort()
        totals[47] = second.total.toShort()
        return GameFacts(first, second, totals, ByteArray(24) { curve(it).toByte() })
    }

    private fun job(a: String, b: String, vararg games: GameScore, comp: Comp = Comp.LEAGUE): JobResult {
        val gs = games.toList()
        return JobResult(Fixture(comp, 0, a, b, 0L), listOf(BoardResult(a, b, gs, emptyList())), TieRule.match(gs), 0)
    }

    @Test
    fun aMirroredMatchCountsEachGameForTheRightSide() {
        val a1 = card(3, 6, 9, 12, 15, 18, 22, 20, 25, 15, 30, 50)
        val b1 = card(2, 4, 6, 8, 10, 12, 23, 0, 22, 15, 30, 0)
        val b2 = card(3, 6, 9, 12, 15, 18, 22, 20, 20, 15, 30, 50)
        val a2 = card(3, 6, 9, 12, 15, 18, 30, 0, 29, 15, 30, 50)
        assertEquals(listOf(260, 132, 255, 252), listOf(a1, b1, b2, a2).map { it.total })
        // Game 1 (A first): A falls to 15% and comes back. Game 2 (B first): A leads at 90% and loses by 3.
        val f1 = facts(a1, b1) { h -> if (h < 4) 40 else if (h < 8) 15 else if (h < 23) 70 else 100 }
        val f2 = facts(b2, a2) { h -> if (h < 10) 10 else if (h < 23) 60 else 100 }
        val games = listOf(GameScore(260, 132, aYacht = true, aBonus = true), GameScore(252, 255, aYacht = true, bYacht = true, aBonus = true, bBonus = true))
        val fixture = Fixture(Comp.LEAGUE, 0, "a", "b", 1L)
        val r = JobResult(fixture, listOf(BoardResult("a", "b", games, listOf(f1, f2))), TieRule.match(games), 2_000_000L)
        // One game each: the match goes to A's higher total.
        assertEquals(1, r.winner)
        val book = StatsBook()
        val before = mapOf("a" to 1300.0, "b" to 1450.0)
        assertTrue(book.record(r, StatsContext(1, { before.getValue(it) }, { "부산" })).isEmpty())
        val a = assertNotNull(book.season("a"))
        val b = assertNotNull(book.season("b"))
        assertEquals(a, book.career("a"))
        assertEquals(listOf(2, 1, 1, 1, 0), listOf(a.games, a.gameWins, a.gameLosses, a.matchWins, a.matchLosses))
        assertEquals(1, b.matchLosses)
        assertEquals(512L, a.scoreSum)
        assertEquals(260L * 260 + 252L * 252, a.scoreSquares)
        assertEquals(260 to 252, a.best to a.worst)
        // Box by box from each side's own cards.
        for (c in Category.ALL) {
            assertEquals((a1[c] ?: 0) + (a2[c] ?: 0), a.boxSums[c.ordinal], "a $c")
            assertEquals((b1[c] ?: 0) + (b2[c] ?: 0), b.boxSums[c.ordinal], "b $c")
        }
        assertEquals(1, a.boxZeros[Category.FOUR_OF_A_KIND.ordinal])
        assertEquals(1, b.boxZeros[Category.YACHT.ordinal])
        assertEquals(2, a.carded)
        assertEquals(10.0, a.boxAverage(Category.FOUR_OF_A_KIND))
        assertEquals(listOf(2, 1, 2, 1), listOf(a.yachts, b.yachts, a.bonuses, b.bonuses))
        assertEquals(2, a.histogram[10])
        assertEquals(1, b.histogram[5])
        // Comebacks and blown leads from each side's own P(win): both did one of each.
        assertEquals(listOf(1, 1, 1, 1), listOf(a.comebacks, a.blownLeads, b.comebacks, b.blownLeads))
        assertEquals(listOf(0, 1, 1), listOf(a.closeWins, a.closeLosses, b.closeWins))
        // Match ratings: A was the first player in game 1 and the second in game 2.
        assertEquals(f1.ratings.first + f2.ratings.second, a.ratingSum, 1e-9)
        assertEquals(f1.ratings.second + f2.ratings.first, b.ratingSum, 1e-9)
        assertEquals(2, a.ratingCount)
        // 운 보정 승점: 512 to 387 on the same dice. A beat an AI rated 150 higher, in a derby.
        assertEquals(3 to 0, a.aggPoints to b.aggPoints)
        assertEquals(1 to 0, a.giantKills to b.giantKills)
        assertEquals(1 to 1, a.derbyWins to b.derbyLosses)
        assertEquals(0.5, a.cost, 1e-9)
        assertEquals(listOf(-1, 1, 1), listOf(a.streak, a.bestStreak, b.streak))
        // Records: B's comeback from 10% beats A's from 15%.
        assertEquals("b" to 10.0, book.records[RecordKind.COMEBACK]!!.let { it.holder to it.value })
        assertEquals(260.0, book.records[RecordKind.BEST_GAME]!!.value)
        assertEquals(128.0, book.records[RecordKind.MARGIN]!!.value)
        assertEquals(2.0, book.records[RecordKind.YACHTS_MATCH]!!.value)
        // The league meta has the four cards.
        val meta = book.liveMeta()
        assertEquals(4, meta.cards)
        assertEquals(150 / 4.0, meta.boxAverage(Category.YACHT), 1e-9)
        assertEquals(0.25, meta.zeroRate(Category.YACHT), 1e-9)
        assertEquals((260 + 132 + 255 + 252) / 4.0, meta.avgScore, 1e-9)
        // A friendly counts for the careers only; the lab and unrated jobs not at all.
        book.record(JobResult(fixture.copy(comp = Comp.FRIENDLY), r.boards, 1, 0), StatsContext(1, { 1500.0 }))
        assertEquals(2 to 4, book.season("a")!!.games to book.career("a")!!.games)
        book.record(JobResult(fixture.copy(comp = Comp.LAB), r.boards, 1, 0), StatsContext(1, { 1500.0 }))
        book.record(JobResult(fixture.copy(rated = false), r.boards, 1, 0), StatsContext(1, { 1500.0 }))
        assertEquals(4, book.career("a")!!.games)
        assertEquals(2, book.officialGames)
        assertTrue(StatsBook.countsForSeason(Comp.CUP) && StatsBook.countsForSeason(Comp.CLUB))
        assertTrue(!StatsBook.countsForSeason(Comp.ALL_STAR) && !StatsBook.countsForSeason(Comp.LEGEND))
    }

    @Test
    fun aTeamMatchCountsEveryBoardAndATieGoesByItsWinner() {
        val book = StatsBook()
        val g = listOf(GameScore(200, 210), GameScore(200, 190))
        // A knockout tie level after everything: side A (the higher seed) goes through.
        val tie = Fixture(Comp.CUP, 3, "x", "y", 0L, kind = JobKind.TIE)
        book.record(JobResult(tie, listOf(BoardResult("x", "y", g, emptyList())), 1, 0), StatsContext(1, { Double.NaN }))
        assertEquals(1 to 1, book.season("x")!!.matchWins to book.season("y")!!.matchLosses)
        // No facts: games and points count, boxes and ratings do not.
        assertEquals(0, book.season("x")!!.carded)
        assertTrue(book.season("x")!!.avgRating.isNaN())
        val team = Fixture(Comp.CLUB, 1, "c1", "c2", 0L, kind = JobKind.TEAM, lineupA = listOf("p", "q"), lineupB = listOf("r", "s"))
        val boards = listOf(
            BoardResult("p", "r", listOf(GameScore(220, 200), GameScore(210, 200)), emptyList()),
            BoardResult("q", "s", listOf(GameScore(180, 200), GameScore(190, 200)), emptyList()),
        )
        book.record(JobResult(team, boards, TieRule.team(boards), 4_000_000L), StatsContext(1, { 1500.0 }))
        assertEquals(1, book.season("p")!!.matchWins)
        assertEquals(1, book.season("q")!!.matchLosses)
        assertEquals(1, book.season("s")!!.matchWins)
        // 4 ms over 4 games: half a millisecond per game and side.
        assertEquals(0.5, book.career("q")!!.cost, 1e-9)
    }

    @Test
    fun seatsFollowTheTotalsThenThePlayOrder() {
        val x = flat(200)
        val y = flat(150)
        val z = flat(180)
        fun f(first: Board, second: Board) = facts(first, second) { 50 }
        // Two single-game legs, A first in both: the second game's totals tell.
        val single = BoardResult("a", "b", listOf(GameScore(180, 180), GameScore(200, 150)), listOf(f(z, z), f(x, y)))
        assertEquals(listOf(true, true), StatsBook.seats(single).toList())
        val mirrored = BoardResult("a", "b", listOf(GameScore(180, 180), GameScore(200, 150)), listOf(f(z, z), f(y, x)))
        assertEquals(listOf(true, false), StatsBook.seats(mirrored).toList())
        // Level everywhere: mirrored, then deciders alternating from A.
        val level = BoardResult("a", "b", List(5) { GameScore(180, 180) }, List(5) { f(z, z) }, deciders = 3)
        assertEquals(listOf(true, false, true, false, true), StatsBook.seats(level).toList())
        assertEquals(listOf(true), StatsBook.seats(BoardResult("a", "b", listOf(GameScore(150, 200)), listOf(f(y, x)))).toList())
        // The level game's cards go to the side the order says: B moved first in the second game.
        val w = Board.of(rules, Category.ALL.map { if (it == Category.FULL_HOUSE) 180 else 0 })
        val (sa, sb) = StatsBook.sides(BoardResult("a", "b", listOf(GameScore(200, 150), GameScore(180, 180)), listOf(f(x, y), f(z, w))))
        assertEquals(listOf(x, w), sa.map { it.card })
        assertEquals(listOf(y, z), sb.map { it.card })
    }

    @Test
    fun brokenRecordsAndMilestonesMakeNews() {
        val book = StatsBook(recordWarmup = 10)
        val ctx = StatsContext(1, { 1500.0 })
        val events = ArrayList<LeagueEvent>()
        // The first records are set quietly: early on everything is a record.
        events += book.record(job("y", "z", GameScore(240, 200), GameScore(230, 200)), ctx)
        assertEquals(240.0, book.records[RecordKind.BEST_GAME]!!.value)
        assertTrue(events.isEmpty())
        // x wins 1000 games in a row, a Yacht in every fifth match and one 300.
        repeat(500) { i ->
            val first = if (i == 250) 300 else 210 + i % 5
            events += book.record(job("x", "o${i % 7}", GameScore(first, 200, aYacht = i % 5 == 0), GameScore(205, 200)), ctx)
        }
        val records = events.filter { it.kind == "record" }
        val best = records.single { it.args[0] == RecordKind.BEST_GAME.name }
        assertEquals(listOf("x", "y"), best.keys)
        assertEquals(listOf("BEST_GAME", "300", "240"), best.args)
        assertEquals(listOf("MARGIN", "100", "40"), records.single { it.args[0] == RecordKind.MARGIN.name }.args)
        // A winning run going on is one break, not one per game.
        assertEquals(1, records.count { it.args[0] == RecordKind.STREAK.name })
        assertEquals(1000.0, book.records[RecordKind.STREAK]!!.value)
        assertEquals(RecordKind.STREAK, book.brokenThisSeason.single { it.record.kind == RecordKind.STREAK }.record.kind)
        val milestones = events.filter { it.kind == "milestone" && it.keys == listOf("x") }.map { it.args }
        assertEquals(
            setOf(listOf("wins", "100"), listOf("wins", "500"), listOf("wins", "1000"), listOf("games", "1000"), listOf("yachts", "50"), listOf("yachts", "100"), listOf("300", "1")),
            milestones.toSet(),
        )
        assertEquals(milestones.size, milestones.toSet().size)
        // The milestone list, newest first.
        assertEquals(listOf("wins", "1000"), book.milestones.first().args)
        assertEquals(listOf(1000, 5000, 10000, 50000), (1..60000).filter { StatsBook.isMilestone(it, 1000) })
        assertEquals(listOf(50, 100, 500, 1000), (1..1000).filter { StatsBook.isMilestone(it, 50) })
        assertEquals("승리 확률 3%에서 역전", RecordKind.COMEBACK.text(3.0))
    }

    @Test
    fun ratingGameAndCareerRecords() {
        val book = StatsBook(recordWarmup = 10)
        val ratings = HashMap(mapOf("low" to 1400.0, "high" to 1800.0, "x" to 1700.0))
        val ctx = StatsContext(1, { ratings[it] ?: 1500.0 })
        val events = ArrayList<LeagueEvent>()
        // The lower rated wins: the biggest upset, a low winning score and the best two-player total.
        events += book.record(job("low", "high", GameScore(150, 140), GameScore(160, 150)), ctx)
        val upset = book.records[RecordKind.UPSET]!!
        assertEquals(listOf("low", "high"), listOf(upset.holder, upset.other))
        assertEquals(400.0, upset.value)
        assertEquals(150.0, book.records[RecordKind.LOW_WIN]!!.value)
        assertEquals(310.0, book.records[RecordKind.BEST_PAIR]!!.value)
        assertEquals("레이팅 400 높은 상대를 꺾음", RecordKind.UPSET.text(400.0))
        // No peak before 60 games: a new rating still moves a lot.
        assertNull(book.records[RecordKind.PEAK_RATING])
        // x plays 40 matches (80 games) climbing to 1739: its peak counts from its 60th game.
        repeat(40) { i ->
            ratings["x"] = 1700.0 + i
            events += book.record(job("x", "o${i % 3}", GameScore(250, 200), GameScore(240, 210)), ctx)
        }
        val peak = book.records[RecordKind.PEAK_RATING]!!
        assertEquals("x" to 1739.0, peak.holder to peak.value)
        assertEquals("x" to 80.0, book.records[RecordKind.CAREER_GAMES]!!.let { it.holder to it.value })
        assertEquals(80.0, book.records[RecordKind.CAREER_WINS]!!.value)
        assertEquals(450.0, book.records[RecordKind.BEST_PAIR]!!.value)
        // A count one holder keeps raising is one break, not one a game.
        val records = events.filter { it.kind == "record" }
        for (k in listOf(RecordKind.CAREER_GAMES, RecordKind.CAREER_WINS, RecordKind.PEAK_RATING, RecordKind.STREAK)) {
            assertTrue(records.count { it.args[0] == k.name } <= 1, "$k: ${records.filter { it.args[0] == k.name }}")
        }
        // Every kind has a section, a label and a text.
        for (k in RecordKind.entries) assertTrue(k.label.isNotBlank() && k.text(12.0).isNotBlank(), k.name)
        assertEquals("12,345판", RecordKind.CAREER_GAMES.text(12345.0))
    }

    @Test
    fun seasonAwardAndChampionRecords() {
        val lg = threeSeasons()
        val r = lg.book.records
        // ai15 gains 40 rating points a season; ai1 is the MVP every season and wins every match.
        assertEquals("ai15" to 40.0, r[RecordKind.RATING_GAIN]!!.let { it.holder to it.value })
        assertEquals("ai1" to 3.0, r[RecordKind.MVPS]!!.let { it.holder to it.value })
        assertEquals(100.0, r[RecordKind.SEASON_WINRATE]!!.value)
        assertEquals(217.5, r[RecordKind.SEASON_SCORE]!!.value)
        assertTrue(r[RecordKind.SEASON_SCORE]!!.holder in setOf("ai1", "ai11"))
        // From the honours: most 이 주의 선수, the youngest and oldest champions (older seasons too, quietly).
        val book = StatsBook()
        val h = HonourBook().apply {
            add(listOf(Honour(1, "L0", "a"), Honour(1, "POTW", "a"), Honour(1, "POTW", "a"), Honour(2, "L0", "b"), Honour(2, "POTW", "b"), Honour(3, "L0", "c"), Honour(3, "POTW", "a")))
        }
        val ages = mapOf("a" to 25, "b" to 19, "c" to 33)
        val events = book.honourRecords(h, 3) { k, s -> ages[k]?.let { it + s - 3 } }
        assertEquals("b" to 18.0, book.records[RecordKind.YOUNGEST_CHAMPION]!!.let { it.holder to it.value })
        assertEquals(2, book.records[RecordKind.YOUNGEST_CHAMPION]!!.season)
        assertEquals("c" to 33.0, book.records[RecordKind.OLDEST_CHAMPION]!!.let { it.holder to it.value })
        assertEquals("a" to 3.0, book.records[RecordKind.POTW]!!.let { it.holder to it.value })
        // News only for this season's champion breaking a record.
        assertEquals(listOf(listOf("c", "a")), events.filter { it.kind == "record" }.map { it.keys })
        assertTrue(book.honourRecords(h, 3) { k, s -> ages[k]?.let { it + s - 3 } }.isEmpty())
    }

    /** A match the stronger AI wins 220-180 and 215-185, with facts (A first in game 1, B in game 2). */
    private fun result(f: Fixture, strength: (String) -> Int): JobResult {
        val aWins = strength(f.a) > strength(f.b)
        val g1 = if (aWins) GameScore(220, 180) else GameScore(180, 220)
        val g2 = if (aWins) GameScore(215, 185) else GameScore(185, 215)
        val up = { h: Int -> if (h == 23) 100 else 50 + h }
        val down = { h: Int -> if (h == 23) 0 else 50 - h }
        val f1 = facts(flat(g1.a), flat(g1.b), if (aWins) up else down)
        val f2 = facts(flat(g2.b), flat(g2.a), if (aWins) down else up)
        val games = listOf(g1, g2)
        return JobResult(f, listOf(BoardResult(f.a, f.b, games, listOf(f1, f2))), TieRule.match(games), 1_000_000L)
    }

    private class League(val book: StatsBook, val archive: Archive, val honours: HonourBook, val ends: List<SeasonEnd>, val replay: () -> SeasonEnd)

    /**
     * Three seasons of 20 AIs in two divisions where the stronger AI always wins; ai15 gains 40
     * rating points every season and a rookie joins in season 2.
     */
    private fun threeSeasons(prepare: (StatsBook, Archive) -> Unit = { _, _ -> }): League {
        val keys = (1..20).map { "ai$it" }
        fun strength(k: String) = if (k == "rookie") 1 else 100 - k.removePrefix("ai").toInt()
        val gains = HashMap<String, Double>()
        fun rating(k: String) = 1500.0 + strength(k) + (gains[k] ?: 0.0)
        val traits = { k: String -> if (k == "rookie") null else mapOf("실수" to if (strength(k) > 85) "없음" else "있음") }
        val name = { k: String -> "선수" + k.removePrefix("ai") }
        val book = StatsBook()
        val archive = Archive()
        prepare(book, archive)
        val honours = HonourBook()
        var roster = keys
        fun context() = Offseason.Context(roster, ::rating, { Family.EV }, { "서울" }, LeagueConfig(divisions = 2))
        var plan = Offseason.first(3L, context())
        val ends = ArrayList<SeasonEnd>()
        var replay: () -> SeasonEnd = { error("no season") }
        repeat(3) { s ->
            book.startSeason(plan.number, roster.associateWith { rating(it) })
            while (true) {
                val f = plan.next(emptySet(), 0, 1) ?: break
                val r = result(f, ::strength)
                book.record(r, StatsContext(plan.number, ::rating, { "서울" }))
                plan.record(r)
            }
            plan.startPost(emptyList())
            assertTrue(plan.finished)
            gains["ai15"] = (gains["ai15"] ?: 0.0) + 40
            if (s == 0) roster = keys + "rookie"
            val (next, h) = Offseason.next(plan, context())
            val ended = plan
            val end = book.endSeason(ended, h, honours, archive, ::rating, { Family.EV }, name, traits = traits)
            honours.add(h + end.awards)
            ends += end
            replay = { book.endSeason(ended, h, honours, archive, ::rating, { Family.EV }, name, traits = traits) }
            plan = next
        }
        return League(book, archive, honours, ends, replay)
    }

    @Test
    fun awardsHallOfFameAndDynastiesOverThreeSeasons() {
        val lg = threeSeasons()
        val (s1, s2, s3) = lg.ends
        fun SeasonEnd.award(kind: String) = awards.firstOrNull { it.kind == kind }?.key
        // MVP: the best average match rating in division 1; the top scorer and Yacht king stay Offseason's.
        for (e in lg.ends) {
            assertEquals("ai1", e.award("MVP"))
            assertEquals("ai15", e.award("MIP"))
            assertTrue(e.awards.all { it.kind in Awards.KINDS })
        }
        // No 신인왕 when the whole league is new; the newcomer of season 2 is the only rookie then.
        assertNull(s1.award("ROOKIE"))
        assertEquals("rookie", s2.award("ROOKIE"))
        assertNull(s3.award("ROOKIE"))
        assertTrue(s1.events.any { it.kind == "award" && it.args == listOf("SCORER") })
        // First titles are milestones.
        assertEquals(
            setOf("ai1", "ai11"),
            s1.events.filter { it.kind == "milestone" && it.args == listOf("title", "1") }.flatMap { it.keys }.toSet(),
        )
        // MVP twice puts ai1 in the hall of fame in season 2, once.
        assertEquals(listOf("ai1"), s2.inducted.map { it.key })
        assertEquals("MVP", s2.inducted.single().reason)
        assertEquals("선수1", lg.archive.hallOfFame.single().name)
        assertTrue(s3.inducted.isEmpty())
        assertTrue(s2.events.any { it.kind == "hall_of_fame" && it.keys == listOf("ai1") })
        // Dynasties: three titles in a row (one run, extended), unbeaten seasons.
        assertEquals(listOf(Dynasty("ai1", Awards.RUN, "L0", 3, 1, 3)), lg.archive.dynasties.filter { it.kind == Awards.RUN })
        assertEquals(listOf(1, 2, 3), lg.archive.dynasties.filter { it.kind == Awards.UNBEATEN && it.key == "ai1" }.map { it.count })
        assertTrue(s3.events.any { it.kind == "dynasty" && it.keys == listOf("ai1") && it.args == listOf(Awards.RUN, "3", "L0") })
        assertTrue(s1.dynasties.none { it.kind == Awards.RUN })
        // Season records (the 11-AI division of season 2 gave ai9 one more win) and the most titles.
        assertEquals(Triple("ai9", 30.0, 2), lg.book.records[RecordKind.SEASON_POINTS]!!.let { Triple(it.holder, it.value, it.season) })
        assertTrue(s2.events.any { it.kind == "record" && it.args == listOf("SEASON_POINTS", "30", "27") })
        assertTrue(s1.events.none { it.kind == "record" })
        assertEquals("ai1" to 3.0, lg.book.records[RecordKind.TITLES]!!.let { it.holder to it.value })
        assertTrue(s3.events.any { it.kind == "record" && it.args.take(2) == listOf("TITLES", "3") })
        assertEquals(lg.book.records[RecordKind.TITLES]!!.holder, yacht.engine.league.Records.mostTitles(lg.honours)!!.holder)
        // Season lines: the growth curve.
        val lines = lg.archive.lines("ai1")
        assertEquals(listOf(1, 2, 3), lines.map { it.season })
        val l = lines.first()
        assertEquals(listOf(0, 1, 9, 0, 0, 27), listOf(l.division, l.position, l.wins, l.draws, l.losses, l.points))
        assertEquals(1599.0, l.rating, 1.0)
        // Every win is worth 6.885 as the first player and 7.865 as the second.
        assertEquals(7.375, l.avgRating, 0.006)
        assertEquals(listOf(2, 3), lg.archive.lines("rookie").map { it.season })
        assertEquals(1, lg.archive.lines("rookie").first().division)
        assertEquals(3, lg.book.career("ai1")!!.seasons)
        // The summary and the review.
        val sum = assertNotNull(lg.archive.summary(1))
        assertEquals("ai1", sum.of("L0"))
        assertEquals("ai11", sum.of("L1"))
        assertEquals("ai1", sum.of("MVP"))
        assertTrue(sum.honours.none { it.kind.startsWith("UP") || it.kind.startsWith("DOWN") })
        assertEquals(180, sum.games)
        assertEquals(200.0, sum.avgScore, 1e-9)
        val review = assertNotNull(s3.review)
        assertEquals(review, lg.archive.pendingReview)
        assertTrue(review.sentences.first().startsWith("3시즌 1부 우승은 선수1이에요."), review.sentences.first())
        assertTrue(review.sentences.any { it.startsWith("MVP는 평균 평점 7.38의 선수1이에요") }, review.sentences.toString())
        assertTrue(review.sentences.any { it == "1부 상위 5위 중 5자리를 기대점수형 계열이 차지했어요." }, review.sentences.toString())
        // A record broken: its value in brackets after the record's name, not after the verb.
        val said = s2.review!!.sentences
        assertTrue("선수9가 한 시즌 최다 승점 기록(승점 30)을 새로 썼어요." in said, said.toString())
        // 최다 승점, 최다 우승 and 최다 MVP (ai1's second).
        assertTrue("이번 시즌 깨진 리그 기록은 모두 3개예요." in said, said.toString())
        assertEquals(RatingMove("ai15", 1665.0, 1705.0), review.risers.single())
        assertTrue(review.meta.any { it.label == "리그 평균 점수" && it.delta == 0.0 })
        println(review.sentences.joinToString("\n"))
        // The meta: every point was in Choice; traits at the top with their change.
        val meta = assertNotNull(lg.book.meta)
        assertEquals(3, meta.season)
        assertEquals(2, lg.book.previousMeta!!.season)
        assertEquals(200.0, meta.boxAverage(Category.CHOICE), 1e-9)
        assertEquals(1.0, meta.zeroRate(Category.ACES), 1e-9)
        // The top fifth is ai15 (rising, with mistakes) and ai1-ai3.
        val none = meta.traits.single { it.trait == "실수" && it.value == "없음" }
        assertEquals(0.75, none.top, 1e-9)
        assertEquals(11 / 16.0, none.rest, 1e-9)
        assertEquals(0.0, none.change, 1e-9)
        // Closing a season again changes nothing and says nothing.
        val archived = lg.archive.encode()
        val again = lg.replay()
        assertEquals(lg.ends.last().awards, again.awards)
        assertTrue(again.events.isEmpty())
        assertEquals(archived, lg.archive.encode())
        // Shown once.
        lg.archive.reviewShown()
        assertNull(lg.archive.pendingReview)
        assertNull(Archive.decode(lg.archive.encode()).pendingReview)
    }

    @Test
    fun awardsNeedEnoughFixturesAndAGain() {
        val keys = (1..10).map { "k$it" }
        val plan = Offseason.first(1L, Offseason.Context(keys, { 1500.0 }, { null }, { "" }, LeagueConfig(divisions = 1)))
        val stats = HashMap<String, AiStats>()
        // Half the season played: nobody has 60% of their fixtures.
        repeat(plan.league.fixturesTotal / 2) {
            val f = plan.next(emptySet(), 0, 1)!!
            plan.record(result(f) { it.removePrefix("k").toInt() })
        }
        for (k in keys) stats[k] = AiStats().apply { ratingSum = 7.0; ratingCount = 1 }
        assertNull(Awards.mvp(plan) { stats[it] })
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(result(f) { it.removePrefix("k").toInt() })
        }
        stats["k3"] = AiStats().apply { ratingSum = 8.0; ratingCount = 1 }
        assertEquals("k3", Awards.mvp(plan) { stats[it] })
        assertNull(Awards.mip(keys, { 1500.0 }, { 1490.0 }))
        assertEquals("k2", Awards.mip(keys, { 1500.0 }, { if (it == "k2") 1510.0 else 1505.0 }))
        assertNull(Awards.rookie(keys, { true }, { 1500.0 }))
        assertEquals("k1", Awards.rookie(keys, { it == "k1" }, { 1500.0 }))
        assertEquals("TITLES", Awards.hallOfFame(3, 0, null))
        assertEquals("GAMES", Awards.hallOfFame(0, 1, AiStats().apply { games = 5000; gameWins = 2750 }))
        assertNull(Awards.hallOfFame(2, 1, AiStats().apply { games = 5000; gameWins = 2700 }))
    }

    @Test
    fun theFilesReadBackAndACutFileKeepsWhatCameBeforeTheCut() {
        val lg = threeSeasons()
        val stats = lg.book.encode()
        val arch = lg.archive.encode()
        assertEquals(stats, StatsBook.decode(stats).encode())
        assertEquals(arch, Archive.decode(arch).encode())
        val back = StatsBook.decode(stats)
        assertEquals(lg.book.career("ai7"), back.career("ai7"))
        assertEquals(lg.book.records.all, back.records.all)
        assertEquals(lg.book.meta!!.traits, back.meta!!.traits)
        assertEquals(lg.archive.review, Archive.decode(arch).review)
        assertEquals(lg.archive.lines("ai15"), Archive.decode(arch).lines("ai15"))
        // Cut anywhere: no throw, and every AI read back is whole or missing.
        for (cut in 7 until stats.length step 13) {
            val b = StatsBook.decode(stats.substring(0, cut))
            for (k in lg.book.keys) b.career(k)?.let { assertEquals(lg.book.career(k), it, "cut at $cut") }
        }
        for (cut in 9 until arch.length step 7) {
            val a = Archive.decode(arch.substring(0, cut))
            for (k in lg.archive.lineKeys) assertTrue(lg.archive.lines(k).containsAll(a.lines(k)), "cut at $cut")
        }
        assertFailsWith<IllegalArgumentException> { StatsBook.decode("ladder 1\n") }
        assertFailsWith<IllegalArgumentException> { Archive.decode("") }
        // Unknown and broken lines are skipped.
        val odd = StatsBook.decode("stats 1\nfuture 1 2 3\nc ai1 zz\nrec NOPE x 1 1 - -\n")
        assertTrue(odd.keys.isEmpty() && odd.records.all.isEmpty())
    }

    @Test
    fun seasonLinesPackAndAgeOut() {
        val a = Archive()
        a.addLine("k", SeasonLine(1, 2, 14, 9, 1, 12, 1487.0, 6.73))
        a.addLine("k", SeasonLine(3, 1, 3, 30, 0, 4, 37.0, Double.NaN))
        a.addLine("k", SeasonLine(3, 1, 2, 31, 0, 3, -60.0, 9.99))
        val back = Archive.decode(a.encode()).lines("k")
        assertEquals(listOf(1, 3), back.map { it.season })
        assertEquals(SeasonLine(1, 2, 14, 9, 1, 12, 1488.0, 6.73), back[0])
        assertEquals(SeasonLine(3, 1, 2, 31, 0, 3, -60.0, 9.99), back[1])
        for (n in 4..40) a.addLine("k", SeasonLine(n, 0, 1, 1, 1, 1, 1500.0, 7.0))
        a.addLine("gone", SeasonLine(5, 0, 1, 1, 1, 1, 1500.0, 7.0))
        a.putSummary(SeasonSummary(40, emptyList(), null, Double.NaN, emptyList(), 0, Double.NaN))
        // The newest 30 league seasons: 11..40; an AI with none left goes.
        assertEquals((11..40).toList(), a.lines("k").map { it.season })
        assertTrue(a.lines("gone").isEmpty() && "gone" !in a.lineKeys)
        assertEquals("1부%20올스타", Field.esc("1부 올스타"))
        for (s in listOf("1부 올스타", "-", "a:b,c;d|e%f", "plain")) assertEquals(s, Field.unesc(Field.esc(s)))
        assertEquals("-", Field.esc(""))
        assertEquals(listOf(0, 7, 1295, 46656), Field.unrun(Field.run(intArrayOf(0, 7, 1295, 46656))).toList())
    }

    @Test
    fun highlightsAreKeptPerSeasonWithinBounds() {
        val a = Archive()
        a.putHighlights(4, listOf("a b c", "x".repeat(Archive.MAX_HIGHLIGHT_BYTES + 1), "d e", "f", "g"))
        assertEquals(listOf("a b c", "d e", "f"), a.highlights(4))
        assertEquals(a.highlights(4), Archive.decode(a.encode()).highlights(4))
        for (n in 5..110) a.putHighlights(n, listOf("h $n"))
        assertTrue(a.highlights(4).isEmpty())
        assertEquals(listOf("h 110"), a.highlights(110))
        assertEquals(Archive.HIGHLIGHT_SEASONS, (1..110).count { a.highlights(it).isNotEmpty() })
    }

    @Test
    fun retiredAisAreForgotten() {
        val book = StatsBook()
        book.startSeason(1, mapOf("a" to 1500.0, "b" to 1400.0))
        book.record(job("a", "b", GameScore(200, 100)), StatsContext(1, { 1500.0 }))
        book.retain(listOf("a"))
        assertNotNull(book.career("a"))
        assertNull(book.career("b"))
        assertNull(book.season("b"))
        assertTrue(book.startRating("b").isNaN())
        assertTrue(book.isRookie("a"))
    }

    /** A plausible final card from [rng]. */
    private fun randomCard(rng: Random): Board = Board.of(
        rules,
        Category.ALL.map { c ->
            when {
                c.isUpper -> c.face * rng.nextInt(6)
                c == Category.CHOICE -> 15 + rng.nextInt(15)
                c == Category.FOUR_OF_A_KIND -> if (rng.nextInt(3) == 0) 0 else 5 + rng.nextInt(25)
                c == Category.FULL_HOUSE -> if (rng.nextInt(4) == 0) 0 else 7 + rng.nextInt(22)
                c == Category.SMALL_STRAIGHT -> if (rng.nextInt(5) == 0) 0 else 15
                c == Category.LARGE_STRAIGHT -> if (rng.nextInt(3) == 0) 0 else 30
                else -> if (rng.nextInt(2) == 0) 0 else 50
            }
        },
    )

    private fun seasonOf(rng: Random, games: Int): AiStats {
        val s = AiStats()
        repeat(games) { i ->
            val card = randomCard(rng)
            val opp = 120 + rng.nextInt(180)
            s.addGame(SideGame(card.total, opp, (card[Category.YACHT] ?: 0) > 0, card.bonus > 0, card, rng.nextDouble() * 0.5, 0.5 + rng.nextDouble() * 0.5, 3 + rng.nextDouble() * 7, i % 40 == 0))
            if (i % 2 == 1) s.addMatch(rng.nextInt(3) - 1, rng.nextInt(61) - 30, rng.nextInt(20) == 0, rng.nextInt(16) == 0)
        }
        s.addCost(12.3)
        return s
    }

    @Test
    fun filesStayUnder450KbWith400AisAnd100Seasons() {
        val rng = Random(9)
        val book = StatsBook()
        val archive = Archive()
        val keys = (1..103).map { "base-ai-$it" } + (1..297).map { "e${it % 100}_${it / 100}" }
        fun key() = keys[rng.nextInt(keys.size)]
        // 400 AIs in 4 divisions of 100: a single round robin and the cups, about 240 games a season.
        val season = seasonOf(rng, 240)
        book.startSeason(101, keys.associateWith { 1400.0 + rng.nextInt(200) })
        for ((i, k) in keys.withIndex()) {
            val career = AiStats()
            // Base AIs have played all 100 seasons, generated ones part of them.
            repeat(if (i < 103) 100 else 10 + rng.nextInt(91)) { career.add(season) }
            book.putCareer(k, career)
            book.putSeason(k, seasonOf(rng, 240))
            // Career titles and MVPs on every line, as large as they get.
            book.putCounts(k, 99, 9)
        }
        for (kind in RecordKind.entries) {
            book.offer(LeagueRecord(kind, key(), 1.0, 90, key(), Comp.LEAGUE), ArrayList(), warm = true)
            book.offer(LeagueRecord(kind, key(), if (kind.lowerIsBetter) 0.5 else 2.0, 101, key(), Comp.CUP), ArrayList(), warm = true)
        }
        repeat(Archive.MAX_SEASONS) { book.milestone(key(), "games", 10000, 101, ArrayList()) }
        repeat(2) { m ->
            val traits = (0 until StatsBook.MAX_TRAITS).map { TraitShare("성향${it / 4}", "값 $it", rng.nextDouble(), rng.nextDouble(), rng.nextDouble()) }
            book.putMeta(MetaReport(99 + m, 41000, 201.3, List(12) { 20 + rng.nextDouble() }, List(12) { rng.nextDouble() }, traits))
        }
        for (n in 1..100) {
            val honours = listOf("L0", "L1", "L2", "L3", "REG", "CUP", "CUP2", "CL", "CL2", "SUPER", "MVP", "ROOKIE", "MIP", "SCORER", "YACHT")
                .map { Honour(n, it, key()) } + Honour(n, "ALLSTAR", "1부 올스타") + Honour(n, "FAMILY", "WIN") + Honour(n, "CLUB", "c07")
            val broken = (0 until 4).map {
                val kind = RecordKind.entries[rng.nextInt(RecordKind.entries.size)]
                RecordBreak(LeagueRecord(kind, key(), 300.0 + it, n), LeagueRecord(kind, key(), 299.0, n - 1))
            }
            archive.putSummary(SeasonSummary(n, honours, keys[0], 231.4, broken, 21000, 201.3))
            // Highlight lines as long as the archive takes them.
            archive.putHighlights(n, List(3) { "E $it ${rng.nextLong()} 1 287:281 comeback,clutch 9.3 ${(0 until 24).joinToString(",") { "${rng.nextInt(101)}" }}".padEnd(Archive.MAX_HIGHLIGHT_BYTES, 'x') })
            // Every AI has a line in each of the newest 30 seasons.
            if (n > 70) for (k in keys) archive.addLine(k, SeasonLine(n, rng.nextInt(4), 1 + rng.nextInt(100), rng.nextInt(60), rng.nextInt(10), rng.nextInt(60), 1200.0 + rng.nextInt(400), 5 + rng.nextDouble() * 3))
        }
        repeat(Archive.MAX_HALL_OF_FAME) { archive.induct(HallOfFameEntry(keys[it], "명예의 전당 선수 $it", it / 3 + 1, "TITLES", 3, 6000, 0.61)) }
        repeat(Archive.MAX_DYNASTIES) { archive.putDynasty(Dynasty(keys[it % 400], Awards.RUN, "L0", 2 + it % 5, it / 3, it / 3 + 2)) }
        val sentences = List(9) { "이번 시즌을 정리하는 문장 $it: 선수 이름과 숫자가 들어간 꽤 긴 한국어 문장이에요." }
        archive.putReview(
            SeasonReview(
                100, List(12) { Honour(100, "L$it", key()) }, List(5) { Honour(100, "MVP", key()) }, List(3) { RatingMove(key(), 1400.0, 1450.0) },
                List(3) { RatingMove(key(), 1450.0, 1400.0) }, archive.summary(100)!!.records, emptyList(), sentences,
            ),
        )
        val statsText = book.encode()
        val archiveText = archive.encode()
        for (text in listOf(statsText, archiveText)) {
            println(text.lines().groupBy { it.substringBefore(' ') }.map { (tag, ls) -> "$tag ${ls.size} lines ${ls.sumOf { it.toByteArray().size + 1 } / 1024} KB" }.joinToString(", "))
        }
        val stats = statsText.toByteArray().size
        val arch = archiveText.toByteArray().size
        println("stats.txt $stats bytes, archive.txt $arch bytes, together ${(stats + arch) / 1024} KB")
        assertTrue(stats + arch <= 450 * 1024, "${stats + arch} bytes")
        // And it all reads back.
        assertEquals(archiveText, Archive.decode(archiveText).encode())
        assertEquals(statsText, StatsBook.decode(statsText).encode())
    }

    // ---- regressions from the review ------------------------------------------------------------

    @Test
    fun matchRatingsReadBackExactlySoAwardsDoNotChangeOnARestart() {
        // 6.885 (a WPA of 0.11) is a half step of 0.01: three of them used to read back as 20.66.
        val s = AiStats()
        repeat(3) { s.addGame(SideGame(220, 180, false, false, null, Double.NaN, Double.NaN, 6.885, false)) }
        val back = AiStats.decode(s.encode().split(' '), 0)
        assertEquals(20.655, s.ratingSum, 1e-9)
        assertEquals(s.ratingSum, back.ratingSum)
        assertEquals(s.avgRating, back.avgRating)
        assertEquals(s, back)
    }

    @Test
    fun ratingSumsFromFilesInHundredthsReadAtTheirValue() {
        // Older versions kept rating sums in 0.01 steps (and no title counts on career lines).
        val s = AiStats()
        repeat(4) { s.addGame(SideGame(220, 180, false, false, null, Double.NaN, Double.NaN, 6.5, false)) }
        val book = StatsBook()
        book.startSeason(3, mapOf("a" to 1500.0))
        val text = book.encode().lines().joinToString("\n") { l ->
            when {
                l.startsWith("book ") -> "book 3 2 4"
                else -> l
            }
        } + "c a " + s.encode().split(' ').toMutableList().also { f ->
            // The rating sum field (the 22nd): what an older version wrote, in hundredths.
            f[21] = java.lang.Long.toString(Math.round(s.ratingSum * 100), 36)
        }.joinToString(" ") + "\n"
        val legacy = text.lines().filter { !it.startsWith("units ") }.joinToString("\n")
        assertEquals(26.0, StatsBook.decode(legacy).career("a")!!.ratingSum, 1e-9)
        // The same line in a file of this version (it says its units) is read as written.
        assertEquals(13.0, StatsBook.decode(text).career("a")!!.ratingSum, 1e-9)
        // A file written now reads back exactly.
        val now = StatsBook.decode(legacy)
        assertEquals(26.0, StatsBook.decode(now.encode()).career("a")!!.ratingSum, 1e-9)
    }

    @Test
    fun anUnknownStartRatingStaysUnknownAfterARestart() {
        val book = StatsBook()
        book.startSeason(4, mapOf("old" to 1500.4, "newborn" to Double.NaN))
        // The app starts again in the middle of the season, when the newborn has a rating.
        val back = StatsBook.decode(book.encode())
        back.startSeason(4, mapOf("old" to 1580.0, "newborn" to 1650.0))
        assertEquals(1500.0, back.startRating("old"))
        assertTrue(back.startRating("newborn").isNaN())
        // So 기량발전상 goes where it would have gone without the restart.
        val keys = listOf("newborn", "old")
        assertEquals("old", Awards.mip(keys, { back.startRating(it) }, { 1700.0 }))
        assertEquals(book.encode(), back.encode())
    }

    @Test
    fun aTornLastLineIsLeftOutRatherThanReadWrong() {
        val book = StatsBook()
        book.milestone("x", "games", 10000, 1, ArrayList())
        val text = book.encode()
        assertTrue(text.endsWith("10000\n"))
        assertEquals(listOf("games", "10000"), StatsBook.decode(text).milestones.single().args)
        // Cut to "...1000": not a 1000-game milestone.
        assertTrue(StatsBook.decode(text.dropLast(2)).milestones.isEmpty())
        val a = Archive()
        a.putHighlights(3, listOf("E 1 2 287:281 comeback"))
        val arch = a.encode()
        assertEquals(listOf("E 1 2 287:281 comeback"), Archive.decode(arch).highlights(3))
        assertTrue(Archive.decode(arch.dropLast(4)).highlights(3).isEmpty())
        // The header alone, cut or not, is an empty file of its kind.
        assertTrue(StatsBook.decode("stats 1").keys.isEmpty())
    }

    @Test
    fun aFullHallOfFameNeverDropsAnAiThatIsStillPlaying() {
        // Every member still plays and still qualifies: none makes room (else it would come back
        // the next season and push the next one out, every season).
        val playing = threeSeasons { book, archive ->
            repeat(Archive.MAX_HALL_OF_FAME) {
                book.putCareer("vet$it", AiStats().apply { games = 6000; gameWins = 4000 })
                assertTrue(archive.induct(HallOfFameEntry("vet$it", "베테랑 $it", 0, "GAMES", 0, 6000, 0.67)))
            }
        }
        assertTrue(playing.ends.all { e -> e.inducted.isEmpty() && e.events.none { it.kind == "hall_of_fame" } })
        assertEquals((0 until Archive.MAX_HALL_OF_FAME).map { "vet$it" }, playing.archive.hallOfFame.map { it.key })
        // Members gone for good make room, oldest first, and do not come back.
        val gone = threeSeasons { _, archive ->
            repeat(Archive.MAX_HALL_OF_FAME) { archive.induct(HallOfFameEntry("gone$it", "은퇴 선수 $it", 0, "TITLES", 3, 900, 0.6)) }
        }
        assertEquals(listOf("ai1"), gone.ends[1].inducted.map { it.key })
        assertTrue(gone.ends[2].inducted.isEmpty())
        assertEquals(Archive.MAX_HALL_OF_FAME, gone.archive.hallOfFame.size)
        assertEquals("gone1" to "ai1", gone.archive.hallOfFame.first().key to gone.archive.hallOfFame.last().key)
    }

    /** Season [number] of [keys] in one division, played to the end (k1 strongest). */
    private fun playedSeason(number: Int, keys: List<String>): SeasonPlan {
        val plan = SeasonPlan(Season.start(number, number.toLong(), listOf(keys)), LeagueConfig(divisions = 1))
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(result(f) { 100 - it.removePrefix("k").toInt() })
        }
        plan.startPost(emptyList())
        assertTrue(plan.finished)
        return plan
    }

    @Test
    fun titleMilestonesCountTheWholeCareer() {
        val keys = (1..6).map { "k$it" }
        val book = StatsBook()
        val archive = Archive()
        val honours = HonourBook()
        fun close(plan: SeasonPlan, titles: List<Honour>): List<Pair<String, String>> {
            val end = book.endSeason(plan, titles, honours, archive, { 1500.0 })
            honours.add(titles + end.awards)
            return end.events.filter { it.kind == "milestone" && it.args[0] == "title" }.map { it.keys.single() to it.args[1] }
        }
        // The league and the cup in the same season: still its first title.
        assertEquals(listOf("k1" to "1"), close(playedSeason(1, keys), listOf(Honour(1, "L0", "k1"), Honour(1, "CUP", "k1"))))
        assertEquals(2, book.titles("k1"))
        // A hundred seasons on, the honours book no longer has them...
        honours.add((2..101).map { Honour(it, "L0", "k6") })
        assertEquals(0, honours.titles("k1"))
        // ...but the career does: the next is its third title, not a first one again.
        assertEquals(emptyList(), close(playedSeason(102, keys), listOf(Honour(102, "L0", "k1"))))
        assertEquals(3, book.titles("k1"))
        assertEquals(3, StatsBook.decode(book.encode()).titles("k1"))
        // Three at once from three: the fifth is passed and announced.
        assertEquals(listOf("k1" to "5"), close(playedSeason(103, keys), listOf(Honour(103, "L0", "k1"), Honour(103, "CUP", "k1"), Honour(103, "CL", "k1"))))
        assertEquals(6, book.titles("k1"))
    }

    @Test
    fun dynastiesFollowEachCompetitionsEditions() {
        val league = Season.start(8, 1L, listOf(listOf("a", "b", "c")))
        val history = listOf(Honour(2, "FAMILY", "EV"), Honour(4, "ALLSTAR", "1부 올스타"), Honour(5, "FAMILY", "EV"), Honour(7, "ALLSTAR", "1부 올스타"))
        // The family cup is held every third season: the 5th season's follows the 2nd's.
        assertEquals(listOf(Dynasty("EV", Awards.RUN, "FAMILY", 2, 2, 5)), Awards.dynasties(5, listOf(Honour(5, "FAMILY", "EV")), history.filter { it.season < 5 }, league, emptyList()))
        // And the 8th extends that run; the all-star game (an exhibition) makes none.
        val run = Dynasty("EV", Awards.RUN, "FAMILY", 2, 2, 5)
        val now = listOf(Honour(8, "FAMILY", "EV"), Honour(8, "ALLSTAR", "1부 올스타"))
        assertEquals(listOf(Dynasty("EV", Awards.RUN, "FAMILY", 3, 2, 8)), Awards.dynasties(8, now, history, league, listOf(run)))
        // A league title is still against the season before.
        assertTrue(Awards.dynasties(8, listOf(Honour(8, "L0", "a")), listOf(Honour(6, "L0", "a"), Honour(7, "L0", "b")), league, emptyList()).isEmpty())
    }

    @Test
    fun theYachtRecordIsPerMatchNotPerTwoLeggedTie() {
        val book = StatsBook()
        val ctx = StatsContext(1, { 1500.0 })
        val y = GameScore(250, 200, aYacht = true)
        // A two-legged tie with A's Yacht in all four games: two in each match.
        val tie = Fixture(Comp.CHAMPIONS, 1, "a", "b", 0L, kind = JobKind.TIE, legs = 2)
        book.record(JobResult(tie, listOf(BoardResult("a", "b", List(4) { y }, emptyList())), 1, 0), ctx)
        assertEquals(2.0, book.records[RecordKind.YACHTS_MATCH]!!.value)
        // A best-of-three match is one match, its decider included.
        val bo3 = listOf(y, GameScore(200, 250, aYacht = true), y)
        book.record(JobResult(Fixture(Comp.LEAGUE, 2, "c", "d", 0L), listOf(BoardResult("c", "d", bo3, emptyList(), deciders = 1)), 1, 0), ctx)
        assertEquals("c" to 3.0, book.records[RecordKind.YACHTS_MATCH]!!.let { it.holder to it.value })
    }

    @Test
    fun levelRankingsAndViewsDoNotDependOnMapOrder() {
        val book = StatsBook()
        // "c" comes before "ba" in a HashMap.
        for (k in listOf("c", "ba")) book.putCareer(k, AiStats().apply { games = 10 })
        assertEquals(listOf("ba", "c"), book.top(2) { it.games.toDouble() }.map { it.first })
        // Views are copies: a snapshot does not change under the next season.
        val a = Archive()
        a.putDynasty(Dynasty("x", Awards.RUN, "L0", 2, 1, 2))
        a.addLine("x", SeasonLine(2, 0, 1, 9, 0, 0, 1600.0, 7.0))
        val dynasties = a.dynasties
        val lines = a.lines("x")
        val keys = book.keys
        a.putDynasty(Dynasty("y", Awards.UNBEATEN, "L0", 1, 3, 3))
        a.addLine("x", SeasonLine(3, 0, 1, 9, 0, 0, 1610.0, 7.0))
        book.putCareer("d", AiStats())
        assertEquals(1 to 1, dynasties.size to lines.size)
        assertEquals(setOf("c", "ba"), keys)
    }
}
