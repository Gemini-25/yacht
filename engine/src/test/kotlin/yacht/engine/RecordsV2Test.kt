package yacht.engine

import yacht.engine.league.AiStats
import yacht.engine.league.BoardResult
import yacht.engine.league.ChaseLine
import yacht.engine.league.Comp
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameScore
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MatchRef
import yacht.engine.league.RatingScale
import yacht.engine.league.RecordChase
import yacht.engine.league.RecordKind
import yacht.engine.league.Records
import yacht.engine.league.StatsBook
import yacht.engine.league.StatsContext
import yacht.engine.league.TieRule
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Records v2 (design UX 4.8, plan amendments 5-7): the records that could no longer be broken
 * retire, a comeback holds the points it overturned, the Yacht run, points per match and sweeps
 * of a season, the holders before each record, record values with their units, 평점 2.0 measured
 * against the season before, and the record chase.
 */
class RecordsV2Test {
    private val rules = RuleSet()

    /** A card with its whole [total] in Choice ([yacht]: 50 of it in the Yacht box). */
    private fun flat(total: Int, yacht: Boolean = false): Board =
        Board.of(rules, Category.ALL.map { if (it == Category.CHOICE) total - (if (yacht) 50 else 0) else if (it == Category.YACHT && yacht) 50 else 0 })

    /**
     * Facts of a game the first player ends on [x] and the second on [y], [lead] the first
     * player's lead after each full turn (12 values: negative while it trails), P(win) [curve].
     */
    private fun facts(x: Int, y: Int, lead: List<Int> = List(12) { 0 }, curve: (Int) -> Int = { 50 }, yachtX: Boolean = false): GameFacts {
        val totals = ShortArray(48)
        for (t in 0 until 12) {
            val h = 2 * t + 1
            // The second player on 100 + 10 t, the first that plus its lead.
            totals[2 * h + 1] = (100 + 10 * t).toShort()
            totals[2 * h] = (100 + 10 * t + lead[t]).toShort()
        }
        totals[46] = x.toShort()
        totals[47] = y.toShort()
        return GameFacts(flat(x, yachtX), flat(y), totals, ByteArray(24) { curve(it).toByte() })
    }

    /** A one-game league match of [a] (first) against [b]. */
    private fun game(id: Int, a: String, b: String, f: GameFacts, comp: Comp = Comp.LEAGUE): JobResult {
        val g = listOf(GameScore(f.first.total, f.second.total, aYacht = (f.first[Category.YACHT] ?: 0) > 0))
        return JobResult(Fixture(comp, id, a, b, id.toLong()), listOf(BoardResult(a, b, g, listOf(f))), TieRule.match(g), 0)
    }

    @Test
    fun theRetiredRecordsAreKeptButNeverOfferedAgain() {
        val r = Records()
        r.put(LeagueRecord(RecordKind.COMEBACK, "old", 0.0, 1))
        r.put(LeagueRecord(RecordKind.YACHTS_MATCH, "old", 2.0, 1))
        r.put(LeagueRecord(RecordKind.SEASON_RATING, "old", 8.31, 2))
        for (k in listOf(RecordKind.COMEBACK, RecordKind.YACHTS_MATCH, RecordKind.SEASON_RATING)) {
            assertTrue(k.retired)
            assertFalse(r.beats(k, if (k.lowerIsBetter) -1.0 else 99.0))
            assertNull(r.offer(LeagueRecord(k, "new", if (k.lowerIsBetter) -1.0 else 99.0, 3)))
            assertEquals("old", r[k]?.holder)
            assertTrue(k !in RecordKind.current)
        }
        assertEquals(3, r.all.size)
        assertTrue(r.current.isEmpty())
        // Read back from stats.txt as kept.
        val book = StatsBook()
        book.records.put(LeagueRecord(RecordKind.SEASON_RATING, "old", 8.31, 2))
        assertEquals(8.31, StatsBook.decode(book.encode()).records[RecordKind.SEASON_RATING]?.value)
        assertEquals("한 시즌 최고 평균 평점 (구 평점)", RecordKind.SEASON_RATING.label)
    }

