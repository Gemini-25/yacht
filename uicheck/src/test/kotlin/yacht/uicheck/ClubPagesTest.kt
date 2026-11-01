package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.ClubRankKind
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.PressureLevel
import yacht.app.core.WorldPages
import yacht.app.core.accountItems
import yacht.app.ui.PlayerClubTexts
import yacht.app.ui.memberMarks
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Derbies
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.StoryStage
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The club world's pages (UX 4-5, 13.2) on the story world's club world variant ([ClubScene]): every
 * national club's page loads with its club life, every member has terms and its own shirt number, the
 * captain is a member, the accounts' items add up to their totals, ranks stay within the clubs and the
 * rivals (≤ 2) have their records; a coach page for every employed coach, the free and the retired;
 * the club rankings sorted with the grades adding up to the clubs; a player's 💼 line. Without a club
 * world (a league not yet migrated) none of it shows and the club page is today's.
 */
class ClubPagesTest {
    private val scene = ClubScene.default

    @Test
    fun everyClubsPageHasItsClubLife() {
        val pages = WorldPages(Host(scene.world))
        val b = scene.book
        val s = scene.world.plan!!.number
        val clubs = b.clubs
        assertTrue(clubs.size >= 20, "${clubs.size} clubs")
        for (c in clubs) {
            pages.loadClub(c.id)
            val p = wait { pages.club(Page.Club(c.id)) }
            val life = assertNotNull(p.life, c.id)
            assertNotNull(p.history, c.id)
            assertEquals(b.members(c.id).toSet(), p.members.map { it.key }.toSet())
            // Every member under contract, each its own number; the captain one of them.
            val numbers = p.members.map { assertNotNull(it.terms, "${c.id} ${it.key}").number }
            assertEquals(numbers.size, numbers.toSet().size, "${c.id}: numbers $numbers")
            assertTrue(numbers.all { it in 1..99 }, "${c.id}: $numbers")
            life.captain?.let { k -> assertTrue(p.members.any { it.key == k && it.terms?.captain == true }, "${c.id}: captain $k") }
            assertEquals(1, p.members.count { it.terms?.captain == true }, c.id)
            // The accounts' items add up to the totals, and the shown top 3 are the biggest.
            val a = assertNotNull(life.last, c.id)
            assertEquals(s - 1, a.season)
            val (inc, exp) = accountItems(a)
            assertEquals(a.income, inc.sumOf { it.amount }, c.id)
            assertEquals(a.expenses, exp.sumOf { it.amount }, c.id)
            assertEquals(inc.take(3), life.incomeTop)
            assertEquals(exp.take(3), life.expenseTop)
            assertTrue(life.years.size in 1..5 && life.years.zipWithNext().all { (x, y) -> x.season > y.season }, "${c.id}: ${life.years}")
            // Ranks within the clubs (the world's, the nation's).
            assertTrue(life.valueRank in 1..clubs.size && life.fansRank in 1..clubs.size, c.id)
            assertTrue(life.fansNationRank in 1..b.clubsOf(c.nation).size, c.id)
            assertTrue(life.health in "ABCDE" && life.trouble in 0..3 && life.mood in 0..100, c.id)
            assertTrue(life.stadium.capacity > 0 && life.stadium.fill in 0.0..1.0, "${c.id}: ${life.stadium}")
            assertEquals(life.squadValue, p.members.sumOf { it.terms!!.value }, c.id)
            // At most two rivals, each with its all-time record and a name.
            assertTrue(life.rivals.size <= 2, c.id)
            for (r in life.rivals) {
                assertNotNull(r.record, "${c.id} vs ${r.badge.id}")
                assertTrue(r.name.isNotEmpty() && r.season.size == 3, "${c.id} vs ${r.badge.id}")
            }
            // Each member's academy, its club's or another's (the 💼 details' "🌱 {club} 유스 출신").
            for (m in p.members) {
                val t = b.terms(m.key) ?: continue
                assertEquals(t.academy, m.terms?.academyClub?.id, "${c.id} ${m.key}")
                assertEquals(t.academy != null && t.academy == c.id, m.terms?.academy, "${c.id} ${m.key}")
            }
            // The coach and the board's outlook of a national club.
            val coach = assertNotNull(life.coach, c.id)
            assertEquals(b.coachOf(c.id)?.id, coach.id)
            val board = assertNotNull(life.board, c.id)
            assertTrue(board.target in 1..board.clubs && board.place in 1..board.clubs, "${c.id}: $board")
            assertNotNull(coach.pressure)
        }
    }

