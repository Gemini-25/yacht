package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubTable
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueFixture
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.WinterMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The season's numbers through a week (design ENG 3.4): only the stored results of the league
 * fixtures of matchdays up to the cut count, so next week's fixtures played early never do; the
 * club table takes the same cut, the winter moves' splits and the deductions, and with the
 * defaults is today's.
 */
class PointsThroughTest {
    /** A result by the fixture's id: a 2-0 sweep by a, a win by a on one game and a draw, or a draw. */
    private fun games(f: LeagueFixture): List<GameScore> = when (f.id % 3) {
        0 -> listOf(GameScore(230, 190), GameScore(210, 200))
        1 -> listOf(GameScore(230, 190), GameScore(200, 200))
        else -> listOf(GameScore(230, 190), GameScore(190, 230))
    }

    /** points, played, wins, draws, losses, sweeps of [key] over [played] counted by hand (판 승점제 with the sweep point). */
    private fun byHand(key: String, played: List<LeagueFixture>): List<Int> {
        var p = 0
        var n = 0
        var w = 0
        var d = 0
        var l = 0
        var sw = 0
        for (f in played) {
            if (key != f.a && key != f.b) continue
            n++
            when (f.id % 3) {
                0 -> if (key == f.a) { w++; p += 4; sw++ } else l++
                1 -> if (key == f.a) { w++; p += 3 } else l++
                else -> { d++; p++ }
            }
        }
        return listOf(p, n, w, d, l, sw)
    }

    private fun take(s: Season, matchday: Int, n: Int = Int.MAX_VALUE): List<LeagueFixture> {
        val out = ArrayList<LeagueFixture>()
        while (out.size < n) {
            val f = s.next(emptySet()) ?: break
            if (f.matchday != matchday) {
                s.release(f.id)
                break
            }
            out += f
        }
        return out
    }

    @Test
    fun nextWeeksFixturesPlayedEarlyNeverCount() {
        val keys = listOf("a", "b", "c", "d", "e", "f")
        val s = Season.start(1, 9L, listOf(keys), false)
        SeasonPlan(s, LeagueConfig())
        assertTrue(s.bonus)
        // Matchday 0 whole, one fixture of matchday 1, then one of matchday 2 while matchday 1 is still on.
        val day0 = take(s, 0)
        assertEquals(3, day0.size)
        for (f in day0) assertTrue(s.record(f.id, games(f)))
        val day1 = take(s, 1)
        assertEquals(3, day1.size)
        assertTrue(s.record(day1[0].id, games(day1[0])))
        val early = s.next(emptySet())!!
        assertEquals(2, early.matchday)
        assertTrue(s.record(early.id, games(early)))
        // Through matchday 0 (week 1): matchday 0 only; through matchday 1: plus the one of matchday 1.
        val through0 = s.pointsThrough(0)
        val through1 = s.pointsThrough(1)
        for (k in keys) {
            assertEquals(byHand(k, day0), through0.getValue(k).toList(), k)
            assertEquals(byHand(k, day0 + day1[0]), through1.getValue(k).toList(), k)
        }
        assertEquals(keys.toSet(), through0.keys, "every member listed, zeros before its first match")
        assertEquals(listOf(0, 0, 0, 0, 0, 0), s.pointsThrough(-1).getValue("a").toList())
        // The early fixture counts once its matchday is in: and the live table has it already.
        assertEquals(byHand("a", day0 + day1[0] + early), s.pointsThrough(2)["a"]!!.toList())
        assertNotEquals(s.standings.values.sumOf { it.points }, through1.values.sumOf { it[Season.THROUGH_POINTS] })
        // Played to the end: the cut through the last matchday is the standings.
        for (f in day1.drop(1)) s.record(f.id, games(f))
        var guard = 0
        while (!s.finished) {
            val f = s.next(emptySet()) ?: break
            s.record(f.id, games(f))
            check(++guard < 1000)
        }
        val all = s.pointsThrough(s.matchdays - 1)
        for (k in keys) {
            val st = s.standings.getValue(k)
            assertEquals(listOf(st.points, st.played, st.wins, st.draws, st.losses, st.sweeps), all.getValue(k).toList(), k)
        }
    }

