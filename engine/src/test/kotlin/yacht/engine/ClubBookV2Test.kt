package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubLeague
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubTable
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.HonourKind
import yacht.engine.league.Hometowns
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRanking
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Leagues
import yacht.engine.league.MemoryCoefficients
import yacht.engine.league.NationCup
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.Origin
import yacht.engine.league.PlayerBook
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.Transfers
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Clubs as every player's affiliation (`clubs 2`, design ENG 3): [ClubBook.found], the national
 * off-season, [ClubBook.migrate] from the club league's book, ids, names and the file.
 */
class ClubBookV2Test {
    private val config = LeagueConfig.DEFAULT

    /** Ratings and nations of keys that are not in [players] (pupils, hand-made members). */
    private val extraRating = HashMap<String, Double>()
    private val extraNation = HashMap<String, String?>()

    private fun rating(k: String): Double =
        extraRating[k] ?: players[k]?.identity?.abilities?.overall?.let { 900.0 + 12 * it + Math.floorMod(k.hashCode(), 17) } ?: 1500.0

    private fun nation(k: String): String? = if (k in extraNation) extraNation[k] else players[k]?.identity?.nation

    private fun age(k: String): Int? = players[k]?.age(2) ?: 22

    /** A national season of [keys] with every league match played (the better rated wins four times in five). */
    private fun season(keys: List<String>, number: Int): SeasonPlan {
        val plan = Offseason.first(31L, Offseason.Context(keys, ::rating, { null }, { "" }, config, nation = ::nation), number)
        val s = plan.league
        while (true) {
            val f = s.next(emptySet()) ?: break
            val aWins = (rating(f.a) >= rating(f.b)) != (f.id % 5 == 0)
            s.record(f.id, List(2) { if (aWins) GameScore(220, 180) else GameScore(180, 220) })
        }
        assertTrue(s.finished)
        return plan
    }

    private fun ranking(plan: SeasonPlan): LeagueRanking {
        val s = plan.league
        return Coefficients.ranking(MemoryCoefficients(), s.leagues, plan.number, { id -> Coefficients.strength(s.leagues.byId(id)!!.divisions.flatMap { s.divisions[it] }, ::rating) }, config)
    }

    /** Every member in exactly one club, every club with members, each member playing for its club's nation. */
    private fun assertWhole(book: ClubBook, members: Collection<String>) {
        val all = book.clubs.flatMap { it.squad }
        assertEquals(all.size, all.toSet().size, "a member in two clubs")
        assertEquals(members.toSet(), all.toSet())
        for (c in book.clubs) {
            assertTrue(c.squad.isNotEmpty(), "${c.id} has no members")
            assertTrue(c.budget >= 0, "${c.id} budget ${c.budget}")
            assertTrue(Nations.of(c.nation) != null, c.nation)
            for (k in c.squad) {
                assertEquals(c.id, book.clubOf(k))
                assertEquals(c.nation, book.playNation(k))
            }
        }
    }

    /** Club counts and sizes per nation within the rules (`C(n)`, or no churn; sizes within the bounds). */
    private fun assertSizes(book: ClubBook) {
        for ((nation, clubs) in book.clubs.groupBy { it.nation }) {
            val n = clubs.sumOf { it.squad.size }
            val c = clubs.size
            val want = ClubBook.clubCount(n, config.clubSize)
            fun off(clubs: Int) = Math.abs(n.toDouble() / clubs - config.clubSize)
            assertTrue(c == want || off(c) <= 2 || off(want) >= off(c), "$nation: $c clubs for $n")
            for (club in clubs) assertTrue(club.squad.size in ClubBook.minSize(n, c)..ClubBook.maxSize(n, c), "${club.id} of $nation: ${club.squad.size} of $n in $c")
        }
    }

    @Test
    fun theCountRuleAndTheSizeBounds() {
        val c = { n: Int -> ClubBook.clubCount(n, 8) }
        assertEquals(listOf(0, 1, 1, 1, 2, 5, 5, 5, 13, 16, 16), listOf(0, 1, 4, 11, 12, 36, 37, 40, 103, 137, 400).map(c))
        assertEquals(2, ClubBook.clubCount(24, 12))
        assertEquals(5, ClubBook.minSize(37, 5))
        assertEquals(11, ClubBook.maxSize(37, 5))
        assertEquals(6, ClubBook.minSize(32, 4))
        assertEquals(11, ClubBook.maxSize(32, 4))
        assertEquals(listOf(60, 60, 250, 300), listOf(0, 100, 1000, 3000).map(ClubBook::historyCap))
        assertEquals("c07", ClubBook.idOf(7))
        assertEquals("c100", ClubBook.idOf(100))
        assertEquals("c1295", ClubBook.idOf(ClubBook.MAX_ID))
        assertEquals(listOf("c9", "c10", "c100", "x"), listOf("x", "c100", "c10", "c9").sortedWith(ClubBook.ID_ORDER))
        // Every nation has at least 10 club cities, its own (by weight) first.
        for (n in Nations.ALL) {
            val cities = ClubNames.cities(n)
            assertTrue(cities.size >= 10, "${n.code} ${cities.size}")
            assertEquals(n.cities.names.sortedWith(compareByDescending<String> { n.cities.weight(it) }.thenBy { it }), cities.take(n.cities.size))
            assertEquals(cities.size, cities.toSet().size, n.code)
        }
        assertEquals(48, ClubNames.MASCOTS.toSet().size)
    }

