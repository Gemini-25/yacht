package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubStart
import yacht.engine.league.ClubWorld
import yacht.engine.league.CoachInput
import yacht.engine.league.CoachMarket
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Market
import yacht.engine.league.MarketStory
import yacht.engine.league.MemoryCoefficients
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.Origin
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerTraits
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.valuer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * National leagues of generated players for the club world's off-season tests (ClubBookV3Test,
 * OffseasonRollbackTest, ClubWorldMigrationTest): a book founded at season 0, seasons of every league
 * match played (the better rated wins four times in five), members who retire and rookies who join,
 * the off-season's input with everything the club world reads (values, hometowns, traits, families).
 */
internal object Cw {
    val config = LeagueConfig.DEFAULT
    val players: PlayerBook by lazy { PlayerBook().also { it.ensureGenerated(1400) } }
    val keys: List<String> by lazy { players.alive.map { it.key } }

    fun rating(k: String): Double =
        players[k]?.identity?.abilities?.overall?.let { 900.0 + 12 * it + Math.floorMod(k.hashCode(), 17) } ?: 1500.0

    fun nation(k: String): String? = players[k]?.identity?.nation

    /** National season [number] of [members], not played yet. */
    fun fresh(members: List<String>, number: Int): SeasonPlan =
        Offseason.first(31L + number, Offseason.Context(members, ::rating, { null }, { "" }, config, nation = ::nation), number)

    /** [plan] with every league match played. */
    fun play(plan: SeasonPlan): SeasonPlan {
        val s = plan.league
        while (true) {
            val f = s.next(emptySet()) ?: break
            val aWins = (rating(f.a) >= rating(f.b)) != (f.id % 5 == 0)
            s.record(f.id, List(2) { if (aWins) GameScore(220, 180) else GameScore(180, 220) })
        }
        return plan
    }

    fun ranking(plan: SeasonPlan): LeagueRanking {
        val s = plan.league
        return Coefficients.ranking(MemoryCoefficients(), s.leagues, plan.number, { id -> Coefficients.strength(s.leagues.byId(id)!!.divisions.flatMap { s.divisions[it] }, ::rating) }, config)
    }

    /** A national league in play: its book, this season's members, the honours so far and the next rookie to join. */
    class League(val book: ClubBook, var members: List<String>, var nextRookie: Int) {
        val honours = ArrayList<Honour>()

        /** The closing season [plan]'s honours, kept. */
        fun close(plan: SeasonPlan) {
            honours += Offseason.honours(plan, Offseason.moves(plan))
        }

        /** Next season's members: about one in eleven retires, [rookies] join. */
        fun next(season: Int, rookies: Int = 30): List<String> {
            val retired = members.filter { Math.floorMod(it.hashCode() * 31 + season, 11) == 0 }.toSet()
            val joining = keys.drop(nextRookie).take(rookies)
            nextRookie += rookies
            return members.filter { it !in retired } + joining
        }

        /** The off-season's input for the members [next] after [plan] (or the season in play's, for a migration). */
        fun input(
            next: List<String>, plan: SeasonPlan, config: LeagueConfig = Cw.config,
            coaches: Boolean = true, market: Boolean = true, winter: Boolean = true,
        ): ClubInput = ClubInput(
            next, ::rating, ::nation, config, honours.toList(), age = { k -> players[k]?.age(plan.number + 1) ?: 22 }, origin = Origin::of,
            ranking = ranking(plan), economy = LeagueEconomy.of(plan, ::rating), drawn = ::rating,
            coachInput = CoachInput(names = players.alive.mapTo(HashSet()) { it.name }), families = { k -> StyleFamily.of(players[k]?.spec?.play) },
            coaches = coaches, market = market, winter = winter,
            hometown = { k -> players[k]?.hometown }, potential = { k -> players[k]?.identity?.potential }, overall = { k -> players[k]?.identity?.abilities?.overall },
            traits = { k -> players[k]?.let { PlayerTraits.of(it.spec.play, it.identity) } },
        )

        /** Plays season [n] and runs its off-season; the off-season's news. */
        fun season(n: Int, config: LeagueConfig = Cw.config, plan: SeasonPlan = play(fresh(members, n))): List<LeagueEvent> {
            close(plan)
            val next = next(n)
            val events = book.offseason(plan, input(next, plan, config))
            members = next
            return events
        }
    }

