package yacht.engine

import yacht.engine.league.Abilities
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubNames
import yacht.engine.league.Coach
import yacht.engine.league.Evolution
import yacht.engine.league.Gender
import yacht.engine.league.GrowthSpeed
import yacht.engine.league.Nations
import yacht.engine.league.Philosophy
import yacht.engine.league.PlayerBook
import yacht.engine.league.Profiles
import yacht.engine.league.Terms
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Growth speed (design ENG 1b.11): speed 1 is today's growth, the nudges are zero-sum per nation and bounded, ceilings and decline never move. */
class GrowthSpeedTest {
    /**
     * 20 seasons of evolution of 1000 generated players (seed 5, ratings from the abilities), the
     * roster's encoding hashed. [speed] gives a season's speeds (null: the default parameter).
     */
    private fun roster(speed: ((PlayerBook, Int) -> (String) -> Double)?): String {
        val book = PlayerBook().apply { ensureGenerated(1000) }
        for (s in 1..20) {
            val r = book.alive.associate { p ->
                val id = p.identity
                p.key to if (id == null) Double.NaN else 1100.0 + 6.0 * id.abilities.overall + (Math.floorMod(DiceStream.mix(p.key.hashCode().toLong(), s.toLong()), 200L) - 100)
            }
            if (speed == null) {
                Evolution.run(book, s, { r[it] ?: Double.NaN }, { 0 }, 5L, 1000)
            } else {
                Evolution.run(book, s, { r[it] ?: Double.NaN }, { 0 }, 5L, 1000, speed = speed(book, s))
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(book.encode().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun speedOneIsTodaysGrowthOverTwentySeasons() {
        // Measured on 3ce4b93's engine sources (the same run, before growth speed existed).
        val golden = "cdf8ea864a3d07b88e24171b82b70b31937b7e3d47a88f2792cdb2b2aced28bc"
        assertEquals(golden, roster(null))
        assertEquals(golden, roster { _, _ -> { 1.0 } })
        // A book without a club world gives every player 1.0.
        val none = ClubBook.decode("clubs 2\nmeta 1 0 - 100\n")
        assertEquals(golden, roster { p, s -> GrowthSpeed.of(p, s, none) })
    }

    /**
     * A probe-shaped world: 1000 generated players in clubs of about 8 of their own nation, levels
     * and coaches' development skill drawn from [levels], a quarter of the young players graduates of
     * their club's academy.
     */
    private fun world(levels: IntRange, seed: Long = 3L): Pair<PlayerBook, ClubBook> {
        val players = PlayerBook().apply { ensureGenerated(1000) }
        val byNation = players.alive.groupBy { it.identity!!.nation }.toSortedMap()
        val specs = ArrayList<Eco.Spec>()
        var n = 1
        for ((nation, ps) in byNation) {
            val clubs = (ps.size + 7) / 8
            val cities = ClubNames.cities(Nations.of(nation)!!).size
            for (k in 0 until clubs) {
                specs += Eco.Spec(ClubBook.idOf(n++), nation, ps.filterIndexed { i, _ -> i % clubs == k }.map { it.key }, city = k % cities)
            }
        }
        val book = Eco.world(Eco.book(specs))
        val w = book.world!!
        val rng = Random(seed)
        fun level() = levels.first + rng.nextInt(levels.last - levels.first + 1)
        for ((i, c) in book.clubs.withIndex()) {
            val id = "k${i + 1}"
            w.state(c.id).apply {
                training = level()
                academy = level()
                coach = id
            }
            w.coaches.active[id] = Coach(id, "감독 $id", c.nation, Gender.MALE, -50, Philosophy.STABLE, level(), 50, c.id, 1, 2, 20, null, 0, 0, 0, 0, emptyList(), 0)
            for (k in c.squad) if (players[k]!!.age(1) <= 21 && rng.nextInt(4) == 0) w.terms[k] = Terms(3, 10, 0, 0, 1, 0, null, c.id, 1)
        }
        return players to book
    }

    @Test
    fun theNudgesAreZeroSumPerNationBeforeTheClamp() {
        val (players, book) = world(2..4)
        val speeds = GrowthSpeed.of(players, 1, book)
        assertEquals(0, speeds.clamped)
        assertEquals(1000, speeds.keys.size)
        // Σ g (speed − 1) = 0 in every nation (up to rounding), with g each player's expected growth.
        val sums = HashMap<String, Double>()
        for (p in players.alive) {
            val id = p.identity!!
            val nation = book.clubOf(p.key)?.let { book.nationOf(it) } ?: id.nation
            sums.merge(nation, GrowthSpeed.growth(id, p.key, id.age(1, p.born)) * (speeds(p.key) - 1), Double::plus)
        }
        assertTrue(sums.size > 10, "nations: ${sums.size}")
        for ((nation, sum) in sums) assertTrue(abs(sum) < 1e-9 * maxOf(1.0, speeds.growth.getValue(nation)), "$nation: $sum")
        // Someone moves: the levels differ between clubs.
        assertTrue(players.alive.any { abs(speeds(it.key) - 1) > 0.02 })
        // A graduate of its own club's academy grows by the academy's term more than a teammate in its
        // first 3 seasons (both have the club's coach and training, and one nation mean), not after.
        val club = book.clubs.first { c -> c.squad.any { book.terms(it)?.academy == c.id } && c.squad.any { book.terms(it) == null } }.id
        book.world!!.state(club).academy = 5
        val grad = book.members(club).first { book.terms(it)?.academy == club }
        val mate = book.members(club).first { book.terms(it) == null }
        for (season in 1..3) {
            val sp = GrowthSpeed.of(players, season, book)
            assertEquals(0.04, sp(grad) - sp(mate), 1e-12)
        }
        val after = GrowthSpeed.of(players, 4, book)
        assertEquals(after(mate), after(grad), 1e-12)
        // A nudge is its three terms: 0.03 a development point, 0.02 a training or academy level, around 3.
        assertEquals(0.03 * 2 + 0.02 * -2 + 0.02 * 1, GrowthSpeed.nudge(5, 1, 4), 1e-12)
        assertEquals(0.0, GrowthSpeed.nudge(null, null, null))
    }

    @Test
    fun everySpeedIsWithinTheClampAndAClampBindsOnlyOnOutliers() {
        val (players, book) = world(1..5)
        val speeds = GrowthSpeed.of(players, 1, book)
        for (p in players.alive) assertTrue(speeds(p.key) in 0.88..1.12, "${p.key}: ${speeds(p.key)}")
        // Neutral levels everywhere: every speed is exactly 1.
        val (p3, b3) = world(3..3)
        val neutral = GrowthSpeed.of(p3, 1, b3)
        assertTrue(p3.alive.all { neutral(it.key) == 1.0 })
        // One nation whose growth sits almost wholly with a +0.14 player: the −0.14 one is clamped.
        val s = GrowthSpeed.of(listOf(GrowthSpeed.Entry("a", "KR", 0.14, 100.0), GrowthSpeed.Entry("b", "KR", -0.14, 0.01), GrowthSpeed.Entry("c", "JP", 0.05, 0.0)))
        assertEquals(1, s.clamped)
        assertEquals(0.88, s("b"), 1e-12)
        assertTrue(s("a") > 1.0 && s("a") < 1.001)
        // A nation where nobody grows is centred on its plain mean; a key without a speed reads 1.
        assertEquals(1.0, s("c"))
        assertEquals(1.0, s("nobody"))
        // The order the entries come in changes nothing.
        val es = (1..50).map { GrowthSpeed.Entry("p$it", if (it % 3 == 0) "JP" else "KR", (it % 7 - 3) * 0.02, (it % 5) * 1.7) }
        val a = GrowthSpeed.of(es)
        val b = GrowthSpeed.of(es.reversed())
        assertTrue(es.all { a(it.key) == b(it.key) })
    }

    @Test
    fun ceilingsAndTheDeclineNeverMove() {
        // Past every peak (the decline and the plateau): the speed changes nothing at all.
        val key = "g042"
        val old = Abilities(70, 65, 60, 55)
        for (age in 33..39) {
            assertEquals(Profiles.grow(old, 80, key, age, 1.0, Random(9L + age)), Profiles.grow(old, 80, key, age, 1.0, Random(9L + age), 1.12))
            assertEquals(Profiles.grow(old, 80, key, age, 0.5, Random(9L + age)), Profiles.grow(old, 80, key, age, 0.5, Random(9L + age), 0.88))
        }
        // Before the peak a faster player grows at least as fast, and never past its ceiling + 4.
        var slow = Abilities(30, 30, 30, 30)
        var fast = slow
        for (age in 17..32) {
            slow = Profiles.grow(slow, 85, key, age, 1.0, Random(100L + age))
            fast = Profiles.grow(fast, 85, key, age, 1.0, Random(100L + age), 1.12)
            for (i in 0 until 4) assertTrue(fast.toList()[i] <= maxOf(30, Profiles.ceiling(85, key, i) + 4))
        }
        assertTrue(fast.toList().sum() >= slow.toList().sum() - 4, "$fast vs $slow")
        // Expected growth is 0 for a player past every peak, positive for a young one with room.
        val id = PlayerBook().apply { ensureGenerated(1) }.alive.single()
        assertEquals(0.0, GrowthSpeed.growth(id.identity!!, id.key, 36))
        assertTrue(GrowthSpeed.growth(id.identity!!.copy(abilities = Abilities(20, 20, 20, 20)), id.key, 19) > 0)
    }
}
