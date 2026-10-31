package yacht.engine

import yacht.engine.league.Archive
import yacht.engine.league.Field
import yacht.engine.league.HighlightBook
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaderStat
import yacht.engine.league.MatchRef
import yacht.engine.league.RatingMove
import yacht.engine.league.ReviewGame
import yacht.engine.league.ReviewLeader
import yacht.engine.league.SeasonReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kept season reviews (design UX 4.7): the newest [Archive.REVIEWS] (3 in a small league), the
 * newest as the `rv` block an older version reads, the others as `rw` blocks; the review's new
 * parts (leaders, numbers, best games, best-rated game, biggest upset) and what
 * [Archive.amendReview] adds after the close, which a replay adds again without a change.
 */
class ReviewArchiveTest {
    private fun review(n: Int, transfers: Int? = null) = SeasonReview(
        n, listOf(Honour(n, "L0", "a$n")), listOf(Honour(n, "MVP", "m$n")), listOf(RatingMove("up$n", 1500.0, 1580.5)),
        listOf(RatingMove("down$n", 1500.0, 1420.0)), emptyList(), emptyList(), listOf("${n}시즌 1부 우승은 a${n}이에요."),
        listOf(ReviewLeader(LeaderStat.SCORER, "s$n", 251.25), ReviewLeader(LeaderStat.MVP, "m$n", 7.415)),
        listOf("경기 수" to "12,345판", "요트율" to "41%"), listOf("$n:L:3:0:1", "$n:C:7:0:4"),
        ReviewGame("m$n", "x$n", 9.875, MatchRef(n, yacht.engine.league.Comp.LEAGUE, 12, 0, 1).encode()),
        ReviewGame("u$n", "f$n", 214.0, MatchRef(n, yacht.engine.league.Comp.CUP, 5, 0).encode()),
        transfers = transfers,
    )

    @Test
    fun theNewestSixAreKeptAndThreeInASmallLeague() {
        val a = Archive()
        for (n in 1..9) a.putReview(review(n))
        assertEquals((9 downTo 4).toList(), a.reviewSeasons)
        assertEquals(9, a.review?.season)
        assertEquals(review(5), a.review(5))
        assertNull(a.review(3))
        val text = a.encode()
        // One "rv" block (the newest), five "rw" blocks.
        assertEquals(listOf("rv head 9 0"), text.lines().filter { it.startsWith("rv head") })
        assertEquals((4..8).map { "rw head $it 1" }, text.lines().filter { it.startsWith("rw head") })
        val back = Archive.decode(text)
        assertEquals(a.reviewSeasons, back.reviewSeasons)
        for (n in 4..9) assertEquals(a.review(n), back.review(n))
        assertEquals(text, back.encode())
        // A small league keeps three.
        val small = Archive()
        for (n in 1..5) small.putReview(review(n), members = Archive.SMALL_LEAGUE - 1)
        assertEquals(listOf(5, 4, 3), small.reviewSeasons)
        // Only the newest waits to be shown.
        assertEquals(9, a.pendingReview?.season)
        a.reviewShown()
        assertNull(Archive.decode(a.encode()).pendingReview)
        assertEquals(9, Archive.decode(a.encode()).review?.season)
    }

    @Test
    fun aReviewReadsBackWithEveryNewPart() {
        val a = Archive()
        a.putReview(review(4, transfers = 12))
        assertTrue(a.amendReview(4, listOf("KR" to 3, "JP" to -2), listOf("‘자동 승강 인원 2명 → 1명’ 가결 (찬성 7, 반대 2)")))
        val back = assertNotNull(Archive.decode(a.encode()).review(4))
        assertEquals(a.review(4), back)
        assertEquals(listOf("s4"), back.leadersOf(LeaderStat.SCORER).map { it.key })
        assertEquals(9.875, back.topRated?.value)
        assertEquals(MatchRef(4, yacht.engine.league.Comp.LEAGUE, 12, 0, 1), MatchRef.decode(back.topRated!!.ref))
        assertEquals(214.0, back.upset?.value)
        assertEquals(listOf("4:L:3:0:1", "4:C:7:0:4"), back.best)
        assertEquals(listOf("KR" to 3, "JP" to -2), back.ranking)
        assertEquals(12, back.transfers)
    }

    @Test
    fun amendingAgainChangesNothing() {
        val a = Archive()
        a.putReview(review(6))
        // After the 요트협회 resolves (close), then the transfers after the club off-season (open).
        assertTrue(a.amendReview(6, ranking = listOf("KR" to 1), federation = listOf("가결")))
        assertTrue(a.amendReview(6, transfers = 9))
        val once = a.encode()
        // A replay after a kill calls both again.
        assertTrue(a.amendReview(6, ranking = listOf("KR" to 1), federation = listOf("가결")))
        assertTrue(a.amendReview(6, transfers = 9))
        assertEquals(once, a.encode())
        assertEquals(listOf("KR" to 1), a.review(6)?.ranking)
        assertEquals(listOf("가결"), a.review(6)?.federation)
        assertEquals(9, a.review(6)?.transfers)
        // A part given again replaces, never adds.
        a.amendReview(6, ranking = listOf("JP" to -1))
        assertEquals(listOf("JP" to -1), a.review(6)?.ranking)
        assertEquals(listOf("가결"), a.review(6)?.federation)
        // No review of that season: nothing.
        assertFalse(a.amendReview(5, transfers = 3))
        assertNull(a.review(5))
    }