    /** [size] players founded at season 0 and [v2] seasons of today's off-season. */
    fun league(size: Int = 400, v2: Int = 2, seed: Long = 77L): League {
        val members = keys.take(size)
        val l = League(ClubBook.found(members, ::nation, ::rating, 0, seed, config), members, size)
        for (n in 1..v2) l.season(n)
        return l
    }

    /** [league] migrated into a club world in the season after its [v2] seasons (in play, week [week]); that season's plan. */
    fun migrated(size: Int = 400, v2: Int = 2, week: Int = 0): Pair<League, SeasonPlan> {
        val l = league(size, v2)
        val plan = fresh(l.members, v2 + 1)
        assertTrue(ClubWorld.migrate(l.book, l.input(l.members, plan), ClubStart(v2 + 1, plan, week)))
        return l to plan
    }

    /** Every member in exactly one club of its nation and every club with members. */
    fun assertWhole(book: ClubBook, members: Collection<String>) {
        val all = book.clubs.flatMap { it.squad }
        assertEquals(all.size, all.toSet().size, "a member in two clubs")
        assertEquals(members.toSet(), all.toSet())
        for (c in book.clubs) {
            assertTrue(c.squad.isNotEmpty(), "${c.id} has no members")
            assertTrue(Nations.of(c.nation) != null, c.nation)
            for (k in c.squad) assertEquals(c.id, book.clubOf(k))
        }
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * The club world's off-season (design ENG 1b.10, [ClubBook.offseason] with a club world): its steps in
 * order, the repeat and the read-back, the frozen club world, the test knobs, and today's off-season
 * unchanged for a book without one.
 */
class ClubBookV3Test {
    /**
     * Five national off-seasons of a book without a club world (members retiring, rookies joining, a
     * repeat and a read-back each season): byte for byte what 3ce4b93 wrote and told. The hash was
     * taken by the same scenario compiled on 3ce4b93's engine sources.
     */
    @Test
    fun aBookWithoutAClubWorldRunsTodaysOffseason() {
        val text = V2Scenario.run()
        assertEquals(61_572, text.length)
        assertEquals("4aeb32bc5ff5b48530b0eca216a4a00892c31aca36d9adab294a8c2cf32c67e1", Cw.sha256(text))
    }

    /** The golden's scenario, as it was compiled on 3ce4b93's engine (its own players, ratings and seasons). */
    private object V2Scenario {
        private val config = LeagueConfig.DEFAULT
        private val players: PlayerBook by lazy { PlayerBook().also { it.ensureGenerated(700) } }

        private fun rating(k: String): Double =
            players[k]?.identity?.abilities?.overall?.let { 900.0 + 12 * it + Math.floorMod(k.hashCode(), 17) } ?: 1500.0

        private fun nation(k: String): String? = players[k]?.identity?.nation

        private fun season(keys: List<String>, number: Int): SeasonPlan {
            val plan = Offseason.first(31L + number, Offseason.Context(keys, ::rating, { null }, { "" }, config, nation = ::nation), number)
            val s = plan.league
            while (true) {
                val f = s.next(emptySet()) ?: break
                val aWins = (rating(f.a) >= rating(f.b)) != (f.id % 5 == 0)
                s.record(f.id, List(2) { if (aWins) GameScore(220, 180) else GameScore(180, 220) })
            }
            return plan
        }

        fun run(): String {
            val all = players.alive.map { it.key }
            var members = all.take(460)
            val book = ClubBook.found(members, ::nation, ::rating, 0, 77L, config)
            val out = StringBuilder(book.encode())
            val honours = ArrayList<Honour>()
            val mentors = HashMap<String, String>()
            for (n in 1..5) {
                val plan = season(members, n)
                honours += Offseason.honours(plan, Offseason.moves(plan))
                val s = plan.league
                val ranking = Coefficients.ranking(MemoryCoefficients(), s.leagues, n, { id -> Coefficients.strength(s.leagues.byId(id)!!.divisions.flatMap { s.divisions[it] }, ::rating) }, config)
                val retired = members.filter { Math.floorMod(it.hashCode() * 31 + n, 11) == 0 }
                val rookies = all.drop(460 + (n - 1) * 40).take(40)
                for ((i, r) in rookies.withIndex()) if (i % 4 == 0) mentors[r] = members[(i * 37 + n) % members.size]
                val next = members.filter { it !in retired } + rookies
                val input = ClubInput(next, ::rating, ::nation, config, honours.toList(), { k -> players[k]?.age(n + 1) ?: 22 }, Origin::of, { mentors[it] }, ranking)
                out.append(book.offseason(plan, input).joinToString("\n")).append('\n')
                out.append(book.encode())
                out.append(book.offseason(plan, input).joinToString("\n")).append('\n')
                val back = ClubBook.decode(book.encode())
                out.append(back.encode() == book.encode()).append('\n')
                members = next
            }
            assertEquals(null, book.world)
            return out.toString()
        }
    }

    @Test
    fun theStepsRunInOrderEachOnTheBookTheLastLeft() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        Cw.play(plan)
        l.close(plan)
        val next = l.next(s)
        val input = l.input(next, plan)
        val seen = ArrayList<Int>()
        book.fault = { n ->
            seen += n
            val ids = book.clubs.map { it.id }
            when (n) {
                0 -> assertTrue(ids.all { book.life(it) != null }, "every club has a state")
                1 -> {
                    assertTrue(book.places(s).isNotEmpty())
                    assertTrue(ids.none { book.life(it)!!.last?.season == s }, "no accounts before step 2")
                }
                2 -> assertTrue(ids.all { book.life(it)!!.last?.season == s }, "accounts of the season")
                4 -> assertTrue(book.coaches.any { it.done == s }, "the coach market ran")
                5 -> assertTrue(book.clubs.flatMap { it.squad }.all { it in next }, "the departures are gone")
                9 -> assertEquals(s, w.market.windowSeason)
                12 -> Cw.assertWhole(book, next)
                13 -> {
                    assertNotNull(book.rankLines.lastOrNull { it.season == s })
                    assertTrue(next.all { book.terms(it) != null }, "every member has terms")
                }
                14 -> assertEquals(s, book.season)
            }
            if (n < 14) assertNotEquals(s, book.season, "the season's guard is set last (step $n)")
        }
        val events = book.offseason(plan, input)
        book.fault = null
        assertEquals((0..14).toList(), seen)
        Cw.assertWhole(book, next)
        for (c in book.clubs) {
            val life = book.life(c.id)!!
            assertEquals(maxOf(0, life.balance), c.budget, c.id)
            assertNotNull(book.coachOf(c.id), "${c.id} has a coach")
        }
        assertTrue(events.all { it.comp == Comp.CLUB && it.season == s }, events.toString())
        val kinds = events.map { it.kind }.toSet()
        for (k in listOf("club_title", "club_accounts", "club_ranking", "market_digest")) assertTrue(k in kinds, "$k in $kinds")
        // The club ranking's news names the top club by value with its club value (not a member's).
        val ranking = events.single { it.kind == "club_ranking" }
        val valuer = book.valuer(input.rating, input.age, input.potential, input.overall)
        assertEquals(book.rankLines.last().value.first(), ranking.keys.first())
        assertEquals(ClubEconomy.clubValue(book, ranking.keys.first(), valuer).toString(), ranking.args.single())
        assertEquals(s, book.digest!!.season)
        // A repeat (a rollover replayed after a kill) changes nothing and tells the same news; so does the book read back.
        val text = book.encode()
        val version = book.version
        assertEquals(events, book.offseason(plan, input))
        assertEquals(text, book.encode())
        assertEquals(version, book.version)
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(events, back.offseason(plan, input))
        assertEquals(text, back.encode())
        // The same book and input make the same book.
        val (l2, plan2) = Cw.migrated()
        Cw.play(plan2)
        l2.close(plan2)
        assertEquals(events, l2.book.offseason(plan2, l2.input(l2.next(s), plan2)))
        assertEquals(text, l2.book.encode())
    }

    @Test
    fun seasonsOfTheClubWorldKeepEveryMemberInAClubWithTerms() {
        val (l, plan) = Cw.migrated(v2 = 1)
        l.season(2, plan = Cw.play(plan))
        for (n in 3..5) {
            val events = l.season(n)
            Cw.assertWhole(l.book, l.members)
            assertTrue(l.members.all { l.book.terms(it) != null })
            // Every club has a coach, and the accounts of the season it played (a club founded now has none yet).
            assertTrue(l.book.clubs.all { l.book.coachOf(it.id) != null && (it.founded > n || l.book.life(it.id)!!.last?.season == n) })
            assertEquals(n, l.book.season)
            assertTrue(events.isNotEmpty())
            // The moves of the club world's own types are saved as such and read back.
            assertEquals(l.book.history, ClubBook.decode(l.book.encode()).history)
        }
        val types = l.book.history.map { it.type }.toSet()
        assertTrue(ClubMoveType.FREE_AGENT in types || ClubMoveType.GRADUATE in types, types.toString())
    }

    /**
     * Step 10's dissolution for money (ENG 1.4, 1b.10): a club in its second season of 법정관리 that no
     * owner rescues is dissolved after the market, its `gone` line saying why ('F'); its members are
     * released and drafted, its coach goes to the pool and its money lines go; one `club_folded` tells it
     * (no `club_dissolved`), the same when asked again and from the book read back.
     */
    @Test
    fun aClubLeftInAdministrationIsDissolvedForMoney() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        Cw.play(plan)
        l.close(plan)
        val next = l.next(s)
        // Every club of a nation with two or more in its second season of administration, deep in debt: the life
        // step rescues seven in ten and dissolves one at most a season.
        val doomed = book.clubs.groupBy { it.nation }.values.filter { it.size >= 2 }.flatten().map { it.id }
        assertTrue(doomed.size >= 6, doomed.toString())
        for (id in doomed) w.clubs.getValue(id).apply {
            admin = 1
            balance = -100_000
        }
        val coach = doomed.associateWith { book.coachOf(it)?.id }
        val input = l.input(next, plan)
        val events = book.offseason(plan, input)
        val g = book.gone.filter { it.dissolved == s }.single { it.reason == ClubBook.MONEY }
        assertTrue(g.id in doomed && book.club(g.id) == null)
        Cw.assertWhole(book, next)
        val released = book.history.count { it.season == s && it.type == ClubMoveType.RELEASED && it.from == g.id }
        assertTrue(released > 0)
        assertTrue(book.coaches.none { it.club == g.id })
        assertTrue(book.coaches.single { it.id == coach.getValue(g.id) }.let { it.club == null || it.club != g.id })
        assertTrue(CoachMarket.report(book, s).moves.any { it.kind == 'L' && it.club == g.id })
        assertTrue(g.id !in w.economy.accounts && g.id !in w.economy.owners && g.id !in w.economy.sponsors)
        assertEquals(listOf(listOf(g.id) to listOf(released.toString())), events.filter { it.kind == "club_folded" }.map { it.keys to it.args })
        assertTrue(events.none { it.kind == "club_dissolved" && g.id in it.keys } && events.none { it.kind == "club_digest" && g.id in it.keys })
        val text = book.encode()
        assertEquals(events, book.offseason(plan, input))
        assertEquals(text, book.encode())
        val back = ClubBook.decode(text)
        assertEquals(ClubBook.MONEY, back.gone.single { it.id == g.id }.reason)
        assertEquals(events, back.offseason(plan, input))
    }

