package yacht.engine.league

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 국가대항전: world cups and continental cups of every size, saved midway, their news, and in real seasons. */
class NationCupTest {
    // ---- national cups -----------------------------------------------------------------------

    /** A league where the first [teams] nations (by table order) have 5-12 players and some others 1-4. */
    private fun start(season: Int, sizes: Map<String, Int>, history: List<Honour> = emptyList(), seed: Long = 7L): SeasonStart {
        val rng = Random(seed)
        val nation = HashMap<String, String>()
        val rating = HashMap<String, Double>()
        var i = 0
        for ((code, n) in sizes) repeat(n) {
            val k = "g" + (++i).toString().padStart(4, '0')
            nation[k] = code
            // Some nations are stronger.
            rating[k] = 1450 + 120 * rng.nextGaussian() + (code.hashCode() % 7) * 12
        }
        val ranking = rating.keys.sortedByDescending { rating.getValue(it) }
        val divisions = ranking.chunked(maxOf(5, (ranking.size + 3) / 4))
        return SeasonStart(
            season, seed * 31 + season, LeagueConfig(), ranking, { rating[it] ?: 1400.0 }, divisions, divisions, emptyList(),
            { null }, { "서울" }, history, { nation[it] },
        )
    }

    private fun play(f: Fixture, rng: Random, rating: (String) -> Double): JobResult {
        val boards = f.lineupA.zip(f.lineupB).map { (x, y) ->
            val edge = (rating(x) - rating(y)) / 20
            val games = List(2) { GameScore((230 + edge + 35 * rng.nextGaussian()).toInt(), (230 - edge + 35 * rng.nextGaussian()).toInt()) }
            BoardResult(x, y, games, emptyList())
        }
        return JobResult(f, boards, TieRule.team(boards), 0L)
    }

    /** Plays [c] to the end; with [roundTripAt], encodes and decodes it after that many fixtures. */
    private fun run(c0: NationCup, s: SeasonStart, roundTripAt: Int = -1, codec: CompetitionCodec? = null): Pair<NationCup, List<LeagueEvent>> {
        var c = c0
        val rng = Random(99)
        val events = ArrayList<LeagueEvent>(c.opening())
        var n = 0
        while (!c.finished) {
            val f = c.next(1.0, emptySet()) ?: error("stuck after $n fixtures")
            assertTrue(c.stageOf(f) >= 0, "stage of ${f.label}")
            events += c.record(play(f, rng, s.rating))
            n++
            if (n == roundTripAt && codec != null) {
                val sb = StringBuilder()
                c.encode(sb)
                c = codec.decode(sb.toString().trimEnd().lines(), c.season, s.seed) as NationCup
            }
        }
        assertEquals(c0.fixturesTotal, n)
        val counts = c.stageCounts()
        assertTrue(counts.all { it.done }, "all stages done: $counts")
        assertEquals(c.stages().size, counts.size)
        return c to events
    }

    @Test
    fun worldCupSizes() {
        val codes = Nations.ALL.map { it.code }
        for (n in 4..24) {
            val sizes = LinkedHashMap<String, Int>()
            for ((i, c) in codes.withIndex()) sizes[c] = if (i < n) 5 + (i * 7) % 8 else 1 + i % 4
            val s = start(3, sizes)
            val cup = NationCup.world(s)!!
            assertEquals(n, cup.teams.size)
            val (done, _) = run(cup, s)
            val groups = cup.groups.map { it.size }
            val sec = cup.sections[0]
            val h = done.honours()
            println(
                "N=$n groups=$groups ko=${sec.knockout} stages=${cup.stages().joinToString { it.label }} fixtures=${cup.fixturesTotal} " +
                    "host=${cup.host} absent=${cup.absent.size} champion=${h.first { it.kind == "WC" }.key} " +
                    "confedMaxPerGroup=${cup.groups.maxOf { g -> g.groupingBy { Nations.of(it.key)!!.confed }.eachCount().values.max() }}",
            )
            assertEquals(1, h.count { it.kind == "WC" })
            assertEquals(5, h.count { it.kind == "WCW" })
            assertEquals(n * 5, h.count { it.kind == "NSQ" })
            assertEquals(if (sec.knockout >= 4) 2 else 0, h.count { it.kind == "WC4" })
        }
    }

