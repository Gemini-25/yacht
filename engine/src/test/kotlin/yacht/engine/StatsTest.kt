package yacht.engine

import yacht.engine.league.AiStats
import yacht.engine.league.Archive
import yacht.engine.league.Awards
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Drama
import yacht.engine.league.Dynasty
import yacht.engine.league.Family
import yacht.engine.league.Field
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameScore
import yacht.engine.league.HallOfFameEntry
import yacht.engine.league.HighlightBook
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MatchFormat
import yacht.engine.league.MetaReport
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.RatingMove
import yacht.engine.league.RatingScale
import yacht.engine.league.RecordBreak
import yacht.engine.league.RecordKind
import yacht.engine.league.ReviewGame
import yacht.engine.league.ReviewLeader
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
        // Records: the P(win) comeback and the match's Yachts are retired (never offered); no winner
        // trailed after a full turn here (the facts' running totals are zero until the end).
        assertNull(book.records[RecordKind.COMEBACK])
        assertNull(book.records[RecordKind.YACHTS_MATCH])
        assertNull(book.records[RecordKind.COMEBACK_POINTS])
        assertEquals(260.0, book.records[RecordKind.BEST_GAME]!!.value)
        assertEquals(128.0, book.records[RecordKind.MARGIN]!!.value)
        // A's Yacht in both games: a run of two going on.
        assertEquals("a" to 2.0, book.records[RecordKind.YACHT_RUN]!!.let { it.holder to it.value })
        assertEquals(2 to 1, book.yachtRun("a") to book.yachtRun("b"))
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
    fun onlyLeaguePlayersHoldCareerRecordsAndNewRecordsSettleQuietly() {
        val book = StatsBook(recordWarmup = 10)
        val ctx = StatsContext(1, { 1500.0 })
        // The 기준 경기 anchor plays friendlies all season: rated, counted in its career, holding nothing.
        repeat(50) { i -> book.record(job("ev", "p${i % 5}", GameScore(250, 200), GameScore(240, 210), comp = Comp.FRIENDLY), ctx) }
        assertEquals(100, book.career("ev")!!.games)
        assertNull(book.records[RecordKind.CAREER_GAMES])
        assertNull(book.records[RecordKind.STREAK])
        book.record(job("y", "z", GameScore(230, 200), GameScore(220, 210)), ctx)
        assertEquals("y", book.records[RecordKind.CAREER_GAMES]!!.holder)
        // An older league updated to these records: well past its warm-up, they start empty, and
        // the players who already passed them come in one by one this season without news.
        repeat(20) { book.record(job("y", "z", GameScore(230, 200), GameScore(220, 210)), ctx) }
        val text = book.encode().lines().filter { !it.startsWith("rec ") }.joinToString("\n")
        val old = StatsBook.decode(text, recordWarmup = 10)
        val events = ArrayList<LeagueEvent>()
        events += old.record(job("p1", "q", GameScore(230, 200), GameScore(220, 210)), ctx)
        events += old.record(job("y", "q", GameScore(230, 200), GameScore(220, 210)), ctx)
        assertEquals("y", old.records[RecordKind.CAREER_GAMES]!!.holder)
        assertTrue(events.none { it.kind == "record" }, events.toString())
        // Read back, it is still quiet this season.
        val again = StatsBook.decode(old.encode(), recordWarmup = 10)
        assertTrue(again.record(job("z", "q", GameScore(300, 100), GameScore(300, 100)), ctx).none { it.kind == "record" && it.args[0] == RecordKind.CAREER_GAMES.name })
    }

    @Test
    fun championAgesAreReadOnceForEarlierSeasons() {
        val book = StatsBook()
        val h = HonourBook().apply { add(listOf(Honour(1, "L0", "a"), Honour(2, "L0", "b"))) }
        book.honourRecords(h, 2) { k, _ -> if (k == "a") 24 else 30 }
        assertEquals("a" to 24.0, book.records[RecordKind.YOUNGEST_CHAMPION]!!.let { it.holder to it.value })
        // With ages standing still, the same players' title ages read lower every season: earlier
        // titles are not read again.
        h.add(listOf(Honour(3, "L0", "b")))
        book.honourRecords(h, 3) { k, _ -> if (k == "a") 20 else 29 }
        assertEquals("a" to 24.0, book.records[RecordKind.YOUNGEST_CHAMPION]!!.let { it.holder to it.value })
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
        // Ten 2-0 wins: 3 points each and the sweep bonus (판 승점제).
        assertEquals(Triple("ai9", 40.0, 2), lg.book.records[RecordKind.SEASON_POINTS]!!.let { Triple(it.holder, it.value, it.season) })
        assertTrue(s2.events.any { it.kind == "record" && it.args.take(2) == listOf("SEASON_POINTS", "40") }, s2.events.filter { it.kind == "record" }.toString())
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
        // 평점 2.0 on the fallback scale (too few games for the league's own): a match won 220-180
        // and 215-185 is worth 6.255 + 6.52 (first in game 1) or 6.675 + 6.1 (second in it).
        assertEquals(RatingScale.FALLBACK, lg.book.ratingScale)
        assertEquals(6.3875, l.avgRating, 0.006)
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
        assertTrue(review.sentences.any { it.startsWith("MVP는 평균 평점 6.39의 선수1이에요") }, review.sentences.toString())
        assertTrue(review.sentences.any { it == "1부 상위 5위 중 5자리를 기대점수형 계열이 차지했어요." }, review.sentences.toString())
        // A record broken: its value in brackets after the record's name, not after the verb.
        val said = s2.review!!.sentences
        assertTrue("선수9가 한 시즌 최다 승점 기록(승점 40)을 새로 썼어요." in said, said.toString())
        // 최다 승점 and 최다 완승 (a new division: every win a 2-0), 최다 우승 and 최다 MVP (ai1's second).
        assertTrue("이번 시즌 깨진 리그 기록은 모두 4개예요." in said, said.toString())
        assertEquals(
            setOf(RecordKind.SEASON_POINTS, RecordKind.SEASON_SWEEPS, RecordKind.TITLES, RecordKind.MVPS),
            s2.review!!.records.map { it.record.kind }.toSet(),
        )
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
        // The 13 digits of a line as before; its league and club apart (sx), from a national season on.
        val packed = a.encode().lines().single { it.startsWith("sl k ") }
        assertEquals("sl k 1 2" + "0e" + "09" + "01" + "0c", packed.take(16))
        a.addLine("k", SeasonLine(4, 0, 1, 1, 1, 1, 1500.0, 7.0, "KR", "c12"))
        assertEquals(packed.length + 13, a.encode().lines().single { it.startsWith("sl k ") }.length)
        // Season 4, 한국 리그 (code 1), club 12 ("0c").
        assertEquals("sx k 0410c", a.encode().lines().single { it.startsWith("sx ") })
        assertEquals(SeasonLine(4, 0, 1, 1, 1, 1, 1500.0, 7.0, "KR", "c12"), Archive.decode(a.encode()).lines("k").last())
        for (n in 5..40) a.addLine("k", SeasonLine(n, 0, 1, 1, 1, 1, 1500.0, 7.0))
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
    fun theArchiveKeepsNoHighlightLines() {
        // A season's best games are the review's (ids) and the log archive's: "hl" lines go, old ones too.
        val a = Archive()
        a.putHighlights(4, listOf("a b c", "d e"))
        assertTrue(a.highlights(4).isEmpty())
        val old = Archive.decode("archive 1\nhl 4 a b c\nhl 4 d e\n")
        assertTrue(old.highlights(4).isEmpty())
        assertTrue(old.encode().lines().none { it.startsWith("hl ") })
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
            // Every AI has a line in each of the newest 30 seasons.
            if (n > 70) for (k in keys) archive.addLine(k, SeasonLine(n, rng.nextInt(4), 1 + rng.nextInt(100), rng.nextInt(60), rng.nextInt(10), rng.nextInt(60), 1200.0 + rng.nextInt(400), 5 + rng.nextDouble() * 3))
        }
        repeat(Archive.MAX_HALL_OF_FAME) { archive.induct(HallOfFameEntry(keys[it], "명예의 전당 선수 $it", it / 3 + 1, "TITLES", 3, 6000, 0.61)) }
        repeat(Archive.MAX_DYNASTIES) { archive.putDynasty(Dynasty(keys[it % 400], Awards.RUN, "L0", 2 + it % 5, it / 3, it / 3 + 2)) }
        val sentences = List(9) { "이번 시즌을 정리하는 문장 $it: 선수 이름과 숫자가 들어간 꽤 긴 한국어 문장이에요." }
        // The kept reviews, every part as large as it gets (28 leagues in the ranking's moves).
        for (n in 95..100) {
            archive.putReview(
                SeasonReview(
                    n, List(12) { Honour(n, "L$it", key()) }, List(5) { Honour(n, "MVP", key()) }, List(3) { RatingMove(key(), 1400.0, 1450.0) },
                    List(3) { RatingMove(key(), 1450.0, 1400.0) }, archive.summary(n)!!.records, emptyList(), sentences,
                    SeasonReview.REVIEW_STATS.flatMap { s -> List(SeasonReview.LEADERS) { ReviewLeader(s, key(), 241.3456) } },
                    List(8) { "숫자 $it" to "12,345판" }, List(3) { "$n:C:${1000 + it}:0:1" },
                    ReviewGame(key(), key(), 9.875, "$n:L:12345:0:1"), ReviewGame(key(), key(), 412.0, "$n:C:777:0"),
                    Nations.ALL.take(28).map { it.code to -12 }, List(3) { "‘자동 승강 인원 2명 → 1명’ 가결 (찬성 7, 반대 2)" }, 14,
                ),
            )
        }
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
        a.putReview(SeasonReview(3, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), transfers = 17))
        val arch = a.encode()
        assertEquals(17, Archive.decode(arch).review(3)?.transfers)
        // Cut to "...xfer 1": not 1 transfer.
        assertNull(Archive.decode(arch.dropLast(2)).review(3)?.transfers)
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
    fun theYachtRunCountsOfficialGamesInARow() {
        // 한 경기 최다 요트 is retired (2 of 2 games within a season): the run of games with a Yacht replaces it.
        val book = StatsBook()
        val ctx = StatsContext(1, { 1500.0 })
        val y = GameScore(250, 200, aYacht = true)
        // A two-legged tie with A's Yacht in all four games.
        val tie = Fixture(Comp.CHAMPIONS, 1, "a", "b", 0L, kind = JobKind.TIE, legs = 2)
        book.record(JobResult(tie, listOf(BoardResult("a", "b", List(4) { y }, emptyList())), 1, 0), ctx)
        assertEquals("a" to 4.0, book.records[RecordKind.YACHT_RUN]!!.let { it.holder to it.value })
        assertNull(book.records[RecordKind.YACHTS_MATCH])
        // A friendly neither extends nor ends it; a game without a Yacht ends it.
        book.record(JobResult(tie.copy(comp = Comp.FRIENDLY), listOf(BoardResult("a", "b", List(2) { y }, emptyList())), 1, 0), ctx)
        assertEquals(4, book.yachtRun("a"))
        book.record(JobResult(Fixture(Comp.LEAGUE, 2, "a", "c", 0L), listOf(BoardResult("a", "c", listOf(GameScore(200, 190)), emptyList())), 1, 0), ctx)
        assertEquals(0, book.yachtRun("a"))
        assertEquals(4.0, book.records[RecordKind.YACHT_RUN]!!.value)
        // The run going on is saved with the career line.
        book.record(JobResult(Fixture(Comp.LEAGUE, 3, "c", "d", 0L), listOf(BoardResult("c", "d", listOf(y, y, y), emptyList(), deciders = 1)), 1, 0), ctx)
        assertEquals(3, book.yachtRun("c"))
        assertEquals(3, StatsBook.decode(book.encode()).yachtRun("c"))
        assertEquals(book.encode(), StatsBook.decode(book.encode()).encode())
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

    @Test
    fun aLegacyLeagueClosesAsBefore() {
        // Hashes first taken from the code before national leagues reached the records (this test
        // on it), then again with 평점 2.0 and records v2 (S15), whose changes to these closes are
        // only those: other MVPs where the new match rating ranks differently, the records the
        // stage adds (경기당 승점, 완승, 평점 2.0, 연속 요트) and retires (승리 확률 역전승, 한 경기
        // 요트), the holders' history, the review's new parts and the stats file's rating lines.
        // Honours, titles, the hall of fame, dynasties and every job are as before, and so is every
        // season line but its average 평점.
        val pyramid = PlayedLeague(600, LeagueConfig(), 11L).apply { play(3) }
        assertEquals("c69373415f90df425d16c009ddf3a94dc28d18bd8b57fb59fa77602fde8d5e99", pyramid.sha(pyramid.closes.toString()))
        val linear = PlayedLeague(60, LeagueConfig(divisions = 3, matchFormat = MatchFormat.MIRRORED, doubleElimination = false, longKnockouts = false), 12L).apply { play(3) }
        assertEquals("4cd152eb64dbbbe7d68572f376b84e9a1ea972d5afde369fa870c3e71287b0f8", linear.sha(linear.closes.toString()))
    }
}

