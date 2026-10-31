package yacht.uicheck

import yacht.app.core.BroadcastPicks
import yacht.app.core.BroadcastPicks.Candidate
import yacht.engine.league.Comp
import yacht.engine.league.Fixture
import yacht.engine.league.JobKind
import yacht.engine.league.MatchRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The broadcast channel's choice (UX 3.7): the score of a match, the diversity caps, star picks and next week's picks. */
class BroadcastPicksTest {
    private var ids = 0

    private fun cand(
        comp: Comp = Comp.LEAGUE,
        base: Int = 0,
        keys: List<String> = listOf("a${ids}", "b${ids}"),
        league: String? = if (comp == Comp.LEAGUE) "KR" else null,
        race: Boolean = false,
        star: Boolean = false,
        mates: Boolean = false,
        percentile: Double = 0.5,
        close: Boolean = false,
        id: Int = ids++,
    ) = Candidate(MatchRef(1, comp, id), 3, comp, "", keys[0], keys[1], keys, league, base, race, star, mates, percentile, close)

    private fun fixture(comp: Comp, label: String, kind: JobKind = JobKind.TIE) = Fixture(comp, 1, "a", "b", 7L, kind, label = label)

    @Test
    fun theBaseOfEveryKindOfMatch() {
        fun base(comp: Comp, label: String, rank: Int = 0, tier: Int? = null, upper: Int? = null, kind: JobKind = JobKind.TIE) =
            BroadcastPicks.base(fixture(comp, label, kind), rank, tier, upper)
        // Finals (a double elimination's grand final too, not its winners' or losers' final) and the super cup.
        assertEquals(100, base(Comp.CUP, "결승"))
        assertEquals(100, base(Comp.CHAMPIONS, "최종 결승"))
        assertEquals(100, base(Comp.CONT_CHAMPIONS, "아시아 결승"))
        assertEquals(100, base(Comp.SUPER, "슈퍼컵"))
        // Title playoffs of the best-ranked leagues (0-based rank 3 is the fourth).
        assertEquals(70, base(Comp.TITLE_PLAYOFF, "한국 4강", rank = 0))
        assertEquals(70, base(Comp.TITLE_PLAYOFF, "일본 4강", rank = 3))
        assertEquals(0, base(Comp.TITLE_PLAYOFF, "태국 4강", rank = 4))
        // Knockouts and groups.
        assertEquals(60, base(Comp.CHAMPIONS, "8강"))
        assertEquals(30, base(Comp.CHAMPIONS, "A조 2차전", kind = JobKind.MATCH))
        assertEquals(60, base(Comp.WORLD_CUP, "16강", kind = JobKind.TEAM))
        assertEquals(30, base(Comp.CONTINENTAL, "B조 1차전", kind = JobKind.TEAM))
        assertEquals(50, base(Comp.CONT_CHAMPIONS, "아시아 4강"))
        assertEquals(20, base(Comp.CONT_CHAMPIONS, "아시아 A조 3차전", kind = JobKind.MATCH))
        assertEquals(40, base(Comp.CLUB_CUP, "4강", kind = JobKind.TEAM))
        assertEquals(20, base(Comp.CLUB_CUP, "A조 1차전", kind = JobKind.TEAM))
        assertEquals(40, base(Comp.ALL_STAR, "올스타전", kind = JobKind.TEAM))
        assertEquals(40, base(Comp.LEGEND, "레전드 매치"))
        // The 요트컵 by its round.
        assertEquals(50, base(Comp.CUP, "8강"))
        assertEquals(50, base(Comp.CUP, "승자조 4강"))
        assertEquals(50, base(Comp.CUP, "패자조 결승"))
        assertEquals(25, base(Comp.CUP, "16강"))
        assertEquals(25, base(Comp.CUP, "32강"))
        assertEquals(0, base(Comp.CUP, "64강"))
        // The 1·2부 promotion tie of a top league only.
        assertEquals(35, base(Comp.PROMOTION_PLAYOFF, "승강전", rank = 1, upper = 0))
        assertEquals(0, base(Comp.PROMOTION_PLAYOFF, "승강전", rank = 1, upper = 1))
        assertEquals(0, base(Comp.PROMOTION_PLAYOFF, "승강전", rank = 6, upper = 0))
        // A league fixture: 1부 only, 30 − 3 × rank.
        assertEquals(30, base(Comp.LEAGUE, "3라운드", rank = 0, tier = 0, kind = JobKind.MATCH))
        assertEquals(24, base(Comp.LEAGUE, "3라운드", rank = 2, tier = 0, kind = JobKind.MATCH))
        assertEquals(0, base(Comp.LEAGUE, "3라운드", rank = 12, tier = 0, kind = JobKind.MATCH))
        assertEquals(0, base(Comp.LEAGUE, "3라운드", rank = Int.MAX_VALUE, tier = 0, kind = JobKind.MATCH))
        assertEquals(0, base(Comp.LEAGUE, "3라운드", rank = 0, tier = 1, kind = JobKind.MATCH))
    }