    @Test
    fun theRivalRecordsAreTheEngines() {
        // 통산 and 이번 시즌 come from one pass over the season's matches (kept for the next pages):
        // the same numbers as the engine's Derbies.allTime and Derbies.series, also on a second load.
        val w = scene.world
        val b = scene.book
        val p = w.plan!!
        val through = p.week - 1
        val series = Derbies.series(b, p, through)
        var added = 0
        repeat(2) {
            val pages = WorldPages(Host(w))
            for (c in b.clubs) {
                pages.loadClub(c.id)
                val life = assertNotNull(wait { pages.club(Page.Club(c.id)) }.life, c.id)
                for (r in life.rivals) {
                    val engine = Derbies.allTime(b, p, c.id, r.badge.id, through)
                    assertEquals(engine, r.record, "${c.id} vs ${r.badge.id}")
                    val now = series.values.firstOrNull { setOf(it.a, it.b) == setOf(c.id, r.badge.id) }?.of(c.id)
                    assertEquals(now?.toList() ?: listOf(0, 0, 0), r.season, "${c.id} vs ${r.badge.id}")
                    if (engine != b.derby(c.id, r.badge.id)) added++
                }
            }
        }
        assertTrue(added > 0, "no rival pair met this season: the records are the stored ones only")
    }

    @Test
    fun theWinterCheckpointIsWeekHalfPlusOne() {
        // ENG 3.3: the winter checkpoint runs once week ⌊R / 2⌋ + 1 closes (22 of 42, 11 of 20).
        assertEquals(22, WorldPages.winterWeek(42))
        assertEquals(11, WorldPages.winterWeek(20))
        assertEquals(20, WorldPages.winterWeek(38))
        // The scene's season is past it, so the board's next checkpoint is the season's end.
        val p = scene.world.plan!!
        assertTrue(p.week > WorldPages.winterWeek(p.calendar.leagueWeeks))
        val pages = WorldPages(Host(scene.world))
        pages.loadClub(scene.club)
        assertEquals(false, wait { pages.club(Page.Club(scene.club)) }.life?.winterNext)
        pages.loadCoach(scene.coach)
        assertEquals(false, wait { pages.coach(Page.Coach(scene.coach)) }.winterNext)
    }

    @Test
    fun theScenesRareStatesShow() {
        val pages = WorldPages(Host(scene.world))
        pages.loadClub(scene.trouble)
        val t = assertNotNull(wait { pages.club(Page.Club(scene.trouble)) }.life)
        assertEquals(3, t.trouble)
        assertEquals('E', t.health)
        assertEquals(0, t.budget, "no transfer budget in administration")
        pages.loadClub(scene.club)
        val p = wait { pages.club(Page.Club(scene.club)) }
        val life = assertNotNull(p.life)
        assertEquals(PressureLevel.SHAKY, life.coach?.pressure)
        assertEquals(2, life.stories.size)
        assertEquals(StoryStage.TALKS, life.stories.first().stage)
        val h = assertNotNull(p.history)
        assertTrue(h.legends.isNotEmpty() && h.legends.count { it.retired } in 1..2, "${h.legends}")
        assertTrue(h.notable.zipWithNext().all { (x, y) -> x.fee >= y.fee }, "notable by fee")
        assertTrue(h.tenures.isNotEmpty())
        pages.loadClub(scene.business)
        assertNotNull(wait { pages.club(Page.Club(scene.business)) }.life?.stadium?.project)
    }

