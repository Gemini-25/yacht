package yacht.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import yacht.engine.league.Abilities
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.Evolution
import yacht.engine.league.Family
import yacht.engine.league.Gender
import yacht.engine.league.Hometowns
import yacht.engine.league.Honour
import yacht.engine.league.Identity
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.Nations
import yacht.engine.league.Origin
import yacht.engine.league.PlayGene
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerNames
import yacht.engine.league.PlayerRecord
import yacht.engine.league.Profiles
import yacht.engine.league.Schools
import yacht.engine.league.Temper
import yacht.engine.league.Tempers
import yacht.engine.league.Tendencies
import yacht.engine.league.Traits
import yacht.engine.league.familyOf
import yacht.engine.league.leagueAi
import yacht.engine.league.traits

class PlayersTest {
    private val rules = RuleSet.NINTENDO

    /** A synthetic rating per key (no games needed). */
    private fun ratingOf(key: String) = 1300.0 + Random(key.hashCode().toLong()).nextDouble() * 400

    /** The genes of generated player [i] (a founding player of a new league). */
    private fun gen(i: Int) = PlayerGenerator.recruit(i, 1, true, emptySet()).spec

    @Test
    fun hometownsAreTheAppsCities() {
        assertEquals(listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "수원", "춘천", "청주", "전주", "포항", "창원", "제주", "강릉"), Hometowns.ALL)
        for (k in listOf("ev", "win", "slip3", "g001", "rule-no-bonus")) assertEquals(Hometowns.ALL[Math.floorMod(k.hashCode(), 16)], Hometowns.of(k))
        assertEquals(Origin.GENERATED, Origin.of("g017"))
        assertEquals(Origin.GENERATED, Origin.of("g1043"))
        assertEquals(Origin.EVOLVED, Origin.of("e12_3"))
        assertEquals(Origin.MINE, Origin.of("custom-a1"))
        assertEquals(Origin.MINE, Origin.of(CustomAis.CLONE))
        assertEquals(Origin.BASE, Origin.of("ev"))
        assertEquals(Origin.BASE, Origin.of("greedy"))
        assertEquals(1043, PlayerGenerator.index("g1043"))
        assertNull(PlayerGenerator.index("g01"))
    }

    @Test
    fun aLeagueGrownByTheRulesGetsPlayersOfEveryAge() {
        val book = PlayerBook().apply { ensureGenerated(200) }
        val r = Evolution.run(book, 1, ::ratingOf, { 0 }, 5L, 1000)
        assertEquals(1000, book.size)
        val newcomers = r.born.mapNotNull { book[it] }
        assertTrue(newcomers.size >= 800 - 1)
        // Not 800 teenage rookies: a founding class of every age, and only the retirees'
        // places go to rookies and pupils.
        val ages = newcomers.map { it.age(2) }
        assertTrue(ages.count { it >= 26 } * 4 >= ages.size, ages.groupingBy { it }.eachCount().toSortedMap().toString())
        assertTrue(newcomers.count { it.origin == Origin.EVOLVED } <= r.retired.size)
    }

    @Test
    fun generatedPlayersAreDeterministicAndAPrefixOfMore() {
        val small = PlayerBook().apply { ensureGenerated(30) }
        val big = PlayerBook().apply { ensureGenerated(40) }
        assertEquals(30, small.size)
        assertEquals(small.alive, big.alive.take(30))
        assertEquals(small.alive, PlayerBook().apply { ensureGenerated(30) }.alive)
        assertEquals((1..30).map { "g" + it.toString().padStart(3, '0') }, small.alive.map { it.key })
        for (p in big.alive) {
            val id = assertNotNull(p.identity)
            assertEquals(p.key, p.spec.id)
            assertEquals(p.name, p.spec.name)
            assertEquals(Origin.GENERATED, p.origin)
            assertNotNull(p.spec.play)
            // Its genes are its abilities' (applying them again changes nothing).
            assertEquals(p.spec, Profiles.apply(p.spec, id))
            assertTrue(p.hometown in Nations.of(id.nation)!!.cities.names, "${id.nation} ${p.hometown}")
        }
        assertEquals(40, big.alive.map { it.name }.toSet().size)
        println(big.alive.take(12).joinToString("\n") { "${it.key} ${it.name} ${it.hometown} ${it.identity!!.nation} ${it.age(1)}세 ${familyOf(it.spec).label} · ${it.spec.describe()}" })
        // Adding more later adds only the new ones (the next keys); asking again adds nothing.
        assertEquals(10, small.ensureGenerated(40, season = 3).size)
        assertEquals(big.alive.take(30), small.alive.take(30))
        assertEquals(3, small["g031"]!!.born)
        assertTrue(small.ensureGenerated(40).isEmpty())
        // Base AIs keep their names: a reserved name is never given out.
        val reserved = big.alive.take(5).map { it.name }
        val other = PlayerBook(reserved).apply { ensureGenerated(10) }
        assertTrue(other.alive.none { it.name in reserved })
        assertEquals(big.alive.take(10).map { it.identity }, other.alive.map { it.identity })
    }

    @Test
    fun identitiesAndStylesFollowTheirShares() {
        val ps = PlayerBook().apply { ensureGenerated(3000) }.alive
        val n = ps.size.toDouble()
        val byNation = ps.groupingBy { it.identity!!.nation }.eachCount()
        for (nation in Nations.ALL) {
            val want = nation.weight.toDouble() / Nations.TOTAL
            assertEquals(want, (byNation[nation.code] ?: 0) / n, 0.02 + want * 0.25, nation.code)
        }
        assertEquals(0.5, ps.count { it.identity!!.gender == Gender.FEMALE } / n, 0.04)
        val ages = ps.map { it.identity!!.debutAge }
        assertTrue(ages.all { it in 17..37 }, "${ages.minOrNull()} ${ages.maxOrNull()}")
        assertEquals(26.0, ages.average(), 2.0)
        for (p in ps) {
            val id = p.identity!!
            assertTrue(id.abilities.toList().all { it in 1..99 })
            assertTrue(id.potential in 25..97)
            assertTrue(id.temperKind != Temper.NONE)
            // A temper only below 멘탈 50; no mistakes only from 판단력 92.
            assertEquals(id.abilities.mental < Profiles.CALM, p.spec.temper != Temper.NONE, "${id.abilities}")
            assertEquals(id.abilities.judgement >= Profiles.PERFECT, p.spec.mistake == CustomMistake.NONE, "${id.abilities}")
            if (p.spec.mistake != CustomMistake.NONE) assertEquals(if (id.feel) CustomMistake.FEEL else CustomMistake.SLIP, p.spec.mistake)
            val g = p.spec.play!!
            assertNotNull(PlayStyles.recipe(g.primary), g.primary)
            g.secondary?.let { assertTrue(PlayStyles.recipe(it)!!.scales && it != g.primary) }
            g.overlay?.let { assertNotNull(PlayStyles.overlay(it)) }
            assertEquals(g, PlayGene.decode(g.encode()))
            // The most promising never take up a misread rule or a beginner's rule-bound style.
            if (PlayerGenerator.Tier.of(id.potential) == PlayerGenerator.Tier.TOP) {
                assertTrue(PlayerGenerator.avgAt(PlayStyles.recipe(g.primary)!!, g.k) >= 179.0, "${g.label}")
            }
        }
        val feel = ps.count { it.identity!!.feel } / n
        assertEquals(PlayerGenerator.FEEL_SHARE, feel, 0.04)
        assertEquals(0.2, ps.count { it.spec.play!!.secondary != null } / n, 0.03)
        assertEquals(0.25, ps.count { it.spec.play!!.overlay != null } / n, 0.05)
        assertTrue(ps.map { it.spec.play!!.primary }.toSet().size == PlayStyles.RECIPES.size)
        // A school tilts its nation: Americans hunt Yachts and gamble more, Germans are sharper.
        fun share(code: String, f: (yacht.engine.league.PlayerRecord) -> Boolean) = ps.filter { it.identity!!.nation == code }.let { xs -> xs.count(f).toDouble() / xs.size }
        val hunter = { p: yacht.engine.league.PlayerRecord -> p.spec.play!!.primary == "hunter" }
        assertTrue(share("US", hunter) > 1.5 * share("KR", hunter), "${share("US", hunter)} ${share("KR", hunter)}")
        assertTrue(Schools.of("US").favoured.contains("hunter"))
        // The same players drawn with the German school's points are that much sharper.
        val de = Schools.of("DE").bias
        val shift = (1..2000).map { i ->
            val with = Profiles.drawAbilities(Random(i.toLong()), "g$i", 25, 64, de).judgement
            val without = Profiles.drawAbilities(Random(i.toLong()), "g$i", 25, 64).judgement
            with - without
        }.average()
        assertEquals(de[0].toDouble(), shift, 0.3)
        for (nation in Nations.ALL) {
            assertTrue(Schools.of(nation.code).motto.isNotEmpty(), nation.code)
            for (k in Schools.of(nation.code).favoured) assertNotNull(PlayStyles.recipe(k), "${nation.code} $k")
        }
    }

    @Test
    fun abilitiesMapToPlayAndBack() {
        // 판단력: perfect from 92; the lower, the more points mistakes cost; the inverse gives it back.
        assertEquals(0.0, Profiles.drop(92))
        assertEquals(0.0, Profiles.drop(99))
        for (j in 1 until 92) {
            assertTrue(Profiles.drop(j) > Profiles.drop(j + 1) || j + 1 == 92 && Profiles.drop(j) > 0, "$j")
            assertEquals(j.toDouble(), Profiles.judgementOf(Profiles.drop(j)).toDouble(), 1.0)
        }
        // Feel and slip amounts cost the same points as the judgement says.
        for (d in listOf(2.0, 8.0, 20.0, 45.0)) {
            assertEquals(d, Profiles.dropOfTau(Profiles.tauFor(d)), 0.2)
            assertEquals(d, Profiles.dropOfSlip(Profiles.slipFor(d)), 0.2)
        }
        // 예측력: full sight at 99, short-sighted at 1; read back from the gene.
        assertEquals(1.0, Profiles.future(99))
        assertTrue(Profiles.future(1) <= 0.45)
        for (f in listOf(10, 40, 70, 95)) assertEquals(f.toDouble(), Profiles.foresightOf(Profiles.future(f)).toDouble(), 8.0)
        // 승부 감각 from 25; 멘탈 calm from 50, the lower the stronger the temper.
        assertEquals(0.0, Profiles.sense(25))
        assertEquals(1.0, Profiles.sense(99))
        assertEquals(0.0, Profiles.temperStrength(50))
        assertTrue(Profiles.temperStrength(10) > Profiles.temperStrength(40))
        val id = Identity("KR", Gender.MALE, 20, Abilities(70, 60, 80, 30), 80, Temper.TILT, true)
        val s = Profiles.apply(CustomAiSpec("g1", "a", play = PlayGene("hunter")), id)
        assertEquals(CustomMistake.FEEL, s.mistake)
        assertEquals(Temper.TILT, s.temper)
        assertEquals(Profiles.temperStrength(30), s.temperStrength)
        assertEquals(Profiles.sense(80), s.play!!.sense)
        assertEquals("hunter", s.play!!.primary)
        val calm = Profiles.apply(s, id.copy(abilities = Abilities(95, 99, 10, 70)))
        assertEquals(CustomMistake.NONE, calm.mistake)
        assertEquals(Temper.NONE, calm.temper)
        assertEquals(0.0, calm.play!!.sense)
        // Tendencies: the plain style is neutral, a Yacht hunter loves Yachts, a timid roller stops early.
        assertEquals(Tendencies(0, 0, 0, 0, 0), Tendencies.of(PlayGene("ev").style()))
        assertTrue(Tendencies.of(PlayGene("hunter").style()).yacht > 20)
        assertTrue(Tendencies.of(PlayGene("timid").style()).persist < -10)
        assertTrue(Tendencies.of(PlayGene("purist").style()).side > 20)
        assertTrue(Tendencies.of(PlayGene("noscratch").style()).zero > 20)
        // Saved as tokens, read back the same.
        val full = id.copy(peakAbl = Abilities(71, 61, 81, 31), lastAbl = Abilities(69, 59, 79, 29))
        assertEquals(full, Identity.read(Identity.fields(full.tokens().split(' '), 0)))
        assertNull(Identity.read(emptyMap()))
    }

    @Test
    fun agingKeepsTheLeagueAliveAndReplaysTheSame() {
        fun book() = PlayerBook().apply { ensureGenerated(300) }
        val a = book()
        val b = book()
        val ratings = HashMap<String, Double>()
        fun rating(k: String) = ratings.getOrPut(k) { ratingOf(k) }
        val lucky = a.alive.maxBy { it.age(1) }.key
        val titles = { k: String -> if (k == lucky) 1 else 0 }
        val honours = { k: String -> if (k == lucky) listOf(Honour(1, "CUP", k), Honour(1, "DOWN3", k)) else emptyList() }
        // The oldest is 37 at most: make it 40 so it must retire.
        val old = a[lucky]!!
        a.update(old.copy(identity = old.identity!!.copy(debutAge = 40)))
        b.update(b[lucky]!!.copy(identity = b[lucky]!!.identity!!.copy(debutAge = 40)))
        val top = a.alive.sortedByDescending { rating(it.key) }.take(150).map { it.key }.toSet()
        val r = Evolution.run(a, 1, ::rating, titles, 99L, 300, honours)
        val r2 = Evolution.run(b, 1, ::rating, titles, 99L, 300, honours)
        assertEquals(r.retired, r2.retired)
        assertEquals(r.born, r2.born)
        assertEquals(r.events, r2.events)
        assertEquals(a.encode(), b.encode())
        assertTrue(lucky in r.retired)
        assertEquals(listOf(lucky), a.legends.map { it.key })
        assertEquals("CUP:1", a.legend(lucky)!!.honours)
        assertEquals(40, a.legend(lucky)!!.record.retiredAge)
        assertEquals(300, a.size)
        assertTrue(r.retired.size in 5..40, "${r.retired.size}")
        val pupils = r.born.filter { it.startsWith("e1_") }
        val rookies = r.born.filter { Origin.of(it) == Origin.GENERATED }
        assertEquals(r.born.size, pupils.size + rookies.size)
        assertEquals(Math.round(r.retired.size * Evolution.CHILD_SHARE).toInt(), pupils.size)
        for (k in pupils) {
            val c = a[k]!!
            assertTrue(c.parentA in top && c.parentB in top, "$c")
            assertEquals(2, c.born)
            assertEquals(Origin.EVOLVED, c.origin)
            assertTrue(c.age(2) in 17..27)
            assertEquals(c.spec, Profiles.apply(c.spec, c.identity!!))
        }
        for (k in rookies) assertTrue(a[k]!!.age(2) in 17..27)
        // Everyone else is a year older: their abilities moved and the arrows know from where.
        val stayed = a.alive.filter { it.born == 1 }
        assertTrue(stayed.all { it.identity!!.lastAbl != null })
        assertTrue(stayed.count { it.identity!!.abilities != it.identity!!.lastAbl } > stayed.size / 2)
        assertTrue(stayed.all { it.spec == Profiles.apply(it.spec, it.identity!!) })
        // News: one legend article, one article for the rest, the rookies and pupils.
        val kinds = r.events.groupingBy { it.kind }.eachCount()
        assertEquals(1, kinds["retired"])
        assertEquals(1, kinds["retirements"])
        assertEquals(1, kinds["rookies"])
        assertEquals(pupils.size, kinds["born"])
        assertEquals(Evolution.NEWS_ROOKIES, kinds["debut"])
        assertEquals(listOf("1", "1", "40"), r.events.first { it.kind == "retired" }.args)
        // A repeated rollover changes nothing and tells the same story (but the rule learning).
        val again = Evolution.run(a, 1, ::rating, titles, 99L, 300, honours)
        assertEquals(r.retired.toSet(), again.retired.toSet())
        assertEquals(r.born, again.born)
        assertEquals(r.events.filter { it.kind != "learned_rule" }, again.events)
        assertEquals(b.encode(), a.encode())
        // Seasons go on and the population stays; ages move on; the save reads back.
        for (season in 2..8) {
            val res = Evolution.run(a, season, ::rating, { 0 }, 99L, 300)
            assertTrue(res.retired.none { a[it] != null })
            assertEquals(300, a.size)
        }
        val back = PlayerBook.decode(a.encode())
        assertEquals(a.encode(), back.encode())
        assertEquals(a.alive.map { it.identity }, back.alive.map { it.identity })
        assertEquals(a.alive.map { it.spec }, back.alive.map { it.spec })
        // A lowered setting shrinks the book only by retirements; aging off changes nobody.
        val before = a.size
        val shrink = Evolution.run(a, 9, ::rating, { 0 }, 99L, 100)
        assertTrue(shrink.born.isEmpty())
        assertEquals(before - shrink.retired.size, a.size)
        val frozen = a.alive.map { it.identity!!.abilities }
        val ages = a.alive.map { it.age(10) }
        val off = Evolution.run(a, 10, ::rating, { 0 }, 99L, 0, aging = false)
        assertTrue(off.retired.isEmpty() && off.born.isEmpty())
        assertEquals(frozen, a.alive.map { it.identity!!.abilities })
        // Nobody grows older either.
        assertEquals(ages, a.alive.map { it.age(11) })
        // Only flawed players (the chaos preset): every newcomer makes mistakes.
        val chaos = PlayerBook().apply { ensureGenerated(150, accept = { it.flawed }) }
        assertTrue(chaos.alive.all { it.spec.flawed })
        for (season in 1..3) {
            val res = Evolution.run(chaos, season, ::rating, { 0 }, 9L, 150, accept = { it.flawed })
            assertTrue(res.born.all { chaos[it]!!.spec.flawed })
            assertEquals(150, chaos.size)
        }
        val empty = Evolution.run(PlayerBook(), 1, ::rating, { 0 }, 1L, 0)
        assertTrue(empty.retired.isEmpty() && empty.born.isEmpty())
    }

    @Test
    fun aRosterFromBeforeIdentitiesReadsAndReforms() {
        // Lines of the version before nations: Korean names, genes, no tokens.
        val text = "roster 1\nevolved 4\ngone 7\n" +
            "ai g001 이건철 서울 GENERATED 1 - - EV 0.03 SLIP 0.006 0.0 0.0 0.0 0.0 true 1.0 MOODY - 1587.3\n" +
            "ai g002 김하린 부산 GENERATED 1 - - RISK 0.06 FEEL 0.5 0.0 0.0 0.0 0.0 false 1.0 NONE - -\n" +
            "ai e3_1 박서연 대구 EVOLVED 4 g001 g002 EV 0.03 NONE 0.0 40.0 0.0 0.0 0.0 false 1.0 NONE no-bonus -\n" +
            "retired g007 최민준 1 3 - - 1702.3 2 대구 GENERATED\n"
        val b = PlayerBook.decode(text)
        assertEquals(3, b.size)
        for (p in b.alive) {
            val id = assertNotNull(p.identity)
            assertEquals("KR", id.nation)
            assertTrue(p.age(5) in 17..38, "${p.age(5)}")
            assertTrue(p.age(5) >= 17 + (5 - p.born))
            assertNull(p.spec.play)
        }
        assertEquals(Gender.MALE, b["g001"]!!.identity!!.gender)
        assertEquals(Gender.FEMALE, b["g002"]!!.identity!!.gender)
        assertEquals("KR", b.retired.single().nation)
        // New keys come after every key ever used.
        assertEquals(8, b.next)
        // Saved and read again: the same, with identities now written.
        val again = PlayerBook.decode(b.encode())
        assertEquals(b.alive, again.alive)
        // The reform: each takes the style closest to its genes, about as strong as before.
        val r = Evolution.run(b, 5, { 1500.0 }, { 0 }, 3L, 3)
        assertEquals(5, b.reformed)
        assertEquals("league_reform", r.events.first().kind)
        assertTrue(b.alive.all { it.spec.play != null })
        // A believer takes the style of its belief; a Yacht lover hunts Yachts.
        b["e3_1"]?.let { assertEquals("lower", it.spec.play!!.primary) }
        assertEquals("hunter", Profiles.reform(PlayerRecord("g8", "a", "서울", Origin.GENERATED, 1, null, null, CustomAiSpec("g8", "a", yacht = 40.0)), 3).spec.play!!.primary)
        assertEquals(r.events.filter { it.kind != "learned_rule" }, Evolution.run(b, 5, { 1500.0 }, { 0 }, 3L, 3).events)
        val legacy = PlayerRecord("g009", "정우진", "서울", Origin.GENERATED, 1, null, null, CustomAiSpec("g009", "정우진", mistake = CustomMistake.SLIP, amount = 0.05))
        val reformed = Profiles.reform(legacy, 3)
        assertEquals(CustomMistake.SLIP, reformed.spec.mistake)
        assertEquals(0.05, reformed.spec.amount, 0.01)
        assertEquals("ev", reformed.spec.play!!.primary)
    }

    @Test
    fun namesComeFromTheirNations() {
        val ps = PlayerBook().apply { ensureGenerated(3000) }.alive
        assertEquals(ps.size, ps.map { it.name }.toSet().size)
        val hangul = Regex("[가-힣 ]+")
        for (p in ps) {
            assertTrue(hangul.matches(p.name), p.name)
            val nation = Nations.of(p.identity!!.nation)!!
            assertNotNull(nation.familyOf(p.name), "${nation.code} ${p.name}")
        }
        for (nation in Nations.ALL) {
            for (pool in listOf(nation.families, nation.male, nation.female, nation.cities)) {
                assertTrue(pool.size > 0, nation.code)
                assertEquals(pool.size, pool.names.toSet().size, "${nation.code} duplicate")
                assertTrue(pool.names.all { hangul.matches(it) }, nation.code)
            }
            assertNotNull(nation.confed)
        }
        assertEquals(Nations.ALL.size, Nations.ALL.map { it.code }.toSet().size)
        assertEquals("🇰🇷 대한민국", Nations.flagged("KR"))
        assertEquals("한일전", Nations.rivalry("JP", "KR"))
    }

    @Test
    fun specLinesKeepOldFilesReadable() {
        // A line saved before tempers and beliefs existed (twelve fields).
        val old = "a1|요트 사랑|EV|0.03|FEEL|0.5|20.0|0.0|0.0|5.0|true|0.9"
        val s = assertNotNull(CustomAiSpec.decode(old))
        assertEquals(Temper.NONE, s.temper)
        assertNull(s.belief)
        assertEquals(old, s.encode())
        val specs = listOf(
            s,
            s.copy(temper = Temper.TILT),
            s.copy(belief = "no-bonus"),
            CustomAiSpec("x", "모험 기복", CustomBase.RISK, theta = -0.06, mistake = CustomMistake.SLIP, amount = 0.05, temper = Temper.MOODY, belief = "cheap-yacht"),
        )
        for (x in specs) {
            assertEquals(x, CustomAiSpec.decode(x.encode()))
            // The old reader takes the first twelve fields: still the same AI apart from the new genes.
            val f = x.encode().split('|')
            assertEquals(if (x.temper == Temper.NONE && x.belief == null) 12 else 14, f.size)
            assertEquals(x.copy(temper = Temper.NONE, belief = null), CustomAiSpec.decode(f.take(12).joinToString("|")))
            assertEquals(x, CustomAiSpec.ofGenes(x.id, x.name, x.genes()))
            assertEquals(CustomAiSpec.GENES, x.genes().size)
            assertTrue(x.genes().none { ' ' in it })
        }
        assertEquals(s, CustomAiSpec.ofGenes(s.id, s.name, s.genes().take(10)))
        // An unknown temper (a newer version) reads as none.
        assertEquals(Temper.NONE, CustomAiSpec.decode("$old|ANGRY|-")!!.temper)
        assertNull(CustomAiSpec.decode("a1|x|EV"))
        assertTrue(specs[2].describe().contains("보너스를 모름"), specs[2].describe())
        assertTrue(specs[1].describe().endsWith("0점 뒤 흔들림"), specs[1].describe())
        assertTrue(specs[1].flawed)
        assertTrue(!CustomAiSpec("p", "정석").flawed)
        assertTrue(CustomAiSpec("p", "기복", temper = Temper.TIRED).flawed)
    }

    /** A book with players, a pupil, the retired and a legend, for the codec tests. */
    private fun sampleBook(): PlayerBook {
        val b = PlayerBook(listOf("기대점수 AI"))
        b.ensureGenerated(20)
        b.notePeak("g003", 1611.5)
        b.notePeak("g003", 1500.0)
        val child = PlayerRecord("e1_1", "홍 길동", "부산", Origin.EVOLVED, 2, "g001", "g002", gen(7).copy(temper = Temper.WARMUP))
        assertTrue(b.add(child))
        assertTrue(!b.add(child))
        b.retire("g004", 1, titles = 0, bestRating = 1405.0)
        b.retire("g005", 1, titles = 2, bestRating = 1702.5, honours = "L0:1,CUP:1")
        b.retire("e1_1", 2)
        return b
    }

    @Test
    fun theBookSavesAndSurvivesACutFile() {
        val b = sampleBook()
        // 20 generated and a pupil, three of them retired.
        assertEquals(18, b.size)
        val text = b.encode()
        assertEquals(PlayerBook.HEADER, text.lines().first())
        println(text.lines().filter { it.isNotEmpty() }.let { it.take(6) + it.takeLast(3) }.joinToString("\n"))
        val back = PlayerBook.decode(text)
        assertEquals(b.alive, back.alive)
        assertEquals(b.retired, back.retired)
        assertEquals(b.legends, back.legends)
        assertEquals(text, back.encode())
        assertEquals(1611.5, back["g003"]!!.peak)
        assertEquals(back["g003"]!!.identity!!.abilities, back["g003"]!!.identity!!.peakAbl)
        assertEquals("홍 길동", back.name("e1_1"))
        assertEquals(listOf("g005"), back.legends.map { it.key })
        assertEquals(2, back.legend("g005")!!.record.titles)
        assertEquals(b.legend("g005")!!.identity, back.legend("g005")!!.identity)
        assertEquals(mapOf("L0" to 1, "CUP" to 1), Evolution.counts(back.legend("g005")!!.honours))
        assertEquals(b.legend("g005")!!.spec, back.legendSpecs().single())
        assertEquals(b.retired.map { it.nation to it.debutAge }, back.retired.map { it.nation to it.debutAge })
        // Retired generated keys never come back: new players take the next keys.
        assertEquals(listOf("g021"), back.ensureGenerated(19).map { it.key })
        // Cut anywhere: never throws, keeps whole lines only.
        for (cut in PlayerBook.HEADER.length until text.length step 7) {
            val part = PlayerBook.decode(text.substring(0, cut))
            assertTrue(b.alive.map { it.key }.containsAll(part.alive.map { it.key }))
            for (p in part.alive) assertEquals(b[p.key], p)
            assertEquals(b.retired.take(part.retired.size), part.retired)
            for (l in part.legends) assertEquals(b.legend(l.key), l)
            assertEquals(part.encode(), PlayerBook.decode(part.encode()).encode())
        }
        // A line from a newer version or garbage is skipped; an unknown token is ignored.
        val noisy = PlayerBook.decode(text.replace("\nretired ", "\nfuture x y z\nai broken\nretired ").replace(" nat=", " wow=1 nat="))
        assertEquals(b.alive, noisy.alive)
        assertEquals(b.retired, noisy.retired)
        assertFailsWith<IllegalArgumentException> { PlayerBook.decode("") }
        assertFailsWith<IllegalArgumentException> { PlayerBook.decode("ladder 1\np x") }
    }

    @Test
    fun descriptionsReadNaturally() {
        // Shares of the expected rest read as whole percents, as the builder's slider shows them.
        for (k in 6..30) {
            val f = k / 20.0
            if (k != 20) assertTrue(CustomAiSpec("f", "f", future = f).describe().contains("미래 ${k * 5}%"), "$f")
        }
        assertTrue(CustomAiSpec("f", "f", future = 0.57f.toDouble()).describe().contains("미래 57%"))
        // A 0% slip is no mistake (the chaos preset leaves it out).
        assertTrue(!CustomAiSpec("s", "s", mistake = CustomMistake.SLIP, amount = 0.0).flawed)
        assertTrue(CustomAiSpec("s", "s", mistake = CustomMistake.SLIP, amount = 0.01).flawed)
        // A styled player is described by its style.
        val styled = CustomAiSpec("s", "s", mistake = CustomMistake.FEEL, amount = 0.5, temper = Temper.TIRED, play = PlayGene("hunter", 1.5, overlay = "gambler"))
        assertEquals("강한 요트 사냥꾼 · 뒤지면 승부수 · 감(τ=0.5점) · 지구력 부족", styled.describe())
        // Children are listed by debut and then in number order (e3_2 before e3_10).
        val b = PlayerBook().apply { ensureGenerated(2) }
        for (n in 1..12) assertTrue(b.add(PlayerRecord("e3_$n", "아이$n", "서울", Origin.EVOLVED, 4, "g001", "g002", gen(1))))
        assertEquals((1..12).map { "e3_$it" }, b.children("g001"))
        assertEquals((1..12).map { "e3_$it" }, b.descendants("g002").single())
    }

    @Test
    fun genealogyGoesUpAndDown() {
        val b = PlayerBook()
        b.ensureGenerated(4)
        fun child(key: String, a: String, p: String, born: Int) =
            assertTrue(b.add(PlayerRecord(key, key, "서울", Origin.EVOLVED, born, a, p, gen(9))))
        child("e1_1", "g001", "g002", 2)
        child("e1_2", "g003", "g004", 2)
        child("e2_1", "e1_1", "e1_2", 3)
        child("e2_2", "e1_1", "g003", 3)
        child("e3_1", "e2_1", "e2_2", 4)
        assertEquals(listOf(listOf("e1_1", "e1_2"), listOf("g001", "g002", "g003", "g004")), b.ancestors("e2_1"))
        assertEquals(listOf(listOf("e1_1", "g003"), listOf("g001", "g002", null, null)), b.ancestors("e2_2"))
        assertTrue(b.ancestors("g001").isEmpty())
        assertEquals(3, b.ancestors("e3_1").size)
        assertEquals(1, b.ancestors("e3_1", generations = 1).size)
        assertEquals(listOf("e2_1", "e2_2"), b.children("e1_1"))
        assertEquals(listOf(listOf("e1_1"), listOf("e2_1", "e2_2"), listOf("e3_1")), b.descendants("g001"))
        assertEquals(listOf(listOf("e1_2", "e2_2"), listOf("e2_1", "e3_1")), b.descendants("g003"))
        assertEquals(1, b.descendants("g001", generations = 1).size)
        // Retired ancestors still count, and survive a save.
        b.retire("e1_1", 3)
        b.retire("g001", 3)
        val back = PlayerBook.decode(b.encode())
        assertEquals(b.ancestors("e3_1"), back.ancestors("e3_1"))
        assertEquals(listOf(listOf("e1_1"), listOf("e2_1", "e2_2"), listOf("e3_1")), back.descendants("g001"))
        assertEquals("e1_1", back.name("e1_1"))
    }

    @Test
    fun tempersScaleTheMistakes() {
        val flat = EvTable(rules, DoubleArray((Category.FULL_MASK + 1) * rules.upperStates))
        fun card(vararg scores: Pair<Category, Int>) = scores.fold(Board(rules)) { b, (c, p) -> b.with(c, p) }
        val opp = Board(rules)
        val rng = Random(1)
        val tired = Tempers.mood(Temper.TIRED, flat, rng)
        val warm = Tempers.mood(Temper.WARMUP, flat, rng)
        val early = card(Category.ACES to 3)
        val late = Category.ALL.take(8).fold(Board(rules)) { b, c -> b.with(c, 5) }
        assertEquals(1.0, tired.level(early, opp))
        assertEquals(Tempers.PHASE_LEVEL, tired.level(late, opp))
        assertEquals(Tempers.PHASE_LEVEL, warm.level(early, opp))
        assertEquals(1.0, warm.level(late, opp))
        // Moody: one level per game, from the game's random source.
        val m1 = Tempers.mood(Temper.MOODY, flat, Random(7)).level(early, opp)
        assertEquals(m1, Tempers.mood(Temper.MOODY, flat, Random(7)).level(late, opp))
        // Games are seeded through DiceStream.mix (plain consecutive seeds give java.util.Random correlated first draws).
        val levels = (1..500L).map { Tempers.mood(Temper.MOODY, flat, Random(DiceStream.mix(5, it))).level(early, opp) }
        assertTrue(levels.all { it in Tempers.MOODY_MIN..Tempers.MOODY_MAX })
        assertTrue(levels.count { it < 1 } in 200..300)
        // Tilt: calm until a zero is written, then tilted for the rest of the game.
        val tilt = Tempers.mood(Temper.TILT, flat, rng)
        assertEquals(1.0, tilt.level(Board(rules), opp))
        assertEquals(1.0, tilt.level(early, opp))
        val zero = early.with(Category.YACHT, 0)
        assertEquals(Tempers.TILT_LEVEL, tilt.level(zero, opp))
        assertEquals(Tempers.TILT_LEVEL, tilt.level(zero.with(Category.CHOICE, 30), opp))
        assertEquals(1.0, tilt.level(Board(rules), opp))
        // A big drop of the expected final is a bad turn too.
        val values = DoubleArray((Category.FULL_MASK + 1) * rules.upperStates) { 100.0 }
        val bigMiss = Tempers.mood(Temper.TILT, EvTable(rules, values.also { it[early.with(Category.CHOICE, 5).mask * rules.upperStates + 3] = 70.0 }), rng)
        assertEquals(1.0, bigMiss.level(early, opp))
        assertEquals(Tempers.TILT_LEVEL, bigMiss.level(early.with(Category.CHOICE, 5), opp))
        // Level 1 is the spec itself; a temper adds mistakes even to a flawless AI.
        assertEquals(0.123, Tempers.slip(0.123, 1.0))
        assertEquals(0.7, Tempers.feel(0.7, 1.0))
        assertEquals(0.0, Tempers.slip(0.0, 0.5))
        assertTrue(Tempers.slip(0.0, Tempers.TILT_LEVEL) > 0.0)
        assertTrue(Tempers.slip(0.05, Tempers.PHASE_LEVEL) > 0.15)
        assertTrue(Tempers.slip(0.5, 3.0) <= Tempers.MAX_SLIP)
    }

    @Test
    fun traitsForTheMetaReport() {
        val t = traits(CustomAiSpec("x", "x", CustomBase.EV, mistake = CustomMistake.SLIP, amount = 0.05, yacht = 25.0, belief = "no-bonus", temper = Temper.TILT))
        assertEquals(mapOf("mistake" to "mid", "risk" to "-", "yacht" to "yes", "belief" to "no-bonus", "temper" to "TILT"), t)
        assertEquals(Traits.NAMES.keys, t.keys)
        val r = traits(CustomAiSpec("y", "y", CustomBase.RISK, theta = -0.1, mistake = CustomMistake.FEEL, amount = 2.0, yacht = 25.0))
        // FEEL on a risk base slips at τ/10 = 20%; tastes and beliefs only count with the EV base.
        assertEquals(mapOf("mistake" to "high", "risk" to "very-cautious", "yacht" to "no", "belief" to "-", "temper" to "NONE"), r)
        assertEquals("none", traits(CustomAiSpec("z", "z"))["mistake"])
        assertEquals("low", traits(CustomAiSpec("z", "z", mistake = CustomMistake.FEEL, amount = 0.3))["mistake"])
        assertEquals("bold", traits(CustomAiSpec("z", "z", CustomBase.RISK, theta = 0.03))["risk"])
        assertEquals("보너스를 모름", Traits.label("belief", "no-bonus"))
        assertEquals("0점 뒤 흔들림", Traits.label("temper", "TILT"))
        assertEquals("실수 보통", Traits.label("mistake", "mid"))
        assertEquals("아주 신중", Traits.label("risk", "very-cautious"))
        // Every generated spec has known values, each with Korean words.
        val codes = mapOf(
            "mistake" to setOf("none", "low", "mid", "high"),
            "risk" to setOf("-", "very-cautious", "cautious", "bold", "very-bold"),
            "yacht" to setOf("yes", "no"),
            "belief" to CustomAiSpec.BELIEFS.keys + "-" + "ss3" + "fh2pair",
            "temper" to Temper.entries.map { it.name }.toSet(),
        )
        for (i in 1..300) for ((k, v) in traits(gen(i))) {
            assertTrue(v in codes.getValue(k), "$k $v")
            assertNotEquals(v, Traits.label(k, v))
        }
    }

    @Test
    fun aGeneratedAiWithTemperAndBeliefPlays() {
        val tables = SolverTest.tables
        val extra = LeagueTables(tables.rules, threads = Runtime.getRuntime().availableProcessors())
        val ev = LeagueAi("ev", "ev", Family.EV, "") { AdvisorPlayer(Advisor(tables), Mode.EXPECTED_SCORE) }
        val spec = CustomAiSpec("g999", "", CustomBase.EV, mistake = CustomMistake.SLIP, amount = 0.05, yacht = 10.0, temper = Temper.TILT, belief = "no-bonus")
        val record = PlayerRecord("g999", "김요트", "서울", Origin.GENERATED, 1, null, null, spec.copy(name = "김요트"))
        val ai = leagueAi(record, tables, extra)
        assertEquals("g999", ai.key)
        assertEquals("김요트", ai.name)
        assertEquals(Family.BELIEF, ai.family)
        assertTrue(!ai.heavy)
        assertTrue(ai.desc.contains("보너스를 모름") && ai.desc.contains("0점 뒤 흔들림"), ai.desc)
        val games = LeagueMatch.play(tables.rules, ai, ev, 11)
        assertEquals(2, games.size)
        assertEquals(games, LeagueMatch.play(tables.rules, ai, ev, 11))
        // Every temper plays whole games, with each kind of mistake.
        for ((i, temper) in Temper.entries.withIndex()) {
            for (mistake in CustomMistake.entries) {
                val s = gen(i + 1).copy(
                    id = "t$i", temper = temper, mistake = mistake, amount = if (mistake == CustomMistake.FEEL) 0.5 else 0.05,
                )
                val x = leagueAi(PlayerRecord("t$i", "t", "서울", Origin.GENERATED, 1, null, null, s), tables, extra)
                val g = LeagueMatch.play(tables.rules, x, ev, 3L + i)
                assertEquals(g, LeagueMatch.play(tables.rules, x, ev, 3L + i))
            }
        }
        // Without tastes, mistakes or temper, a believer plays exactly like the 규칙 착각 family's AI.
        val believed = LeagueRoster.beliefs(tables.rules).getValue("no-bonus")
        val family = LeagueAi("rule-no-bonus", "보너스를 모름", Family.BELIEF, "") { BeliefPlayer(believed, extra.believed("no-bonus", believed), "rule-no-bonus") }
        val plain = leagueAi(record.copy(spec = CustomAiSpec("g999", "", belief = "no-bonus")), tables, extra)
        for (seed in 1L..3L) assertEquals(LeagueMatch.play(tables.rules, family, ev, seed), LeagueMatch.play(tables.rules, plain, ev, seed))
        // A legend is rebuilt from its archived genes.
        val book = PlayerBook().apply { add(record) }
        book.retire("g999", 4, titles = 1, honours = "CUP:1")
        val legend = leagueAi(book.legend("g999")!!, tables, extra)
        assertEquals(games, LeagueMatch.play(tables.rules, legend, ev, 11))
    }

    @Test
    fun aTopUpAddsAtMostWhatTheBookLacks() {
        val book = PlayerBook()
        book.ensureGenerated(10)
        assertEquals(10, book.size)
        // Asked for 30 but only 5 may come in: rookies (a small top-up), not founders.
        val added = book.ensureGenerated(30, 2, max = 5)
        assertEquals(5, added.size)
        assertTrue(added.all { it.age(2) in 17..27 })
        assertEquals(15, book.size)
        // A big top-up is founders of every age.
        assertTrue(book.ensureGenerated(400, 2).any { it.age(2) >= 30 })
    }
}
