package yacht.engine

import yacht.engine.league.ArcContext
import yacht.engine.league.ArcEffects
import yacht.engine.league.ArcKind
import yacht.engine.league.ArcStage
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.Confed
import yacht.engine.league.EndedArc
import yacht.engine.league.Federation
import yacht.engine.league.FederationStats
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Lever
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Proposal
import yacht.engine.league.ProposalKind
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Takeover
import yacht.engine.league.WorldArc
import yacht.engine.league.WorldBook
import yacht.engine.league.WorldEvents
import yacht.engine.league.valuer
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * World events (design ENG 6): each of the five arcs through its stages from a forced trigger, the
 * triggers' seeded chance and cooldowns, one major arc at a time, the effects by each arc's schedule
 * (crisis factors and their scope, the investor's takeover and injections, the merger's merged set in
 * the league formation and its split, the boom's rookie boost and its end), the super league's
 * emergency reform upserted at every close and adopted once, replayed steps, the codec and the stale rule.
 */
class WorldEventsTest {
    private val config = LeagueConfig.DEFAULT

    @AfterTest
    fun noForcing() {
        WorldEvents.forceTrigger = null
    }

    private fun force(vararg at: Pair<ArcKind, Int>) {
        val set = at.toSet()
        WorldEvents.forceTrigger = { k, s -> (k to s) in set }
    }

    // ---- a club world (Cw's national leagues, migrated) ------------------------------------------

    private object Big {
        val league: Cw.League by lazy { Cw.migrated(600).first }
    }

    private fun book(): ClubBook = ClubBook.decode(Big.league.book.encode())

    private fun played(n: Int): SeasonPlan = Cw.play(Cw.fresh(Big.league.members, n))

    private fun ctx(book: ClubBook, plan: SeasonPlan, honours: List<Honour> = Big.league.honours, counts: Map<String, Int>? = null): ArcContext {
        val value = book.valuer(Cw::rating)
        return if (counts == null) {
            ArcContext(plan, config, book, Cw.ranking(plan), honours, value)
        } else {
            ArcContext(plan, config, book, Cw.ranking(plan), honours, value, counts = counts)
        }
    }

    private fun kinds(e: List<LeagueEvent>) = e.map { it.kind }

    // ---- SUPER_LEAGUE -------------------------------------------------------------------------

    @Test
    fun aSuperLeagueThreatRunsRumourAnnouncementShowdownAndACompromise() {
        val book = book()
        val world = WorldBook()
        force(ArcKind.SUPER_LEAGUE to 3)
        val p3 = played(3)
        assertEquals(emptyList(), WorldEvents.close(world, ctx(book, p3)).filter { it.kind.startsWith("superleague") })
        val arc = world.events.arcs.single()
        assertEquals(ArcKind.SUPER_LEAGUE, arc.kind)
        assertEquals(ArcStage.RUMOUR, arc.stage)
        assertEquals(3 to 4, arc.start to arc.next)
        assertTrue(arc.atWinter)
        assertEquals(18, world.events.cooldown(ArcKind.SUPER_LEAGUE))
        // The rumour is told at the open.
        assertEquals(listOf("superleague_rumour"), kinds(WorldEvents.opening(world, 3)))
        // The winter: the 12 most valuable clubs, those of a 재벌, 투자 펀드 or 국부 펀드 first.
        val p4 = Cw.fresh(Big.league.members, 4)
        val winter = WorldEvents.winter(world, ctx(book, p4))
        val founders = world.events.arcs.single().subjects
        assertEquals(12, founders.size)
        assertEquals(listOf(LeagueEvent("superleague_announce", 4, founders, listOf("12"))), winter)
        assertEquals(winter, WorldEvents.winter(world, ctx(book, p4)), "a repeated winter step tells the same")
        val value = book.valuer(Cw::rating)
        val w = book.world!!
        fun rich(id: String) = (w.economy.owners[id] ?: Owner.NONE).type.let { it == OwnerType.TYCOON || it == OwnerType.FUND || it == OwnerType.STATE }
        val ranked = w.clubs.keys.filter { book.club(it) != null }.sortedWith(
            compareByDescending<String> { ClubEconomy.clubValue(book, it, value) ?: 0 }.thenComparing(ClubBook.ID_ORDER),
        )
        assertEquals((ranked.filter(::rich) + ranked.filterNot(::rich)).take(12), founders)
        // Every founder under a 국부 펀드 with angry fans: each stays with 0.75, so a compromise.
        for (id in founders) {
            w.economy.owners[id] = Owner(OwnerType.STATE, "테스트 인베스트먼트", 5, 2, 1, 0)
            w.clubs.getValue(id).mood = 0
        }
        Cw.play(p4)
        val before = world.encode()
        // With the 요트협회's assembly off the rules never change by themselves: the same compromise, without the reform.
        val offWorld = WorldBook.decode(before)
        val off = config.copy(federation = false)
        WorldEvents.close(offWorld, ArcContext(p4, off, book, Cw.ranking(p4), Big.league.honours, book.valuer(Cw::rating)))
        assertEquals('P' to "-", offWorld.events.ended.single().let { it.outcome to it.args[1] })
        assertEquals(emptyList(), WorldEvents.effects(offWorld, 4).emergency)
        assertEquals("-", WorldEvents.opening(offWorld, 4).single().args[1])
        assertTrue(WorldEvents.effects(offWorld, 5).clPrizeFactor > 1.0, "the prize money still rises")
        val news = WorldEvents.close(world, ctx(book, p4))
        assertTrue(world.events.arcs.isEmpty())
        val ended = world.events.ended.single()
        assertEquals('P', ended.outcome)
        assertEquals(4, ended.end)
        val stayers = ended.args[0].split(',')
        assertTrue(stayers.size >= 4 && founders.containsAll(stayers))
        assertEquals("${config.clDirect}", ended.args[1])
        assertEquals(LeagueEvent("superleague_showdown", 4, stayers, listOf("${stayers.size}", "12")), news.single { it.kind == "superleague_showdown" })
        // Asked again, and on the book read back: the same news and the same file.
        val after = world.encode()
        assertEquals(news, WorldEvents.close(world, ctx(book, p4)))
        assertEquals(after, world.encode())
        assertEquals(news, WorldEvents.close(WorldBook.decode(after), ctx(book, p4)))
        assertTrue(before != after)
        // The outcome at the open, the effects at the close and the five seasons after it.
        val outcome = WorldEvents.opening(world, 4).single()
        val prize = 1.25 + 0.05 * minOf(5, stayers.size - 4)
        assertEquals(LeagueEvent("superleague_outcome", 5, stayers, listOf("P", "${config.clDirect}>${config.clDirect + 1}", "${Math.round(prize * 100)}")), outcome)
        val fx = WorldEvents.effects(world, 4)
        assertEquals(stayers.associateWith { 0.05 }, fx.fines)
        assertEquals(founders.associateWith { -15 }, fx.moodShocks)
        assertEquals(emptyList(), fx.takeovers)
        val reform = fx.emergency.single()
        assertEquals(Proposal(3, Proposal.EMERGENCY_NUMBER, ProposalKind.CL_DIRECT_UP, "7", "8", 1.0, listOf("${ended.id}"), result = Proposal.YES, by = Proposal.EMERGENCY), reform)
        assertEquals(5, reform.appliesFrom)
        assertEquals("슈퍼리그 사태로 긴급 결정했어요.", reform.evidence)
        for (s in 5..9) assertEquals(prize, WorldEvents.effects(world, s).clPrizeFactor, 1e-9)
        assertEquals(ArcEffects.NONE, WorldEvents.effects(world, 10))
        assertEquals(ArcEffects.NONE, WorldEvents.effects(world, 3))
        // The reform reaches the 요트협회 at every close of the season, and is adopted once.
        val fed = Federation()
        fed.assemble(3, statsFor(3, champMatches = 20, cclPoints = 30, cclMatches = 20), config, false)
        assertEquals(listOf(ProposalKind.CL_DIRECT_DOWN), fed.pending().map { it.kind })
        val beforeClose = fed.encode()
        assertTrue(fed.upsert(fx.emergency))
        assertFalse(fed.upsert(WorldEvents.effects(world, 4).emergency), "upserted again: nothing changes")
        val set = fed.of(3).single { it.kind == ProposalKind.CL_DIRECT_DOWN }
        assertEquals(Proposal.VOID to Proposal.EMERGENCY, set.result to set.by, "the reform sets the pending rule change aside")
        val adopted = fed.resolve(4, 99L, (1..9).map { "m$it" })
        assertEquals(listOf(reform), adopted)
        val (rules, void) = Federation.applyTo(config, adopted)
        assertEquals(emptyList(), void)
        assertEquals(8, rules.clDirect)
        val afterClose = fed.encode()
        // A close replayed after a kill: federation.txt as before the close, or as after it; either way once.
        for (text in listOf(beforeClose, afterClose)) {
            val f = Federation.decode(text)
            f.upsert(WorldEvents.effects(WorldBook.decode(after), 4).emergency)
            assertEquals(listOf(reform), f.resolve(4, 99L, (1..9).map { "m$it" }), "replayed on ${if (text == beforeClose) "the old" else "the new"} file")
            assertEquals(afterClose, f.encode())
        }
        assertEquals(1, Federation.decode(afterClose).of(3).count { it.emergency })
    }

    @Test
    fun aSuperLeagueThatCollapsesFinesItsStayersAndTheirOwnersMaySell() {
        val book = book()
        val world = WorldBook()
        force(ArcKind.SUPER_LEAGUE to 3)
        WorldEvents.close(world, ctx(book, played(3)))
        // No winter checkpoint this season: the close announces it on the way.
        val founders0 = world.events.arcs.single()
        assertTrue(founders0.subjects.isEmpty())
        val w = book.world!!
        val p4 = played(4)
        // 팬 소유 founders: each stays with 0.05 at most, so a collapse.
        val ranked = w.clubs.keys.filter { book.club(it) != null }
        for (id in ranked) w.economy.owners[id] = Owner(OwnerType.FANS, "팬 협동조합", 2, 4, 1, 0)
        val news = WorldEvents.close(world, ctx(book, p4))
        assertEquals(emptyList(), WorldEvents.winterNews(world, 4), "no announcement told for a winter that never ran")
        val ended = world.events.ended.single()
        assertEquals('C', ended.outcome)
        assertEquals(12, ended.subjects.size)
        val stayers = ended.args[0].split(',').filter { it.isNotEmpty() }
        assertTrue(stayers.size < 4)
        assertEquals("-", ended.args[1])
        assertEquals(1, news.count { it.kind == "superleague_showdown" })
        val fx = WorldEvents.effects(world, 4)
        assertEquals(stayers.associateWith { 0.10 }, fx.fines)
        assertEquals(emptyList(), fx.emergency)
        assertEquals(emptyList(), fx.takeovers, "a fans' club never sells")
        assertEquals("C", WorldEvents.opening(world, 4).single().args[0])
        assertEquals(1.0, WorldEvents.effects(world, 5).clPrizeFactor)
        // A collapse whose stayers sell: the takeovers ride on the args and reach the off-season.
        val sold = WorldBook()
        sold.events.endedList += EndedArc(1, ArcKind.SUPER_LEAGUE, 3, 4, 'C', listOf("a", "b", "c"), listOf("a,b", "-", "a:L:3:4"))
        assertEquals(listOf(Takeover("a", OwnerType.LOCAL, 3, 4, null, 1)), WorldEvents.effects(sold, 4).takeovers)
        assertEquals(mapOf("a" to 0.10, "b" to 0.10), WorldEvents.effects(sold, 4).fines)
    }

    // ---- INVESTOR -----------------------------------------------------------------------------

    @Test
    fun anInvestorTakesOverInjectsMoneyAndIsJudgedThreeSeasonsLater() {
        val book = book()
        val world = WorldBook()
        val w = book.world!!
        force(ArcKind.INVESTOR to 3)
        val p3 = played(3)
        WorldEvents.close(world, ctx(book, p3))
        val arc = world.events.arcs.single()
        assertEquals(ArcKind.INVESTOR, arc.kind)
        val club = arc.subjects.single()
        val owner = w.economy.owners[club] ?: Owner.NONE
        val st = w.clubs.getValue(club)
        assertTrue(owner.type == OwnerType.LOCAL || (owner.type == OwnerType.FANS && st.balance < 0), "$owner")
        assertTrue(st.trouble == 0 && st.admin == 0)
        assertEquals(listOf(LeagueEvent("investor_rumour", 4, listOf(club))), WorldEvents.opening(world, 3))
        // The talks at the winter name the investor; the takeover at the close.
        val p4 = Cw.fresh(Big.league.members, 4)
        val talks = WorldEvents.winter(world, ctx(book, p4)).single()
        val (type, name) = world.events.arcs.single().args.let { OwnerType.of(it[0].single())!! to it[1] }
        assertTrue(type == OwnerType.FUND || type == OwnerType.STATE)
        assertTrue(name.endsWith(if (type == OwnerType.FUND) " 파트너스" else " 인베스트먼트"), name)
        assertTrue(w.economy.owners.values.none { it.name == name })
        assertEquals(LeagueEvent("investor_talks", 4, listOf(club), listOf(name, "${type.code}")), talks)
        Cw.play(p4)
        val takeover = WorldEvents.close(world, ctx(book, p4))
        assertEquals(LeagueEvent("investor_takeover", 4, listOf(club), listOf(name, "${type.code}")), takeover.single())
        val active = world.events.arcs.single()
        assertEquals(ArcStage.ACTIVE to 7, active.stage to active.next)
        val injection = if (type == OwnerType.STATE) 1.2 else 0.8
        assertEquals(ArcEffects(takeovers = listOf(Takeover(club, type, 5, 2, name, arc.id)), injections = mapOf(club to injection)), WorldEvents.effects(world, 4))
        for (s in 5..6) assertEquals(ArcEffects(injections = mapOf(club to injection)), WorldEvents.effects(world, s))
        // Two closes without a step, then the verdict: a member's 챔피언스 리그 title is a success.
        for (s in 5..6) assertEquals(emptyList(), WorldEvents.close(world, ctx(book, played(s))).filter { it.kind.startsWith("investor") })
        val p7 = played(7)
        val member = book.members(club).first()
        val success = WorldBook.decode(world.encode())
        val verdict = WorldEvents.close(success, ctx(book, p7, Big.league.honours + Honour(6, "CL", member)))
        assertEquals(LeagueEvent("investor_verdict", 7, listOf(club), listOf("S", name, "${type.code}")), verdict.single { it.kind == "investor_verdict" })
        assertEquals('S', success.events.ended.single { it.kind == ArcKind.INVESTOR }.outcome)
        assertEquals(8, success.events.cooldown(ArcKind.INVESTOR), "the next may start a season after it ended")
        val stays = WorldEvents.effects(success, 7)
        assertTrue(stays.takeovers.isEmpty() && stays.sellOff.isEmpty() && stays.injections.isEmpty())
        // An investor that leaves: a 지역 기업 takes over, and a 투자 펀드 sells its two most valuable members.
        val exit = WorldBook()
        exit.events.endedList += EndedArc(4, ArcKind.INVESTOR, 3, 7, 'E', listOf("c1"), listOf("P", "모나코 파트너스", "3:2"))
        assertEquals(
            ArcEffects(takeovers = listOf(Takeover("c1", OwnerType.LOCAL, 3, 2, null, 4)), sellOff = mapOf("c1" to 2)),
            WorldEvents.effects(exit, 7),
        )
        assertEquals(ArcEffects(injections = mapOf("c1" to 0.8)), WorldEvents.effects(exit, 6))
        // A club gone by the verdict: lapsed, with no verdict; what the investor did before stays as it was.
        val gone = WorldBook()
        gone.events.active += WorldArc(6, ArcKind.INVESTOR, ArcStage.ACTIVE, 3, 7, false, listOf("c999"), listOf("P", "모나코 파트너스"))
        val before = (3..7).map { WorldEvents.effects(gone, it) }
        assertEquals(emptyList(), WorldEvents.close(gone, ctx(book, p7)).filter { it.kind.startsWith("investor") })
        assertEquals('L', gone.events.ended.single().outcome)
        assertEquals(before, (3..7).map { WorldEvents.effects(gone, it) })
        assertEquals(listOf("investor_takeover"), kinds(WorldEvents.closeNews(gone, 4)))
        // Lapsed (the club was gone by the takeover): no effect at all.
        val lapsed = WorldBook()
        lapsed.events.endedList += EndedArc(5, ArcKind.INVESTOR, 3, 4, 'L', listOf("c1"), listOf("S", "카타르 인베스트먼트"))
        for (s in 3..8) assertEquals(ArcEffects.NONE, WorldEvents.effects(lapsed, s))
    }

    @Test
    fun anInvestorWhoseWinterWasMissedStillTakesOverAtTheClose() {
        val book = book()
        val world = WorldBook()
        force(ArcKind.INVESTOR to 3)
        WorldEvents.close(world, ctx(book, played(3)))
        val news = WorldEvents.close(world, ctx(book, played(4)))
        val arc = world.events.arcs.single()
        assertEquals(ArcStage.ACTIVE, arc.stage)
        assertEquals(2, arc.args.size)
        assertEquals("investor_takeover", news.single().kind)
        assertEquals(emptyList(), WorldEvents.winterNews(world, 4))
    }

    // ---- CRISIS -------------------------------------------------------------------------------

    /** A close with neither an investor (no ranking: no league among the best 16) nor a super league or merger (below 500 members). */
    private fun quiet(book: ClubBook, plan: SeasonPlan, config: LeagueConfig = this.config) =
        ArcContext(plan, config, book, LeagueRanking.EMPTY, Big.league.honours, book.valuer(Cw::rating), counts = mapOf("KR" to 300))

    @Test
    fun aCrisisHitsTheMoneyTwoSeasonsRecoversAndEndsWithItsAdministrations() {
        val book = book()
        val w = book.world!!
        // Some debt in Asia only, so a confederation's crisis falls there.
        for ((i, id) in w.clubs.keys.filter { book.nationOf(it)?.let(Nations::confedOf) == Confed.ASIA }.withIndex()) if (i % 3 == 0) w.clubs.getValue(id).balance = -500
        val world = WorldBook()
        force(ArcKind.CRISIS to 6)
        val news = WorldEvents.close(world, quiet(book, played(6)))
        val arc = world.events.arcs.single()
        val scope = arc.subjects.single()
        assertTrue(scope == WorldEvents.WORLD || scope == "ASIA", scope)
        assertEquals(listOf(LeagueEvent("crisis_begins", 6, emptyList(), listOf(scope))), news)
        val nations = if (scope == WorldEvents.WORLD) null else Nations.ALL.filter { it.confed == Confed.ASIA }.map { it.code }.toSet()
        for (s in 6..7) assertEquals(ArcEffects(0.7, 0.85, 0.5, 2.0, scope = nations), WorldEvents.effects(world, s))
        assertEquals(ArcEffects(0.85, 0.93, 0.75, 1.5, scope = nations), WorldEvents.effects(world, 8))
        assertEquals(ArcEffects.NONE, WorldEvents.effects(world, 9))
        assertEquals(ArcEffects.NONE, WorldEvents.effects(world, 5))
        // The scope: a club outside it reads neutral factors.
        val fx = WorldEvents.effects(world, 6)
        assertEquals(0.85, fx.at("KR").tvFactor)
        assertEquals(if (nations == null) 0.85 else 1.0, fx.at("GB").tvFactor)
        assertEquals(1.0, ArcEffects.NONE.at("KR").tvFactor)
        // A club in administration during it is counted at the end.
        val admin = w.clubs.values.first { book.club(it.id) != null && (nations == null || book.nationOf(it.id) in nations) }
        admin.admin = 1
        WorldEvents.close(world, quiet(book, bare(7)))
        assertEquals(ArcStage.ACTIVE, world.events.arcs.single().stage)
        admin.admin = 0
        // Most are rescued at the off-season they go into administration (admin back to 0): the season's
        // 법정관리 deduction tells them.
        val rescued = w.clubs.values.first { it.id != admin.id && it.admin == 0 && it.deduction == 0 && book.club(it.id) != null && (nations == null || book.nationOf(it.id) in nations) }
        rescued.deduction = ClubEconomy.ADMIN_DEDUCTION
        WorldEvents.close(world, quiet(book, bare(8)))
        rescued.deduction = 0
        assertEquals(ArcStage.RECOVERY to 9, world.events.arcs.single().let { it.stage to it.next })
        val end = WorldEvents.close(world, quiet(book, bare(9)))
        val ended = world.events.ended.single()
        assertEquals(9, ended.end)
        assertTrue(admin.id in ended.args.single().split(','))
        assertTrue(rescued.id in ended.args.single().split(','), "a club rescued at once still went into administration")
        assertEquals(LeagueEvent("crisis_ends", 9, emptyList(), listOf("3", "${ended.args.single().split(',').size}", scope)), end.single { it.kind == "crisis_ends" })
        assertEquals(18, world.events.cooldown(ArcKind.CRISIS))
    }

    /** The big league's season 3, for [bare] seasons. */
    private val base: Season by lazy { Cw.fresh(Big.league.members, 3).league }

    /** National season [n] of the big league's divisions without a game (the triggers read the plan's number and seed). */
    private fun bare(n: Int, seed: Long = Offseason.seasonSeed(77L, n)): SeasonPlan =
        SeasonPlan(Season.start(n, seed, base.divisions, true, base.leagues), config)

    private fun roll(plan: SeasonPlan, kind: ArcKind): Double =
        Random(DiceStream.mix(plan.seed, 0xE7E7, plan.number.toLong(), kind.ordinal.toLong())).nextDouble()

    @Test
    fun aCrisisStartsExactlyWhenItsSeededRollIsBelowItsChanceAndWaitsOutItsCooldown() {
        val book = book()
        // A crisis starts when its roll is below 0.07, from season 6.
        var fired = 0
        for (n in 1..240) {
            val plan = bare(n)
            val world = WorldBook()
            WorldEvents.close(world, quiet(book, plan))
            val started = world.events.arcs.any { it.kind == ArcKind.CRISIS }
            assertEquals(n >= 6 && roll(plan, ArcKind.CRISIS) < 0.07, started, "season $n")
            if (started) fired++
        }
        assertTrue(fired in 6..30, "$fired crises in 235 closes (expected about 16)")
        // One world through the seasons: after a crisis no other starts for 12 seasons, whatever its roll.
        val world = WorldBook()
        val starts = ArrayList<Int>()
        fun crises() = world.events.arcs.count { it.kind == ArcKind.CRISIS } + world.events.ended.count { it.kind == ArcKind.CRISIS }
        for (n in 1..240) {
            val before = crises()
            WorldEvents.close(world, quiet(book, bare(n)))
            if (crises() > before) starts += n
        }
        assertTrue(starts.size >= 3, "$starts")
        for ((a, b) in starts.zipWithNext()) assertTrue(b - a >= 12, "$starts")
        // Each start is the first season after the cooldown whose roll is below the chance.
        for ((a, b) in starts.zipWithNext()) assertEquals(b, (a + 12..240).first { roll(bare(it), ArcKind.CRISIS) < 0.07 })
    }

    // ---- MERGER and BOOM (hand-made national leagues) -------------------------------------------

    /** Asia's KR, JP, CN, VN and TH, Europe's GB and FR, the Americas' US; VN and TH the smallest. */
    private val small = NationalStart(
        listOf(
            "KR" to listOf(16, 16), "JP" to listOf(16, 16), "CN" to listOf(16, 16), "VN" to listOf(15, 15), "TH" to listOf(15, 15),
            "GB" to listOf(16, 16), "FR" to listOf(16, 16), "US" to listOf(16, 16),
        ),
    )

    private fun nationOf(k: String) = k.take(2).uppercase()

    private val smallBook: ClubBook by lazy { ClubBook.found(small.keys, ::nationOf, small::rating, 0, 5L, config) }

    private fun smallPlan(n: Int, seed: Long = 1000L + n) = SeasonPlan(Season.start(n, seed, small.divisions, true, small.leagues), config)

    /** VN and TH at the bottom of the ranking. */
    private val ranking = small.ranking(listOf("KR", "JP", "GB", "FR", "US", "CN", "VN", "TH"))

    private val counts = small.leagues.list.associate { it.id to it.divisions.sumOf { d -> small.divisions[d].size } }

    private fun smallCtx(n: Int, counts: Map<String, Int> = this.counts, seed: Long = 1000L + n) =
        ArcContext(smallPlan(n, seed), config, smallBook, ranking, emptyList(), counts = counts)

    @Test
    fun theRankingsBottomQuarterIsKeptFiveSeasons() {
        val world = WorldBook()
        for (n in 1..7) WorldEvents.close(world, smallCtx(n))
        assertEquals((3..7).toList(), world.events.bottom.keys.toList())
        assertEquals(listOf("VN", "TH"), world.events.bottom(7))
    }

    @Test
    fun aMergerIsProposedVotedOnMergesTheTwoLeaguesAndSplitsThemLater() {
        assertEquals(Confed.ASIA, Nations.confedOf("VN"))
        assertEquals(Confed.ASIA, Nations.confedOf("TH"))
        val world = WorldBook()
        WorldEvents.close(world, smallCtx(8))
        // Not yet three seasons at the bottom: a forced merger finds no pair.
        force(ArcKind.MERGER to 9)
        WorldEvents.close(world, smallCtx(9))
        assertTrue(world.events.arcs.isEmpty())
        force(ArcKind.MERGER to 10)
        WorldEvents.close(world, smallCtx(10))
        val arc = world.events.arcs.single()
        assertEquals(listOf("VN", "TH"), arc.subjects)
        assertEquals(ArcStage.VOTE to 11, arc.stage to arc.next)
        assertEquals(listOf(LeagueEvent("merger_proposed", 11, emptyList(), listOf("VN", "TH"))), WorldEvents.opening(world, 10))
        // The vote: TH (ranked lower) all yes; VN's clubs each yes with 0.5; a majority of both merges.
        val outcomes = HashMap<Boolean, WorldBook>()
        for (seed in 1L..40L) {
            val copy = WorldBook.decode(world.encode())
            val news = WorldEvents.close(copy, smallCtx(11, seed = seed))
            val merged = copy.events.arcs.isNotEmpty()
            val tally = (if (merged) copy.events.arcs.single().args else copy.events.ended.single().args).map { it.toInt() }
            assertEquals(tally[3], tally[2], "TH's clubs all vote yes")
            assertEquals(smallBook.clubsOf("VN").size to smallBook.clubsOf("TH").size, tally[1] to tally[3])
            assertEquals(tally[0] * 2 > tally[1], merged)
            assertEquals(if (merged) listOf("league_merged") else listOf("merger_failed"), kinds(news))
            outcomes.putIfAbsent(merged, copy)
        }
        assertEquals(setOf(true, false), outcomes.keys)
        val failed = outcomes.getValue(false)
        assertEquals('F', failed.events.ended.single().outcome)
        assertEquals(ArcEffects.NONE, WorldEvents.effects(failed, 11))
        // Merged: VN and TH join Asia's 연합 리그 from the next season on.
        val m = outcomes.getValue(true)
        assertEquals(ArcStage.ACTIVE to 17, m.events.arcs.single().let { it.stage to it.next })
        assertEquals(setOf("VN", "TH"), WorldEvents.effects(m, 11).merged)
        assertEquals(emptySet(), WorldEvents.effects(m, 10).merged)
        val last = small.leagues.list.map { it.id }.toSet()
        val formed = NationalLeagues.plan(counts, config, last, WorldEvents.effects(m, 11).merged)
        assertEquals("XAS", formed["VN"])
        assertEquals("XAS", formed["TH"])
        assertEquals("KR", formed["KR"])
        assertEquals("VN", NationalLeagues.plan(counts, config, last)["VN"])
        // No split while neither nation is big enough, and none before six merged seasons.
        val bigVn = counts + ("VN" to 40)
        val held = WorldBook.decode(m.encode())
        for (n in 12..30) WorldEvents.close(held, smallCtx(n))
        assertEquals(ArcStage.ACTIVE, held.events.arcs.single().stage)
        for (n in 12..16) WorldEvents.close(m, smallCtx(n, bigVn))
        assertEquals(ArcStage.ACTIVE, m.events.arcs.single().stage)
        var split = 0
        for (n in 17..60) {
            val news = WorldEvents.close(m, smallCtx(n, bigVn))
            if (m.events.arcs.isEmpty()) {
                assertEquals(listOf(LeagueEvent("league_split", n, emptyList(), listOf("VN", "TH"))), news.filter { it.kind == "league_split" })
                split = n
                break
            }
        }
        assertTrue(split >= 17, "split at $split")
        assertEquals('M', m.events.ended.single().outcome)
        assertEquals(setOf("VN", "TH"), WorldEvents.effects(m, split - 1).merged)
        assertEquals(emptySet(), WorldEvents.effects(m, split).merged)
        // The split's leagues: VN has its own again (today's league_formed tells it).
        assertEquals("VN", NationalLeagues.plan(bigVn, config, last - "VN" - "TH" + "XAS", WorldEvents.effects(m, split).merged)["VN"])
    }

    @Test
    fun aBoomDoublesANationsRookiesUntilItHasItsOwnLeague() {
        // IN has no league of its own: its 14 members (at least half of leagueMin) play elsewhere.
        val withIn = counts + ("IN" to 14)
        val world = WorldBook()
        force(ArcKind.BOOM to 6)
        val news = WorldEvents.close(world, smallCtx(6, withIn))
        assertEquals(listOf(LeagueEvent("boom_begins", 6, emptyList(), listOf("IN", "14"))), news)
        assertEquals(emptyMap(), WorldEvents.effects(world, 6).rookieBoost, "the close it starts at drew its rookies already")
        val lapse = WorldBook.decode(world.encode())
        for (n in 7..9) {
            assertEquals(mapOf("IN" to 2.0), WorldEvents.effects(world, n).rookieBoost)
            assertEquals(emptyList(), WorldEvents.close(world, smallCtx(n, withIn)))
        }
        // Enough members for a league of its own next season: done (N).
        val end = WorldEvents.close(world, smallCtx(10, counts + ("IN" to 24)))
        assertEquals(listOf(LeagueEvent("boom_ends", 10, emptyList(), listOf("IN", "N"))), end)
        assertEquals(mapOf("IN" to 2.0), WorldEvents.effects(world, 10).rookieBoost, "the boost of the close it ends at stays")
        assertEquals(emptyMap(), WorldEvents.effects(world, 11).rookieBoost)
        assertEquals(16, world.events.cooldown(ArcKind.BOOM))
        // Never enough: it cools after six seasons (L).
        for (n in 7..11) WorldEvents.close(lapse, smallCtx(n, withIn))
        assertEquals(ArcStage.ACTIVE, lapse.events.arcs.single().stage)
        assertEquals("L", WorldEvents.close(lapse, smallCtx(12, withIn)).single().args[1])
        assertEquals(mapOf("IN" to 2.0), WorldEvents.effects(lapse, 12).rookieBoost)
        assertEquals(ArcEffects.NONE, WorldEvents.effects(lapse, 13))
    }

    @Test
    fun aBoomStartsByItsSeededRollFromSeasonSix() {
        val withIn = counts + ("IN" to 14)
        for (n in 1..120) {
            val world = WorldBook()
            WorldEvents.close(world, smallCtx(n, withIn))
            val plan = smallPlan(n)
            // Below 500 members: no merger before it; no club world: no crisis or investor.
            assertEquals(n >= 6 && roll(plan, ArcKind.BOOM) < 0.08, world.events.arcs.any { it.kind == ArcKind.BOOM }, "season $n")
        }
        // A nation below half of leagueMin is no candidate, forced or not; nor one with enough members for
        // a league of its own next season anyway.
        force(ArcKind.BOOM to 6)
        val none = WorldBook()
        WorldEvents.close(none, smallCtx(6, counts + ("IN" to 11)))
        assertTrue(none.events.arcs.isEmpty())
        assertEquals("IN", NationalLeagues.plan(counts + ("IN" to 24), config, small.leagues.list.map { it.id }.toSet())["IN"])
        val already = WorldBook()
        WorldEvents.close(already, smallCtx(6, counts + ("IN" to 24)))
        assertTrue(already.events.arcs.isEmpty())
    }

    // ---- the rules for all ---------------------------------------------------------------------

    @Test
    fun oneMajorArcAtATimeBesideOneInvestor() {
        val book = book()
        val world = WorldBook()
        WorldEvents.forceTrigger = { _, _ -> true }
        WorldEvents.close(world, ctx(book, played(3)))
        assertEquals(listOf(ArcKind.SUPER_LEAGUE, ArcKind.INVESTOR), world.events.arcs.map { it.kind })
        // While it runs, no other major arc and no second investor start, forced or not.
        WorldEvents.close(world, ctx(book, played(4)))
        val after = world.events.arcs.map { it.kind } + world.events.ended.map { it.kind }
        assertEquals(1, after.count { it == ArcKind.INVESTOR })
        // The super league ended at 4: a new major arc may start at that very close.
        assertTrue(world.events.ended.any { it.kind == ArcKind.SUPER_LEAGUE })
        assertTrue(world.events.arcs.count { it.kind.major } <= 1)
    }

    @Test
    fun withWorldEventsOffNothingStartsButArcsUnderWayFinish() {
        val book = book()
        val off = config.copy(worldEvents = false)
        val world = WorldBook()
        WorldEvents.forceTrigger = { _, _ -> true }
        WorldEvents.close(world, ArcContext(played(3), off, book, Cw.ranking(played(3)), Big.league.honours, book.valuer(Cw::rating)))
        assertTrue(world.events.arcs.isEmpty())
        WorldEvents.forceTrigger = { k, s -> k == ArcKind.CRISIS && s == 6 }
        WorldEvents.close(world, ctx(book, played(6)))
        assertEquals(ArcKind.CRISIS, world.events.arcs.single().kind)
        WorldEvents.forceTrigger = { _, _ -> true }
        for (n in 7..9) WorldEvents.close(world, ArcContext(bare(n), off, book, LeagueRanking.EMPTY, emptyList(), book.valuer(Cw::rating), counts = mapOf("KR" to 300)))
        assertTrue(world.events.arcs.isEmpty())
        assertEquals(listOf(ArcKind.CRISIS), world.events.ended.map { it.kind })
    }

    @Test
    fun aLegacySeasonHasNoArcsAndTheGuardsNeverRunAhead() {
        val legacy = SeasonPlan(Season.start(4, 9L, listOf(small.keys.take(20), small.keys.drop(20).take(20))), LeagueConfig())
        val world = WorldBook()
        WorldEvents.forceTrigger = { _, _ -> true }
        assertEquals(emptyList(), WorldEvents.close(world, ArcContext(legacy, config)))
        assertTrue(world.events.arcs.isEmpty() && world.events.bottom.isEmpty())
        assertEquals(4, world.arcClose)
        assertFalse(world.ahead(4))
        // A winter after its season's close changes nothing.
        assertEquals(emptyList(), WorldEvents.winter(world, ArcContext(legacy, config)))
        assertEquals(0, world.arcWinter)
    }

    @Test
    fun theArcsReadBackAsSavedAndAStaleBookDropsThem() {
        val world = WorldBook()
        val e = world.events
        e.active += WorldArc(3, ArcKind.INVESTOR, ArcStage.ANNOUNCED, 7, 8, false, listOf("c12"), listOf("S", "카타르 인베스트먼트"))
        e.active += WorldArc(4, ArcKind.CRISIS, ArcStage.RECOVERY, 5, 8, false, listOf("EUROPE"), listOf("c1,c2"))
        e.endedList += EndedArc(1, ArcKind.SUPER_LEAGUE, 2, 3, 'P', listOf("c1", "c2"), listOf("c1", "7", ""))
        e.endedList += EndedArc(2, ArcKind.BOOM, 1, 4, 'N', listOf("IN"))
        e.cooldowns[ArcKind.CRISIS] = 17
        e.bottom[7] = listOf("VN", "TH")
        val text = world.encode()
        assertTrue("ev 3 INVESTOR A 7 8 0 c12 S|카타르%20인베스트먼트 -" in text, text)
        assertTrue("evh 1 SUPER_LEAGUE 2 3 P c1,c2 c1|7|-" in text, text)
        assertTrue("evh 2 BOOM 1 4 N IN\n" in text, text)
        val back = WorldBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(e.arcs, back.events.arcs)
        assertEquals(e.ended, back.events.ended)
        assertEquals(listOf("c1", "7", ""), back.events.ended.first().args)
        // The next id is above every id kept.
        assertEquals(5, back.events.nextId())
        // A book behind the season in play by more than one close drops the arcs under way and the bottom quarters.
        world.closeSeason = 7
        assertFalse(world.dropStale(8))
        assertTrue(world.dropStale(9))
        assertTrue(world.events.arcs.isEmpty() && world.events.bottom.isEmpty())
        assertEquals(2, world.events.ended.size)
        assertEquals(17, world.events.cooldown(ArcKind.CRISIS))
    }

    /** Statistics of season [season] that make no proposal but the ones the arguments press for. */
    private fun statsFor(season: Int, champMatches: Int = 0, cclPoints: Int = 0, cclMatches: Int = 0) = FederationStats(
        season, true, 0, 0, null, 0, 0, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0, champMatches, cclPoints, cclMatches,
        Lever.entries.map { config.valueOf(it.key)!! },
    )

    @Test
    fun withoutArcsTheEffectsAreNeutral() {
        val world = WorldBook()
        for (s in 0..20) assertEquals(ArcEffects.NONE, WorldEvents.effects(world, s))
        assertNull(ArcEffects.NONE.scope)
        assertNotNull(ArcEffects.NONE.at(null))
    }
}