    @Test
    fun foundingDraftsEachNationIntoItsOwnClubs() {
        val nationless = listOf(LeagueRoster.ADAPTIVE, "base1", "base2", "custom-1")
        val keys = big.alive.map { it.key } + nationless
        val rate = { k: String -> big[k]?.identity?.abilities?.overall?.let { 900.0 + 12 * it + Math.floorMod(k.hashCode(), 17) } ?: 1500.0 }
        val nat = { k: String -> big[k]?.identity?.nation }
        val book = ClubBook.found(keys, nat, rate, 0, 5L, config)
        assertEquals(ClubBook.AFFILIATION, book.format)
        assertWhole(book, keys)
        for (k in nationless) assertEquals("KR", book.playNation(k), k)
        // C(n) clubs per nation, a snake draft (sizes within one, the best spread over the clubs).
        val home = keys.groupBy { ClubBook.homeOf(nat(it)) }
        for ((code, members) in home) {
            val clubs = book.clubsOf(code)
            assertEquals(ClubBook.clubCount(members.size, config.clubSize), clubs.size, code)
            val sizes = clubs.map { it.squad.size }
            assertTrue(sizes.max() - sizes.min() <= 1, "$code $sizes")
            val best = members.sortedByDescending(rate).take(clubs.size)
            assertEquals(clubs.size, best.map { book.clubOf(it) }.toSet().size, "$code: the best ${clubs.size} in different clubs")
            // Names: unique mascots in a nation; cities in founding order.
            assertEquals(clubs.size, clubs.map { it.mascot }.toSet().size)
            val cities = ClubNames.cities(Nations.of(code)!!)
            for ((i, c) in clubs.withIndex()) {
                assertEquals(cities[i % cities.size], c.city)
                assertEquals("${c.city} ${c.mascot}", c.name)
                assertEquals(c.mascot.first(), c.emblem.initial)
                assertEquals(1, c.founded)
                assertEquals(ClubBook.START_BUDGET, c.budget)
            }
        }
        // More than 99 clubs: ids go on past c99, in order.
        val ids = book.clubs.map { it.id }
        assertTrue(ids.size > 300, "${ids.size}")
        assertEquals((1..ids.size).map { ClubBook.idOf(it) }.toSet(), ids.toSet())
        assertEquals(ids, ids.sortedWith(ClubBook.ID_ORDER))
        assertEquals(book.clubs.size, book.clubs.map { it.name }.toSet().size, "a name twice")
        // The file: `clubs 2`, read back the same; the same seed makes the same book, another seed another.
        val text = book.encode()
        assertTrue(text.startsWith("clubs 2\n"))
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(book.clubs, back.clubs)
        assertEquals(text, ClubBook.found(keys, nat, rate, 0, 5L, config).encode())
        assertNotEquals(book.clubs.map { it.name }, ClubBook.found(keys, nat, rate, 0, 6L, config).clubs.map { it.name })
        assertNotEquals(book.version, back.version)
        // The club cup's view of the book.
        for (c in book.clubs.take(5)) assertEquals(c.squad, book.members(c.id))
        assertEquals(emptyList(), book.champions(1))
        assertEquals(emptyList(), book.members("c99999"))
        // Its off-season after season 0 is done: a repeat changes nothing.
        assertEquals(0, book.season)
    }