    @Test
    fun knockoutKeepsGroupMatesApart() {
        val codes = Nations.ALL.map { it.code }
        var clashes = 0
        var ties = 0
        for (seed in 1L..40L) {
            val sizes = LinkedHashMap<String, Int>()
            for (c in codes) sizes[c] = 8
            val s = start(3, sizes, seed = seed)
            val (done, _) = run(NationCup.world(s)!!, s)
            val v = done.view() as CompetitionView.Groups
            val groupOf = HashMap<String, Int>()
            for ((g, members) in done.groups.withIndex()) for (e in members) groupOf[e.key] = g
            for (t in v.knockout!!.rounds.first().ties) {
                ties++
                if (groupOf[t.a] == groupOf[t.b]) clashes++
            }
        }
        println("first-round ties between group mates: $clashes of $ties")
    }

    @Test
    fun roundTrip() {
        val sizes = LinkedHashMap<String, Int>()
        for (c in Nations.ALL.map { it.code }) sizes[c] = 9
        val s = start(7, sizes)
        val (a, ea) = run(NationCup.world(s)!!, s)
        for (at in listOf(5, 36, 40, 47)) {
            val (b, _) = run(NationCup.world(s)!!, s, at, NationCup.worldCodec)
            assertEquals(a.honours(), b.honours(), "round trip at $at")
            assertEquals(a.lines.values.map { it.encode() }, b.lines.values.map { it.encode() })
        }
        // Continental round trip.
        val s5 = start(5, sizes)
        val (c, _) = run(NationCup.continental(s5)!!, s5)
        val (d, _) = run(NationCup.continental(s5)!!, s5, 12, NationCup.continentalCodec)
        assertEquals(c.honours(), d.honours())
        println("round trips ok; world cup events ${ea.groupingBy { it.kind }.eachCount()}")
    }

    @Test
    fun continentalSizes() {
        val byConfed = Nations.ALL.groupBy { it.confed }
        for (pattern in listOf(listOf(6, 6, 6, 6), listOf(6, 5, 4, 3), listOf(6, 2, 4, 1), listOf(3, 3, 0, 0))) {
            val sizes = LinkedHashMap<String, Int>()
            for ((ci, c) in Confed.entries.withIndex()) for ((i, nat) in byConfed.getValue(c).withIndex()) sizes[nat.code] = if (i < pattern[ci]) 7 else 2
            val s = start(5, sizes)
            val cup = NationCup.continental(s) ?: run { println("pattern $pattern: no continental cup"); return@run null } ?: continue
            val (done, events) = run(cup, s)
            val h = done.honours()
            println(
                "pattern $pattern: sections=${cup.sections.map { "${it.title}(${it.groupCount}조, ko ${it.knockout})" }} stages=${cup.stages().joinToString { it.label }} " +
                    "fixtures=${cup.fixturesTotal} CC=${h.filter { it.kind == "CC" }.map { it.key }} absent=${cup.absent}",
            )
            assertEquals(cup.sections.size, h.count { it.kind == "CC" })
            if (pattern == listOf(6, 5, 4, 3)) {
                val articles = News.articles(events, { it }, team = Competitions::teamLabel)
                for (a in articles.filter { it.kind in setOf("final", "title", "cup_round", "draw_cc", "cc_open", "ko_set") }.take(10)) println("   [${a.kind}] ${a.headline} — ${a.body}")
            }
        }
    }

    @Test
    fun calendar() {
        val sizes = LinkedHashMap<String, Int>()
        for (c in Nations.ALL.map { it.code }) sizes[c] = 17
        val history = listOf(Honour(2, "L0", "g0001"), Honour(2, "CUP", "g0002"))
        for ((season, weeks) in listOf(3 to 49, 3 to 19, 5 to 49, 3 to 99)) {
            val s0 = start(season, sizes, history)
            val s = SeasonStart(s0.number, s0.seed, s0.config, s0.ranking, s0.rating, s0.divisions, s0.lastTables, history, s0.family, s0.hometown, history, s0.nation)
            val comps = Competitions.factories().mapNotNull { it(s) }
            val cal = SeasonCalendar.build(weeks, comps, emptyList())
            println("season $season, $weeks weeks, ${s.ranking.size} players:")
            for (e in cal.entries) println("  ${e.comp.label}: " + e.stages.indices.joinToString(" ") { "${e.stages[it].label}@${e.week(it)}" })
        }
    }