    @Test
    fun everyCoachHasAPage() {
        val pages = WorldPages(Host(scene.world))
        val b = scene.book
        for (c in b.coaches) {
            pages.loadCoach(c.id)
            val p = wait { pages.coach(Page.Coach(c.id)) }
            assertEquals(Page.Coach(c.id).loadKey, p.key)
            assertEquals(c.club, p.club?.id)
            assertTrue(p.name == c.name && p.shown.endsWith(c.name))
            assertTrue(p.olderClubs >= 0 && p.olderSeasons >= 0)
            if (c.club != null) {
                assertNotNull(p.board, c.id)
                assertNotNull(p.pressure, c.id)
                assertNull(p.freeRank)
                assertTrue(p.league.isNotEmpty(), c.id)
            } else {
                assertTrue(p.freeRank in 1..b.freeCoaches.size, c.id)
            }
            assertEquals(c.player, p.player)
            if (c.player != null) assertTrue(p.playerSeasons > 0, c.id)
        }
        // The retired ex-player coach the scene adds.
        pages.loadCoach(ClubScene.RETIRED_COACH)
        val r = wait { pages.coach(Page.Coach(ClubScene.RETIRED_COACH)) }
        assertEquals(scene.world.plan!!.number - 1, r.retired)
        assertNull(r.club)
        assertNotNull(r.player)
        assertTrue(r.playerSeasons > 0)
        assertEquals(listOf(2, 5, 1, 1, 4, 17), listOf(r.clubTitles, r.leagueTitles, r.awards, r.sacked, r.olderClubs, r.olderSeasons))
        // No such coach: nothing.
        pages.loadCoach("k99999")
        Thread.sleep(50)
        assertNull(pages.coach(Page.Coach("k99999")))
    }

    @Test
    fun theClubRankingsAreSortedAndTheGradesAddUp() {
        val host = Host(scene.world)
        val pages = WorldPages(host)
        val b = scene.book
        pages.loadClubRanking(null)
        val r = wait { pages.clubRanking(Page.ClubRanking()) }
        assertEquals(ClubRankKind.entries.toSet(), r.lists.keys)
        assertEquals(b.clubs.size, r.grades.sum())
        for ((kind, rows) in r.lists) {
            assertEquals((1..rows.size).toList(), rows.map { it.rank }, "$kind")
            assertEquals(minOf(b.clubs.size, 50), rows.size, "$kind")
            // Moves only for the top 10 of a list that keeps them.
            if (kind !in r.moved) assertTrue(rows.all { it.move == null }, "$kind")
            assertTrue(rows.filter { it.rank > 10 }.all { it.move == null }, "$kind")
        }
        assertEquals(setOf(ClubRankKind.VALUE, ClubRankKind.RESULTS), r.moved)
        // One league's scope: its nations' clubs only, ranked among themselves.
        val lg = scene.world.plan!!.league.leagues.list.first { it.national && b.clubs.count { c -> host.leagueOf(c.id) == it.id } >= 2 }
        pages.loadClubRanking(lg.id)
        val one = wait { pages.clubRanking(Page.ClubRanking(scope = lg.id)) }
        val ids = b.clubs.filter { host.leagueOf(it.id) == lg.id }.map { it.id }.toSet()
        for (rows in one.lists.values) {
            assertEquals(ids, rows.map { it.badge.id }.toSet())
            assertTrue(rows.all { it.move == null })
        }
        assertEquals(ids.size, one.grades.sum())
        // A followed club far down still shows.
        val last = r.lists.getValue(ClubRankKind.VALUE).last().badge.id
        host.favs = setOf(LeagueSelector.CLUB + last)
        pages.loadClubRanking(null)
        wait { pages.clubRanking(Page.ClubRanking())?.takeIf { v -> v.lists.getValue(ClubRankKind.VALUE).any { it.followed } } }
    }