    @Test
    fun aNationalOffseasonKeepsEveryMemberInOneClubOfItsNation() {
        val generated = players.alive.map { it.key }
        val nationless = listOf(LeagueRoster.ADAPTIVE, "base1", "base2", "custom-1")
        val first = generated.take(1040) + nationless
        val closing = season(first, 1)
        val book = ClubBook.found(first, ::nation, ::rating, 0, 77L, config)
        val before = ClubBook.decode(book.encode())
        val version0 = book.version
        val honours = Offseason.honours(closing, Offseason.moves(closing)).toMutableList()
        val champions = honours.filter { HonourKind.base(it.kind) == "L0" }.map { it.key }
        val someone = { i: Int -> first[i * 37 % first.size] }
        honours += listOf(
            Honour(1, "CUP", someone(1)), Honour(1, "CUP2", someone(2)), Honour(1, "CL", someone(3)), Honour(1, "CCL.ASIA", someone(4)),
            Honour(1, "MVP", someone(5)), Honour(1, "CLUBCUP", "c03"), Honour(1, "CLUBCUP2", "c09"),
        )
        // A cup winner of long ago leaves too (notable), and so do 2부 players (not).
        val oldCup = first.first { it in generated && it !in champions && closing.league.leagues.tier(closing.league.divisionOf(it)!!) > 0 }
        honours += Honour(0, "CUP", oldCup)
        val retired = (first.filter { it in generated && it != oldCup }.shuffled(Random(3)).take(38) + champions.first() + oldCup).toSet()
        val rookies = generated.drop(1040)
        val stay = first.filter { it in generated && it !in retired }
        val mentors = HashMap<String, String>()
        val pupils = (1..20).map { i ->
            val m = stay[i * 41 % stay.size]
            "e2_$i".also {
                mentors[it] = m
                extraNation[it] = nation(m)
                extraRating[it] = 1300.0 + i
            }
        }
        val next = first.filter { it !in retired } + rookies + pupils
        val input = ClubInput(next, ::rating, ::nation, config, honours, ::age, Origin::of, { mentors[it] }, ranking(closing))
        val events = book.offseason(closing, input)
        assertWhole(book, next)
        assertSizes(book)
        val moves = book.history.filter { it.season == 1 }
        // Each member plays for its own nation unless it moved abroad now.
        val abroad = moves.filter { it.type == ClubMoveType.ABROAD }.map { it.ai }.toSet()
        for (k in next) if (k !in abroad) assertEquals(ClubBook.homeOf(nation(k)), book.playNation(k), k)

        // 1. The season's club tables: one line per league, money by place and honours, a title for each champion.
        val tables = closing.league.leagues.list.associate { it.id to ClubTable.of(closing.league, before, it.id, honours.filter { h -> h.season == 1 }) }
        val places = book.places(1)
        assertEquals(tables.keys, places.map { it.league }.toSet())
        for (p in places) {
            val t = tables.getValue(p.league)
            assertEquals(t.take(3).map { it.club }, p.clubs)
            assertEquals(t.first().points, p.points)
            assertEquals(1, book.club(p.clubs.first())?.honours ?: book.gone(p.clubs.first())!!.honours)
        }
        assertEquals(places.map { it.clubs.first() }, book.champions(1))
        val resized = book.gone.any { it.dissolved == 1 } || book.clubs.any { it.founded == 2 }
        if (!resized) {
            val prizes = tables.values.sumOf { t -> t.indices.sumOf { ClubBook.prize(it + 1) } }
            val honourMoney = honours.filter { it.season == 1 }.sumOf { h ->
                val holder = if (HonourKind.base(h.kind).startsWith("CLUBCUP")) before.club(h.key)?.id else before.clubOf(h.key)
                if (holder == null) 0 else ClubBook.honourMoney(h.kind)
            }
            assertTrue(honourMoney >= 20 + 10 + 30 + 25 + 30 + 15, "$honourMoney")
            assertEquals(before.clubs.sumOf { it.budget } + prizes + honourMoney, book.clubs.sumOf { it.budget }, "money is only won, never made by moves")
        }

        // 2. Departures: a move only for the notable (a major title, or the 1부 at the close).
        val tier = { k: String -> closing.league.divisionOf(k)?.let { closing.league.leagues.tier(it) } }
        val notable = retired.filter { k -> tier(k) == 0 || honours.any { it.key == k && HonourBook.isMajor(it) } }.toSet()
        assertTrue(oldCup in notable && champions.first() in notable)
        assertEquals(notable, moves.filter { it.type == ClubMoveType.RETIRED }.map { it.ai }.toSet())

        // 3 and 4. Transfers abroad (nobody was abroad yet, so nobody returns).
        val ranking = input.ranking
        val f = moves.filter { it.type == ClubMoveType.ABROAD }
        assertTrue(f.size in 1..Transfers.count(config, next.size), "${f.size}")
        val leagueOf = Transfers.leagues(closing.league, before::clubOf, before::nationOf)
        for (m in f) {
            assertTrue(Transfers.abroad(nation(m.ai), book.playNation(m.ai)), m.toString())
            val from = closing.league.leagues.of(closing.league.divisionOf(m.ai)!!).id
            val to = leagueOf.getValue(book.nationOf(m.to!!)!!)
            assertTrue(ranking.rank(to) <= ranking.rank(from) - Transfers.GAP, "$m $from $to")
            assertEquals(0, tier(m.ai))
        }
        assertEquals(emptyList(), moves.filter { it.type == ClubMoveType.HOME })
        assertEquals(f.map { it.ai }.toSet(), book.moved(1, ClubMoveType.ABROAD))

        // 5. The domestic window: worst club first, a seller placed above the buyer, at the fee; C/2 deals a nation at most.
        val place = tables.values.flatMap { t -> t.mapIndexed { i, row -> row.club to i + 1 } }.toMap()
        val t = moves.filter { it.type == ClubMoveType.TRANSFER }
        assertTrue(t.isNotEmpty())
        for (m in t) {
            assertTrue(place.getValue(m.from!!) < place.getValue(m.to!!), m.toString())
            assertEquals(before.nationOf(m.from!!), before.nationOf(m.to!!))
            assertEquals(ClubBook.fee(ClubBook.value(rating(m.ai))), m.amount)
        }
        for ((code, deals) in t.groupBy { before.nationOf(it.from!!) }) assertTrue(deals.size <= before.clubsOf(code!!).size / 2, "$code ${deals.size}")

        // 7. Pupils join their mentor's club when it is of their nation; the rest are drafted.
        val y = moves.filter { it.type == ClubMoveType.YOUTH }
        assertTrue(y.size >= 10, "${y.size}")
        for (m in y) {
            assertEquals(book.clubOf(mentors.getValue(m.ai)), m.to)
            assertEquals(nation(m.ai), book.nationOf(m.to!!))
        }
        val d = moves.filter { it.type == ClubMoveType.DRAFT }
        assertTrue(d.isNotEmpty() && d.all { it.ai in rookies || it.ai in pupils || it.ai in first })

        // News, comp CLUB: four club titles and a digest, at most 3 named of each kind.
        assertTrue(events.all { it.comp == Comp.CLUB && it.season == 1 }, events.toString())
        val titles = events.filter { it.kind == "club_title" }
        assertEquals(places.take(4).map { listOf(it.clubs.first()) }, titles.map { it.keys })
        assertEquals(places.take(4).map { it.league }, titles.map { it.args[0] })
        assertEquals((places.size - 4).toString(), events.single { it.kind == "club_champions" }.args[0])
        assertTrue(events.count { it.kind == "transfer" } <= 3)
        assertTrue(events.count { it.kind == "draft" || it.kind == "youth" } == 3)
        assertTrue(events.count { it.kind == "transfer_abroad" } in 1..3)
        assertEquals(listOf(f.size.toString(), "0"), events.single { it.kind == "transfer_window" }.args)
        val digest = events.single { it.kind == "market_digest" }
        assertEquals(t.size.toString(), digest.args[0])
        assertEquals((rookies.size + pupils.size).toString(), digest.args[1])
        assertEquals(t.maxByOrNull { it.amount }!!.amount.toString(), digest.args[5])
        assertTrue(events.size <= 25, "${events.size}")

        // The national teams are by nation, whatever the clubs.
        fun teams(b: ClubBook) = NationCup.teams(
            SeasonStart(2, 1L, config, next.sortedByDescending(::rating), ::rating, emptyList(), emptyList(), emptyList(), { null }, { "" }, nation = ::nation, clubOf = b::clubOf, clubNation = b::playNation),
        ).first.map { it.id to it.squad }
        assertEquals(teams(before), teams(book))

        // A repeat (a rollover replayed after a kill) changes nothing and tells the same news, also read back.
        val text = book.encode()
        val version1 = book.version
        assertNotEquals(version0, version1)
        assertEquals(events, book.offseason(closing, input))
        assertEquals(text, book.encode())
        assertEquals(version1, book.version)
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(book.digest, back.digest)
        assertEquals(book.clubs, back.clubs)
        assertEquals(book.gone, back.gone)
        assertEquals(book.history, back.history)
        assertEquals(book.places(1), back.places(1))
        assertEquals(events, back.offseason(closing, input))
        assertEquals(text, back.encode())
        // Replayed with a member gone and one more without a club: the same news; it leaves, the other is drafted.
        val leaving = next.first { it in stay && it !in mentors.values }
        extraNation["g99999"] = "BR"
        extraRating["g99999"] = 1400.0
        val changed = ClubInput(next - leaving + "g99999", ::rating, ::nation, config, honours, ::age, Origin::of, { mentors[it] }, input.ranking)
        assertEquals(events, back.offseason(closing, changed))
        assertNull(back.clubOf(leaving))
        assertEquals("BR", back.playNation("g99999"))
        assertWhole(back, next - leaving + "g99999")
        assertNotEquals(text, back.encode())
    }

