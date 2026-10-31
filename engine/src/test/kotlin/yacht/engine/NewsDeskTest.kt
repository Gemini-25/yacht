package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Cup
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.MatchRef
import yacht.engine.league.NewsDesk
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The news desk (design_ux 5.2) on its own: a week's picks and their caps, a week closed once,
 * a big cup stage held for its digest and a small one not, milestones and group qualifiers kept
 * for the week, and every pending line read back after a kill.
 */
class NewsDeskTest {
    private val rules = RuleSet.NINTENDO

    /** 1부 p00-p07, 2부 p08-p15, 3부 p16-p23, ... (8 a division, linear). */
    private fun keys(n: Int) = (0 until n).map { "p%02d".format(it) }

    /** A legacy season of [n] players in divisions of 8, with the 요트컵 when [cup]. */
    private fun plan(n: Int = 24, cup: Boolean = false): SeasonPlan {
        val ks = keys(n)
        val config = LeagueConfig(generated = 0, clubs = 0, familyCup = false)
        val p = SeasonPlan(Season.start(1, 77L, ks.chunked(8)), config)
        if (cup) {
            val start = SeasonStart(1, 77L, config, ks, ::rating, ks.chunked(8), emptyList(), emptyList(), { null }, { "" })
            p.comps += Cup.create(start)!!
        }
        return p
    }

    /** Stronger by key: p00 is the best (seeds follow it). */
    private fun rating(k: String): Double = 2000.0 - 10 * k.drop(1).toInt()

    private fun card(total: Int) = Board.of(rules, List(Category.COUNT) { if (it == Category.CHOICE.ordinal) total else 0 })

    /** A game the first player ends [first] to [second], its P(win) staying at [p]% until the last turns. */
    private fun facts(first: Int, second: Int, p: Int): GameFacts {
        val totals = ShortArray(48) { i -> Math.round((if (i % 2 == 0) first else second) * (i / 2 + 1) / 24.0).toInt().toShort() }
        val end = if (first > second) 100 else 0
        return GameFacts(card(first), card(second), totals, ByteArray(24) { h -> (if (h < 22) p else end).toByte() })
    }

    /**
     * A league match [a] beats [b] 2-0 in week [week]: its last game (B moves first in it) won
     * from a P(win) of [low]% (B's curve at 100 - low), or no facts.
     */
    private fun match(id: Int, a: String, b: String, week: Int, low: Int? = null, comp: Comp = Comp.LEAGUE): JobResult {
        val kind = if (comp == Comp.LEAGUE) JobKind.MATCH else JobKind.TIE
        val f = Fixture(comp, id, a, b, 100L + id, kind, label = if (comp == Comp.LEAGUE) "${week}주차" else "16강", week = week)
        val games = listOf(GameScore(231, 200), GameScore(231, 229))
        val facts = if (low == null) emptyList() else listOf(facts(231, 200, 70), facts(229, 231, 100 - low))
        return JobResult(f, listOf(BoardResult(a, b, games, facts)), 1, 0)
    }

    private fun ctx(p: SeasonPlan, written: Set<String> = emptySet()) = NewsDesk.Context(p, StatsBook(), null) { it in written }