    @Test
    fun aPlayersContractLine() {
        val pages = WorldPages(Host(scene.world))
        val k = scene.captain
        pages.loadPlayerClub(k)
        val card = wait { pages.playerClub(k) }
        assertEquals(scene.club, card.club?.id)
        val t = assertNotNull(card.terms)
        assertTrue(t.captain)
        assertEquals(scene.book.terms(k)?.number, t.number)
        assertEquals(scene.coach, card.clubCoach)
        assertEquals(StoryStage.RUMOUR, card.story?.stage)
        assertNotNull(card.family)
        // Its marks: the contract's last season, a clause.
        assertEquals("⏳🔓", memberMarks(t.copy(academy = false, loanFrom = null, until = 7, clause = 50), 7))
        assertEquals("", memberMarks(t.copy(academy = false, loanFrom = null, until = 9, clause = 0), 7))
        // A retired player who coaches: no contract, the coach line.
        val ex = scene.book.coaches.firstOrNull { it.player != null && scene.book.clubOf(it.player!!) == null }
        if (ex != null) {
            pages.loadPlayerClub(ex.player!!)
            val c = wait { pages.playerClub(ex.player!!) }
            assertNull(c.terms)
            assertEquals(ex.id, c.coach)
            assertEquals(ex.club, c.coachClub?.id)
            assertEquals(0, c.coachRetired)
        }
        // A player whose coaching days are over too: the coach line says so (not "쉬고 있는 감독"). The
        // scene's retired coach was a legend another coach was made from too, so it is moved to a
        // retired player no coach was.
        val b = scene.book
        val player = scene.world.players.retired.first { r -> b.coaches.none { it.player == r.key } && b.retiredCoaches.none { it.player == r.key } }.key
        val files = StoryWorld.variant(scene.files) {
            clubs = clubs?.lines()?.joinToString("\n") { l ->
                if (l.startsWith("kr ${ClubScene.RETIRED_COACH} ")) l.split(' ').toMutableList().also { it[9] = player }.joinToString(" ") else l
            }
        }
        val w = ClubStory.decode(files)
        val moved = WorldPages(Host(w))
        val retired = w.clubs!!.retiredCoaches.first { it.id == ClubScene.RETIRED_COACH }
        assertEquals(player, retired.player)
        moved.loadPlayerClub(player)
        val r = wait { moved.playerClub(player) }
        assertNull(r.terms)
        assertEquals(ClubScene.RETIRED_COACH, r.coach)
        assertNull(r.coachClub)
        assertEquals(retired.retired, r.coachRetired)
        assertEquals("👔 시즌 ${retired.retired}에 감독에서 은퇴했어요 ›", PlayerClubTexts.coach(r.coachClub?.name, r.coachRetired))
    }

    @Test
    fun withoutAClubWorldNothingOfItShows() {
        // The story world as P2 built it: national clubs, no club world (a league not yet migrated).
        val w = ClubStory.decode(StoryWorld.files())
        assertNull(w.clubs?.world)
        val pages = WorldPages(Host(w))
        val id = w.clubs!!.clubs.first().id
        pages.loadClub(id)
        val p = wait { pages.club(Page.Club(id)) }
        assertNull(p.life)
        assertNull(p.history)
        assertTrue(p.loansOut.isEmpty() && p.members.all { it.terms == null })
        pages.loadClubTable(w.plan!!.league.leagues.list.first { it.national }.id)
        assertTrue(wait { pages.clubTable }.all.all { it.health == null && !it.angry })
        pages.loadClubRanking(null)
        assertTrue(wait { pages.clubRanking(Page.ClubRanking()) }.lists.isEmpty())
        val k = w.clubs!!.members(id).first()
        pages.loadPlayerClub(k)
        pages.loadCoach("k1")
        Thread.sleep(100)
        assertNull(pages.playerClub(k))
        assertNull(pages.coach(Page.Coach("k1")))
    }

    @Test
    fun withTheClubWorldSwitchedOffThePagesAreTodays() {
        // UX 4.1: with `clubWorld` off the club world stands still and the club pages are today's.
        val w = ClubStory.decode(scene.files)
        w.config = w.config.copy(clubWorld = false)
        assertNotNull(w.clubs?.world)
        val pages = WorldPages(Host(w))
        pages.loadClub(scene.club)
        val p = wait { pages.club(Page.Club(scene.club)) }
        assertNull(p.life)
        assertNull(p.history)
        assertTrue(p.members.all { it.terms == null })
        pages.loadClubRanking(null)
        assertTrue(wait { pages.clubRanking(Page.ClubRanking()) }.lists.isEmpty())
        pages.loadClubTable(w.plan!!.league.leagues.list.first { it.national }.id)
        assertTrue(wait { pages.clubTable }.all.all { it.health == null && !it.angry })
    }

    /** [get] once it gives a value (the loaders read off the main thread). */
    private fun <T : Any> wait(get: () -> T?): T {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            Thread.sleep(10)
        }
        error("nothing loaded in time")
    }

    /** A host of world [w] alone (no snapshot), its players named by key, [favs] followed. */
    private class Host(var w: LeagueWorld) : LeagueHost {
        var favs: Set<String> = emptySet()
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = favs
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = w.members.size

        /** The league club [id]'s members play in this season. */
        fun leagueOf(id: String): String? = w.clubs?.members(id)?.firstNotNullOfOrNull { w.plan?.league?.leagueOf(it)?.id }
    }
}