    @Test
    fun aRecordIsBrokenOnlyByABetterValue() {
        for (k in RecordKind.current) {
            val r = Records()
            val first = if (k.lowerIsBetter) 100.0 else 10.0
            assertNotNull(r.offer(LeagueRecord(k, "a", first, 1)))
            // Level or worse: no break (the first to reach a value keeps it).
            assertNull(r.offer(LeagueRecord(k, "b", first, 1)), k.name)
            assertNull(r.offer(LeagueRecord(k, "b", if (k.lowerIsBetter) first + 1 else first - 1, 1)), k.name)
            val better = if (k.lowerIsBetter) first - 1 else first + 1
            val b = assertNotNull(r.offer(LeagueRecord(k, "b", better, 2)), k.name)
            assertEquals(first, b.previous?.value)
        }
        // Only the lowest winning score and the youngest champion go down.
        assertEquals(setOf(RecordKind.LOW_WIN, RecordKind.YOUNGEST_CHAMPION), RecordKind.current.filter { it.lowerIsBetter }.toSet())
    }

    @Test
    fun aComebackRecordHoldsThePointsTheWinnerOverturned() {
        // News from the second official game (the first sets the records quietly).
        val book = StatsBook(recordWarmup = 2)
        val ctx = StatsContext(1, { 1500.0 })
        val events = ArrayList<LeagueEvent>()
        // The first player trails by 87 after turn 6 and wins 250-240.
        events += book.record(game(0, "a", "b", facts(250, 240, List(12) { t -> if (t == 6) -87 else -10 })), ctx)
        val c = assertNotNull(book.records[RecordKind.COMEBACK_POINTS])
        assertEquals(Triple("a", 87.0, "b"), Triple(c.holder, c.value, c.other))
        assertEquals("87점 뒤지다 역전", RecordKind.COMEBACK_POINTS.text(87.0))
        // The second player trailing by 120 and winning: a bigger comeback, an article with the
        // values going up.
        events += book.record(game(1, "c", "d", facts(200, 260, List(12) { t -> if (t == 3) 120 else 5 })), ctx)
        assertEquals("d" to 120.0, book.records[RecordKind.COMEBACK_POINTS]!!.let { it.holder to it.value })
        val e = events.single { it.kind == "record" && it.args[0] == RecordKind.COMEBACK_POINTS.name }
        assertEquals(listOf("COMEBACK_POINTS", "120", "87"), e.args)
        // A loser that led, a draw, a smaller comeback: nothing.
        book.record(game(2, "e", "f", facts(240, 240, List(12) { -150 })), ctx)
        book.record(game(3, "g", "h", facts(230, 250, List(12) { -130 })), ctx)
        book.record(game(4, "i", "j", facts(260, 250, List(12) { -60 })), ctx)
        assertEquals("d" to 120.0, book.records[RecordKind.COMEBACK_POINTS]!!.let { it.holder to it.value })
        // The P(win) record is retired: a winner from 0% sets nothing.
        book.record(game(5, "k", "l", facts(270, 260, curve = { h -> if (h < 20) 0 else 100 })), ctx)
        assertNull(book.records[RecordKind.COMEBACK])
    }

    @Test
    fun aSeasonsPointsPerMatchAndSweepsAreRecordsAtTheClose() {
        val lg = PlayedLeague(60, LeagueConfig(divisions = 3), 31L).apply { play(1) }
        val plan = lg.plans.single()
        val league = plan.league
        assertTrue(league.bonus)
        // The players still in the league (one left during the season: no record of its).
        val table = league.standings.values.filter { it.key in lg.keys && it.played > 0 && it.played >= 0.6 * league.fixturesOf(it.key) }
        val ppm = assertNotNull(lg.book.records[RecordKind.SEASON_PPM])
        assertEquals(table.maxOf { Math.round(100.0 * it.points / it.played) / 100.0 }, ppm.value)
        val sweeps = assertNotNull(lg.book.records[RecordKind.SEASON_SWEEPS])
        assertEquals(league.standings.values.filter { it.key in lg.keys }.maxOf { it.sweeps }.toDouble(), sweeps.value)
        assertEquals("경기당 승점 ${RecordKind.SEASON_PPM.plain(ppm.value)}", RecordKind.SEASON_PPM.text(ppm.value))
        // A season without the bonus has no sweep record.
        val mirrored = PlayedLeague(60, LeagueConfig(divisions = 3, matchFormat = yacht.engine.league.MatchFormat.MIRRORED), 31L).apply { play(1) }
        assertFalse(mirrored.plans.single().league.bonus)
        assertNull(mirrored.book.records[RecordKind.SEASON_SWEEPS])
    }