    @Test
    fun playersAbroadPlayInTheirClubsLeagueAndComeHome() {
        // Four seasons as the world will run them: the club off-season, then the next season's
        // leagues by each member's club nation, with the transfers in and the returns.
        val keys = players.alive.map { it.key }.take(1000)
        var plan = season(keys, 1)
        val book = ClubBook.found(keys, ::nation, ::rating, 0, 4L, config)
        var history = emptyList<Honour>()
        val abroadEver = HashSet<String>()
        for (n in 1..4) {
            val honours = Offseason.honours(plan, Offseason.moves(plan))
            history = history + honours
            val ranking = ranking(plan)
            val input = ClubInput(keys, ::rating, ::nation, config, history, { k -> players[k]?.age(n + 1) }, Origin::of, ranking = ranking)
            book.offseason(plan, input)
            assertWhole(book, keys)
            val out = book.moved(n, ClubMoveType.ABROAD)
            val back = book.moved(n, ClubMoveType.HOME)
            abroadEver += out
            for (k in back) assertTrue(k in abroadEver, "$k came home without going")
            val ctx = Offseason.Context(
                keys, ::rating, { null }, { "" }, config, history = history, nation = ::nation,
                playNation = { k -> book.playNation(k) ?: nation(k) }, transfersIn = out, returned = back, ranking = ranking,
            )
            val next = Offseason.next(plan, ctx).first
            val s = next.league
            // Each member plays in the league of its club's nation; those who moved abroad start in its 1부.
            val leagueOf = Transfers.leagues(s, book::clubOf, book::nationOf)
            for (k in keys) assertEquals(leagueOf[book.playNation(k)], s.leagues.of(s.divisionOf(k)!!).id, k)
            for (k in out) assertEquals(0, s.leagues.tier(s.divisionOf(k)!!), k)
            // Nationality never changes: a player abroad still plays for its own national team.
            for (k in out) assertTrue(Transfers.abroad(nation(k), book.playNation(k)))
            val s2 = s
            while (true) {
                val f = s2.next(emptySet()) ?: break
                val aWins = (rating(f.a) >= rating(f.b)) != (f.id % 5 == 0)
                s2.record(f.id, List(2) { if (aWins) GameScore(220, 180) else GameScore(180, 220) })
            }
            next.ranking = ranking
            plan = next
        }
        assertTrue(abroadEver.size >= 8, "${abroadEver.size}")
        assertTrue(book.history.any { it.type == ClubMoveType.HOME }, "nobody came home in four seasons")
    }