/**
 * A league (one legacy league: linear divisions or a pyramid; national leagues with a [nation]
 * per player and `config.national`) played with made-up games that carry facts, every
 * competition on, closed season by season as [LeagueWorld] closes it: what the records, awards
 * and media make of it is written into [closes] (the golden tests of a legacy league's close and
 * odds), each season's plan into [plans] and its close into [ends].
 */
internal class PlayedLeague(players: Int, val config: LeagueConfig, val seed: Long, private val nation: (String) -> String? = { null }) {
    private val rules = RuleSet()
    private fun index(k: String) = k.removePrefix("p").toInt()
    var keys: List<String> = (1..players).map { "p%04d".format(it) }
        private set

    /** Rating points gained since the start (a few players grow every season). */
    private val drift = HashMap<String, Double>()
    fun rating(k: String): Double = 2000.0 - 2 * (index(k) % 1000) + (drift[k] ?: 0.0)

    val book = StatsBook()
    val archive = Archive()
    val honours = HonourBook()
    val closes = StringBuilder()

    /** Called halfway through each season's league stage and once it is over (before the post-season). */
    var onHalf: (SeasonPlan) -> Unit = {}
    var onEnd: (SeasonPlan) -> Unit = {}

    /** Called with every fixture before its result is recorded. */
    var onJob: (SeasonPlan, Fixture) -> Unit = { _, _ -> }