    @Test
    fun theScoreAddsTheRaceTheStarTheMatesAndTheLevel() {
        // level = 20 × (1 − percentile) + 10 when close.
        assertEquals(24 + 0 + 0 + 0 + 10, cand(base = 24, percentile = 0.5).score)
        assertEquals(24 + 25 + 80 + 10 + 15 + 10, cand(base = 24, race = true, star = true, mates = true, percentile = 0.25, close = true).score)
        assertEquals(100 + 20, cand(Comp.CUP, base = 100, percentile = 0.0).score)
        assertEquals(0, cand(base = 0, percentile = 1.0).score)
        // Ratings ranks outside 0-1 never make the level negative or over 20.
        assertEquals(20, cand(percentile = -0.5).level)
        assertEquals(0, cand(percentile = 1.5).level)
    }

    @Test
    fun picksKeepToTheCaps() {
        val cups = List(4) { cand(Comp.CUP, base = 50 + it) }
        val leagues = List(3) { cand(Comp.LEAGUE, base = 30, league = "KR") } + listOf(cand(Comp.LEAGUE, base = 29, league = "JP"), cand(Comp.LEAGUE, base = 28, league = "CN"), cand(Comp.LEAGUE, base = 27, league = "US"))
        // Shares a player with the best cup tie: never both.
        val clash = cand(Comp.CHAMPIONS, base = 45, keys = listOf(cups.last().a, "z"))
        val other = cand(Comp.CHAMPIONS, base = 44)
        val picks = BroadcastPicks.choose(cups + leagues + clash + other, emptyList())
        assertEquals(BroadcastPicks.PER_WEEK, picks.size, picks.joinToString { "${it.comp}/${it.ref.id}/${it.score}" })
        assertEquals(2, picks.count { it.comp == Comp.CUP }, "two a competition")
        assertTrue(picks.count { it.comp == Comp.LEAGUE } <= BroadcastPicks.LEAGUE_MAX)
        assertEquals(1, picks.count { it.league == "KR" }, "one a league")
        assertTrue(clash !in picks, "a player once")
        val keys = picks.flatMap { it.keys }
        assertEquals(keys.size, keys.toSet().size)
        // Score order: the best cup ties first.
        assertEquals(listOf(cups[3], cups[2]), picks.take(2))
        // Ties of score go by base, then competition order, then id.
        val a = cand(Comp.CHAMPIONS, base = 40, percentile = 0.5)
        val b = cand(Comp.CUP, base = 40, percentile = 0.5)
        val c = cand(Comp.CUP, base = 40, percentile = 0.5)
        assertEquals(listOf(b, c, a), BroadcastPicks.choose(listOf(a, c, b), emptyList()))
    }

    @Test
    fun atMostTwoStarPicks() {
        val stars = List(4) { cand(Comp.entries[it + 1], base = 10, star = true) }
        val plain = List(4) { cand(Comp.entries[it + 5], base = 60) }
        val picks = BroadcastPicks.choose(stars + plain, emptyList())
        assertEquals(BroadcastPicks.STARS, picks.count { it.star })
        // The star bonus puts them first.
        assertTrue(picks.take(2).all { it.star })
        assertEquals(BroadcastPicks.PER_WEEK, picks.size)
    }

    @Test
    fun nextWeeksPicksStayFirstAndCountForTheCaps() {
        // Picked a week ago, as next week's: they stay, first, and fill the caps they take.
        val early = listOf(cand(Comp.CUP, base = 25), cand(Comp.CUP, base = 25), cand(Comp.LEAGUE, base = 30, star = true))
        val now = listOf(cand(Comp.CUP, base = 100), cand(Comp.LEAGUE, base = 30, league = "JP", star = true), cand(Comp.LEAGUE, base = 30, league = "JP"),
            cand(Comp.CHAMPIONS, base = 60), cand(Comp.ALL_STAR, base = 40), cand(Comp.CONT_CHAMPIONS, base = 50), cand(Comp.CLUB_CUP, base = 40))
        val more = BroadcastPicks.choose(now + early, early)
        assertTrue(more.none { it in early }, "a pick is never picked twice")
        assertEquals(BroadcastPicks.PER_WEEK - early.size, more.size, "the week's picks only grow, to six")
        assertTrue(more.none { it.comp == Comp.CUP }, "the cup has its two already: even its final waits")
        assertEquals(1, more.count { it.star }, "one star pick left")
        // Nothing to add once the week is full.
        assertEquals(emptyList(), BroadcastPicks.choose(now, now.take(6)))
    }
}