    @Test
    fun aWeeksPicksAreItsBiggestUpsetsInTheTopTiersAndOneComeback() {
        val p = plan()
        val desk = NewsDesk()
        desk.begin(p)
        assertEquals(1, desk.week)
        // The winner is rated by the key's number: p07 over p00 is 70 points (no upset), p15 over p08 too.
        val r = { k: String -> rating(k) * (if (k.drop(1).toInt() % 2 == 0) 1.0 else 0.8) }
        // Upsets of about 400 points in the 1부 and 2부, one in the 3부 (no news); comebacks from
        // 1% (in a match told as an upset), 2% (an upset not picked), 3% and 4%.
        desk.offer(match(1, "p01", "p00", 1, low = 1), p, r)
        desk.offer(match(2, "p03", "p02", 1), p, r)
        desk.offer(match(3, "p09", "p08", 1, low = 2), p, r)
        desk.offer(match(4, "p11", "p10", 1), p, r)
        desk.offer(match(5, "p17", "p16", 1), p, r)
        desk.offer(match(6, "p04", "p05", 1, low = 4), p, r)
        desk.offer(match(7, "p12", "p13", 1, low = 3), p, r)
        // A friendly and a team match are never offered.
        desk.offer(match(8, "p21", "p20", 1, comp = Comp.FRIENDLY), p, r)
        val gaps = (0..23).associate { "p%02d".format(it) to r("p%02d".format(it)) }
        val upsets = listOf(1 to "p00", 2 to "p02", 3 to "p08", 4 to "p10").map { (id, l) -> id to gaps.getValue(l) - gaps.getValue(listOf("p01", "p03", "p09", "p11")[id - 1]) }
        assertTrue(upsets.all { it.second >= 150 }, upsets.toString())
        assertEquals((1..7).filter { it != 5 }.map { MatchRef(1, Comp.LEAGUE, it).encode() }.toSet(), desk.candidates)
        // Nothing before the week is over; then once.
        assertTrue(desk.close(ctx(p), 1).isEmpty())
        val week = desk.close(ctx(p), 2)
        assertTrue(desk.close(ctx(p), 2).isEmpty())
        assertEquals(2, desk.week)
        val picked = week.filter { it.kind == "league_upset" }
        val biggest = upsets.sortedByDescending { it.second }.take(NewsDesk.UPSETS).map { MatchRef(1, Comp.LEAGUE, it.first).encode() }
        assertEquals(biggest, picked.map { it.ref })
        assertTrue(picked.all { it.args[1] == "-" && it.args[2].toInt() <= 1 && it.args[3] == "2-0" && it.week == 1 }, picked.toString())
        // The comeback: the lowest P(win) but the match told as an upset already.
        val comeback = week.single { it.kind == "comeback" }
        assertEquals(listOf("p09", "p08"), comeback.keys)
        assertEquals(listOf("2", "L", "1주차", "-", "1"), comeback.args)
        assertEquals(MatchRef(1, Comp.LEAGUE, 3, 0, 1).encode(), comeback.ref)
        assertTrue(desk.candidates.isEmpty())
        // A late result of a closed week goes into the week being collected; a match an article
        // already links (a week played again after a kill) is not written twice.
        desk.offer(match(9, "p05", "p04", 1, low = 1), p) { 1500.0 }
        desk.offer(match(10, "p07", "p06", 2, low = 2), p) { 1500.0 }
        val again = desk.close(ctx(p, written = setOf(MatchRef(1, Comp.LEAGUE, 9, 0, 1).encode())), 3)
        assertEquals(listOf(MatchRef(1, Comp.LEAGUE, 10, 0, 1).encode()), again.filter { it.kind == "comeback" }.map { it.ref })
        // A flood of candidates keeps the best of each kind.
        val many = NewsDesk()
        many.begin(p)
        for (i in 0 until 40) many.offer(match(100 + i, "p01", "p00", 1, low = 1 + i % 5), p) { k -> if (k == "p01") 1000.0 - i else 1500.0 }
        assertTrue(many.held <= NewsDesk.MAX_CANDIDATES, many.held.toString())
        val best = many.close(ctx(p), 2)
        assertEquals(MatchRef(1, Comp.LEAGUE, 139).encode(), best.first { it.kind == "league_upset" }.ref)
    }

