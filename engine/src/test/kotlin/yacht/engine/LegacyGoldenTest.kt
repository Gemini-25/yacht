package yacht.engine

import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Pyramid
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Tiers
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A legacy season (one league: a regional pyramid or linear divisions) is built, played and saved
 * byte for byte as at 945de5e, before national leagues: the same fixtures, ids, deals, labels,
 * weeks, tables and `season 4` text. The hashes were taken from that version's code.
 */
class LegacyGoldenTest {
    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Plays [share] of the fixtures with seeded games (sweeps, splits, draws, yachts), one left in progress. */
    private fun play(season: Season, share: Double, seed: Long) {
        val rng = Random(seed)
        val target = (season.fixturesTotal * share).toInt()
        var played = 0
        while (played < target) {
            val f = season.next(emptySet()) ?: break
            val games = List(2) {
                val a = 120 + rng.nextInt(200)
                val b = if (rng.nextInt(9) == 0) a else 120 + rng.nextInt(200)
                GameScore(a, b, aYacht = rng.nextInt(7) == 0, bYacht = rng.nextInt(7) == 0, aBonus = rng.nextBoolean(), bBonus = rng.nextBoolean())
            }
            assertTrue(season.record(f.id, games))
            played++
        }
        season.next(emptySet())
    }

    /** The season's save, plus every fixture as a job (deal seed, label, week) and the calendar counts. */
    private fun fingerprint(season: Season): String = buildString {
        append(season.encode())
        for (id in 0 until season.fixturesTotal) {
            val j = season.job(season.fixture(id))
            append("${j.id} ${j.a} ${j.b} ${j.seed} ${j.label} ${j.week}\n")
        }
        append(season.matchdayCounts().joinToString(",") { "${it.played}/${it.total}" }).append('\n')
        append(season.playersOfWeek(0 until season.matchdays).values.joinToString(",") { "${it.matchday}:${it.key}" }).append('\n')
    }

    @Test
    fun aPyramidOf1001PlayersIsBuiltPlayedAndSavedAsBefore() {
        val keys = (0..1000).map { "g%04d".format(it) }
        val config = LeagueConfig()
        val shape = Pyramid.shape(keys.size, config)!!
        val sizes = Pyramid.sizes(keys.size, shape)
        var at = 0
        val divisions = sizes.map { n -> keys.subList(at, at + n).also { at += n } }
        val regions = listOf("", "한국", "유럽", "아시아·유럽", "아메리카")
        val tiers = Tiers.of(shape, List(sizes.size) { regions[it % regions.size] })
        val plan = SeasonPlan(Season.start(3, 0x5EED_1001L, divisions, false, tiers), config)
        val season = plan.league
        play(season, 0.6, 17L)
        val text = season.encode()
        assertTrue(text.startsWith("season 4\n"))
        assertEquals(text, Season.decode(text, keys).encode(), "a round trip")
        assertEquals(PYRAMID, sha(fingerprint(season)))
    }

    @Test
    fun aDoubleRoundRobinOfLinearDivisionsIsBuiltPlayedAndSavedAsBefore() {
        val keys = (0 until 61).map { "d%02d".format(it) }
        // Odd and even divisions, the two-game total (no sweep bonus), one empty division dropped.
        val divisions = Season.split(keys, 4).let { it.take(2) + listOf(emptyList()) + it.drop(2) }
        val plan = SeasonPlan(Season.start(8, 424_242L, divisions, true), LeagueConfig(matchFormat = MatchFormat.MIRRORED))
        val season = plan.league
        assertEquals(4, season.divisions.size)
        play(season, 0.75, 5L)
        val text = season.encode()
        assertTrue(text.startsWith("season 4\n"))
        assertEquals(text, Season.decode(text, keys).encode(), "a round trip")
        assertEquals(LINEAR, sha(fingerprint(season)))
    }

    private companion object {
        const val PYRAMID = "910b5e1e3e5cf12a38fffaf7dacd3b1fcd1e4dd2eb5f7085618da1aa35304872"
        const val LINEAR = "5e9af6cdf98ee0dd2f5fe92c387bca8bba2662b5c542404a5ef5669c6a9ab808"
    }
}