    @Test
    fun theWindowGoesWorstFirstAndPupilsJoinTheirMentors() {
        // Korea alone, four clubs of eight; the club table c01 > c02 > c03 > c04.
        val clubs = listOf("c01" to "a", "c02" to "b", "c03" to "c", "c04" to "d")
        val text = buildString {
            append("clubs 2\nmeta 3 0 - 5\n")
            for ((id, p) in clubs) append("club $id KR 도시 $id 도시%20$id 0 ffc62828 ffffffff 가 100 0 1 ${(1..8).joinToString(",") { "$p$it" }}\n")
        }
        val book = ClubBook.decode(text)
        val base = mapOf("a" to 1600.0, "b" to 1550.0, "c" to 1500.0, "d" to 1450.0)
        for ((_, p) in clubs) for (i in 1..8) {
            extraRating["$p$i"] = base.getValue(p) - 10 * i
            extraNation["$p$i"] = "KR"
        }
        val top = clubs.flatMap { (_, p) -> (1..4).map { "$p$it" } }
        val second = clubs.flatMap { (_, p) -> (5..8).map { "$p$it" } }
        val season = Season.start(1, 4L, listOf(top, second), false, Leagues.national(listOf("KR"), listOf(2)))
        for ((_, p) in clubs) for (i in 1..8) season.standings.getValue("$p$i").points = mapOf("a" to 30, "b" to 20, "c" to 10, "d" to 0).getValue(p)
        val closing = SeasonPlan(season, config)
        // Pupils: two with their mentor's club (c02 and c04), one whose mentor left; a rookie.
        val pupils = mapOf("e2_1" to "b1", "e2_2" to "d1", "e2_3" to "zz")
        for ((i, k) in (pupils.keys + "g0001").withIndex()) {
            extraRating[k] = 1400.0 - 10 * i
            extraNation[k] = "KR"
        }
        val members = top + second + pupils.keys + "g0001"
        val events = book.offseason(closing, ClubInput(members, ::rating, ::nation, config, mentor = { pupils[it] }))
        val moves = book.history.filter { it.season == 1 }
        // c04 buys first, then c03; C/2 = 2 deals. Each takes the best of the clubs above it it can afford.
        val t = moves.filter { it.type == ClubMoveType.TRANSFER }
        assertEquals(listOf(Triple("a1", "c01", "c04"), Triple("a2", "c01", "c03")), t.map { Triple(it.ai, it.from, it.to) })
        assertEquals(listOf(ClubBook.fee(ClubBook.value(1590.0)), ClubBook.fee(ClubBook.value(1580.0))), t.map { it.amount })
        // Pupils at their mentors' clubs, though c01 is the smallest; the others to the smallest club, c01.
        assertEquals(listOf("e2_1" to "c02", "e2_2" to "c04"), moves.filter { it.type == ClubMoveType.YOUTH }.map { it.ai to it.to })
        assertEquals("c01", book.clubOf("e2_3"))
        assertEquals("c01", book.clubOf("g0001"))
        // Draft moves: a club's first pick (and the named rookies): g0001 was c01's second, and is not one of the 3 named.
        assertEquals(listOf("e2_3" to 1), moves.filter { it.type == ClubMoveType.DRAFT }.map { it.ai to it.amount })
        // Money: prizes 38, 36, 34, 32; the fees change hands.
        val budget = book.clubs.associate { it.id to it.budget }
        assertEquals(100 + 38 + t[0].amount + t[1].amount, budget["c01"])
        assertEquals(100 + 32 - t[0].amount, budget["c04"])
        assertEquals(100 + 34 - t[1].amount, budget["c03"])
        assertEquals(1, book.club("c01")!!.honours)
        // News.
        assertEquals(
            listOf("club_title", "transfer", "transfer", "youth", "youth", "draft", "market_digest"),
            events.map { it.kind },
        )
        // c01: a1-a4 30 each in the 1부, a5 and a6 30 × 0.6 in the 2부.
        assertEquals(LeagueEvent("club_title", 1, listOf("c01"), listOf("KR", "156.0"), Comp.CLUB), events[0])
        assertEquals(listOf("2", "4", "a1", "c01", "c04", t[0].amount.toString()), events.last().args)
        assertEquals(listOf(LeagueEvent("draft", 1, listOf("e2_3", "c01"), listOf("1"), Comp.CLUB)), events.filter { it.kind == "draft" })
    }