    /** What the close is told: each player's club, and which league titles are major. */
    var club: (String) -> String? = { null }
    var major: (season: Int, league: String) -> Boolean = { _, _ -> true }

    /** Offered every game when set (the review names the season's best). */
    var highlights: HighlightBook? = null

    val plans = ArrayList<SeasonPlan>()
    val ends = ArrayList<SeasonEnd>()

    /** Each season's honours as the off-season gave them (titles, no awards). */
    val seasonHonours = ArrayList<List<Honour>>()

    private val cards = HashMap<Int, Board>()
    private fun card(total: Int): Board = cards.getOrPut(total) { Board.of(rules, Category.ALL.map { if (it == Category.CHOICE) total else 0 }) }

    private val factCache = HashMap<Triple<Int, Int, Int>, GameFacts>()

    /** Facts of a game the first player ends on [x] and the second on [y]; [shape] 0 steady, 1 a comeback, 2 a seesaw. */
    private fun facts(x: Int, y: Int, shape: Int): GameFacts = factCache.getOrPut(Triple(x, y, shape)) {
        val end = if (x > y) 100 else if (x < y) 0 else 50
        val curve = ByteArray(24) { h ->
            val t = (h + 1) / 24.0
            val p = when (shape) {
                1 -> if (h < 16) 50 + (if (end >= 50) -1 else 1) * (35 * (h + 1) / 16.0) else 50 + (end - 50) * (h - 15) / 8.0
                2 -> 50 + 30 * (if (h % 6 < 3) 1 else -1) * (1 - t) + (end - 50) * t
                else -> 50 + (end - 50) * t
            }
            p.toInt().coerceIn(0, 100).toByte()
        }
        val totals = ShortArray(48)
        totals[46] = x.toShort()
        totals[47] = y.toShort()
        GameFacts(card(x), card(y), totals, curve)
    }