    @Test
    fun withTheClubWorldOffItIsFrozenAndOnAgainItResumes() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        l.season(3, plan = Cw.play(plan))
        val w = book.world!!
        fun lines(text: String, prefixes: Set<String>) = text.lines().filter { it.substringBefore(' ') in prefixes }
        val kept = setOf("cw", "k", "kr", "ms", "tx", "rk", "rv", "dr", "lg", "rn", "cr")
        val before = book.encode()
        val balances = book.clubs.associate { it.id to w.clubs.getValue(it.id).balance }
        val accounts = book.clubs.associate { it.id to book.life(it.id)!!.last }
        val off = Cw.config.copy(clubWorld = false)
        val plan4 = Cw.play(Cw.fresh(l.members, 4))
        l.close(plan4)
        val next4 = l.next(4)
        val input4 = l.input(next4, plan4, off)
        val events = book.offseason(plan4, input4)
        l.members = next4
        val after = book.encode()
        // Today's window priced in its units; its fees are kept as money (× 0.6, as at the migration), told so, and so again by a repeat.
        val domestic = book.history.filter { it.season == 4 && it.type == ClubMoveType.TRANSFER && it.from != null }
        assertTrue(domestic.isNotEmpty())
        for (m in domestic) assertEquals(Math.round(ClubBook.fee(ClubBook.value(Cw.rating(m.ai))) * 0.6).toInt(), m.amount, m.toString())
        for (e in events.filter { it.kind == "transfer" }) assertEquals(domestic.last { it.ai == e.keys[0] }.amount.toString(), e.args[0])
        assertEquals(events, book.offseason(plan4, input4))
        assertEquals(after, book.encode())
        assertEquals(events, ClubBook.decode(after).offseason(plan4, input4))
        // No money, no coaches, no stories, no history: those lines stay as they were.
        assertEquals(lines(before, kept), lines(after, kept))
        for (c in book.clubs) {
            if (c.id !in balances) continue
            assertEquals(balances[c.id], w.clubs.getValue(c.id).balance, c.id)
            assertEquals(accounts[c.id], book.life(c.id)!!.last, c.id)
            assertEquals(maxOf(0, balances.getValue(c.id)), c.budget)
        }
        assertEquals(3, w.market.windowSeason)
        // Today's off-season ran: its moves and its news, and the terms followed the members who moved.
        assertEquals(4, book.season)
        assertTrue(book.history.any { it.season == 4 }, "today's window and draft")
        assertTrue(events.none { it.kind == "club_accounts" || it.kind == "club_ranking" })
        val moved = book.history.filter { it.season == 4 && it.to != null && it.kind == yacht.engine.league.ClubMoveKind.TRANSFER }
        for (m in moved) if (book.clubOf(m.ai) == m.to && book.terms(m.ai) != null) assertEquals(5, book.terms(m.ai)!!.since, m.toString())
        assertEquals(after, ClubBook.decode(after).encode())
        // On again: the next open runs the club world's off-season from the frozen state.
        val again = l.season(5)
        Cw.assertWhole(book, l.members)
        assertTrue(book.clubs.filter { it.founded <= 5 }.all { book.life(it.id)?.last?.season == 5 }, "accounts again, for every club that played (also one founded meanwhile)")
        assertTrue(l.members.all { book.terms(it) != null })
        assertTrue(again.any { it.kind == "club_accounts" })
        assertEquals(5, w.market.windowSeason)
    }

    @Test
    fun theTestKnobsTurnOffTheCoachesTheStoriesAndTheCheckpoint() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        Cw.play(plan)
        l.close(plan)
        val next = l.next(s)
        // A transfer story in talks: the market's stories decide it, unless the knob is off.
        val buyer = book.clubs.first { book.clubsOf(it.nation).size >= 2 }
        val seller = book.clubsOf(buyer.nation).first { it.id != buyer.id }
        val key = seller.squad.first { it in next }
        w.market.stories += MarketStory(1, s, StoryStage.TALKS, key, buyer.id, seller.id, 10, 28, 30, '-', false, null)
        val copy = ClubBook.decode(book.encode())
        book.offseason(plan, l.input(next, plan, coaches = false, market = false))
        assertTrue(book.coaches.none { it.done == s }, "no coach market")
        assertTrue(book.stories(s).none { it.id == 1 }, "the talks were not held")
        copy.offseason(plan, l.input(next, plan))
        assertTrue(copy.coaches.any { it.done == s })
        assertTrue(copy.stories(s).any { it.id == 1 && it.stage != StoryStage.TALKS }, "the talks were held: ${copy.stories(s)}")
        // The checkpoint: nothing at all with its knob off (the hook after it is not reached).
        var reached = false
        book.fault = { if (it == ClubBook.CHECKPOINT) reached = true }
        val next2 = Cw.fresh(l.members, s + 1)
        book.checkpoint(next2, l.input(l.members, next2, winter = false), ClubWorld.winterWeek(next2))
        assertFalse(reached)
        book.checkpoint(next2, l.input(l.members, next2), ClubWorld.winterWeek(next2))
        assertTrue(reached)
        book.fault = null
        assertTrue(CoachMarket.report(book, s).moves.isEmpty())
        assertEquals(s, w.market.windowSeason)
        assertTrue(Market.report(book, s).deals.isNotEmpty())
    }
}