    @Test
    fun clubCountsHoldNearTheEdgeAndNewClubsReachTheFloor() {
        // KR: one club of 13 (two would be 6.5 each: a second club). JP: two clubs of 11 (one of 11
        // would be no closer to 8 than two of 5.5: both stay). CN: two of 10 (one of 10 is closer
        // than two of 5: one goes). Nobody new, so the new Korean club is filled from c01.
        val squads = mapOf("c01" to ("KR" to (1..13).map { "k$it" }), "c02" to ("JP" to (1..6).map { "j$it" }), "c03" to ("JP" to (7..11).map { "j$it" }))
            .plus(mapOf("c04" to ("CN" to (1..5).map { "n$it" }), "c05" to ("CN" to (6..10).map { "n$it" })))
        val text = buildString {
            append("clubs 2\nmeta 3 0 - 6\n")
            for ((id, p) in squads) append("club $id ${p.first} 도시 $id 도시%20$id 0 ffc62828 ffffffff 가 0 0 1 ${p.second.joinToString(",")}\n")
        }
        for ((_, p) in squads) for ((i, k) in p.second.withIndex()) {
            extraNation[k] = p.first
            extraRating[k] = 1500.0 - 10 * i
        }
        val book = ClubBook.decode(text)
        val members = squads.values.flatMap { it.second }
        fun closing(n: Int) = SeasonPlan(
            Season.start(n, 5L + n, listOf("KR", "JP", "CN").map { c -> members.filter { extraNation[it] == c } }, false, Leagues.national(listOf("KR", "JP", "CN"), listOf(1, 1, 1))),
            config,
        )
        val events = book.offseason(closing(1), ClubInput(members, ::rating, ::nation, config))
        assertWhole(book, members)
        assertSizes(book)
        val kr = book.clubsOf("KR")
        assertEquals(2, kr.size)
        assertEquals(listOf(9, 4), kr.map { it.squad.size })
        // c01 let its four weakest go, and the new club drafted them.
        val moves = book.history.filter { it.season == 1 }
        assertEquals((10..13).map { "k$it" }.toSet(), moves.filter { it.type == ClubMoveType.RELEASED && it.from == "c01" }.map { it.ai }.toSet())
        assertEquals((10..13).map { "k$it" }.toSet(), kr[1].squad.toSet())
        assertEquals(listOf("c02", "c03"), book.clubsOf("JP").map { it.id })
        assertEquals(listOf("c04"), book.clubsOf("CN").map { it.id })
        assertEquals(listOf("c05"), book.gone.map { it.id })
        assertEquals(listOf(kr[1].id), events.filter { it.kind == "club_founded" }.map { it.keys.single() })
        assertEquals(listOf(LeagueEvent("club_dissolved", 1, listOf("c05"), listOf("5"), Comp.CLUB)), events.filter { it.kind == "club_dissolved" })
        // A season later the same members keep the same clubs.
        val again = book.offseason(closing(2), ClubInput(members, ::rating, ::nation, config))
        assertEquals(kr.map { it.id }, book.clubsOf("KR").map { it.id })
        assertEquals(2, book.clubsOf("JP").size)
        assertEquals(1, book.clubsOf("CN").size)
        assertTrue(again.none { it.kind == "club_founded" || it.kind == "club_dissolved" }, again.toString())
    }

    @Test
    fun aNewClubSizeFillsTheNewClubsAndMakesOneArticle() {
        // Korea, four clubs of ten; the club size goes from 8 to 5: eight clubs.
        val keys = (1..40).map { "k$it" }
        val text = "clubs 2\nmeta 3 0 - 5\n" + (0 until 4).joinToString("") { c ->
            "club c0${c + 1} KR 도시 c0${c + 1} 도시%20c0${c + 1} 0 ffc62828 ffffffff 가 0 0 1 ${keys.drop(10 * c).take(10).joinToString(",")}\n"
        }
        for ((i, k) in keys.withIndex()) {
            extraNation[k] = "KR"
            extraRating[k] = 1500.0 - i
        }
        val book = ClubBook.decode(text)
        val closing = SeasonPlan(Season.start(1, 6L, listOf(keys), false, Leagues.national(listOf("KR"), listOf(1))), config)
        val small = config.copy(clubSize = 5)
        val events = book.offseason(closing, ClubInput(keys, ::rating, ::nation, small))
        assertWhole(book, keys)
        assertEquals(listOf(7, 7, 7, 7, 3, 3, 3, 3), book.clubsOf("KR").map { it.squad.size })
        assertEquals(12, book.history.count { it.season == 1 && it.type == ClubMoveType.RELEASED })
        assertEquals(listOf(LeagueEvent("club_digest", 1, listOf("c05", "c06", "c07", "c08"), listOf("4", "0"), Comp.CLUB)), events.filter { it.kind.startsWith("club_") && it.kind != "club_title" })
        assertEquals(events, book.offseason(closing, ClubInput(keys, ::rating, ::nation, small)))
    }