    @Test
    fun theHoldersBeforeAreKeptThreeDeep() {
        val r = Records()
        for ((i, k) in listOf("a", "b", "c", "d", "e").withIndex()) r.offer(LeagueRecord(RecordKind.BEST_GAME, k, 300.0 + i, i + 1))
        assertEquals("e", r[RecordKind.BEST_GAME]?.holder)
        assertEquals(listOf("d", "c", "b"), r.history(RecordKind.BEST_GAME).map { it.holder })
        // A run its holder extends stays one entry; another holder taking it over is history.
        for (n in 1..5) r.offer(LeagueRecord(RecordKind.STREAK, "x", n.toDouble(), 1))
        assertTrue(r.history(RecordKind.STREAK).isEmpty())
        r.offer(LeagueRecord(RecordKind.STREAK, "y", 6.0, 2))
        assertEquals(listOf("x" to 5.0), r.history(RecordKind.STREAK).map { it.holder to it.value })
        // A holder that beats its own game record leaves its old one behind; its own titles count on.
        r.offer(LeagueRecord(RecordKind.BEST_GAME, "e", 310.0, 6))
        assertEquals(listOf("e", "d", "c"), r.history(RecordKind.BEST_GAME).map { it.holder })
        for (n in 1..3) r.offer(LeagueRecord(RecordKind.TITLES, "t", n.toDouble(), n))
        assertTrue(r.history(RecordKind.TITLES).isEmpty())
        // Saved and read back with stats.txt.
        val book = StatsBook()
        for ((i, k) in listOf("a", "b", "c", "d").withIndex()) book.records.offer(LeagueRecord(RecordKind.MARGIN, k, 100.0 + i, i + 1, "o$i", Comp.CUP))
        val back = StatsBook.decode(book.encode())
        assertEquals(book.records.history(RecordKind.MARGIN), back.records.history(RecordKind.MARGIN))
        assertEquals(listOf("c", "b", "a"), back.records.history(RecordKind.MARGIN).map { it.holder })
        assertEquals(book.encode(), back.encode())
    }

    @Test
    fun aRecordsValueCarriesItsUnit() {
        assertEquals("레이팅 차 514", RecordKind.UPSET.withUnit(514.0))
        assertEquals("레이팅 1,820", RecordKind.PEAK_RATING.withUnit(1820.0))
        assertEquals("87점 차", RecordKind.COMEBACK_POINTS.withUnit(87.0))
        assertEquals("345점", RecordKind.BEST_GAME.withUnit(345.0))
        assertEquals("17연승", RecordKind.STREAK.withUnit(17.0))
        assertEquals("평점 7.85", RecordKind.SEASON_RATING2.withUnit(7.85))
        assertEquals("요트 2,345개", RecordKind.CAREER_YACHTS.withUnit(2345.0))
        for (k in RecordKind.entries) {
            val s = k.withUnit(12.0)
            assertTrue(s.any { it.isDigit() } && s.any { !it.isDigit() && it != '.' && it != ',' }, "$k: $s")
            assertTrue(k.label.isNotBlank() && k.text(12.0).isNotBlank(), k.name)
        }
    }

    // ---- 평점 2.0 --------------------------------------------------------------------------------

    @Test
    fun aMatchRatingFollowsTheFormula() {
        val scale = RatingScale(200.0, 40.0, 0.1)
        // First player: 260 with a Yacht, a win from 15%.
        val f = facts(260, 200, curve = { h -> if (h == 0) 15 else if (h < 23) 50 else 100 }, yachtX = true)
        val (w1, w2) = f.wpa
        val (r1, r2) = f.ratings(scale)
        val p1 = GameFacts.WPA * w1 + GameFacts.RESULT + GameFacts.YACHT + GameFacts.COMEBACK
        assertEquals(p1, GameFacts.parts(260, 200, w1, 0.15, yacht = true, bonus = false), 1e-9)
        val raw1 = RatingScale.BASE + GameFacts.Z * (260 - 200) / 40.0 + p1 - 0.1
        assertEquals(Math.round(raw1 * 200) / 200.0, r1, 1e-9)
        val raw2 = RatingScale.BASE + GameFacts.Z * 0.0 + GameFacts.WPA * w2 - GameFacts.RESULT - 0.1
        assertEquals(Math.round(raw2 * 200) / 200.0, r2, 1e-9)
        // In steps of 0.005, within [1, 10]; 300 points add the big-score part.
        assertEquals(0.0, abs(r1 * 200 - Math.round(r1 * 200)), 1e-9)
        assertEquals(10.0, GameFacts.rating(400, 5.0, scale))
        assertEquals(1.0, GameFacts.rating(20, -1.0, scale))
        assertEquals(GameFacts.BIG, GameFacts.parts(300, 100, 0.0, 0.5, false, false) - GameFacts.parts(299, 100, 0.0, 0.5, false, false), 1e-9)
        // The first rating stays as it was for a season begun on it.
        assertEquals(GameFacts.rating(w1) to GameFacts.rating(w2), f.ratings(RatingScale.V1))
    }