    @Test
    fun aBigCupStageWaitsForItsEndAndASmallOneDoesNot() {
        val p = plan(64, cup = true)
        val cup = p.competition(Comp.CUP)!!
        val desk = NewsDesk()
        desk.begin(p)
        val written = ArrayList<List<LeagueEvent>>()
        // The lower seed wins every third tie (a giant killing with these ratings).
        fun play(stage: Int): List<LeagueEvent> {
            val out = ArrayList<LeagueEvent>()
            while (true) {
                val f = cup.next(1.0, emptySet()) ?: break
                if (cup.stageOf(f) != stage) {
                    cup.release(f)
                    break
                }
                val under = f.id % 3 == 0
                val g = List(2 * f.legs) { if (under) GameScore(190, 230) else GameScore(230, 190) }
                val e = p.record(JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), if (under) -1 else 1, 0))
                val shown = desk.sift(e, p, f)
                written += shown
                out += shown
                // Read back while it holds a stage, the desk is the same.
                if (stage == 0 && written.size == 20) assertEquals(desk.encode(), NewsDesk.decode(desk.encode()).encode())
                if (cup.stageCounts()[stage].done) break
                // Nothing of a big stage is written before its end.
                if (cup.stageCounts()[stage].total > NewsDesk.SMALL_STAGE) assertTrue(shown.isEmpty(), shown.toString())
            }
            return out
        }
        val first = play(0)
        val ties = cup.stageCounts()[0].total
        assertTrue(ties > NewsDesk.SMALL_STAGE)
        val digest = first.single { it.kind == "cup_digest" }
        assertEquals(ties.toString(), digest.args[1])
        val rounds = first.filter { it.kind == "cup_round" }
        assertTrue(rounds.size in 1..NewsDesk.SINGLES, rounds.toString())
        // The singles are the stage's biggest giant killings; the digest counts all of them.
        val killings = digest.args[2].toInt()
        assertTrue(killings > NewsDesk.SINGLES, digest.toString())
        assertTrue(rounds.all { r -> first.any { it.kind == "upset" && it.keys == r.keys } })
        assertEquals("cup_digest", first.last().kind)
        // Played again after a kill, the stage's last tie tells nothing new.
        val replay = desk.sift(first.filter { it.kind == "cup_round" }.take(1), p, Fixture(Comp.CUP, 0, "x", "y", 0L, JobKind.TIE))
        assertTrue(replay.none { it.kind == "cup_digest" })
        // A stage of 8 ties or fewer is written as it goes, with no digest.
        play(1)
        val small = (2 until cup.stageCounts().size).first { cup.stageCounts()[it].total <= NewsDesk.SMALL_STAGE }
        for (s in 1 until small) play(s)
        val late = play(small)
        assertTrue(late.none { it.kind == "cup_digest" }, late.toString())
        assertTrue(late.isNotEmpty())
    }

    @Test
    fun milestonesAndQualifiersWaitForTheWeekAndEveryPendingLineIsReadBack() {
        val p = plan(cup = true)
        val desk = NewsDesk()
        desk.begin(p)
        val ms = (0 until 12).map { i -> LeagueEvent("milestone", 1, listOf("p%02d".format(i)), listOf(if (i < 8) "wins" else "yachts", if (i == 0) "1000" else "100"), null, 1) } +
            LeagueEvent("milestone", 1, listOf("p20"), listOf("300", "1"), null, 1) + LeagueEvent("milestone", 1, listOf("p21"), listOf("title", "1"), null, 1)
        // Title milestones (the close's) go through; the others wait.
        assertEquals(listOf("title"), desk.sift(ms, p).map { it.args[0] })
        val qualified = (0 until 8).map { i -> LeagueEvent("qualified", 1, listOf("p%02d".format(i)), listOf("8강", "${'A' + i / 2}조 ${i % 2 + 1}위"), Comp.CHAMPIONS, 1) }
        val out = LeagueEvent("eliminated", 1, listOf("p09"), listOf("조별 리그"), Comp.CHAMPIONS, 1)
        assertTrue(desk.sift(qualified + out, p).isEmpty())
        desk.offer(match(1, "p01", "p00", 1, low = 2), p) { k -> if (k == "p01") 1000.0 else 1500.0 }
        // Read back after a kill, the desk is the same and closes the week the same.
        val text = desk.encode()
        val back = NewsDesk.decode("news 1\na 1 x - - - - 1\n" + text)
        assertEquals(text, back.encode())
        assertEquals(desk.candidates, back.candidates)
        assertEquals(desk.held, back.held)
        val week = desk.close(ctx(p), 2)
        assertEquals(week, back.close(ctx(p), 2))
        assertEquals(week.map { it.ref }, back.encode().let { NewsDesk.decode(text).close(ctx(p), 2).map { it.ref } })
        assertEquals(desk.encode(), back.encode())
        // The league upset of the week, not again as its comeback.
        assertEquals(listOf("league_upset"), week.filter { it.ref != null }.map { it.kind })
        // Two singles (the top of a ladder first), one digest of the rest.
        val singles = week.filter { it.kind == "milestone" }
        assertEquals(listOf("p00", "p20"), singles.map { it.keys.single() })
        val digest = week.single { it.kind == "milestones_week" }
        assertEquals(listOf("wins:100:7", "yachts:100:4"), digest.args)
        assertEquals(11, digest.keys.size)
        // The group stage: its top seed out alone, one digest naming the group winners first; told
        // again after a kill, nothing.
        assertEquals(listOf(out), week.filter { it.kind == "eliminated" })
        val group = week.single { it.kind == "group_digest" }
        assertEquals(listOf("p00", "p02", "p04", "p06", "p01", "p03", "p05", "p07"), group.keys)
        assertEquals(listOf(NewsDesk.GROUP_STAGE, "8", "8", "1"), group.args)
        assertTrue(desk.sift(qualified + out, p).isEmpty())
        assertTrue(desk.close(ctx(p), 3).none { it.kind == "group_digest" })
        // A desk of no season writes nothing; another season's lines are not this one's.
        assertEquals("", NewsDesk().encode())
        val other = NewsDesk.decode(text.replace("desk 1 ", "desk 9 "))
        assertTrue(other.sift(ms, p) == ms && other.close(ctx(p), 5).isEmpty())
    }

    @Test
    fun theSeasonsEndWritesWhatIsLeft() {
        val p = plan()
        val desk = NewsDesk()
        desk.begin(p)
        desk.offer(match(1, "p01", "p00", 3), p) { k -> if (k == "p01") 1000.0 else 1500.0 }
        desk.offer(match(2, "p05", "p04", 3, low = 2), p) { 1500.0 }
        desk.sift(listOf(LeagueEvent("milestone", 1, listOf("p05"), listOf("wins", "100"), null, 3)), p)
        val left = desk.flush(ctx(p))
        assertEquals(listOf("league_upset", "comeback", "milestone"), left.map { it.kind })
        assertTrue(desk.flush(ctx(p)).isEmpty())
        assertEquals(0, desk.held)
    }
}
