package yacht.engine

import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.Evolution
import yacht.engine.league.Family
import yacht.engine.league.Honour
import yacht.engine.league.Hometowns
import yacht.engine.league.KoreanNames
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.Origin
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerRecord
import yacht.engine.league.Temper
import yacht.engine.league.Tempers
import yacht.engine.league.Traits
import yacht.engine.league.familyOf
import yacht.engine.league.leagueAi
import yacht.engine.league.traits
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayersTest {
    private val rules = RuleSet.NINTENDO

    /** A synthetic rating per key (no games needed). */
    private fun ratingOf(key: String) = 1300.0 + Random(key.hashCode().toLong()).nextDouble() * 400

    @Test
    fun hometownsAreTheAppsCities() {
        assertEquals(listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "수원", "춘천", "청주", "전주", "포항", "창원", "제주", "강릉"), Hometowns.ALL)
        for (k in listOf("ev", "win", "slip3", "g001", "rule-no-bonus")) assertEquals(Hometowns.ALL[Math.floorMod(k.hashCode(), 16)], Hometowns.of(k))
        assertEquals(Origin.GENERATED, Origin.of("g017"))
        assertEquals(Origin.EVOLVED, Origin.of("e12_3"))
        assertEquals(Origin.MINE, Origin.of("custom-a1"))
        assertEquals(Origin.MINE, Origin.of(CustomAis.CLONE))
        assertEquals(Origin.BASE, Origin.of("ev"))
        assertEquals(Origin.BASE, Origin.of("greedy"))
    }

    @Test
    fun generatedPlayersAreDeterministicAndAPrefixOfMore() {
        val small = PlayerBook().apply { ensureGenerated(30) }
        val big = PlayerBook().apply { ensureGenerated(40) }
        assertEquals(30, small.size)
        assertEquals(small.alive, big.alive.take(30))
        assertEquals(small.alive, PlayerBook().apply { ensureGenerated(30) }.alive)
        assertEquals((1..30).map { "g" + it.toString().padStart(3, '0') }, small.alive.map { it.key })
        for ((i, p) in big.alive.withIndex()) {
            assertEquals(PlayerGenerator.spec(i + 1).copy(name = p.name), p.spec)
            assertEquals(p.key, p.spec.id)
            assertEquals(Hometowns.of(p.key), p.hometown)
            assertEquals(Origin.GENERATED, p.origin)
            assertEquals(3, p.name.length, p.name)
        }
        assertEquals(40, big.alive.map { it.name }.toSet().size)
        println(big.alive.take(12).joinToString("\n") { "${it.key} ${it.name} ${it.hometown} ${familyOf(it.spec).label} · ${it.spec.describe()}" })
        // Adding more later adds only the new ones; asking again adds nothing.
        assertEquals(10, small.ensureGenerated(40, season = 3).size)
        assertEquals(big.alive.take(30), small.alive.take(30))
        assertEquals(3, small["g031"]!!.born)
        assertTrue(small.ensureGenerated(40).isEmpty())
        // Base AIs keep their names: a reserved name is never given out.
        val reserved = big.alive.take(5).map { it.name }
        val other = PlayerBook(reserved).apply { ensureGenerated(10) }
        assertTrue(other.alive.none { it.name in reserved })
        assertEquals(big.alive.take(10).map { it.spec.copy(name = "") }, other.alive.map { it.spec.copy(name = "") })
    }

    @Test
    fun geneSharesFollowTheSpec() {
        val specs = (1..1000).map { PlayerGenerator.spec(it) }
        val families = specs.groupingBy { familyOf(it) }.eachCount()
        fun share(f: Family) = (families[f] ?: 0) / 1000.0
        assertEquals(0.55, share(Family.EV), 0.05)
        assertEquals(0.20, share(Family.RISK), 0.04)
        assertEquals(0.10, share(Family.BELIEF), 0.03)
        assertEquals(0.15, share(Family.SITUATION), 0.035)
        assertEquals(setOf(Family.EV, Family.RISK, Family.BELIEF, Family.SITUATION), families.keys)
        assertEquals(LeagueRoster.beliefs(rules).keys, CustomAiSpec.BELIEFS.keys)
        for (s in specs) {
            assertNotEquals(CustomBase.WIN, s.base)
            if (s.base == CustomBase.RISK) assertTrue(s.theta in LeagueRoster.THETAS)
            s.belief?.let { assertTrue(it in CustomAiSpec.BELIEFS) }
            when (s.mistake) {
                CustomMistake.NONE -> assertEquals(0.0, s.amount)
                CustomMistake.SLIP -> assertTrue(s.amount in 0.003..0.30, "${s.amount}")
                CustomMistake.FEEL -> assertTrue(s.amount in 0.1..3.0, "${s.amount}")
            }
            // A temper always has mistakes to scale; tastes only shape the max-expectation base.
            if (s.temper != Temper.NONE) assertNotEquals(CustomMistake.NONE, s.mistake)
            if (s.base != CustomBase.EV) assertTrue(s.yacht == 0.0 && s.straight == 0.0 && s.bonus == 0.0 && s.zero == 0.0 && !s.saveChoice && s.future == 1.0)
            assertTrue(s.future in 0.3..1.5)
        }
        val ev = specs.filter { it.base == CustomBase.EV }
        assertEquals(0.3, ev.count { it.yacht != 0.0 } / ev.size.toDouble(), 0.06)
        assertEquals(0.3, ev.count { it.saveChoice } / ev.size.toDouble(), 0.06)
        assertEquals(0.3, ev.count { it.future != 1.0 } / ev.size.toDouble(), 0.06)
        val m = specs.filter { it.temper == Temper.NONE }.groupingBy { it.mistake }.eachCount()
        assertEquals(0.30, (m[CustomMistake.NONE] ?: 0) / 850.0, 0.06)
        assertTrue((m[CustomMistake.SLIP] ?: 0) > 200 && (m[CustomMistake.FEEL] ?: 0) > 200, "$m")
        assertTrue(specs.map { it.temper }.toSet().size == Temper.entries.size)
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
        assertTrue(specs[1].describe().endsWith("멘탈 약함"), specs[1].describe())
        assertTrue(specs[1].flawed)
        assertTrue(!CustomAiSpec("p", "정석").flawed)
        assertTrue(CustomAiSpec("p", "기복", temper = Temper.TIRED).flawed)
    }

    /** A book with players, a child, the retired and a legend, for the codec tests. */
    private fun sampleBook(): PlayerBook {
        val b = PlayerBook(listOf("기대점수 AI"))
        b.ensureGenerated(20)
        b.notePeak("g003", 1611.5)
        b.notePeak("g003", 1500.0)
        val child = PlayerRecord("e1_1", "홍 길동", "부산", Origin.EVOLVED, 2, "g001", "g002", PlayerGenerator.spec(7).copy(temper = Temper.WARMUP, belief = "four-only"))
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
        // 20 generated and a child, three of them retired.
        assertEquals(18, b.size)
        val text = b.encode()
        assertEquals(PlayerBook.HEADER, text.lines().first())
        println(text.lines().filter { it.isNotEmpty() }.let { it.take(5) + it.takeLast(3) }.joinToString("\n"))
        val back = PlayerBook.decode(text)
        assertEquals(b.alive, back.alive)
        assertEquals(b.retired, back.retired)
        assertEquals(b.legends, back.legends)
        assertEquals(text, back.encode())
        assertEquals(1611.5, back["g003"]!!.peak)
        assertEquals("홍 길동", back.name("e1_1"))
        assertEquals(listOf("g005"), back.legends.map { it.key })
        assertEquals(2, back.legend("g005")!!.record.titles)
        assertEquals(mapOf("L0" to 1, "CUP" to 1), Evolution.counts(back.legend("g005")!!.honours))
        assertEquals(b.legend("g005")!!.spec, back.legendSpecs().single())
        // Retired generated keys never come back, even once their records are gone.
        assertTrue(back.ensureGenerated(20).isEmpty())
        assertEquals(1, back.ensureGenerated(21).size)
        // Cut anywhere: never throws, keeps whole lines only (a last line cut inside its peak,
        // "1611.5" read as "16", or inside its titles, is dropped rather than read short).
        for (cut in PlayerBook.HEADER.length until text.length) {
            val part = PlayerBook.decode(text.substring(0, cut))
            assertTrue(b.alive.map { it.key }.containsAll(part.alive.map { it.key }))
            for (p in part.alive) assertEquals(b[p.key], p)
            assertEquals(b.retired.take(part.retired.size), part.retired)
            for (l in part.legends) assertEquals(b.legend(l.key), l)
            assertEquals(part.encode(), PlayerBook.decode(part.encode()).encode())
        }
        // A line from a newer version or garbage is skipped.
        val noisy = PlayerBook.decode(text.replace("\nretired ", "\nfuture x y z\nai broken\nretired "))
        assertEquals(b.alive, noisy.alive)
        assertEquals(b.retired, noisy.retired)
        assertFailsWith<IllegalArgumentException> { PlayerBook.decode("") }
        assertFailsWith<IllegalArgumentException> { PlayerBook.decode("ladder 1\np x") }
    }

    @Test
    fun evolutionIsDeterministicAndKeepsItsRules() {
        fun book() = PlayerBook().apply { ensureGenerated(100) }
        val a = book()
        val b = book()
        val ratings = HashMap<String, Double>()
        fun rating(k: String) = ratings.getOrPut(k) { ratingOf(k) }
        val before = a.alive.sortedByDescending { rating(it.key) }.map { it.key }
        val top = before.take(50).toSet()
        val lucky = before.last()
        val titles = { k: String -> if (k == lucky) 1 else 0 }
        val honours = { k: String -> if (k == lucky) listOf(Honour(1, "CUP", k), Honour(1, "DOWN3", k)) else emptyList() }
        val r = Evolution.run(a, 1, ::rating, titles, 8, 99L, honours)
        val r2 = Evolution.run(b, 1, ::rating, titles, 8, 99L, honours)
        assertEquals(r.retired, r2.retired)
        assertEquals(r.born, r2.born)
        assertEquals(r.events, r2.events)
        assertEquals(a.encode(), b.encode())
        // The eight lowest go (lowest first); eight children of the top half come.
        assertEquals(before.takeLast(8).reversed(), r.retired)
        assertEquals((1..8).map { "e1_$it" }, r.born)
        assertEquals(100, a.size)
        for (k in r.born) {
            val c = a[k]!!
            assertTrue(c.parentA in top && c.parentB in top, "$c")
            assertNotEquals(c.parentA, c.parentB)
            assertEquals(2, c.born)
            assertEquals(Origin.EVOLVED, c.origin)
            assertEquals(a[c.parentA!!]!!.hometown, c.hometown)
            assertEquals(k, c.spec.id)
            assertEquals(c.name, c.spec.name)
        }
        assertEquals(100, a.alive.map { it.name }.toSet().size)
        assertTrue(a.alive.all { Origin.of(it.key) == it.origin })
        // News: retired [ai] (seasons, titles), born [child, parentA, parentB].
        assertEquals(8, r.events.count { it.kind == "retired" })
        val bornEvents = r.events.filter { it.kind == "born" }
        assertEquals(r.born, bornEvents.map { it.keys[0] })
        assertEquals(a[r.born[0]]!!.parentB, bornEvents[0].keys[2])
        assertEquals(listOf("1", "1"), r.events.first { it.kind == "retired" && it.keys == listOf(lucky) }.args)
        // The titled AI is a legend (its move is not an honour); the others are only retired.
        assertEquals(listOf(lucky), a.legends.map { it.key })
        assertEquals("CUP:1", a.legend(lucky)!!.honours)
        assertEquals(8, a.retired.size)
        // A repeated rollover changes nothing and tells the same story.
        val again = Evolution.run(a, 1, ::rating, titles, 8, 99L, honours)
        assertEquals(r.retired, again.retired)
        assertEquals(r.born, again.born)
        assertEquals(r.events.toSet(), again.events.toSet())
        assertEquals(b.encode(), a.encode())
        // Seasons go on and the population stays. Players added for next season (the weakest of
        // all here) neither retire nor breed before their debut.
        val waiting = (101..105).map { PlayerGenerator.key(it) }
        for (k in waiting) ratings[k] = 1000.0
        for (season in 2..6) {
            if (season == 4) assertEquals(waiting, a.ensureGenerated(105, season = 5).map { it.key })
            val res = Evolution.run(a, season, ::rating, { 0 }, 8, 99L)
            assertEquals(8, res.retired.size)
            assertTrue(res.retired.none { a[it] != null })
            assertEquals(if (season < 4) 100 else 105, a.size)
            if (season == 4) {
                assertTrue(res.retired.none { it in waiting })
                assertTrue(res.born.none { a[it]!!.parentA in waiting || a[it]!!.parentB in waiting })
            }
            if (season == 5) assertEquals(waiting, res.retired.take(5).sorted())
        }
        assertTrue(a.alive.all { it.origin == Origin.GENERATED || it.origin == Origin.EVOLVED })
        // Fewer children shrink the population; no players, no children.
        assertEquals(4, Evolution.run(a, 7, ::rating, { 0 }, 8, 99L, children = 4).born.size)
        assertEquals(101, a.size)
        val empty = Evolution.run(PlayerBook(), 1, ::rating, { 0 }, 8, 1L)
        assertTrue(empty.retired.isEmpty() && empty.born.isEmpty())
        // A small league: only the bottom half can go.
        val few = PlayerBook().apply { ensureGenerated(6) }
        assertEquals(3, Evolution.run(few, 1, ::rating, { 0 }, 8, 1L).retired.size)
        assertEquals(6, few.size)
    }

    @Test
    fun evolutionFollowsThePopulationAndThePresetFilter() {
        val ratings = HashMap<String, Double>()
        fun rating(k: String) = ratings.getOrPut(k) { ratingOf(k) }
        // A lowered setting shrinks the book by the retirements; raised again, it refills
        // (the g-keys already used never come back, so only children can).
        val book = PlayerBook().apply { ensureGenerated(100) }
        val shrink = Evolution.run(book, 1, ::rating, { 0 }, 8, 3L, population = 60)
        assertEquals(8, shrink.retired.size)
        assertTrue(shrink.born.isEmpty())
        assertEquals(92, book.size)
        assertTrue(book.ensureGenerated(100, season = 2).isEmpty())
        val refill = Evolution.run(book, 2, ::rating, { 0 }, 8, 3L, population = 100)
        assertEquals(8, refill.retired.size)
        assertEquals(16, refill.born.size)
        assertEquals(100, book.size)
        // An explicit number of children wins over the population.
        assertEquals(2, Evolution.run(book, 3, ::rating, { 0 }, 8, 3L, children = 2, population = 100).born.size)
        // Only flawed AIs (the chaos preset): every child makes mistakes.
        val chaos = PlayerBook().apply { ensureGenerated(150) }
        for (season in 1..6) {
            val r = Evolution.run(chaos, season, ::rating, { 0 }, 20, 9L, accept = { it.flawed })
            assertEquals(20, r.born.size)
            assertTrue(r.born.all { chaos[it]!!.spec.flawed }, "${r.born.map { chaos[it]!!.spec.describe() }}")
        }
        // A filter nothing passes: the retirements still happen, no child is born.
        val none = Evolution.run(PlayerBook().apply { ensureGenerated(40) }, 1, ::rating, { 0 }, 8, 1L, accept = { false })
        assertEquals(8, none.retired.size)
        assertTrue(none.born.isEmpty())
    }

    @Test
    fun namesAndDescriptionsReadNaturally() {
        // Never the surname twice ("정정우") or a doubled syllable ("민민").
        val rng = Random(3)
        repeat(20_000) {
            val n = KoreanNames.draw(rng)
            assertEquals(3, n.length, n)
            assertTrue(n[0] != n[1] && n[1] != n[2], n)
        }
        // Shares of the expected rest read as whole percents, as the builder's slider shows them.
        for (k in 6..30) {
            val f = k / 20.0
            if (k != 20) assertTrue(CustomAiSpec("f", "f", future = f).describe().contains("미래 ${k * 5}%"), "$f")
        }
        assertTrue(CustomAiSpec("f", "f", future = 0.57f.toDouble()).describe().contains("미래 57%"))
        // A 0% slip is no mistake (the chaos preset leaves it out).
        assertTrue(!CustomAiSpec("s", "s", mistake = CustomMistake.SLIP, amount = 0.0).flawed)
        assertTrue(CustomAiSpec("s", "s", mistake = CustomMistake.SLIP, amount = 0.01).flawed)
        // Children are listed by debut and then in number order (e3_2 before e3_10).
        val b = PlayerBook().apply { ensureGenerated(2) }
        for (n in 1..12) assertTrue(b.add(PlayerRecord("e3_$n", "아이$n", "서울", Origin.EVOLVED, 4, "g001", "g002", PlayerGenerator.spec(1))))
        assertEquals((1..12).map { "e3_$it" }, b.children("g001"))
        assertEquals((1..12).map { "e3_$it" }, b.descendants("g002").single())
    }

    @Test
    fun childrenMixTheirParentsGenes() {
        val a = PlayerGenerator.spec(1).copy(yacht = 30.0, temper = Temper.NONE)
        val b = PlayerGenerator.spec(2).copy(yacht = -10.0, temper = Temper.TIRED)
        val rng = Random(5)
        repeat(200) {
            val c = Evolution.cross(a, b, rng)
            assertTrue(c.yacht == a.yacht || c.yacht == b.yacht)
            assertTrue((c.base == a.base && c.theta == a.theta && c.belief == a.belief) || (c.base == b.base && c.theta == b.theta && c.belief == b.belief))
            assertTrue((c.mistake == a.mistake && c.amount == a.amount) || (c.mistake == b.mistake && c.amount == b.amount))
            val m = Evolution.mutate(c, rng, p = 1.0)
            if (m.base == CustomBase.RISK) assertTrue(m.theta in LeagueRoster.THETAS)
            assertTrue(m.yacht in -30.0..70.0 && m.future in 0.3..1.5 && m.zero in 0.0..35.0)
            if (m.mistake == CustomMistake.SLIP) assertTrue(m.amount in 0.003..0.30)
        }
        // A step of θ moves to a neighbouring attitude (plain expectation sits between ±0.01).
        val risky = CustomAiSpec("r", "r", CustomBase.RISK, theta = 0.01)
        val steps = (1..100).map { Evolution.mutate(risky, Random(it.toLong()), p = 1.0) }.map { if (it.base == CustomBase.EV) 0.0 else it.theta }.toSet()
        assertEquals(setOf(0.0, 0.03), steps)
        // A believer that mutates learns the rule.
        assertNull(Evolution.mutate(CustomAiSpec("b", "b", belief = "no-bonus"), Random(1), p = 1.0).belief)
    }

    @Test
    fun genealogyGoesUpAndDown() {
        val b = PlayerBook()
        b.ensureGenerated(4)
        fun child(key: String, a: String, p: String, born: Int) =
            assertTrue(b.add(PlayerRecord(key, key, "서울", Origin.EVOLVED, born, a, p, PlayerGenerator.spec(9))))
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
        assertEquals("멘탈 약함", Traits.label("temper", "TILT"))
        assertEquals("실수 보통", Traits.label("mistake", "mid"))
        assertEquals("아주 신중", Traits.label("risk", "very-cautious"))
        // Every generated spec has known values, each with Korean words.
        val codes = mapOf(
            "mistake" to setOf("none", "low", "mid", "high"),
            "risk" to setOf("-", "very-cautious", "cautious", "bold", "very-bold"),
            "yacht" to setOf("yes", "no"),
            "belief" to CustomAiSpec.BELIEFS.keys + "-",
            "temper" to Temper.entries.map { it.name }.toSet(),
        )
        for (i in 1..300) for ((k, v) in traits(PlayerGenerator.spec(i))) {
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
        assertTrue(ai.desc.contains("보너스를 모름") && ai.desc.contains("멘탈 약함"), ai.desc)
        val games = LeagueMatch.play(tables.rules, ai, ev, 11)
        assertEquals(2, games.size)
        assertEquals(games, LeagueMatch.play(tables.rules, ai, ev, 11))
        // Every temper plays whole games, with each kind of mistake.
        for ((i, temper) in Temper.entries.withIndex()) {
            for (mistake in CustomMistake.entries) {
                val s = PlayerGenerator.spec(i + 1).copy(
                    id = "t$i", base = CustomBase.EV, temper = temper, mistake = mistake, amount = if (mistake == CustomMistake.FEEL) 0.5 else 0.05, belief = null,
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
}