    /** [n] one-game matches whose first player scores 160 + i % 80 against 150 (no comebacks). */
    private fun season(book: StatsBook, n: Int, from: Int = 0) {
        val ctx = StatsContext(book.season, { 1500.0 })
        for (i in from until from + n) book.record(game(i, "p${i % 20}", "q${i % 17}", facts(160 + i % 80, 150)), ctx)
    }

    @Test
    fun theScaleIsTheSeasonBeforesAndTheFirstSeasonsOwn() {
        val book = StatsBook()
        book.startSeason(1, emptyMap())
        // A first season: the fallback until 1000 official side-games, then its own games so far.
        season(book, 400)
        assertEquals(RatingScale.FALLBACK, book.ratingScale)
        season(book, 200, from = 400)
        val running = book.ratingScale
        assertTrue(running != RatingScale.FALLBACK && running.version == 2)
        // Its own games: about 165 on average (the first players' 160-239 and the second's 150).
        assertTrue(running.mean in 160.0..200.0, running.toString())
        // A restart reads the same scale (the games so far are saved exactly).
        assertEquals(running, StatsBook.decode(book.encode()).ratingScale)
        // The season closes (no plan here: the scale is kept for the next start as measured).
        val measured = book.ratingScale
        season(book, 200, from = 600)
        val atClose = book.ratingScale
        assertTrue(atClose != measured)
        // The next season is measured against the closed one's games, all season long.
        book.startSeason(2, emptyMap())
        assertEquals(atClose, book.ratingScale)
        season(book, 500, from = 800)
        assertEquals(atClose, book.ratingScale)
        val back = StatsBook.decode(book.encode())
        assertEquals(atClose, back.ratingScale)
        assertEquals(book.encode(), back.encode())
        // Every season's average rating stays near 6.0 (the scale centers it).
        val all = (0 until 20).mapNotNull { book.season("p$it") } + (0 until 17).mapNotNull { book.season("q$it") }
        val avg = all.sumOf { it.ratingSum } / all.sumOf { it.ratingCount }
        assertTrue(abs(avg - RatingScale.BASE) < 0.25, "average $avg")
    }

    @Test
    fun aSeasonBegunOnTheFirstRatingEndsOnIt() {
        // A stats.txt from before 평점 2.0 in the middle of a season: no "rating" line.
        val old = StatsBook()
        old.startSeason(4, emptyMap())
        season(old, 50)
        val text = old.encode().lines().filter { !it.startsWith("rating ") && !it.startsWith("racc ") }.joinToString("\n")
        val book = StatsBook.decode(text)
        assertEquals(RatingScale.V1, book.ratingScale)
        // Its games go on with the first rating (one scale for the season's averages and MVP).
        val f = facts(230, 150, curve = { h -> 50 + h })
        val before = book.career("p0")!!.ratingCount
        val sum = book.season("p0")!!.ratingSum
        book.record(game(99, "p0", "q0", f), StatsContext(4, { 1500.0 }))
        assertEquals(GameFacts.rating(f.wpa.first), book.season("p0")!!.ratingSum - sum, 1e-9)
        assertEquals(before + 1, book.career("p0")!!.ratingCount)
        assertEquals(RatingScale.V1, StatsBook.decode(book.encode()).ratingScale)
        // The next season is on 평점 2.0, and careers' averages start again on it.
        book.startSeason(5, emptyMap())
        assertEquals(2, book.ratingScale.version)
        assertEquals(0, book.career("p0")!!.ratingCount)
        // A file from before 평점 2.0 between seasons starts on it at once.
        val closed = StatsBook()
        closed.startSeason(1, emptyMap())
        season(closed, 50)
        closed.putCareer("z", AiStats().also { it.ratingSum = 65.0; it.ratingCount = 10 })
        val between = closed.encode().lines().filter { !it.startsWith("rating ") && !it.startsWith("racc ") && !it.startsWith("box ") }.joinToString("\n")
        val fresh = StatsBook.decode(between)
        assertEquals(2, fresh.ratingScale.version)
        assertEquals(0, fresh.career("z")!!.ratingCount)
    }

    // ---- the record chase -------------------------------------------------------------------