    @Test
    fun news() {
        val sizes = LinkedHashMap<String, Int>()
        for ((i, c) in Nations.ALL.map { it.code }.withIndex()) sizes[c] = if (i == 22) 3 else 9
        // An earlier world cup: squads and winners, so the squads change and a first title can show.
        val s3 = start(3, sizes)
        val (first, _) = run(NationCup.world(s3)!!, s3)
        val history = first.honours()
        val s = start(7, sizes, history, seed = 8L)
        val (done, events) = run(NationCup.world(s)!!, s)
        val names = HashMap<String, String>()
        val book = PlayerBook()
        book.ensureGenerated(300, 1)
        val pool = book.alive.map { it.name }
        fun name(k: String) = names.getOrPut(k) { pool[names.size % pool.size] }
        val articles = News.articles(events, ::name, team = Competitions::teamLabel)
        println("articles by kind: " + articles.groupingBy { it.kind }.eachCount())
        for (kind in listOf("wc_open", "draw_wc", "nation_squad", "upset", "rivalry", "ko_set", "eliminated", "cup_round", "final", "title", "nation_awards")) {
            for (a in articles.filter { it.kind == kind }.take(2)) println("  [${a.kind} ${a.importance}] ${a.headline} — ${a.body}")
        }
        assertTrue(articles.none { it.headline == News.PLAIN_HEADLINE }, "every article has a template")
        assertTrue(articles.none { it.body.contains('{') || it.headline.contains('{') }, "no slot left")
        val v = done.view() as CompetitionView.Groups
        println("notes: host=${v.notes?.host} absent=${v.notes?.absent} mvp race=${v.notes?.mvpRace?.take(3)} captains=${v.notes?.captains?.size}")
        println("honours: " + done.honours().filter { it.kind != "NSQ" && it.kind != "WCW" })
    }

    @Test
    fun nationNewsReadAsKorean() {
        val names = mapOf("g1" to "사토 하루토", "g2" to "김서연", "g3" to "제임스 밀러", "g4" to "응우옌 반안")
        val n = { k: String -> names[k] ?: k }
        val evs = listOf(
            LeagueEvent("rivalry", 7, listOf("KR", "JP"), listOf("한일전", "3-2", "A조 2차전"), Comp.WORLD_CUP),
            LeagueEvent("rivalry", 7, listOf("JP", "KR"), listOf("한일전", "4-1", "8강"), Comp.WORLD_CUP),
            LeagueEvent("rivalry", 9, listOf("BR", "AR"), listOf("남미 라이벌전", "3-2", "결승", "@아메리카컵"), Comp.CONTINENTAL),
            LeagueEvent("upset", 9, listOf("VN", "JP"), listOf("130", "@아시아컵"), Comp.CONTINENTAL),
            LeagueEvent("eliminated", 7, listOf("DE"), listOf("조별 리그"), Comp.WORLD_CUP),
            LeagueEvent("eliminated", 9, listOf("DE"), listOf("조별 리그", "@유럽컵"), Comp.CONTINENTAL),
            LeagueEvent("cup_round", 9, listOf("MA", "EG"), listOf("4강", "3.5-1.5", "@아프리카·중동컵"), Comp.CONTINENTAL),
            LeagueEvent("final", 9, listOf("MA", "SA"), listOf("3-2", "@아프리카·중동컵"), Comp.CONTINENTAL),
            LeagueEvent("title", 9, listOf("MA"), listOf("아프리카·중동컵"), Comp.CONTINENTAL),
            LeagueEvent("title", 11, listOf("KR"), listOf("요트 월드컵", "first"), Comp.WORLD_CUP),
            LeagueEvent("draw_wc", 11, listOf("KR", "BR", "NG", "SE"), listOf("A조", "16강", "KR"), Comp.WORLD_CUP),
            LeagueEvent("draw_wc", 11, listOf("FR", "US", "EG"), listOf("B조", "16강"), Comp.WORLD_CUP),
            LeagueEvent("nation_squad", 11, listOf("g1"), listOf("JP", "요트 월드컵", "1"), Comp.WORLD_CUP),
            LeagueEvent("nation_squad", 11, listOf("g2"), listOf("KR", "요트 월드컵", "0"), Comp.WORLD_CUP),
            LeagueEvent("nation_squad", 13, listOf("g4", "g3"), listOf("VN", "아시아컵", "1"), Comp.CONTINENTAL),
            LeagueEvent("dynasty", 15, listOf("KR"), listOf("RUN", "2", "WC")),
            LeagueEvent("cc_open", 13, emptyList(), listOf("아시아컵·유럽컵"), Comp.CONTINENTAL),
        )
        for (seed in 0..1) {
            for (e in evs) {
                val e2 = if (seed == 0) e else e.copy(season = e.season + 4)
                val a = News.article(e2, n, team = Competitions::teamLabel)
                println("[${a.kind} ${a.importance}] ${a.headline} — ${a.body}")
                assertTrue(!a.body.contains('{') && a.headline != News.PLAIN_HEADLINE, a.toString())
            }
        }
    }

    private fun worldStrength(k: String): Int = 40 + (k.hashCode() and 63)