    @Test
    fun anOldReviewBlockStillDecodes() {
        // An archive.txt of 945de5e: one "rv" block without the new sub-lines, and "hl" lines.
        val old = listOf(
            "archive 1",
            "hl 3 a b c",
            "rv head 3 1",
            "rv h L0 g12",
            "rv h MVP g07",
            "rv up g07 1500 1612.5",
            "rv down g44 1500 1388",
            "rv meta 리그%20평균%20점수 201.3 204.8 0",
            "rv say 3시즌 1부 우승은 g12예요.",
            "",
        ).joinToString("\n")
        val a = Archive.decode(old)
        val r = assertNotNull(a.review(3))
        assertEquals(listOf(3), a.reviewSeasons)
        assertEquals("g12", r.champions.single().key)
        assertEquals("g07", r.awards.single().key)
        assertEquals(listOf("3시즌 1부 우승은 g12예요."), r.sentences)
        assertTrue(r.leaders.isEmpty() && r.numbers.isEmpty() && r.best.isEmpty() && r.topRated == null && r.transfers == null)
        assertNull(a.pendingReview)
        // Saved again: the same review, the highlight lines gone.
        val again = Archive.decode(a.encode())
        assertEquals(r, again.review(3))
        assertTrue(a.encode().lines().none { it.startsWith("hl ") })
    }

    @Test
    fun anOlderVersionReadsTheNewestReviewAsBefore() {
        val a = Archive()
        for (n in 1..4) a.putReview(review(n))
        val old = oldReview(a.encode())
        assertNotNull(old)
        assertEquals(4, old.season)
        assertEquals(listOf("a4", "m4"), old.honours)
        assertEquals(listOf("4시즌 1부 우승은 a4이에요."), old.sentences)
        assertEquals(listOf("up4"), old.up)
    }

    @Test
    fun aClosedSeasonsReviewNamesItsBestGames() {
        val lg = PlayedLeague(60, LeagueConfig(divisions = 3), 21L)
        lg.highlights = HighlightBook()
        lg.play(2)
        for ((i, end) in lg.ends.withIndex()) {
            val r = assertNotNull(end.review)
            val n = i + 1
            assertEquals(r, lg.archive.review(n))
            // Its three best games, the highlights' own (the log archive keeps them by these ids).
            assertEquals(3, r.best.size)
            assertTrue(r.best.all { MatchRef.decode(it)?.season == n }, r.best.toString())
            // Leaders of the boards the review keeps, at most three each, best first; a board led by
            // nothing (no upper bonus in these made-up games) has none.
            for (stat in SeasonReview.REVIEW_STATS) {
                val top = r.leadersOf(stat)
                assertTrue(top.size <= SeasonReview.LEADERS && top.all { it.value > 0 }, "$stat $top")
                if (stat == LeaderStat.BONUS) assertTrue(top.isEmpty(), "$stat $top")
                if (stat in listOf(LeaderStat.SCORER, LeaderStat.YACHTS, LeaderStat.MVP, LeaderStat.COMEBACK)) assertEquals(SeasonReview.LEADERS, top.size, "$stat $top")
                val sorted = if (stat.lowFirst) top.sortedBy { it.value } else top.sortedByDescending { it.value }
                assertEquals(sorted.map { it.value }, top.map { it.value })
            }
            assertEquals(listOf("경기 수", "평균 점수", "요트율", "보너스율", "300점 경기", "이변", "역전승", "2:0 완승 비율"), r.numbers.map { it.first })
            // The best-rated game and the biggest upset of the season, with a ref of it.
            val top = assertNotNull(r.topRated)
            assertEquals(n, MatchRef.decode(top.ref)?.season)
            assertTrue(top.value in 1.0..10.0)
            r.upset?.let { u -> assertTrue(u.value > 0 && (MatchRef.decode(u.ref)?.board ?: -1) >= 0, u.toString()) }
        }
        // Both kept (a league of 60: three at most) and read back.
        assertEquals(listOf(2, 1), lg.archive.reviewSeasons)
        assertEquals(lg.archive.encode(), Archive.decode(lg.archive.encode()).encode())
    }

    /** What 945de5e's archive decoder kept of a review: its `rv` lines only, the sub-lines it knew. */
    private class OldReview(val season: Int, val honours: List<String>, val up: List<String>, val sentences: List<String>)

    private fun oldReview(text: String): OldReview? {
        var season: Int? = null
        val honours = ArrayList<String>()
        val up = ArrayList<String>()
        val say = ArrayList<String>()
        for (line in text.lines()) {
            val f = line.split(' ')
            if (f[0] != "rv") continue
            runCatching {
                when (f[1]) {
                    "head" -> season = f[2].toInt()
                    "h" -> honours += Field.unesc(f[3])
                    "up" -> up += Field.unesc(f[2])
                    "say" -> say += line.substringAfter("rv say ")
                }
            }
        }
        return season?.let { OldReview(it, honours, up, say) }
    }
}