    @Test
    fun onlyPlayersNewToTheLeagueJoinTheirMentorsClub() {
        // Korea, two clubs; an evolved veteran who played the closing season is without a club (a
        // cut file), and so is a pupil new to the league: both have a mentor in c01.
        val text = "clubs 2\nmeta 3 0 - 3\n" +
            "club c01 KR 도시 c01 도시%20c01 0 ffc62828 ffffffff 가 0 0 1 ${(1..8).joinToString(",") { "a$it" }}\n" +
            "club c02 KR 도시 c02 도시%20c02 0 ffc62828 ffffffff 가 0 0 1 ${(1..7).joinToString(",") { "b$it" }}\n"
        val book = ClubBook.decode(text)
        val old = (1..8).map { "a$it" } + (1..7).map { "b$it" } + "e1_1"
        for ((i, k) in (old + "e3_1").withIndex()) {
            extraNation[k] = "KR"
            extraRating[k] = 1500.0 - i
        }
        val closing = SeasonPlan(Season.start(1, 6L, listOf(old), false, Leagues.national(listOf("KR"), listOf(1))), config)
        val mentors = mapOf("e1_1" to "a1", "e3_1" to "a1")
        book.offseason(closing, ClubInput(old + "e3_1", ::rating, ::nation, config, mentor = { mentors[it] }))
        val moves = book.history.filter { it.season == 1 }
        assertEquals(listOf("e3_1" to "c01"), moves.filter { it.type == ClubMoveType.YOUTH }.map { it.ai to it.to })
        assertEquals("c02", book.clubOf("e1_1"))
        assertEquals(ClubMoveType.DRAFT, moves.single { it.ai == "e1_1" }.type)
    }

    @Test
    fun aNewClubTakesNoNameOfADissolvedOne() {
        val jp = Nations.of("JP")!!
        // Japan's first new club after one dissolved one: city 1 and the first mascot of Japan's order.
        val first = ClubNames.identity("c03", jp, 1, 3L, emptySet())
        val text = "clubs 2\nmeta 3 4 - 3\n" +
            "club c01 KR 도시 c01 도시%20c01 0 ffc62828 ffffffff 가 100 0 1 ${(1..8).joinToString(",") { "k$it" }}\n" +
            "gone c02 JP ${first.name.replace(" ", "%20")} 0 ffc62828 ffffffff 가 3 1 4\n"
        val kr = (1..8).map { "k$it" }
        val jpKeys = (1..8).map { "j$it" }
        for (k in kr) extraNation[k] = "KR"
        for (k in jpKeys) extraNation[k] = "JP"
        val book = ClubBook.decode(text)
        val closing = SeasonPlan(Season.start(5, 7L, listOf(kr), false, Leagues.national(listOf("KR"), listOf(1))), config)
        book.offseason(closing, ClubInput(kr + jpKeys, ::rating, ::nation, config))
        val club = book.clubsOf("JP").single()
        assertEquals(first.city, club.city)
        assertNotEquals(first.name, club.name)
        assertNotEquals(first.mascot, club.mascot)
    }

    @Test
    fun idsGoUpTo1295AndComeBackOnlyAfter30Seasons() {
        val text = buildString {
            append("clubs 2\nmeta 3 35 - 1295\n")
            append("club c01 KR 서울 호랑이 서울%20호랑이 0 ffc62828 ffffffff 호 100 0 1 ${(1..8).joinToString(",") { "k$it" }}\n")
            append("gone c07 KR 부산%20돌고래 1 ff1565c0 ffffffff 돌 2 1 4\n")
            append("gone c20 KR 대구%20늑대 2 ff2e7d32 ffffffff 늑 0 3 20\n")
        }
        val book = ClubBook.decode(text)
        assertNotNull(book.gone("c07"))
        val kr = (1..8).map { "k$it" }
        val jp = (1..24).map { "j$it" }
        for (k in kr) extraNation[k] = "KR"
        for (k in jp) extraNation[k] = "JP"
        fun closing(n: Int) = SeasonPlan(Season.start(n, 9L + n, listOf(kr, jp), false, Leagues.national(listOf("KR", "JP"), listOf(1, 1))), config)
        val events = book.offseason(closing(36), ClubInput(kr + jp, ::rating, ::nation, config))
        // Japan needs 3 clubs: c1295, then c07 (dissolved 32 seasons ago); c20 (16) is not free yet.
        assertEquals(listOf("c07", "c1295"), book.clubsOf("JP").map { it.id })
        assertNull(book.gone("c07"))
        assertNotNull(book.gone("c20"))
        assertWhole(book, kr + jp)
        assertEquals(listOf(12, 12), book.clubsOf("JP").map { it.squad.size })
        assertEquals(setOf("c07", "c1295"), events.filter { it.kind == "club_founded" }.map { it.keys.single() }.toSet())
        assertTrue(events.filter { it.kind == "club_founded" }.all { it.args == listOf("JP") })
        assertEquals(37, book.club("c07")!!.founded)
        // The c07 of Japan is a new club: its own name, no titles.
        assertNotEquals("부산 돌고래", book.club("c07")!!.name)
        assertEquals(0, book.club("c07")!!.honours)
        // A season later still no id is free: no club is founded, the members wait in two.
        book.offseason(closing(37), ClubInput(kr + jp, ::rating, ::nation, config))
        assertEquals(listOf("c07", "c1295"), book.clubsOf("JP").map { it.id })
        val back = ClubBook.decode(book.encode())
        assertEquals(book.encode(), back.encode())
        assertEquals(book.gone, back.gone)
    }