    private fun worldGames(a: String, b: String, n: Int): List<GameScore> =
        List(n) { i -> if ((worldStrength(a) + i * 7) % 97 >= (worldStrength(b) + i * 3) % 97) GameScore(240, 200, aYacht = true) else GameScore(200, 240) }

    private fun worldPlay(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> worldGames(f.a, f.b, 2).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> worldGames(f.a, f.b, 2 * f.legs).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, worldGames(x, y, 2), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    @Test
    fun fiveGeneratedSeasonsWithNationCups() {
        val config = LeagueConfig(divisions = 4, generated = 240, aging = true, clubs = 16, squad = 6)
        var w = LeagueWorld(Ladder(listOf("ev", Ladder.ME)), config)
        w.players.ensureGenerated(config.generated)
        w.members = w.players.alive.map { it.key }
        w.ladder = Ladder(w.members + "ev" + Ladder.ME)
        w.family = { k -> w.players[k]?.spec?.let(::familyOf) }
        w.openFirstSeason(5L)
        val t0 = System.nanoTime()
        repeat(5) {
            val n = w.plan!!.number
            val comps = w.plan!!.comps.map { it.comp }
            var played = 0
            var saved = false
            while (!w.seasonOver) {
                val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("season $n stuck")
                w.recordJob(worldPlay(f))
                played++
                // Save and read back in the middle of the national cup.
                val cup = w.plan!!.competition(Comp.WORLD_CUP) ?: w.plan!!.competition(Comp.CONTINENTAL)
                if (!saved && cup != null && cup.fixturesLeft in 1 until cup.fixturesTotal / 2) {
                    saved = true
                    val files = w.encodeFiles()
                    val back = LeagueWorld.decode(files, w.ladder.keys.toList(), emptyList())
                    back.members = w.members
                    back.family = w.family
                    back.config = config
                    assertEquals(files[LeagueWorld.SEASON], back.encodeFiles()[LeagueWorld.SEASON])
                    w = back
                }
            }
            val cal = w.plan!!.calendar
            val wc = listOf(Comp.WORLD_CUP, Comp.CONTINENTAL).mapNotNull { c -> cal.entry(c)?.let { e -> "${c.label} " + e.stages.indices.joinToString(" ") { "${e.stages[it].label}@${e.week(it)}" } } }
            w.ladder.fit()
            val closing = w.closeSeason()
            w.members = w.players.alive.map { it.key }
            w.ladder = Ladder.decode(w.members + "ev" + Ladder.ME, w.ladder.encode())
            w.openSeason()
            val nat = closing.honours.filter { it.kind in setOf("WC", "WC2", "WC4", "WCMVP", "WCTOP", "WCH", "CC", "CC2") }
            println("season $n: ${played} fixtures, comps=$comps, ${wc.joinToString()}; nation honours=${nat.map { "${it.kind}:${it.key}" }}; NSQ=${closing.honours.count { it.kind == "NSQ" }} WCW=${closing.honours.count { it.kind == "WCW" }}")
            if (n == 3) assertTrue(nat.any { it.kind == "WC" } && closing.events.any { it.kind == "style_trend" })
            if (n == 5) assertTrue(nat.any { it.kind == "CC" })
            assertTrue(Comp.FAMILY !in comps)
        }
        println("legends ${w.players.legends.size}, retired ${w.players.retired.size}; ${(System.nanoTime() - t0) / 1_000_000} ms")
        // The records: the world cup of season 3 and the continental cups of season 5.
        val book = w.nations
        assertEquals(listOf(3 to true, 5 to false), book.editions.map { it.season to it.world })
        assertTrue(book.nations.any { it.worldTitles == 1 } && book.nations.sumOf { it.continentalTitles } == book.editions[1].podiums.size)
        assertTrue(book.caps.isNotEmpty() && book.caps.all { it.caps in 1..2 })
        assertEquals(book.encode(), NationBook.decode(book.encode()).encode())
        val champion = book.editions[0].podiums[0].winner!!
        assertTrue(book.ranking().first().code == champion || book.ranking().first().worldTitles == 1)
        // The world champion's style came into fashion, and fades.
        assertTrue(w.players.meta.isNotEmpty(), "${w.players.meta}")
        println("nations alive: " + w.players.alive.groupingBy { it.identity?.nation }.eachCount().entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}${it.value}" })
        val nationNews = w.news.all.filter { it.comp == Comp.WORLD_CUP || it.comp == Comp.CONTINENTAL || it.kind == "dynasty" }
        println("nation news kept: ${nationNews.size}; sample: " + nationNews.take(4).joinToString(" | ") { it.headline })
        val milestone = w.news.all.filter { it.kind == "milestone" }.take(2).joinToString(" | ") { it.headline }
        println("milestones: $milestone")
    }
}