    @Test
    fun theChaseListsTheNearestChallengers() {
        val book = StatsBook()
        book.startSeason(1, emptyMap())
        book.records.put(LeagueRecord(RecordKind.CAREER_YACHTS, "holder", 100.0, 1))
        book.records.put(LeagueRecord(RecordKind.STREAK, "holder", 12.0, 1))
        for ((k, yachts, streak) in listOf(Triple("holder", 100, 3), Triple("a", 97, 11), Triple("b", 90, 2), Triple("c", 40, 0), Triple("d", 99, 4))) {
            book.putCareer(k, AiStats().also { it.yachts = yachts; it.streak = streak; it.games = 500 })
        }
        val chase = RecordChase.of(book, null).toMap()
        // Career counts: what each still needs, the holder left out.
        val yachts = chase.getValue(RecordKind.CAREER_YACHTS)
        assertEquals(listOf("d", "a", "b"), yachts.map { it.key })
        assertEquals("통산 최다 요트까지 2개", yachts[0].text())
        assertEquals("통산 최다 요트까지 4개", yachts[1].text())
        assertTrue(RecordChase.near(yachts[0]) && !RecordChase.near(yachts[1]))
        // A run going on.
        val streak = chase.getValue(RecordKind.STREAK)
        assertEquals("a", streak.first().key)
        assertEquals("연승 기록까지 2연승", streak.first().text())
        // No season in play: no pace.
        assertTrue(RecordKind.SEASON_POINTS !in chase)
        // Paces and averages read the season's league.
        val line = ChaseLine(RecordKind.SEASON_POINTS, "x", 60.0, 103.0, 100.0, "y")
        assertEquals("기록 페이스 +3점", line.text())
        assertEquals("기록 페이스 -5점", line.copy(pace = 95.0).text())
        assertEquals("기록 대비 평점 -0.12", ChaseLine(RecordKind.SEASON_RATING2, "x", 7.0, Double.NaN, 7.12, "y").text())
        assertTrue(RecordChase.near(line) && !RecordChase.near(line.copy(pace = 95.0)))
    }

    @Test
    fun theChaseOfASeasonGoesByPace() {
        var chase: List<Pair<RecordKind, List<ChaseLine>>> = emptyList()
        val lg = PlayedLeague(60, LeagueConfig(divisions = 3), 41L)
        lg.onHalf = { plan -> chase = RecordChase.of(lg.book, plan, plan.league.standings.keys) }
        lg.play(2)
        // Halfway through season 2, the season's points record of season 1 is chased by pace.
        val points = chase.toMap().getValue(RecordKind.SEASON_POINTS)
        val plan = lg.plans[1]
        assertTrue(points.size in 1..RecordChase.TOP)
        assertTrue(points.zipWithNext().all { (x, y) -> x.gap >= y.gap })
        for (l in points) {
            assertTrue(l.paced && l.pace >= l.value && l.key in plan.league.standings, l.toString())
            assertTrue(l.text().startsWith("기록 페이스 "), l.text())
        }
        // Built in one pass a kind: cheap at any size.
        val t0 = System.nanoTime()
        repeat(20) { RecordChase.of(lg.book, plan, plan.league.standings.keys) }
        assertTrue((System.nanoTime() - t0) / 20 < 50_000_000L)
    }

    @Test
    fun aRefOfTheSeasonsBestRatedGameIsKept() {
        val book = StatsBook()
        book.startSeason(3, emptyMap())
        val ctx = StatsContext(3, { 1500.0 })
        book.record(game(7, "a", "b", facts(250, 150)), ctx)
        book.record(game(8, "c", "d", facts(330, 120, yachtX = true)), ctx)
        // Rated 10.0 too, but further above it before the bounds: the better game.
        book.record(game(9, "e", "f", facts(380, 100)), ctx)
        book.record(game(10, "g", "h", facts(240, 230)), ctx)
        val text = book.encode()
        val top = text.lines().single { it.startsWith("top ") }.split(' ')
        assertEquals("e" to 10.0, top[1] to top[3].toDouble())
        assertEquals(MatchRef(3, Comp.LEAGUE, 9, 0, 0), MatchRef.decode(yacht.engine.league.Field.unesc(top[4])))
        assertEquals(text, StatsBook.decode(text).encode())
        // After a restart a game at 10.0 that is not as good leaves it.
        val back = StatsBook.decode(text)
        back.record(game(11, "i", "j", facts(340, 100)), ctx)
        assertEquals("e", back.encode().lines().single { it.startsWith("top ") }.split(' ')[1])
    }
}