    @Test
    fun theClubLeaguesBookBecomesNational() {
        val generated = players.alive.map { it.key }.take(1000)
        val nationless = listOf(LeagueRoster.ADAPTIVE, "base1")
        val keys = generated + nationless
        // A legacy season (a pyramid) with its club league, after an earlier off-season that gave titles.
        val v1 = ClubBook.create(keys.sortedByDescending(::rating), 16, 6, 12L, Hometowns.ALL)
        val ids = v1.clubs.map { it.id }
        v1.offseason(1, ids.reversed(), listOf(Honour(1, "CLUB", ids[14]), Honour(1, "CLUB", ids[3])), ::rating, keys.toSet(), 16, 6, 5L)
        val legacy = LeagueConfig()
        val plan = Offseason.first(8L, Offseason.Context(keys, ::rating, { null }, { "서울" }, legacy, listOf(ClubLeague.factory({ v1 })), nation = ::nation), 2)
        assertNotNull(plan.competition(Comp.CLUB))
        val rng = Random(1)
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(
                when (f.kind) {
                    yacht.engine.league.JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> yacht.engine.league.BoardResult(x, y, List(2) { if (rng.nextBoolean()) GameScore(200, 150) else GameScore(150, 200) }, emptyList()) }
                        .let { yacht.engine.league.JobResult(f, it, yacht.engine.league.TieRule.team(it), 0) }
                    else -> List(2) { if (rating(f.a) >= rating(f.b)) GameScore(200, 150) else GameScore(150, 200) }
                        .let { yacht.engine.league.JobResult(f, listOf(yacht.engine.league.BoardResult(f.a, f.b, it, emptyList())), yacht.engine.league.TieRule.match(it), 0) }
                },
            )
        }
        plan.startPost(emptyList())
        val old = ClubBook.decode(v1.encode())
        val leaving = keys.take(5).toSet()
        val next = keys - leaving
        val input = ClubInput(next, ::rating, ::nation, config)
        val events = v1.migrate(plan, input)
        assertEquals(ClubBook.AFFILIATION, v1.format)
        assertTrue(v1.encode().startsWith("clubs 2\n"))
        assertWhole(v1, next)
        assertSizes(v1)
        // Korea keeps its best C_KR clubs (titles, then money), with their ids, names, badges and titles.
        val korea = ClubBook.clubCount(next.count { ClubBook.homeOf(nation(it)) == "KR" }, config.clubSize)
        // The club league's money of season 2 came first: prizes by its table and the cups.
        val table = (plan.competition(Comp.CLUB) as ClubLeague).order()
        val paid = old.clubs.associate { c -> c.id to c.budget + ClubBook.prize(table.indexOf(c.id) + 1) }
        val kept = old.clubs.sortedWith(compareByDescending<yacht.engine.league.Club> { it.honours + if (it.id == table.first()) 1 else 0 }.thenByDescending { paid.getValue(it.id) }.thenBy { it.id }).take(korea).map { it.id }
        assertEquals(kept.sorted(), v1.clubsOf("KR").map { it.id }.filter { it in ids })
        for (id in kept) {
            val was = old.club(id)!!
            val now = v1.club(id)!!
            assertEquals(listOf(was.name, was.city, was.mascot), listOf(now.name, now.city, now.mascot))
            assertEquals(was.emblem, now.emblem)
            assertEquals("KR", now.nation)
            assertTrue(now.squad.all { ClubBook.homeOf(nation(it)) == "KR" })
        }
        assertEquals(ids.filter { it !in kept }.toSet(), v1.gone.map { it.id }.toSet())
        assertTrue(v1.gone.all { it.dissolved == 2 && it.nation == "KR" })
        // New clubs take ids after the old ones.
        assertEquals((17..(16 + v1.clubs.size - kept.size)).map { ClubBook.idOf(it) }, v1.clubs.map { it.id }.filter { it !in ids })
        // One article, then the same as a repeat (also through the plan's entry point and read back).
        assertEquals(listOf("club_reform"), events.map { it.kind })
        assertEquals(listOf(v1.clubs.size.toString(), v1.clubs.map { it.nation }.distinct().size.toString()), events.single().args)
        val text = v1.encode()
        assertEquals(events, v1.offseason(plan, input))
        assertEquals(text, v1.encode())
        assertEquals(events, ClubBook.decode(text).offseason(plan, input))
        val viaOffseason = ClubBook.decode(old.encode())
        assertEquals(events, viaOffseason.offseason(plan, input))
        assertEquals(text, viaOffseason.encode())
        // A club league's book still reads as one, and the club league no longer runs on a national book.
        assertEquals(ClubBook.CLUB_LEAGUE, old.format)
        assertNull(ClubLeague.factory({ v1 })(SeasonStart(3, 1L, legacy, next, ::rating, listOf(next), emptyList(), emptyList(), { null }, { "" })))
    }

    private companion object {
        /** 1100 generated players (1040 in the first season, 60 rookies). */
        val players: PlayerBook by lazy { PlayerBook().also { it.ensureGenerated(1100) } }

        /** 3000 generated players (more than 99 clubs). */
        val big: PlayerBook by lazy { PlayerBook().also { it.ensureGenerated(3000) } }
    }
}