    /** [n] games of [a] against [b] from [rng]: the better rated wins more often; facts with every game. */
    private fun board(a: String, b: String, n: Int, rng: Random): BoardResult {
        val games = ArrayList<GameScore>(n)
        val fs = ArrayList<GameFacts>(n)
        for (i in 0 until n) {
            val edge = (rating(a) - rating(b)) / 400.0
            val aWins = rng.nextDouble() < 0.5 + edge.coerceIn(-0.3, 0.3)
            val level = rng.nextInt(25) == 0
            val hi = 190 + rng.nextInt(120)
            val lo = (hi - 1 - rng.nextInt(70)).coerceAtLeast(60)
            val (sa, sb) = if (level) hi to hi else if (aWins) hi to lo else lo to hi
            games += GameScore(sa, sb, aYacht = rng.nextInt(9) == 0, bYacht = rng.nextInt(9) == 0)
            // A moves first in even games (mirrored matches alternate).
            val shape = rng.nextInt(3)
            fs += if (i % 2 == 0) facts(sa, sb, shape) else facts(sb, sa, shape)
        }
        return BoardResult(a, b, games, fs)
    }

    fun play(f: Fixture): JobResult {
        val rng = Random(f.seed xor 0x5EED)
        return when (f.kind) {
            JobKind.MATCH -> board(f.a, f.b, 2, rng).let { JobResult(f, listOf(it), TieRule.match(it.games, config.matchFormat), 1000L, config.matchFormat) }
            JobKind.TIE -> board(f.a, f.b, 2 * maxOf(1, f.legs), rng).let { JobResult(f, listOf(it), TieRule.match(it.games).let { w -> if (w == 0) 1 else w }, 1000L) }
            JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> board(x, y, 2, rng) }.let { JobResult(f, it, TieRule.team(it).let { w -> if (w == 0) 1 else w }, 1000L) }
        }
    }

    private fun family(k: String): Family = Family.entries[index(k) % Family.entries.size]

    private fun context(): Offseason.Context =
        Offseason.Context(keys, ::rating, ::family, { k -> "도시${index(k) % 7}" }, config, Competitions.factories(), honours.all, nation = nation)

    private fun line(s: String) = closes.append(s).append('\n')

    /** Plays and closes [seasons] seasons. */
    fun play(seasons: Int) {
        var plan = Offseason.first(seed, context())
        repeat(seasons) { s ->
            plans += plan
            book.startSeason(plan.number, keys.associateWith { rating(it) })
            val half = plan.league.fixturesTotal / 2
            var halfDone = false
            var guard = 0
            while (!plan.finished) {
                if (plan.league.finished && !plan.postStarted) {
                    onEnd(plan)
                    plan.startPost(Competitions.postFactories())
                }
                val f = plan.next(emptySet(), 0, 1) ?: break
                onJob(plan, f)
                val r = play(f)
                for (e in book.record(r, StatsContext(plan.number, ::rating, { k -> "도시${index(k) % 7}" }))) line("rec ${e.encode()}")
                highlights?.offer(r, plan.number, ::rating)
                plan.record(r)
                line("job ${f.comp.code} ${f.id} ${f.label} ${Drama.isFinal(f)} ${Drama.titleRace(plan.league, f)}")
                if (!halfDone && plan.league.fixturesPlayed >= half) {
                    halfDone = true
                    onHalf(plan)
                }
                check(++guard < 2_000_000)
            }
            check(plan.finished)
            // A few grow, one leaves, and newcomers arrive for the next season.
            for (k in keys) if (index(k) % 13 == s) drift[k] = (drift[k] ?: 0.0) + 60.0
            val gone = setOf(keys[keys.size / 2])
            val moves = Offseason.moves(plan, gone)
            val h = Offseason.honours(plan, moves, gone)
            val end = book.endSeason(
                plan, h, honours, archive, ::rating, ::family, { k -> "선수${index(k)}" }, traits = { k -> mapOf("버릇" to "${index(k) % 3}") }, gone = gone,
                club = club, major = major, highlights = highlights,
            )
            ends += end
            seasonHonours += h
            val picks = plan.league.playersOfWeek(0 until plan.league.matchdays, gone).values.sortedBy { it.matchday }.map { Honour(plan.number, "POTW", it.key) }
            honours.add(h + end.awards + picks)
            line("season ${plan.number}")
            line("honours ${h.joinToString(",") { it.encode() }}")
            line("awards ${end.awards.joinToString(",") { it.encode() }}")
            for (e in end.events) line("event ${e.encode()}")
            line("inducted ${end.inducted}")
            line("dynasties ${end.dynasties}")
            line("review ${end.review}")
            for (e in book.honourRecords(honours, plan.number) { k, n -> 20 + n + index(k) % 15 }) line("hrec ${e.encode()}")
            line(archive.encode())
            line(book.encode())
            val newcomers = (1..6).map { "p%04d".format(1000 * (s + 1) + it) }
            keys = keys.filter { it !in gone } + newcomers
            plan = Offseason.next(plan, context()).first
        }
    }

    fun sha(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
