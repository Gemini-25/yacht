package yacht.engine

import yacht.engine.league.Article
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Drama
import yacht.engine.league.DramaContext
import yacht.engine.league.DramaTag
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameScore
import yacht.engine.league.HighlightBook
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Josa
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LiveBoard
import yacht.engine.league.MatchFormat
import yacht.engine.league.News
import yacht.engine.league.NewsBook
import yacht.engine.league.Season
import yacht.engine.league.TieRule
import yacht.engine.league.TitleRaceWatch
import yacht.engine.league.WinEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTest {
    private val rules = RuleSet.NINTENDO

    private fun card(total: Int) = Board.of(rules, List(Category.COUNT) { if (it == Category.CHOICE.ordinal) total else 0 })

    /**
     * Synthetic facts of a game the first player ends [first] to [second]: totals grow evenly
     * (the second gets [swing] extra in the middle turns, for lead changes), the curve as given.
     */
    private fun facts(first: Int, second: Int, curve: IntArray, swing: Int = 0): GameFacts {
        val totals = ShortArray(48)
        for (h in 0 until 24) {
            totals[2 * h] = Math.round(first * (h + 1) / 24.0).toInt().toShort()
            totals[2 * h + 1] = (Math.round(second * (h + 1) / 24.0).toInt() + if (h in 8..19) swing else 0).toShort()
        }
        return GameFacts(card(first), card(second), totals, ByteArray(24) { curve[it].toByte() })
    }

    /** The first player leads all the way. */
    private val blowout = IntArray(24) { if (it == 23) 100 else minOf(99, 55 + 2 * it) }

    /** The first player falls to [low]% and wins on the last turn. */
    private fun comeback(low: Int = 8) = IntArray(24) { h ->
        when {
            h < 4 -> 50 - (50 - low) * h / 4
            h < 16 -> low
            h < 22 -> minOf(45, low + (h - 15) * 6)
            h == 22 -> 60
            else -> 100
        }
    }

    // ---- drama ---------------------------------------------------------------------------------

    @Test
    fun aComebackOutranksABlowout() {
        val come = facts(231, 230, comeback(), swing = 30)
        val blow = facts(280, 150, blowout)
        assertEquals(2, come.leadChanges)
        val c = Drama.of(come)
        val b = Drama.of(blow)
        assertTrue(c.score > 60 && b.score < 5, "comeback ${c.score}, blowout ${b.score}")
        assertTrue(DramaTag.COMEBACK_BIG in c.tags && DramaTag.LAST_TURN in c.tags && DramaTag.ONE_POINT in c.tags, c.tags.toString())
        assertTrue(b.tags.isEmpty(), b.tags.toString())
        // Stakes multiply; an upset (the winner rated far lower) adds and is tagged.
        val final = Drama.of(come, DramaContext(final = true))
        assertEquals(c.score * 1.5, final.score, 1e-9)
        assertEquals(DramaTag.FINAL, final.tags.first())
        val upset = Drama.of(blow, DramaContext(firstRating = 1300.0, secondRating = 1500.0))
        assertTrue(DramaTag.UPSET in upset.tags && upset.score > b.score)
        // 300 points.
        assertTrue(DramaTag.BIG_SCORE in Drama.of(facts(310, 150, blowout)).tags)
        assertTrue(Drama.isFinal(Fixture(Comp.CUP, 1, "a", "b", 0, JobKind.TIE, label = "결승")))
        assertFalse(Drama.isFinal(Fixture(Comp.CUP, 1, "a", "b", 0, JobKind.TIE, label = "4강")))
    }

    // ---- highlights ----------------------------------------------------------------------------

    /** Fixture [i]: game 0 a comeback for A falling to [low]%, game 1 a blowout for B (who moves first in it). */
    private fun job(i: Int, low: Int, comp: Comp = Comp.LEAGUE): JobResult {
        val f = Fixture(comp, i, "a$i", "b$i", 1000L + i, label = "${i + 1}라운드")
        val games = listOf(GameScore(231, 230), GameScore(150, 280))
        val facts = listOf(facts(231, 230, comeback(low), swing = 30), facts(280, 150, blowout))
        return JobResult(f, listOf(BoardResult(f.a, f.b, games, facts)), TieRule.match(games), 0)
    }

    @Test
    fun theBookKeepsTheTop20AndArchives3() {
        val book = HighlightBook()
        for (i in 0 until 30) book.offer(job(i, low = 2 + i), season = 1, version = { "v1" })
        val top = book.thisSeason
        assertEquals(HighlightBook.SEASON_TOP, top.size)
        assertTrue(top.zipWithNext().all { (x, y) -> x.drama >= y.drama })
        // The deepest comebacks (lowest P) are the best; blowouts never get in.
        assertEquals((0 until 20).map { "a$it" }, top.map { it.fixture.a })
        assertTrue(top.all { it.game == 0 && it.scoreA == 231 })
        assertEquals(20, book.allTime.size)
        // The same fixture again (replayed after a kill) and friendlies change nothing.
        book.offer(job(0, low = 2), season = 1)
        book.offer(job(99, low = 0, comp = Comp.FRIENDLY), season = 1)
        assertEquals(top.map { it.id }, book.thisSeason.map { it.id })
        val lines = book.archiveLines(1)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("hl ") })
        val back = HighlightBook.decodeArchive(lines + "hl cut 1 2" + "")
        assertEquals(top.take(3).map { it.id }, back.map { it.id })
        assertEquals(top[0].curve.toList(), back[0].curve.toList())
        // A new season: its list starts empty, the old season's best can still be archived.
        book.startSeason(2)
        assertTrue(book.thisSeason.isEmpty())
        assertEquals(lines, book.archiveLines(1))
        assertEquals(20, book.allTime.size)
        book.offer(job(5, low = 50), season = 2)
        assertEquals(2, book.thisSeason.size)
        // An old season's game is ignored.
        assertTrue(book.offer(job(7, low = 1), season = 1).isEmpty())
    }

    @Test
    fun highlightsSaveAndSurviveACutFile() {
        val book = HighlightBook()
        for (i in 0 until 25) book.offer(job(i, low = 3 + i), season = 4, version = { "v$it" })
        // A team match (board 2 is a lineup pair) and a two-legged tie, with labels holding spaces.
        val team = Fixture(Comp.FAMILY, 101, "WIN", "EV", 77L, JobKind.TEAM, lineupA = listOf("x1", "x2"), lineupB = listOf("y1", "y2"), label = "A조 2차전")
        val tg = listOf(GameScore(231, 230), GameScore(150, 280))
        val tf = listOf(facts(231, 230, comeback(1), swing = 30), facts(280, 150, blowout))
        book.offer(JobResult(team, listOf(BoardResult("x1", "y1", tg, tf), BoardResult("x2", "y2", tg, tf)), 0, 0), season = 4, hometown = { "서울" })
        // A generated player's genes are kept with its games.
        val genes = yacht.engine.league.CustomAiSpec("g7", "리암 존슨", play = yacht.engine.league.PlayGene("hunter", 1.2, overlay = "gambler"))
        book.offer(job(30, low = 1), season = 4, version = { "v$it" }, genes = { if (it == job(30, 1).fixture.a) genes else null })
        val text = book.encode()
        assertEquals(HighlightBook.HEADER, text.lines().first())
        val back = HighlightBook.decode(text)
        val kept = back.thisSeason.first { it.fixture.id == job(30, 1).fixture.id }
        assertEquals(genes, kept.snapshot(kept.a))
        assertEquals(null, kept.snapshot(kept.b))
        assertEquals(4, back.season)
        assertEquals(book.thisSeason.map { it.encode() }, back.thisSeason.map { it.encode() })
        assertEquals(book.allTime.map { it.encode() }, back.allTime.map { it.encode() })
        val t = back.thisSeason.first { it.fixture.kind == JobKind.TEAM && it.board == 1 }
        assertEquals("x2" to "y2", t.a to t.b)
        assertEquals(team, t.fixture)
        assertTrue(DramaTag.DERBY in t.tags && "2번 보드" in t.caption, t.caption)
        // Cut in the middle of the last line: that line is lost, the rest is kept.
        val cut = HighlightBook.decode(text.substring(0, text.length - 12))
        assertEquals(book.allTime.size - 1, cut.allTime.size)
        assertEquals(book.thisSeason.size, cut.thisSeason.size)
        // A garbled line is skipped; a file that is not one throws.
        assertEquals(book.thisSeason.size, HighlightBook.decode(text.replaceFirst("cur 4 ", "cur x ")).thisSeason.size + 1)
        assertFailsWith<IllegalArgumentException> { HighlightBook.decode("") }
        assertFailsWith<IllegalArgumentException> { HighlightBook.decode("season 3\ncur 1") }
    }

    @Test
    fun aBookReadBackBehavesLikeTheOneInMemory() {
        val book = HighlightBook()
        for (i in 0 until 25) book.offer(job(i, low = 3 + i), season = 2, rating = { if (it.startsWith("a")) 1400.0 + it.length else 1530.0 + it.length })
        val back = HighlightBook.decode(book.encode())
        // The drama is kept at the saved precision, so the lists match exactly ...
        assertEquals(book.thisSeason.map { it.drama }, back.thisSeason.map { it.drama })
        assertEquals(book.allTime.map { it.drama }, back.allTime.map { it.drama })
        // ... and so do the games either book admits next.
        for (i in 25 until 40) {
            val j = job(i, low = 2 + (i * 7) % 20)
            assertEquals(book.offer(j, season = 2).map { it.id }, back.offer(j, season = 2).map { it.id })
        }
        assertEquals(book.encode(), back.encode())
        // The lists handed out are snapshots: a view built under the lock may keep them.
        val seen = book.thisSeason
        val ever = book.allTime
        book.startSeason(3)
        book.offer(job(77, low = 1), season = 3)
        assertEquals(20, seen.size)
        assertTrue(seen.all { it.season == 2 } && ever.none { it.season == 3 })
        assertEquals(2, book.thisSeason.size)
    }

    @Test
    fun aStoredGameReplaysToTheSameScores() {
        val tables = SolverTest.tables
        val estimate = WinEstimate(tables)
        val ais = listOf(0.3, 0.8, 1.5, 3.0).mapIndexed { i, tau ->
            LeagueAi("s$i", "감 $tau", Family.HUMAN, "") { r -> SoftmaxPlayer(tables.ev, tau, r, "s$i") }
        }.associateBy { it.key }
        fun replays(job: Fixture, format: MatchFormat): JobResult {
            val r = LeagueMatch.playJob(rules, job, { ais[it] }, format, estimate)!!
            for ((bi, bd) in r.boards.withIndex()) {
                val per = if (format == MatchFormat.SINGLE) 1 else 2
                for ((gi, g) in bd.games.withIndex()) {
                    val again = LeagueMatch.playGame(rules, LeagueMatch.deal(job, bi, gi, per), ais.getValue(bd.a), ais.getValue(bd.b), estimate)
                    assertEquals(g, again.score, "${job.kind} board $bi game $gi")
                    assertEquals(bd.facts[gi].curve.toList(), again.facts!!.curve.toList())
                }
            }
            return r
        }
        val tie = replays(Fixture(Comp.CUP, 3, "s0", "s3", 11L, JobKind.TIE, legs = 2, label = "결승"), MatchFormat.MIRRORED)
        replays(Fixture(Comp.FAMILY, 4, "T1", "T2", 12L, JobKind.TEAM, lineupA = listOf("s0", "s1"), lineupB = listOf("s2", "s3")), MatchFormat.MIRRORED)
        replays(Fixture(Comp.LEAGUE, 5, "s1", "s2", 13L), MatchFormat.SINGLE)
        // A level best-of-3 match gets its decider game: find one and replay it too.
        val decider = (20L until 60L).asSequence()
            .map { replays(Fixture(Comp.LEAGUE, it.toInt(), "s1", "s2", it), MatchFormat.BEST_OF_3) }
            .firstOrNull { it.boards[0].deciders == 1 }
        assertNotNull(decider)
        // Stored as a highlight, the final replays turn by turn with the same scores.
        val book = HighlightBook()
        book.offer(tie, season = 1, version = { "v1" })
        val h = book.thisSeason.first()
        assertTrue(DramaTag.FINAL in h.tags)
        val back = HighlightBook.decode(book.encode()).thisSeason.first()
        val replay = assertNotNull(back.replay(rules, { ais[it] }))
        assertTrue(replay.same)
        assertNull(replay.message)
        assertEquals(24, replay.turns.size)
        val last = replay.turns.last()
        val (fa, fb) = if (back.deal.aFirst) last.first.total to last.second.total else last.second.total to last.first.total
        assertEquals(h.scoreA to h.scoreB, fa to fb)
        assertTrue(back.sameVersions { "v1" })
        assertFalse(back.sameVersions { "v2" })
        // An AI that is gone cannot replay.
        assertNull(back.replay(rules, { null }))
    }

    // ---- news ----------------------------------------------------------------------------------

    private val names = mapOf("ai1" to "김민준", "ai2" to "승률 AI (앱 기본)", "ai3" to "목표 180점", "ai4" to "이서연", "e3_1" to "박하람", "e3_2" to "최별")
    private val clubs = mapOf("c1" to "서울 타이거즈", "c2" to "부산 갈매기")

    private fun samples(s: Int): List<LeagueEvent> {
        fun e(kind: String, keys: List<String>, args: List<String> = emptyList(), comp: Comp? = null) = LeagueEvent(kind, s, keys, args, comp)
        val two = listOf("ai1", "ai2")
        val four = listOf("ai1", "ai2", "ai3", "ai4")
        return listOf(
            e("upset", two, listOf("152"), Comp.CUP), e("upset", two),
            e("comeback", two, listOf("6"), Comp.LEAGUE), e("comeback", two, listOf("0.04")), e("comeback", two),
            e("record", listOf("ai1"), listOf("best_game", "341", "330")), e("record", listOf("ai3"), listOf("streak", "17")),
            e("milestone", listOf("ai1"), listOf("games", "1000")), e("milestone", listOf("ai2"), listOf("wins", "500")),
            e("milestone", listOf("ai3"), listOf("yachts", "100")), e("milestone", listOf("ai1"), listOf("title", "1")),
            e("milestone", listOf("ai1"), listOf("titles", "5")), e("milestone", listOf("ai4"), listOf("300", "1")),
            e("title", listOf("ai1"), listOf("0"), Comp.LEAGUE), e("title", listOf("ai2"), listOf("2"), Comp.LEAGUE),
            e("title", listOf("ai1"), listOf("요트컵"), Comp.CUP), e("title", listOf("HUMAN"), listOf("계열 대항전"), Comp.FAMILY),
            e("title", listOf("c1"), emptyList(), Comp.CLUB), e("title", listOf("ai3"), listOf("0"), Comp.TITLE_PLAYOFF),
            e("promoted", listOf("ai1"), listOf("1")), e("promoted", listOf("ai1"), listOf("2", "1"), Comp.PROMOTION_PLAYOFF),
            e("relegated", listOf("ai2"), listOf("1")), e("relegated", listOf("ai2"), listOf("0", "1")),
            e("cup_round", listOf("ai1", "ai4"), listOf("16강", "2-0"), Comp.CUP), e("cup_round", listOf("ai3", "ai4"), listOf("8강", "1.5-0.5"), Comp.CHAMPIONS),
            e("cup_round", two, listOf("1·2부 승강전", "2-1"), Comp.PROMOTION_PLAYOFF), e("cup_round", listOf("WIN", "EV"), listOf("4강", "3-2"), Comp.FAMILY),
            e("cup_round", listOf("ai4", "ai2"), listOf("32강", "2-1"), Comp.CUP),
            e("eliminated", listOf("ai2"), listOf("32강"), Comp.CUP), e("eliminated", listOf("ai2"), listOf("조별 리그"), Comp.CHAMPIONS),
            e("qualified", listOf("ai1"), listOf("8강", "A조 1위"), Comp.CHAMPIONS), e("qualified", listOf("WIN"), listOf("4강", "B조 1위"), Comp.FAMILY),
            e("qualified", listOf("ai3"), listOf("8강"), Comp.CHAMPIONS),
            e("final", two, listOf("2-1"), Comp.CUP), e("final", two, listOf("2-0"), Comp.SUPER),
            e("final", listOf("TOP", "CHALLENGER"), listOf("3.5-1.5"), Comp.ALL_STAR), e("final", two, listOf("1-0"), Comp.TITLE_PLAYOFF),
            e("transfer", listOf("ai1", "c1", "c2"), listOf("35"), Comp.CLUB), e("transfer", listOf("ai3", "-", "c2"), listOf("12"), Comp.CLUB),
            e("transfer", listOf("ai3", "c2"), emptyList(), Comp.CLUB),
            e("draft", listOf("ai4", "c1"), listOf("3"), Comp.CLUB), e("draft", listOf("ai4", "c1"), emptyList(), Comp.CLUB),
            e("born", listOf("e3_1", "ai1", "ai4")), e("born", listOf("e3_2", "ai1")), e("born", listOf("e3_2")),
            e("retired", listOf("ai3"), listOf("7", "2")), e("retired", listOf("ai4"), listOf("3", "0")), e("retired", listOf("ai4")),
            e("retired", listOf("ai3"), listOf("12", "2", "34")), e("retired", listOf("ai4"), listOf("9", "0", "31")),
            e("retirements", listOf("ai1", "ai2", "ai3"), listOf("57")), e("rookies", listOf("ai1", "ai2", "ai3"), listOf("120")),
            e("debut", listOf("ai1"), listOf("BR", "19", "상파울루", "88")), e("debut", listOf("ai2"), listOf("KR", "18", "부산", "60")),
            e("debut", listOf("ai3"), listOf("JP", "20", "오사카", "70")),
            e("breakout", listOf("ai1"), listOf("21", "0", "55", "63")), e("decline", listOf("ai2"), listOf("35", "3", "70", "62")),
            e("learned_rule", listOf("ai1"), listOf("ss3")), e("league_reform", emptyList()),
            e("founders_farewell", listOf("ai1", "ai2", "ai3"), listOf("103")),
            e("wc_open", listOf("KR"), listOf("24"), Comp.WORLD_CUP), e("cc_open", emptyList(), listOf("아시아컵·유럽컵"), Comp.CONTINENTAL),
            e("draw_wc", listOf("KR", "BR", "NG", "SE"), listOf("A조", "16강", "KR"), Comp.WORLD_CUP),
            e("draw_cc", listOf("JP", "CN", "VN"), listOf("A조", "@아시아컵"), Comp.CONTINENTAL),
            e("nation_squad", listOf("ai1", "ai2"), listOf("KR", "요트 월드컵", "1"), Comp.WORLD_CUP),
            e("ko_set", listOf("KR", "JP", "BR", "AR"), listOf("4강"), Comp.WORLD_CUP),
            e("rivalry", listOf("KR", "JP"), listOf("한일전", "3-2", "A조 2차전"), Comp.WORLD_CUP),
            e("nation_awards", listOf("ai1", "ai2"), listOf("요트 월드컵", "5.5", "251.3"), Comp.WORLD_CUP),
            e("style_trend", listOf("BR"), listOf("hunter"), Comp.WORLD_CUP),
            e("award", listOf("ai1"), listOf("MVP")), e("award", listOf("ai4"), listOf("ROOKIE")), e("award", listOf("ai2"), listOf("MIP")),
            e("award", listOf("ai3"), listOf("YACHT")), e("award", listOf("ai1"), listOf("SCORER")),
            e("callup", listOf("ai1"), listOf("승률형"), Comp.FAMILY), e("dropped", listOf("ai2"), listOf("승률형"), Comp.FAMILY),
            e("title_race", two, listOf("1", "lead"), Comp.LEAGUE), e("title_race", two, listOf("0", "close"), Comp.LEAGUE),
            e("title_race", two, listOf("1", "close")),
            e("hall_of_fame", listOf("ai1")),
            e("dynasty", listOf("ai1"), listOf("L0", "3")), e("dynasty", listOf("ai1"), listOf("double")), e("dynasty", listOf("ai1"), listOf("treble")),
            e("dynasty", listOf("ai1"), listOf("unbeaten")), e("dynasty", listOf("ai1"), listOf("CUP", "2")),
            e("legend_match", two, listOf("2-0"), Comp.LEGEND), e("legend_match", two, listOf("0-2"), Comp.LEGEND), e("legend_match", two, listOf("1-1"), Comp.LEGEND),
            e("draw_cup", four, listOf("205", "256강", "51"), Comp.CUP), e("draw_cup", four, listOf("128", "128강", "0"), Comp.CUP),
            e("draw_cl", four, listOf("A조"), Comp.CHAMPIONS),
            e("draw_super", two, emptyList(), Comp.SUPER),
            e("draw_allstar", four, listOf("TOP"), Comp.ALL_STAR),
            e("draw_family", listOf("WIN", "EV", "HUMAN"), listOf("A조"), Comp.FAMILY),
            e("season_review", listOf("ai1", "ai2")), e("season_review", listOf("ai1")), e("season_review", emptyList()),
        )
    }

    private val sentenceEnd = Regex("[.?!](?=\\s|$)")

    @Test
    fun everyKindMakesAKoreanArticleWithoutPlaceholders() {
        val name = { k: String -> names[k] ?: k }
        val club = { k: String -> clubs[k] ?: k }
        // ai4 is the underdog: its cup wins are giant killings.
        val rating = { k: String -> if (k == "ai4") 1300.0 else 1500.0 }
        assertTrue(News.KINDS.all { kind -> samples(1).any { it.kind == kind } })
        val upsets = HashSet<String>()
        for (s in 1..8) {
            for (e in samples(s)) {
                val a = News.article(e, name, club, rating = rating)
                val text = a.headline + " " + a.body
                for (bad in listOf("{", "}", "[[", "]]", "null")) assertFalse(bad in text, "$e: $text")
                assertTrue(a.headline.isNotBlank() && a.body.any { it in '가'..'힣' }, "$e: $text")
                assertTrue(a.headline != News.PLAIN_HEADLINE, "$e fell back: $text")
                val sentences = sentenceEnd.findAll(a.body).count()
                assertTrue(sentences in 2..3 && a.body.trimEnd().last() in ".?!", "$e: ${a.body}")
                assertTrue(a.importance in 1..5)
                assertEquals(a, News.article(e, name, club, rating = rating), "deterministic")
                // The first key shows by its display name.
                val shown = e.keys.firstOrNull()?.let { k -> names[k] ?: clubs[k] ?: Family.entries.firstOrNull { it.name == k }?.label ?: if (k == "TOP") "1부 올스타" else null }
                if (shown != null) assertTrue(shown in text, "$e: $text")
                if (e.kind == "upset") upsets += a.headline
            }
        }
        assertTrue(upsets.size >= 2, "templates vary: $upsets")
        val giant = News.article(LeagueEvent("cup_round", 1, listOf("ai4", "ai2"), listOf("32강", "2-1"), Comp.CUP), name, club, rating = rating)
        assertTrue("200점" in giant.body && ("자이언트 킬링" in giant.headline || "이변" in giant.headline), giant.toString())
        // An unknown kind still reads as news.
        val other = News.article(LeagueEvent("something_new", 3, listOf("ai1")), name)
        assertEquals(News.PLAIN_HEADLINE, other.headline)
        assertTrue("김민준" in other.body)
    }

    @Test
    fun oneKnockoutTieMakesOneArticle() {
        val name = { k: String -> names[k] ?: k }
        // What the cup reports when a top seed falls to an AI rated 200 lower in a late round.
        val tie = listOf(
            LeagueEvent("cup_round", 2, listOf("ai4", "ai2"), listOf("8강", "2-1"), Comp.CUP),
            LeagueEvent("eliminated", 2, listOf("ai2"), listOf("8강"), Comp.CUP),
            LeagueEvent("upset", 2, listOf("ai4", "ai2"), listOf("200"), Comp.CUP),
        )
        val one = News.articles(tie, name).single()
        assertEquals("cup_round", one.kind)
        // A giant killing from the upset's gap, even without ratings.
        assertTrue("200점" in one.body && ("자이언트 킬링" in one.headline || "이변" in one.headline), one.toString())
        // Events of other ties, an upset without a round (a group match) and an early exit stay.
        val others = listOf(
            LeagueEvent("upset", 2, listOf("ai4", "ai1"), listOf("150"), Comp.CHAMPIONS),
            LeagueEvent("eliminated", 2, listOf("ai1"), listOf("32강"), Comp.CUP),
            LeagueEvent("upset", 2, listOf("ai3", "ai2"), listOf("120"), Comp.CUP),
        )
        assertEquals(listOf("cup_round", "upset", "eliminated", "upset"), News.articles(tie + others, name).map { it.kind })
        // A single article stays as it was.
        assertEquals(News.article(tie[2], name), News.articles(listOf(tie[2]), name).single())
    }

    @Test
    fun titlesNameTheirCompetitionInKorean() {
        val name = { k: String -> names[k] ?: k }
        fun title(arg: String, comp: Comp? = null) = News.article(LeagueEvent("title", 1, listOf("ai1"), listOf(arg), comp), name)
        assertTrue("요트컵" in title("CUP", Comp.CUP).headline + title("CUP", Comp.CUP).body)
        assertTrue("챔피언스 리그" in title("CHAMPIONS").let { it.headline + it.body })
        assertTrue("2부 리그" in title("L1", Comp.LEAGUE).let { it.headline + it.body })
        assertEquals(5, title("L0", Comp.LEAGUE).importance)
        assertEquals(5, title("0", Comp.TITLE_PLAYOFF).importance)
        // Nonsense never throws: it is shown as it is.
        assertTrue("99999999999" in title("99999999999").let { it.headline + it.body })
        // Counters for AIs read as people.
        val draws = (1..8).map { s -> News.article(LeagueEvent("draw_cup", s, listOf("ai1", "ai2"), listOf("205", "256강", "51"), Comp.CUP), name).body }
        assertTrue(draws.none { "개 AI" in it } && draws.any { "205명이 나서는" in it && "상위 시드 51명" in it }, draws.toString())
    }

    @Test
    fun aTieBreakSwapBetweenLevelLeadersIsNoNews() {
        assertNull(News.titleRace(5, listOf("a" to 30, "b" to 30), listOf("b" to 33, "a" to 33), 0.6))
        // Drawing level and going top on the tie-break is.
        assertEquals(listOf("0", "lead"), News.titleRace(5, listOf("a" to 31, "b" to 30), listOf("b" to 31, "a" to 31), 0.6)?.args)
    }

    @Test
    fun particlesFollowTheLastSound() {
        assertEquals("이", Josa.after("김민준", "가"))
        assertEquals("가", Josa.after("박하나", "이"))
        assertEquals("이", Josa.after("최별", "가"))
        assertEquals("이", Josa.after("승률 AI (앱 기본)", "이"))
        assertEquals("을", Josa.after("목표 180점", "를"))
        assertEquals("는", Josa.after("MVP", "은"))
        assertEquals("으로", Josa.after("2-0", "로"))
        assertEquals("로", Josa.after("1.5-0.5", "으로"))
        assertEquals("로", Josa.after("서울", "으로"))
        assertEquals("예요", Josa.after("12", "이에요"))
        assertEquals("이었어요", Josa.after("330", "였어요"))
        assertEquals("와", Josa.after("부산 갈매기", "과"))
    }

    @Test
    fun theNewsBookKeepsTheNewest200AndSurvivesACut() {
        val book = NewsBook()
        repeat(250) { i -> book.add(Article(1 + i / 100, "upset", "헤드라인 $i", "본문 $i, 쉼표와 100% 그리고 a_b.\n둘째 줄이에요.", listOf("ai$i", "-"), if (i % 2 == 0) Comp.CUP else null, 1 + i % 5)) }
        assertEquals(NewsBook.MAX, book.size)
        assertEquals("헤드라인 249", book.all.first().headline)
        assertEquals("헤드라인 50", book.all.last().headline)
        // The same article twice in a row is kept once.
        book.add(book.all.first())
        assertEquals("헤드라인 248", book.all[1].headline)
        val text = book.encode()
        assertEquals(NewsBook.HEADER, text.lines().first())
        val back = NewsBook.decode(text)
        assertEquals(book.all, back.all)
        assertEquals(listOf(book.all.first()), back.about("ai249"))
        // Cut short: the newest (last written) article is lost, the others are kept.
        val cut = NewsBook.decode(text.substring(0, text.length - 4))
        assertEquals(book.all.drop(1), cut.all)
        assertFailsWith<IllegalArgumentException> { NewsBook.decode("highlights 1\n") }
        // Articles made from events go in as they are.
        val made = News.articles(samples(2), { names[it] ?: it }, { clubs[it] ?: it })
        val fresh = NewsBook()
        fresh.add(made)
        assertTrue(fresh.size in made.size - 5..made.size, "${fresh.size} of ${made.size}")
        assertEquals(made.last(), fresh.all.first())
        assertEquals(fresh.all, NewsBook.decode(fresh.encode()).all)
    }

    @Test
    fun theTitleRaceIsCalledOnALeadChangeAndALateClose() {
        val lead = News.titleRace(5, listOf("a" to 30, "b" to 28), listOf("b" to 31, "a" to 30), 0.5)
        assertEquals(LeagueEvent("title_race", 5, listOf("b", "a"), listOf("1", "lead"), Comp.LEAGUE), lead)
        assertNull(News.titleRace(5, listOf("a" to 3, "b" to 0), listOf("b" to 4, "a" to 3), 0.1))
        val close = News.titleRace(5, listOf("a" to 30, "b" to 27, "c" to 20), listOf("a" to 30, "b" to 29, "c" to 23), 0.8)
        assertEquals(listOf("1", "close"), close?.args)
        assertNull(News.titleRace(5, listOf("a" to 30, "b" to 27), listOf("a" to 30, "b" to 29), 0.5))
        assertNull(News.titleRace(5, listOf("a" to 30, "b" to 27), listOf("a" to 33, "b" to 27), 0.9))
        // A small division played out: a and b are strong and draw each other.
        val league = Season.start(1, 7L, listOf(listOf("a", "b", "c", "d")))
        val watch = TitleRaceWatch()
        fun strength(k: String) = if (k == "a" || k == "b") 3 else 1
        val events = ArrayList<LeagueEvent>()
        while (true) {
            val f = league.next(emptySet()) ?: break
            val d = strength(f.a) - strength(f.b)
            league.record(f.id, listOf(if (d > 0) GameScore(250, 150) else if (d < 0) GameScore(150, 250) else GameScore(200, 200)))
            watch.check(league)?.let { events += it }
        }
        assertTrue(events.all { it.kind == "title_race" && it.keys.size == 2 })
        assertTrue(league.finished)
        assertTrue(Drama.titleRace(league, Fixture(Comp.LEAGUE, 0, "a", "c", 0)))
        assertFalse(Drama.titleRace(league, Fixture(Comp.LEAGUE, 0, "c", "d", 0)))
        assertFalse(Drama.titleRace(league, Fixture(Comp.CUP, 0, "a", "b", 0)))
    }

    // ---- live board ----------------------------------------------------------------------------

    @Test
    fun theLiveBoardReplaysSixFixturesTurnByTurn() {
        val board = LiveBoard()
        for (i in 0 until 45) {
            val f = Fixture(Comp.LEAGUE, i, "p$i", "q$i", i.toLong())
            val games = listOf(GameScore(200 + i, 150), GameScore(180, 190))
            // Game 1 has q first: its facts are q's.
            val facts = listOf(facts(200 + i, 150, blowout), facts(190, 180, blowout))
            board.add(JobResult(f, listOf(BoardResult(f.a, f.b, games, facts)), TieRule.match(games), 0), 1000L + i)
        }
        // A job without facts (a forfeit) is not shown.
        board.add(JobResult(Fixture(Comp.LEAGUE, 99, "x", "y", 0), listOf(BoardResult("x", "y", listOf(GameScore(1, 0)), emptyList())), 1, 0), 2000)
        assertEquals(LiveBoard.FIXTURES, board.fixtures.size)
        assertEquals(LiveBoard.CURVES, board.curves.size)
        val pair = board.curves("q44", "p44")
        assertEquals(listOf(0, 1), pair.map { it.game })
        assertEquals(100 - blowout[3], pair[1].curveA()[3])

        val start = 10_000L
        val step = LiveBoard.STEP_MS
        val first = board.window(start, start)
        assertEquals((44 downTo 39).toList(), first.map { it.fixture.id })
        assertTrue(first.all { f -> f.boards.single().let { it.halfTurn == 0 && it.totalA == 0 && it.pA == 50 } && !f.finished })
        // Ten half-turns in: game 0, where p moved first.
        val at10 = board.window(start + 10 * step, start)
        val b10 = at10[0].boards.single()
        assertEquals(0 to 10, b10.game to b10.halfTurn)
        assertEquals(Math.round(244 * 10 / 24.0).toInt(), b10.totalA)
        assertEquals(blowout[9], b10.pA)
        // Game 1: q moved first, so A's total is the second seat's; game 0 counts as won.
        val at30 = board.window(start + 30 * step, start)[0]
        val b30 = at30.boards.single()
        assertEquals(1 to 5, b30.game to b30.halfTurn)
        assertEquals(Math.round(180 * 5 / 24.0).toInt() to Math.round(190 * 5 / 24.0).toInt(), b30.totalA to b30.totalB)
        assertEquals(1.0 to 0.0, at30.scoreA to at30.scoreB)
        // The end: the final result stays, then the slots move on to the next newest.
        val end = board.window(start + 50 * step, start)[0]
        assertTrue(end.finished && end.boards.single().done)
        assertEquals(1, end.winner)
        assertEquals(1.0 to 1.0, end.scoreA to end.scoreB)
        assertEquals(44, board.window(start + 50 * step + LiveBoard.HOLD_MS - 1, start)[0].fixture.id)
        val next = board.window(start + 50 * step + LiveBoard.HOLD_MS, start)
        assertEquals((38 downTo 33).toList(), next.map { it.fixture.id })
        // A new viewing starts from the newest again.
        assertEquals(44, board.window(99_000, 99_000)[0].fixture.id)

        // A team match: boards count once they are over.
        val team = Fixture(Comp.CLUB, 200, "c1", "c2", 5L, JobKind.TEAM, lineupA = listOf("m1", "m2"), lineupB = listOf("n1", "n2"))
        val win = listOf(GameScore(250, 150), GameScore(150, 250))
        val winFacts = listOf(facts(250, 150, blowout), facts(250, 150, blowout))
        val aWins = listOf(GameScore(250, 150), GameScore(250, 150))
        val aWinsFacts = listOf(facts(250, 150, blowout), facts(150, 250, comeback()))
        board.add(JobResult(team, listOf(BoardResult("m1", "n1", aWins, aWinsFacts), BoardResult("m2", "n2", win, winFacts)), 1, 0), 3000)
        val s2 = 200_000L
        assertEquals(200, board.window(s2, s2)[0].fixture.id)
        val mid = board.window(s2 + 25 * step, s2).first { it.fixture.id == 200 }
        assertEquals(2, mid.boards.size)
        assertEquals(0.0 to 0.0, mid.scoreA to mid.scoreB)
        val done = board.window(s2 + 50 * step, s2).first { it.fixture.id == 200 }
        assertEquals(1.5 to 0.5, done.scoreA to done.scoreB)
    }
}
