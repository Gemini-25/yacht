package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubLeague
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubMoveKind
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubNames
import yacht.engine.league.Comp
import yacht.engine.league.Emblem
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Leagues
import yacht.engine.league.Offseason
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.TieRule
import yacht.engine.league.Tiers
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClubsTest {
    private val cities = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "수원", "춘천", "청주", "전주", "포항", "창원", "제주", "강릉")

    private fun keys(n: Int) = (1..n).map { "ai%03d".format(it) }

    private fun <T> List<T>.pick(rng: Random): T = this[rng.nextInt(size)]

    /** ai001 is the strongest. */
    private fun strength(k: String) = 400 - k.filter { it.isDigit() }.toInt()

    private fun games(a: String, b: String, rng: Random): List<GameScore> {
        val d = strength(a) - strength(b)
        fun g(): GameScore = when {
            rng.nextInt(10) == 0 -> GameScore(180, 180)
            rng.nextInt(200) < 100 + d -> GameScore(190 + rng.nextInt(30), 160 + rng.nextInt(20))
            else -> GameScore(160 + rng.nextInt(20), 190 + rng.nextInt(30))
        }
        return listOf(g(), g())
    }

    private fun result(f: Fixture, rng: Random): JobResult {
        if (f.kind == JobKind.TEAM) {
            val boards = f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, rng), emptyList()) }
            return JobResult(f, boards, TieRule.team(boards), 0)
        }
        val g = games(f.a, f.b, rng)
        return JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0)
    }

    private fun assertWhole(book: ClubBook, alive: Set<String>, squad: Int? = null) {
        val all = book.clubs.flatMap { it.squad }
        assertEquals(all.size, all.toSet().size, "an AI in two clubs")
        assertTrue(all.all { it in alive }, "a member who left")
        for (c in book.clubs) {
            assertTrue(c.budget >= 0, "${c.id} budget ${c.budget}")
            if (squad != null) assertEquals(squad, c.squad.size, c.id)
            for (k in c.squad) assertEquals(c.id, book.clubOf(k))
        }
    }

    @Test
    fun creationIsABalancedSnakeDraft() {
        val ranking = keys(150)
        val book = ClubBook.create(ranking, 16, 6, 42L, cities)
        val clubs = book.clubs
        assertEquals((1..16).map { ClubBook.idOf(it) }, clubs.map { it.id })
        assertEquals("c01", clubs.first().id)
        val members = clubs.flatMap { it.squad }
        assertEquals(ranking.take(96).toSet(), members.toSet())
        assertWhole(book, ranking.toSet(), 6)
        // Every club gets one pick from each block of 16, and the snake evens the rank sums out.
        for (c in clubs) for (r in 0 until 6) assertEquals(1, c.squad.count { ranking.indexOf(it) / 16 == r }, "${c.id} round $r")
        assertEquals(1, clubs.map { c -> c.squad.sumOf { ranking.indexOf(it) } }.toSet().size)
        assertTrue(clubs.all { it.budget == ClubBook.START_BUDGET && it.honours == 0 })
        assertNull(book.clubOf("ai150"))
        // The draw decides who picks first: not always club 1.
        val firsts = (1L..8L).map { s -> ClubBook.create(ranking, 16, 6, s, cities).clubOf("ai001") }.toSet()
        assertTrue(firsts.size > 1, firsts.toString())
        // 16 clubs: each hometown once, every name different.
        assertEquals(cities.toSet(), clubs.map { it.city }.toSet())
        assertEquals(16, clubs.map { it.name }.toSet().size)
        // Too few AIs: the last squads are short.
        val few = ClubBook.create(keys(20), 4, 6, 1L, cities)
        assertEquals(20, few.clubs.sumOf { it.squad.size })
        assertTrue(few.clubs.all { it.squad.size in 5..6 })
    }

    @Test
    fun namesAndBadgesAreDeterministic() {
        val a = ClubNames.identity("c03", 7L, cities)
        assertEquals(a, ClubNames.identity("c03", 7L, cities))
        assertEquals("${a.city} ${a.mascot}", a.name)
        assertTrue(a.city in cities && a.mascot in ClubNames.MASCOTS)
        assertEquals(a.mascot.first(), a.emblem.initial)
        val ids = (1..24).map { ClubBook.idOf(it) }
        val all = ids.map { ClubNames.identity(it, 7L, cities) }
        assertEquals(24, all.map { it.name }.toSet().size)
        assertEquals(24, all.map { it.mascot }.toSet().size)
        assertTrue(all.groupBy { it.city }.values.all { it.size <= 2 })
        assertEquals(12, all.take(12).map { it.emblem.colour1 }.toSet().size)
        for (i in all) {
            assertTrue(i.emblem.shape in 0 until Emblem.SHAPES)
            assertNotEquals(i.emblem.colour1, i.emblem.colour2)
            assertEquals(0xFF, i.emblem.colour1 ushr 24)
            assertEquals(0xFF, i.emblem.colour2 ushr 24)
        }
        // Another seed, other clubs; the same seed, the same book.
        assertNotEquals(all.map { it.name }, ids.map { ClubNames.identity(it, 8L, cities).name })
        assertEquals(ClubBook.create(keys(100), 16, 6, 7L, cities).encode(), ClubBook.create(keys(100), 16, 6, 7L, cities).encode())
        // Club 3 is the same club in a league of 8 and of 24.
        assertEquals(ClubBook.create(keys(50), 8, 6, 7L, cities).club("c03")!!.name, ClubBook.create(keys(150), 24, 6, 7L, cities).club("c03")!!.name)
        // No cities: the mascot alone.
        assertEquals(ClubNames.identity("c01", 1L, emptyList()).mascot, ClubNames.identity("c01", 1L, emptyList()).name)
    }

    @Test
    fun aClubSeasonPlaysOutThroughTheSeasonPlan() {
        val keys = keys(60)
        val rating = { k: String -> 1100.0 + strength(k) }
        val book = ClubBook.create(keys.sortedByDescending(rating), 8, 6, 9L, cities)
        val config = LeagueConfig(divisions = 2, clubs = 8, squad = 6)
        val ctx = Offseason.Context(keys, rating, { null }, { "서울" }, config, listOf(ClubLeague.factory({ book })))
        val plan = Offseason.first(77L, ctx)
        val club = assertNotNull(plan.competition(Comp.CLUB) as? ClubLeague)
        assertEquals(7, club.rounds)
        assertEquals(28, club.fixturesTotal)
        for (c in book.clubs) assertEquals(c.squad.sortedByDescending(rating).take(5), club.lineups.getValue(c.id))
        val rng = Random(3)
        val inFlight = ArrayDeque<Fixture>()
        val busy = HashSet<String>()
        val events = ArrayList<LeagueEvent>()
        var saved: String? = null
        var savedLeft = 0
        var savedTable = club.table()
        var savedJobs = emptyList<Fixture>()
        var clubJobs = 0
        while (true) {
            while (inFlight.size < 6) {
                val f = plan.next(busy, 0, 1) ?: break
                assertTrue(f.players.none { it in busy })
                if (f.comp == Comp.CLUB) {
                    clubJobs++
                    assertEquals(JobKind.TEAM, f.kind)
                    assertTrue(f.rated)
                    assertEquals(5, f.lineupA.size)
                    assertEquals(club.lineups[f.a], f.lineupA)
                    val round = club.matches().first { it.id == f.id }.round
                    assertTrue(round < club.openRounds(plan.progress), "round $round open at ${plan.progress}")
                    assertEquals("${round + 1}라운드", f.label)
                }
                busy += f.players
                inFlight += f
            }
            if (saved == null && club.fixturesLeft in 1..20 && inFlight.any { it.comp == Comp.CLUB }) {
                saved = plan.encode()
                savedLeft = club.fixturesLeft
                savedTable = club.table()
                savedJobs = club.matches().filter { it.result == null }.map { club.job(it) }
            }
            val f = inFlight.removeFirstOrNull() ?: break
            busy -= f.players.toSet()
            events += plan.record(result(f, rng))
        }
        assertEquals(28, clubJobs)
        assertTrue(plan.league.finished && club.finished)
        val table = club.table()
        assertTrue(table.all { it.played == 7 && it.wins + it.draws + it.losses == 7 })
        assertEquals(0, table.sumOf { it.boardDiff })
        assertEquals(28 * 5, table.sumOf { it.boardsWon + it.boardsLost + it.boardsLevel } / 2)
        assertEquals(table.sumOf { it.wins }, table.sumOf { it.losses })
        assertEquals(table, table.sortedWith(compareByDescending<yacht.engine.league.ClubRow> { it.points }.thenByDescending { it.boardDiff }))
        assertEquals(listOf(LeagueEvent("title", 1, listOf(table[0].club), listOf("K"), Comp.CLUB)), events.filter { it.comp == Comp.CLUB })
        plan.startPost(emptyList())
        assertTrue(plan.finished)
        val honours = Offseason.honours(plan, Offseason.moves(plan))
        assertEquals(Honour(1, "CLUB", table[0].club), honours.single { it.kind == "CLUB" })
        assertEquals(Honour(1, "CLUB2", table[1].club), honours.single { it.kind == "CLUB2" })
        // The winner of the team match is the one with more boards (a level board is half).
        for (m in club.matches()) {
            val r = assertNotNull(m.result)
            if (r.won != r.lost) assertEquals(if (r.won > r.lost) 1 else -1, r.winner)
        }

        // Saved mid-season: the same table, and the fixtures in flight are played again identically.
        val back = SeasonPlan.decode(saved!!, keys, mapOf(Comp.CLUB to ClubLeague.codec))
        val again = assertNotNull(back.competition(Comp.CLUB) as? ClubLeague)
        assertEquals(savedLeft, again.fixturesLeft)
        assertEquals(28, again.fixturesTotal)
        assertEquals(savedTable, again.table())
        assertEquals(club.lineups, again.lineups)
        val replay = generateSequence { again.next(1.0, emptySet()) }.toList()
        assertEquals(savedJobs, replay)
        // Without the codec the club league is dropped; a cut section still reads.
        assertNull(SeasonPlan.decode(saved, keys, emptyMap()).competition(Comp.CLUB))
        val body = StringBuilder().also { club.encode(it) }.lines()
        val cut = body.take(body.size - 4) + body[body.size - 4].take(12)
        assertEquals(25, ClubLeague.decode(cut, 1, 0L).fixturesTotal)
        assertFailsWith<IllegalArgumentException> { ClubLeague.decode(listOf("nonsense"), 1, 0L) }

        // The off-season after it: the champion's title, prize money by place.
        val alive = keys.toSet()
        val budgets = book.clubs.associate { it.id to it.budget }
        book.offseason(plan, rating, alive, config)
        assertEquals(1, book.club(table[0].club)!!.honours)
        assertEquals(1, book.season)
        assertWhole(book, alive, 6)
        val spent = book.history.filter { it.kind == ClubMoveKind.TRANSFER && it.from == null }.sumOf { it.amount }
        assertEquals(budgets.values.sum() + (1..8).sumOf { ClubBook.prize(it) } - spent, book.clubs.sumOf { it.budget })
    }

    @Test
    fun roundsOpenWithTheLeagueAndShortSquadsFieldFewerBoards() {
        val lineups = linkedMapOf(
            "c01" to (1..5).map { "a$it" },
            "c02" to (1..5).map { "b$it" },
            "c03" to (1..3).map { "c$it" },
            "c04" to (1..5).map { "d$it" },
        )
        val lg = ClubLeague.start(2, 5L, lineups)
        assertEquals(3, lg.rounds)
        assertEquals(6, lg.fixturesTotal)
        assertEquals(1, lg.openRounds(0.0))
        assertEquals(1, lg.openRounds(0.3))
        assertEquals(2, lg.openRounds(1.0 / 3 * 0.95))
        assertEquals(3, lg.openRounds(1.0))
        // A fixture with a busy AI waits.
        val round0 = lg.matches().filter { it.round == 0 }
        val blocked = round0.first()
        val f1 = assertNotNull(lg.next(0.0, setOf(lineups.getValue(blocked.a).first())))
        assertNotEquals(blocked.id, f1.id)
        val f0 = assertNotNull(lg.next(0.0, emptySet()))
        assertEquals(blocked.id, f0.id)
        assertNull(lg.next(0.0, emptySet()))
        // A stopped job is playable again.
        lg.release(f0)
        assertEquals(f0, lg.next(0.0, emptySet()))
        for (f in listOf(f0, f1)) {
            val shortSide = f.a == "c03" || f.b == "c03"
            assertEquals(if (shortSide) 3 else 5, f.lineupA.size)
            assertEquals(f.lineupA.size, f.lineupB.size)
        }
        // 2 boards each and a level one: the points over all boards decide.
        val boards = listOf(
            BoardResult("x", "y", listOf(GameScore(200, 150), GameScore(200, 150)), emptyList()),
            BoardResult("x", "y", listOf(GameScore(200, 150), GameScore(200, 150)), emptyList()),
            BoardResult("x", "y", listOf(GameScore(150, 160), GameScore(150, 160)), emptyList()),
            BoardResult("x", "y", listOf(GameScore(150, 160), GameScore(150, 160)), emptyList()),
            BoardResult("x", "y", listOf(GameScore(170, 170), GameScore(170, 170)), emptyList()),
        )
        assertEquals(1, TieRule.team(boards))
        assertEquals(emptyList(), lg.record(JobResult(f0, boards, TieRule.team(boards), 0)))
        // A result recorded twice counts once.
        lg.record(JobResult(f0, boards, TieRule.team(boards), 0))
        val row = lg.table().first { it.club == f0.a }
        assertEquals(1, row.played)
        assertEquals(3, row.points)
        assertEquals(0, row.boardDiff)
        assertEquals(1, row.boardsLevel)
        assertEquals(lg.table().first().club, f0.a)
        assertEquals(emptyList(), lg.honours())
    }

    @Test
    fun theFactoryFollowsTheConfigAndTheSeasonStart() {
        val ranking = keys(40)
        val rating = { k: String -> 1300.0 + strength(k) }
        val book = ClubBook.create(ranking, 4, 6, 2L, cities)
        fun start(config: LeagueConfig, ranking: List<String>) =
            SeasonStart(1, 11L, config, ranking, rating, listOf(ranking), emptyList(), emptyList(), { null }, { "서울" })
        assertNull(ClubLeague.factory({ book })(start(LeagueConfig(clubs = 0), ranking)))
        assertNull(ClubLeague.factory({ null })(start(LeagueConfig(clubs = 4), ranking)))
        // An AI that left the league is not fielded; lineups are the 5 best, strongest first.
        val gone = book.club("c01")!!.squad.sortedByDescending(rating).first()
        val lg = assertNotNull(ClubLeague.factory({ book })(start(LeagueConfig(clubs = 4), ranking - gone)) as? ClubLeague)
        val c01 = book.club("c01")!!.squad.filter { it != gone }.sortedByDescending(rating)
        assertEquals(c01, lg.lineups.getValue("c01"))
        assertEquals(book.club("c02")!!.squad.sortedByDescending(rating).take(5), lg.lineups.getValue("c02"))
        // Only one club with someone left to play: no club league.
        val onlyC02 = book.club("c02")!!.squad
        assertNull(ClubLeague.factory({ book })(start(LeagueConfig(clubs = 4), onlyC02)))
        // No book yet: made by the creator.
        var made: ClubBook? = null
        val created = ClubLeague.factory({ made }, { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, cities).also { made = it } })(start(LeagueConfig(clubs = 4), ranking))
        assertNotNull(created)
        assertEquals(4, made!!.clubs.size)
    }

    @Test
    fun theOffseasonKeepsSquadsWholeAndMoneyHonest() {
        val rng = Random(5)
        val ratings = HashMap<String, Double>()
        val start = keys(150)
        for (k in start) ratings[k] = 1200.0 + rng.nextInt(400)
        val rating = { k: String -> ratings[k] ?: Double.NaN }
        var book = ClubBook.create(start.sortedByDescending(rating), 16, 6, 3L, cities)
        var alive = start.toSet()
        var transfers = 0
        var fromClubs = 0
        for (season in 1..8) {
            val before = book.clubs
            val table = before.map { it.id }.shuffled(rng)
            val members = before.flatMap { it.squad }
            val free = alive.filter { book.clubOf(it) == null }
            val honours = listOf(
                Honour(season, "CUP", members.pick(rng)), Honour(season, "CUP2", free.pick(rng)),
                Honour(season, "CL", members.pick(rng)), Honour(season, "CL2", members.pick(rng)),
                Honour(season, "CLUB", table[0]), Honour(season, "CLUB2", table[1]), Honour(season, "L0", members.pick(rng)),
                // Another season's honour pays nothing.
                Honour(season - 1, "CL", members.pick(rng)),
            )
            val honourMoney = honours.filter { it.season == season }.sumOf { h -> if (book.clubOf(h.key) != null) ClubBook.honourMoney(h.kind) else 0 }
            val retired = alive.shuffled(rng).take(10).toSet()
            val born = (1..10).map { "e${season}_$it" }
            for (k in born) ratings[k] = 1250.0 + rng.nextInt(400)
            alive = alive - retired + born
            for (k in alive) ratings[k] = ratings.getValue(k) + rng.nextGaussian() * 25
            val events = book.offseason(season, table, honours, rating, alive, 16, 6, 11L)

            assertWhole(book, alive, 6)
            assertTrue(retired.none { book.clubOf(it) != null })
            assertEquals(before.first { it.id == table[0] }.honours + 1, book.club(table[0])!!.honours)
            // Money: prizes and cup money come in, free agents' fees go out, club fees change hands.
            val tr = events.filter { it.kind == "transfer" }
            val spent = tr.filter { it.keys[1] == "-" }.sumOf { it.args[0].toInt() }
            assertEquals(before.sumOf { it.budget } + (1..16).sumOf { ClubBook.prize(it) } + honourMoney - spent, book.clubs.sumOf { it.budget })
            for (e in tr) {
                val v = ClubBook.value(rating(e.keys[0]))
                assertEquals(if (e.keys[1] == "-") v else ClubBook.fee(v), e.args[0].toInt())
                assertEquals(Comp.CLUB, e.comp)
                assertEquals(season, e.season)
            }
            assertTrue(tr.groupBy { it.keys[2] }.values.all { it.size <= ClubBook.BUYS })
            assertEquals(tr.size, tr.map { it.keys[0] }.toSet().size, "an AI sold twice in one window")
            transfers += tr.size
            fromClubs += tr.count { it.keys[1] != "-" }
            // Draft picks are numbered in order and go to free agents, best rated first.
            val picks = events.filter { it.kind == "draft" }
            assertEquals((1..picks.size).map { it.toString() }, picks.map { it.args[0] })
            val drafted = picks.map { rating(it.keys[0]) }
            assertEquals(drafted.sortedDescending(), drafted)
            val leftOver = alive.filter { book.clubOf(it) == null }.maxOfOrNull(rating) ?: Double.NEGATIVE_INFINITY
            assertTrue(drafted.all { it >= leftOver })
            // A repeated rollover changes nothing and tells the same news; saved and read back the same.
            val text = book.encode()
            assertEquals(events, book.offseason(season, table, honours, rating, alive, 16, 6, 11L))
            assertEquals(text, book.encode())
            book = ClubBook.decode(text)
            assertEquals(text, book.encode())
        }
        assertTrue(transfers > 0 && fromClubs > 0, "transfers $transfers from clubs $fromClubs")
        assertTrue(book.history.size <= ClubBook.HISTORY || book.history.all { it.season == book.season })
        assertTrue(book.history.any { it.kind == ClubMoveKind.RETIRED })
    }

    @Test
    fun buysImproveTheClubAndPrizesFollowTheTable() {
        // Nobody affordable (value 190) and no free agents: only the money moves.
        val ranking = keys(48)
        val dear = { _: String -> 3000.0 }
        val book = ClubBook.create(ranking, 8, 6, 1L, cities)
        val table = book.clubs.map { it.id }.reversed()
        val cupWinner = book.club(table[3])!!.squad.first()
        val events = book.offseason(1, table, listOf(Honour(1, "CUP", cupWinner), Honour(1, "CLUB", table[0])), dear, ranking.toSet(), 8, 6, 1L)
        assertEquals(emptyList(), events)
        for ((i, id) in table.withIndex()) {
            assertEquals(ClubBook.START_BUDGET + ClubBook.prize(i + 1) + if (i == 3) 20 else 0, book.club(id)!!.budget)
        }
        assertEquals(38, ClubBook.prize(1))
        assertEquals(0, ClubBook.prize(24))
        assertEquals(1, book.club(table[0])!!.honours)
        assertEquals(30, ClubBook.value(1400.0))
        assertEquals(1, ClubBook.value(1000.0))
        assertEquals(1, ClubBook.value(Double.NaN))
        assertEquals(39, ClubBook.fee(30))
        assertEquals(13, ClubBook.fee(10))

        // A rising free agent is bought by the poorest club, replacing its weakest member.
        val rating = HashMap<String, Double>()
        for ((i, k) in keys(60).withIndex()) rating[k] = 1500.0 - 5 * i
        val b = ClubBook.create(keys(60), 8, 6, 1L, cities)
        rating["ai060"] = 1600.0
        val events2 = b.offseason(1, emptyList(), emptyList(), { rating[it] ?: Double.NaN }, keys(60).toSet(), 8, 6, 1L)
        val buy = events2.first { it.kind == "transfer" }
        // Every club has 100: the draw picks the first buyer; it takes the best it can pay for.
        assertEquals("ai060", buy.keys[0])
        assertEquals("-", buy.keys[1])
        assertEquals(ClubBook.value(1600.0).toString(), buy.args[0])
        val buyer = b.club(buy.keys[2])!!
        assertTrue("ai060" in buyer.squad)
        assertEquals(6, buyer.squad.size)
        val released = b.history.first { it.kind == ClubMoveKind.RELEASED && it.from == buyer.id }
        assertTrue(rating.getValue(released.ai) < 1600.0)
        // No buy is worse than the buyer's weakest member at the time: nobody below every squad is bought.
        assertTrue(events2.filter { it.kind == "transfer" }.all { rating.getValue(it.keys[0]) > 1500.0 - 5 * 48 })
        assertWhole(b, keys(60).toSet(), 6)
    }

    @Test
    fun configChangesResizeTheClubs() {
        val ranking = keys(150)
        val rating = { k: String -> 1500.0 - k.filter { it.isDigit() }.toInt() }
        val alive = ranking.toSet()
        val book = ClubBook.create(ranking, 16, 6, 4L, cities)
        val names = book.clubs.associate { it.id to it.name }
        // Fewer clubs: the bottom of the table is dissolved, its members free.
        val table = book.clubs.map { it.id }.shuffled(Random(1))
        book.offseason(1, table, emptyList(), rating, alive, 12, 6, 1L)
        assertEquals(table.take(12).sorted(), book.clubs.map { it.id })
        assertWhole(book, alive, 6)
        assertEquals(24, book.history.count { it.kind == ClubMoveKind.RELEASED && it.from in table.drop(12) })
        // More clubs: the free ids come back with their names and the draft fills them.
        book.offseason(2, book.clubs.map { it.id }, emptyList(), rating, alive, 20, 6, 1L)
        assertEquals((1..20).map { ClubBook.idOf(it) }, book.clubs.map { it.id })
        assertWhole(book, alive, 6)
        for (c in book.clubs) assertEquals(names[c.id] ?: ClubNames.identity(c.id, book.seed, cities).name, c.name)
        // Smaller squads: each club lets its weakest go (nobody affordable, so the window is quiet).
        val strongest = book.clubs.associate { c -> c.id to c.squad.sortedByDescending(rating).take(5).toSet() }
        val dear = { k: String -> 3000.0 + rating(k) }
        assertEquals(emptyList(), book.offseason(3, emptyList(), emptyList(), dear, alive, 20, 5, 1L))
        assertWhole(book, alive, 5)
        for (c in book.clubs) assertEquals(strongest.getValue(c.id), c.squad.toSet(), c.id)
        // Bigger squads with fewer clubs: 16 x 8 = 128 of 150.
        book.offseason(4, book.clubs.map { it.id }, emptyList(), rating, alive, 16, 8, 1L)
        assertWhole(book, alive, 8)
        assertEquals(16, book.clubs.size)
        // Not enough AIs for every place: squads stay short, nobody is in two clubs.
        val few = ranking.take(100).toSet()
        book.offseason(5, emptyList(), emptyList(), rating, few, 16, 8, 1L)
        assertWhole(book, few)
        assertEquals(100, book.clubs.sumOf { it.squad.size })
        // Club league off: the clubs wait as they are (only departures), and come back later.
        val frozen = book.clubs.map { it.id to it.squad }
        val fewer = few - ranking.take(3).toSet()
        assertEquals(emptyList(), book.offseason(6, emptyList(), emptyList(), rating, fewer, 0, 8, 1L))
        assertEquals(frozen.map { (id, s) -> id to s.filter { it in fewer } }, book.clubs.map { it.id to it.squad })
        book.offseason(7, emptyList(), emptyList(), rating, alive, 16, 6, 1L)
        assertWhole(book, alive, 6)

        // A big expansion makes more moves than the history keeps: that off-season stays whole,
        // so a repeat (also after saving) tells all of it; the next one trims it.
        val many = keys(250).toSet()
        val big = ClubBook.create(keys(250), 8, 6, 6L, cities)
        val news = big.offseason(1, emptyList(), emptyList(), rating, many, 24, 8, 1L)
        assertWhole(big, many, 8)
        assertTrue(news.size > ClubBook.HISTORY, "${news.size}")
        assertEquals(news, big.offseason(1, emptyList(), emptyList(), rating, many, 24, 8, 1L))
        assertEquals(news, ClubBook.decode(big.encode()).offseason(1, emptyList(), emptyList(), rating, many, 24, 8, 1L))
        val next = big.offseason(2, big.clubs.map { it.id }, emptyList(), rating, many, 24, 8, 1L)
        assertTrue(next.size < ClubBook.HISTORY)
        assertEquals(ClubBook.HISTORY, big.history.size)
    }

    @Test
    fun theBookSavesNamesWithSpacesAndSurvivesACutFile() {
        val ranking = keys(100)
        val odd = listOf("새 도시", "100%:시티", "-", "부,산")
        val book = ClubBook.create(ranking, 8, 6, 5L, odd)
        val rating = { k: String -> 1500.0 - k.filter { it.isDigit() }.toInt() }
        book.offseason(1, book.clubs.map { it.id }, emptyList(), rating, ranking.toSet() - "ai001", 8, 6, 5L)
        val text = book.encode()
        assertTrue(text.lines().all { l -> !l.startsWith("club ") || l.split(' ').size == 12 }, text)
        val back = ClubBook.decode(text)
        assertEquals(book.clubs, back.clubs)
        assertEquals(book.history, back.history)
        assertEquals(odd, back.cities)
        assertEquals(1, back.season)
        assertTrue(back.clubs.any { it.city == "새 도시" && it.name.startsWith("새 도시 ") })
        for (c in book.clubs) for (k in c.squad) assertEquals(c.id, back.clubOf(k))
        // Cut in the middle of a club line: the clubs before it are kept.
        val lines = text.lines()
        val at = lines.indexOfFirst { it.startsWith("club c05") }
        val cut = ClubBook.decode((lines.take(at) + lines[at].take(20)).joinToString("\n"))
        assertEquals(book.clubs.take(4), cut.clubs)
        // Unknown and broken lines are skipped; an AI listed twice stays in the first club.
        val c1 = book.club("c01")!!.squad.first()
        val messy = text + "\nnews whatever\nmove x\nclub c99 a b c 1 ff ff 가 10 0 $c1\n"
        val m = ClubBook.decode(messy)
        assertEquals("c01", m.clubOf(c1))
        assertEquals(emptyList(), m.club("c99")!!.squad)
        assertFailsWith<IllegalArgumentException> { ClubBook.decode("hello") }
        assertFailsWith<IllegalStateException> { ClubBook.decode("clubs 1\n") }
        // A meta line without the last plan seed (the first files) still reads, and a repeat is still a repeat.
        val old = text.lines().joinToString("\n") { l -> if (l.startsWith("meta ")) l.split(' ').take(4).joinToString(" ") else l }
        val oldBook = ClubBook.decode(old)
        assertEquals(book.clubs, oldBook.clubs)
        val news = book.offseason(1, emptyList(), emptyList(), rating, emptySet(), 8, 6, 5L)
        assertTrue(news.isNotEmpty())
        assertEquals(news, oldBook.offseason(1, emptyList(), emptyList(), rating, emptySet(), 8, 6, 77L))
        assertEquals(book.clubs, oldBook.clubs)
    }

    @Test
    fun aLeagueThatStartsOverDoesNotFreezeTheClubs() {
        // Regression: the off-season ran once per season number only, so a book from season 3
        // skipped every off-season of a league that started over at season 1 (a new league, or
        // a season file that could not be read) until season 4: no retirements, no money, no draft.
        val ranking = keys(100)
        val rating = { k: String -> 1500.0 - k.filter { it.isDigit() }.toInt() }
        val alive = ranking.toSet()
        val book = ClubBook.create(ranking, 8, 6, 1L, cities)
        for (s in 1..3) book.offseason(s, book.clubs.map { it.id }, emptyList(), rating, alive, 8, 6, 100L + s)
        assertEquals(3, book.season)
        assertTrue(book.history.any { it.season == 3 })
        // The same season and plan seed again (a rollover replayed after a kill): nothing changes, also after saving.
        val text = book.encode()
        val again = book.offseason(3, book.clubs.map { it.id }, emptyList(), rating, alive, 8, 6, 103L)
        assertEquals(text, book.encode())
        assertEquals(again, ClubBook.decode(text).offseason(3, book.clubs.map { it.id }, emptyList(), rating, alive, 8, 6, 103L))
        // A new league at season 1: applied to the clubs as they are; the old timeline's moves go.
        val member = book.club("c01")!!.squad.first()
        val budgets = book.clubs.sumOf { it.budget }
        val news = book.offseason(1, book.clubs.map { it.id }, emptyList(), rating, alive - member, 8, 6, 999L)
        assertEquals(1, book.season)
        assertNull(book.clubOf(member))
        assertTrue(book.history.all { it.season == 1 }, book.history.toString())
        assertTrue(book.history.any { it.kind == ClubMoveKind.RETIRED && it.ai == member })
        val spent = news.filter { it.kind == "transfer" && it.keys[1] == "-" }.sumOf { it.args[0].toInt() }
        assertEquals(budgets + (1..8).sumOf { ClubBook.prize(it) } - spent, book.clubs.sumOf { it.budget })
        assertWhole(book, alive - member, 6)
        // That one is a repeat now; the same number with yet another seed (read back from the file) is not.
        val saved = book.encode()
        assertEquals(news, book.offseason(1, book.clubs.map { it.id }, emptyList(), rating, alive - member, 8, 6, 999L))
        assertEquals(saved, book.encode())
        val back = ClubBook.decode(saved)
        val before = back.clubs.sumOf { it.budget }
        val other = back.offseason(1, back.clubs.map { it.id }, emptyList(), rating, alive - member, 8, 6, 5L)
        val spentAgain = other.filter { it.kind == "transfer" && it.keys[1] == "-" }.sumOf { it.args[0].toInt() }
        assertEquals(before + (1..8).sumOf { ClubBook.prize(it) } - spentAgain, back.clubs.sumOf { it.budget })
        assertTrue(back.history.all { it.season == 1 })
        assertWhole(back, alive - member, 6)
    }

    @Test
    fun pricesNeverOverflow() {
        // Regression: 13 * value overflowed Int for huge values, giving a negative fee.
        assertEquals(Int.MAX_VALUE, ClubBook.fee(Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, ClubBook.fee(ClubBook.value(Double.POSITIVE_INFINITY)))
        assertTrue(ClubBook.fee(200_000_000) > 200_000_000)
        assertEquals(1, ClubBook.value(Double.NEGATIVE_INFINITY))
        // An absurdly rated member is never sold at a negative price.
        val ranking = keys(60)
        val book = ClubBook.create(ranking, 8, 6, 1L, cities)
        val star = book.club("c02")!!.squad.first()
        val rating = { k: String -> if (k == star) 2e9 else 1500.0 - k.filter { it.isDigit() }.toInt() }
        book.offseason(1, emptyList(), emptyList(), rating, ranking.toSet(), 8, 6, 1L)
        assertEquals("c02", book.clubOf(star))
        assertWhole(book, ranking.toSet(), 6)
    }

    @Test
    fun anOddClubCountRestsOneClubPerRound() {
        val lineups = linkedMapOf(
            "c01" to (1..5).map { "a$it" },
            "c02" to (1..5).map { "b$it" },
            "c03" to (1..3).map { "c$it" },
            "c04" to (1..5).map { "d$it" },
            "c05" to (1..5).map { "e$it" },
        )
        val lg = ClubLeague.start(1, 9L, lineups)
        assertEquals(5, lg.rounds)
        assertEquals(10, lg.fixturesTotal)
        for (c in lineups.keys) assertEquals(4, lg.matches().count { it.a == c || it.b == c })
        for (r in 0 until 5) assertEquals(2, lg.matches().count { it.round == r })
        // Only the boards that play keep a fixture waiting: c01's 4th and 5th sit out against c03.
        val vs03 = lg.matches().first { (it.a == "c01" && it.b == "c03") || (it.a == "c03" && it.b == "c01") }
        val f = assertNotNull(lg.next(1.0, setOf("a4", "a5") + lineups.keys.filter { it != "c01" && it != "c03" }.flatMap { lineups.getValue(it) }))
        assertEquals(vs03.id, f.id)
        assertEquals(3, f.lineupA.size)
        assertNull(lg.next(1.0, setOf("a1") + lineups.keys.filter { it != "c01" }.flatMap { lineups.getValue(it) }))
    }

    @Test
    fun aReplayedOffSeasonDraftsAfterTheSeasonsPicks() {
        val all = keys(120)
        val rating = { k: String -> strength(k).toDouble() }
        val book = ClubBook.create(all.take(96), 16, 6, 7L, cities)
        // The off-season of season 1, with 24 free agents to draft.
        book.offseason(1, emptyList(), emptyList(), rating, all.toSet(), 16, 6, 99L)
        val first = book.history.filter { it.season == 1 && it.kind == ClubMoveKind.DRAFT }.maxOfOrNull { it.amount } ?: 0
        // Replayed with one squad member gone: it leaves, and the draft goes on from the last pick.
        val gone = book.clubs.first().squad.first()
        book.offseason(1, emptyList(), emptyList(), rating, (all - gone).toSet(), 16, 6, 99L)
        val picks = book.history.filter { it.season == 1 && it.kind == ClubMoveKind.DRAFT }.map { it.amount }
        assertEquals(picks.size, picks.toSet().size, picks.toString())
        assertTrue(picks.max() > first)
        assertTrue(book.clubs.none { gone in it.squad })
    }

    @Test
    fun theClubLeagueIsForLegacySeasonsOnly() {
        val ranking = keys(40)
        val rating = { k: String -> 1300.0 + strength(k) }
        val book = ClubBook.create(ranking, 4, 6, 2L, cities)
        fun start(national: Boolean) = SeasonStart(
            1, 11L, LeagueConfig(clubs = 4, national = national), ranking, rating, listOf(ranking.take(20), ranking.drop(20)), emptyList(), emptyList(), { null }, { "서울" },
            leagues = if (national) Leagues.national(listOf("KR"), listOf(2)) else Leagues.legacy(Tiers.linear(2)),
        )
        assertNotNull(ClubLeague.factory({ book })(start(false)))
        // National leagues: the clubs are every player's, and meet in the club cup instead.
        assertNull(ClubLeague.factory({ book })(start(true)))
        var made: ClubBook? = null
        assertNull(ClubLeague.factory({ made }, { s -> ClubBook.create(s.ranking, 4, 6, s.seed, cities).also { made = it } })(start(true)))
        assertNull(made)
        // A national book plays no club league, even in a legacy season.
        val national = ClubBook.found(ranking, { "KR" }, rating, 0, 1L, LeagueConfig())
        assertNull(ClubLeague.factory({ national })(start(false)))
        // The file: both versions read back as they are.
        assertTrue(book.encode().startsWith("clubs 1\n"))
        assertEquals(ClubBook.CLUB_LEAGUE, ClubBook.decode(book.encode()).format)
        assertEquals(ClubBook.AFFILIATION, ClubBook.decode(national.encode()).format)
        assertEquals(national.encode(), ClubBook.decode(national.encode()).encode())
        // The club league's off-season leaves a national book as it is, but for members who left.
        val gone = national.clubs.first().squad.first()
        val version = national.version
        assertEquals(emptyList(), national.offseason(1, emptyList(), emptyList(), rating, ranking.toSet() - gone, 4, 6, 1L))
        assertNull(national.clubOf(gone))
        assertEquals(ranking.toSet() - gone, national.clubs.flatMap { it.squad }.toSet())
        assertNotEquals(version, national.version)
        assertEquals(0, national.season)
        // The moves of national leagues belong to the card's four kinds.
        assertEquals(
            listOf(ClubMoveKind.TRANSFER, ClubMoveKind.DRAFT, ClubMoveKind.RELEASED, ClubMoveKind.RETIRED, ClubMoveKind.TRANSFER, ClubMoveKind.TRANSFER, ClubMoveKind.DRAFT),
            ClubMoveType.entries.map { it.kind },
        )
        assertEquals("TDRXFHY", ClubMoveType.entries.joinToString("") { it.code.toString() })
        assertEquals(ClubMoveKind.TRANSFER, ClubMove(2, ClubMoveType.ABROAD, "a", "c01", "c02", 50).kind)
    }

    /**
     * A book of the club league (`clubs 1`) is made, changed and saved byte for byte as before
     * clubs became every player's affiliation: creation, names and badges, off-seasons through both
     * entry points (resizes, squad changes, the league off, departures, replays, a league that
     * started over), their news and the file. The hash was taken from the code before that change.
     */
    @Test
    fun aLegacyBookIsAsBefore() {
        val out = StringBuilder()
        for (seed in listOf(1L, 7L, 42L)) for (id in (1..40).map { ClubBook.idOf(it) } + "x9") {
            out.append(ClubNames.identity(id, seed, cities)).append('\n')
        }
        out.append(ClubNames.identity("c05", 3L, emptyList())).append('\n')
        val rng = Random(11)
        val ratings = HashMap<String, Double>()
        val pool = keys(260)
        for (k in pool) ratings[k] = 1150.0 + rng.nextInt(500)
        val rating = { k: String -> ratings[k] ?: Double.NaN }
        for ((clubs, squad, n) in listOf(Triple(16, 6, 150), Triple(8, 8, 60), Triple(24, 5, 260), Triple(4, 6, 20))) {
            var book = ClubBook.create(pool.take(n).sortedWith(compareByDescending<String> { rating(it) }.thenBy { it }), clubs, squad, 100L + n, cities)
            out.append(book.encode())
            var alive = pool.take(n).toSet()
            var c = clubs
            var sq = squad
            for (season in 1..7) {
                val members = book.clubs.flatMap { it.squad }
                val table = book.clubs.map { it.id }.shuffled(rng)
                val honours = if (members.isEmpty()) emptyList() else listOf(
                    Honour(season, "CUP", members.pick(rng)), Honour(season, "CL", members.pick(rng)), Honour(season, "CL2", members.pick(rng)),
                    Honour(season, "CUP2", pool.pick(rng)), Honour(season, "CLUB", table.first()),
                )
                val retired = alive.shuffled(rng).take(n / 15).toSet()
                val born = (1..n / 15).map { "e${season}_$it" }
                for (k in born) ratings[k] = 1200.0 + rng.nextInt(500)
                alive = alive - retired + born
                for (k in alive) ratings[k] = ratings.getValue(k) + rng.nextGaussian() * 30
                when (season) {
                    3 -> c = (c - 4).coerceAtLeast(4)
                    4 -> sq = (sq + 1).coerceAtMost(8)
                    5 -> c = 0
                    6 -> c = clubs + 2
                }
                val events = book.offseason(season, table, honours, rating, alive, c, sq, 900L + season)
                for (e in events) out.append(e.encode()).append('\n')
                for (e in book.offseason(season, table, honours, rating, alive - alive.take(2).toSet(), c, sq, 900L + season)) out.append(e.encode()).append('\n')
                val text = book.encode()
                out.append(text)
                book = ClubBook.decode(text)
            }
            // A league that started over at season 2, with another plan seed.
            for (e in book.offseason(2, book.clubs.map { it.id }, emptyList(), rating, alive, clubs, squad, 5L)) out.append(e.encode()).append('\n')
            out.append(book.encode())
        }
        // The plan entry point after a played club season.
        val keys = keys(60)
        val r2 = { k: String -> 1100.0 + strength(k) }
        val book = ClubBook.create(keys.sortedByDescending(r2), 8, 6, 9L, cities)
        val config = LeagueConfig(divisions = 2, clubs = 8, squad = 6)
        val plan = Offseason.first(77L, Offseason.Context(keys, r2, { null }, { "서울" }, config, listOf(ClubLeague.factory({ book }))))
        val play = Random(3)
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(result(f, play))
        }
        plan.startPost(emptyList())
        // The plan as the hash was taken: its rules' copy was version 2 then (the lines are the same since).
        out.append(plan.encode().replace("\ncfg config ${LeagueConfig.VERSION}\n", "\ncfg config 2\n"))
        for (e in book.offseason(plan, r2, keys.drop(3).toSet(), config.copy(clubs = 10))) out.append(e.encode()).append('\n')
        out.append(book.encode())
        val sha = MessageDigest.getInstance("SHA-256").digest(out.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(LEGACY_BOOK, sha)
    }

    private companion object {
        /** From the code before clubs became every player's affiliation. */
        const val LEGACY_BOOK = "8e42b7122cf2e068941beb87ae700175eae00af8ca1183b4f4c1801c764c855c"
    }
}