    /** A national book from hand-written club lines (id, nation, members). */
    private fun book(vararg clubs: Triple<String, String, List<String>>): ClubBook = ClubBook.decode(
        buildString {
            append("clubs 2\nmeta 1 0 - 100\n")
            for ((id, nation, members) in clubs) append("club $id $nation 도시 $id 도시%20$id 0 ffc62828 ffffffff 가 100 0 1 ${members.joinToString(",").ifEmpty { "-" }}\n")
        },
    )

    @Test
    fun theClubTableTakesTheCutTheSplitsAndTheDeductions() {
        val keys = listOf("a", "b", "c", "d", "e", "f")
        val s = Season.start(1, 9L, listOf(keys), false, Leagues.national(listOf("KR"), listOf(1)))
        SeasonPlan(s, LeagueConfig())
        val day0 = take(s, 0)
        for (f in day0) s.record(f.id, games(f))
        val day1 = take(s, 1)
        for (f in day1) s.record(f.id, games(f))
        // One fixture of matchday 2 played early.
        val early = s.next(emptySet())!!
        assertEquals(2, early.matchday)
        s.record(early.id, games(early))
        // "a" moved from c01 to c02 at the checkpoint after week 1 (matchday 0) with its points then.
        val clubs = book(Triple("c01", "KR", listOf("b", "c")), Triple("c02", "KR", listOf("a", "d", "e", "f")))
        val atMove = byHand("a", day0)[0]
        val split = listOf(WinterMove(1, "a", "c01", "c02", 1, atMove, 0))
        // Defaults: the 4-argument table (today's), live points.
        val live = ClubTable.of(s, clubs, "KR", emptyList())
        assertEquals(live, ClubTable.of(s, clubs, "KR", emptyList(), null, emptyList(), emptyMap()))
        fun livePoints(k: String) = s.standings.getValue(k).points.toDouble()
        assertEquals(listOf("a", "d", "e", "f").sumOf(::livePoints), live.first { it.club == "c02" }.points)
        // Through week 2 (matchdays 0 and 1): the early fixture does not count.
        val cut = ClubTable.of(s, clubs, "KR", emptyList(), through = 2)
        fun cutPoints(k: String) = byHand(k, day0 + day1)[0].toDouble()
        assertEquals(cutPoints("b") + cutPoints("c"), cut.first { it.club == "c01" }.points)
        assertEquals(listOf("a", "d", "e", "f").sumOf(::cutPoints), cut.first { it.club == "c02" }.points)
        assertNotEquals(live, cut)
        // The split: a's points up to the move for c01 (which counts it too), the rest for c02.
        val split2 = ClubTable.of(s, clubs, "KR", emptyList(), through = 2, splits = split)
        val c01 = split2.first { it.club == "c01" }
        val c02 = split2.first { it.club == "c02" }
        assertEquals(cutPoints("b") + cutPoints("c") + atMove, c01.points)
        assertEquals(listOf("d", "e", "f").sumOf(::cutPoints) + cutPoints("a") - atMove, c02.points)
        assertTrue("a" in c01.counted && "a" in c02.counted)
        assertEquals(2, c01.members)
        // Another season's split is not this one's.
        assertEquals(cut, ClubTable.of(s, clubs, "KR", emptyList(), through = 2, splits = listOf(split[0].copy(season = 2))))
        // A deduction takes its percentage off the club's points only.
        val cutBy = ClubTable.of(s, clubs, "KR", emptyList(), through = 2, deductions = mapOf("c02" to 10))
        val was = cut.first { it.club == "c02" }
        val now = cutBy.first { it.club == "c02" }
        assertEquals(Math.round(was.points * 0.9 * 10) / 10.0, now.points)
        assertEquals(was.leaguePart, now.leaguePart)
        assertEquals(cut.first { it.club == "c01" }, cutBy.first { it.club == "c01" })
        assertEquals(ClubTable.all(s, clubs, emptyList(), through = 2).getValue("KR"), cut)
    }
}
