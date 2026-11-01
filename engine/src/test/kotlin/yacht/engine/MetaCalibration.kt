package yacht.engine

import yacht.engine.league.Evolution
import yacht.engine.league.FamilyStats
import yacht.engine.league.Imitation
import yacht.engine.league.Meta
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerRecord
import yacht.engine.league.StyleFamily
import yacht.engine.league.WorldBook
import java.util.Locale
import java.util.Random
import kotlin.test.Test

/**
 * The meta on a synthetic world (design ENG 4.3-4.5, 1b.11 run A), the skeleton the calibration
 * stage (P21) extends to real played seasons once LeagueWorld closes them with the meta:
 * `META_PLAYERS` (1000) generated players evolve `META_SEASONS` (30) seasons twice, with the meta
 * off and on (weights, the default style cap, imitation). A season's rating is a proxy: 1100 +
 * 6 × overall + 6 × (the style's average − 188.5) + noise, so a style's quality shows as its
 * family's residual. Each nation's 16 best are its 1부; the 8 largest nations' champions and the
 * best of their 1부s (the 챔피언스 리그) are the big titles. Every 5 seasons it prints the family
 * shares of all players (and the largest over its neutral share), the weights, the era and the
 * candidate, and the rating proxy's mean and p90 on against off; at the end the eras, the share of
 * seasons inside a family's era and the median length. ENG 4.5's targets: an era in 40-70% of
 * seasons, median 3-8 seasons, no family in eras above 40% of the seasons; 1b.11's: no family
 * above 1.6 × its neutral share, the proxy's mean within 3 points of off. `META_SEED` picks the run.
 *
 * Not part of the build's tests: it runs only with `META_CALIBRATION=1` (a minute).
 */
class MetaCalibration {
    private fun styleAverage(p: PlayerRecord): Double {
        val g = p.spec.play ?: return PlayStyles.EV_AVG
        val r = PlayStyles.recipe(g.primary) ?: return PlayStyles.EV_AVG
        val second = g.secondary?.let { PlayStyles.recipe(it) }?.let { 0.4 * (PlayerGenerator.avgAt(it, 0.5) - PlayStyles.EV_AVG) } ?: 0.0
        return PlayerGenerator.avgAt(r, if (r.scales) g.k else 1.0) + second
    }

    private class Run(val book: PlayerBook, val world: WorldBook, val families: HashMap<Int, FamilyStats>, val lines: ArrayList<String>)

    private fun run(players: Int, seasons: Int, seed: Long, on: Boolean): Run {
        val book = PlayerBook().apply { ensureGenerated(players) }
        val world = WorldBook()
        val families = HashMap<Int, FamilyStats>()
        val lines = ArrayList<String>()
        for (s in 1..seasons) {
            val noise = Random(seed * 31 + s)
            val rating = HashMap<String, Double>()
            for (p in book.alive) {
                val id = p.identity ?: continue
                rating[p.key] = 1100 + 6.0 * id.abilities.overall + 6 * (styleAverage(p) - PlayStyles.EV_AVG) + 30 * noise.nextGaussian()
            }
            val byNation = book.alive.filter { it.key in rating }.groupBy { it.identity!!.nation }
            val tops = byNation.mapValues { (_, ps) -> ps.sortedByDescending { rating.getValue(it.key) }.take(16) }
            val big = tops.entries.sortedByDescending { byNation.getValue(it.key).size }.take(8)
            val fam = { k: String -> StyleFamily.of(book[k]?.spec?.play) }
            val champion = big.associate { (n, ps) -> n to ps.maxBy { rating.getValue(it.key) + 40 * noise.nextGaussian() } }
            val elite = big.flatMap { it.value }
            val cl = elite.maxBy { rating.getValue(it.key) + 40 * noise.nextGaussian() }
            val counts = { ps: List<PlayerRecord> -> StyleFamily.entries.map { f -> ps.count { fam(it.key) == f } } }
            // The families' residuals: rating against overall, one slope, shrunk as the close does.
            val all = book.alive.filter { it.key in rating && it.identity != null }
            val mx = all.map { it.identity!!.abilities.overall.toDouble() }.average()
            val my = all.map { rating.getValue(it.key) }.average()
            val beta = all.sumOf { (it.identity!!.abilities.overall - mx) * (rating.getValue(it.key) - my) } / all.sumOf { Math.pow(it.identity!!.abilities.overall - mx, 2.0) }
            val residuals = StyleFamily.entries.map { f ->
                val ps = all.filter { fam(it.key) == f }
                if (ps.isEmpty()) 0.0 else ps.sumOf { rating.getValue(it.key) - (my + beta * (it.identity!!.abilities.overall - mx)) } / ps.size * ps.size / (ps.size + Meta.SHRINK)
            }
            val fs = FamilyStats(s, counts(elite), counts(all), champion.values.map { fam(it.key) } + fam(cl.key), residuals, emptyList(), emptyMap())
            families[s] = requireNotNull(FamilyStats.decode(fs.encode().trimEnd().split(' ')))
            if (on) Meta.close(world, families, s)
            val imitation = if (on) {
                val bottom = byNation.values.flatMap { ps -> ps.sortedByDescending { rating.getValue(it.key) }.let { it.drop(it.size - it.size / 2) } }.map { it.key }.toSet()
                Imitation({ it in bottom }, { null }, { k -> book[k]?.identity?.nation?.let { champion[it] }?.spec?.play?.primary })
            } else null
            Evolution.run(
                book, s, { rating[it] ?: Double.NaN }, { 0 }, seed, players,
                metaWeights = if (on) Meta.newcomers(world.meta) else null, styleCap = if (on) 1.5 else null, imitation = imitation,
            )
            if (s % 5 == 0) {
                val alive = book.alive
                val shares = StyleFamily.entries.map { f -> alive.count { fam(it.key) == f }.toDouble() / alive.size }
                val over = StyleFamily.entries.maxOf { shares[it.ordinal] / StyleFamily.NEUTRAL.getValue(it) }
                val proxy = rating.values.sorted()
                val era = world.meta.era?.let { world.meta.name(it) } ?: "-"
                lines += String.format(
                    Locale.ROOT, "s%d %s shares %s max/b %.2f weights %s era %s cand %s proxy mean %.1f p90 %.1f",
                    s, if (on) "on" else "off", shares.joinToString(",") { String.format(Locale.ROOT, "%.3f", it) }, over,
                    StyleFamily.entries.joinToString(",") { String.format(Locale.ROOT, "%.2f", world.meta.weight(it)) }, era,
                    world.meta.candidate?.let { "${it.family?.label ?: "-"}×${it.held}" } ?: "-", proxy.average(), proxy[proxy.size * 9 / 10],
                )
            }
        }
        return Run(book, world, families, lines)
    }

    @Test
    fun theMetaOnASyntheticWorld() {
        if (System.getenv("META_CALIBRATION") != "1") return
        val players = System.getenv("META_PLAYERS")?.toInt() ?: 1000
        val seasons = System.getenv("META_SEASONS")?.toInt() ?: 30
        val seed = System.getenv("META_SEED")?.toLong() ?: 5L
        val off = run(players, seasons, seed, false)
        val on = run(players, seasons, seed, true)
        for ((a, b) in off.lines.zip(on.lines)) {
            println(a)
            println(b)
        }
        val eras = on.world.meta.eras
        for (e in eras) println("era ${e.n} ${on.world.meta.name(e)} ${e.from}-${if (e.to == 0) seasons else e.to} peak ${e.peak}")
        val inFamily = eras.filter { it.family != null }.sumOf { (if (it.to == 0) seasons else it.to) - it.from + 1 }
        val lengths = eras.filter { it.family != null }.map { (if (it.to == 0) seasons else it.to) - it.from + 1 }.sorted()
        println(String.format(Locale.ROOT, "seasons in a family's era %.0f%%, median length %s", 100.0 * inFamily / seasons, lengths.getOrNull(lengths.size / 2) ?: "-"))
        // The bytes the meta adds: world.txt's meta lines, stats.txt's kept `fm` lines (the newest
        // with a matchup for every ordered pair of two families, as a full league has), the roster's im= tokens.
        val world = on.world.encode().lines().filter { it.substringBefore(' ') in setOf("meta", "ss", "era", "cand") }.sumOf { it.length + 1 }
        val stats = yacht.engine.league.StatsBook()
        val last = on.families.keys.max()
        for (s in on.families.keys.sorted()) {
            val fs = on.families.getValue(s)
            val pairs = if (s == last) StyleFamily.entries.flatMap { f -> StyleFamily.entries.filter { it != f }.map { g -> yacht.engine.league.FamilyMatchup(f, g, 1234, 456, -12.34) } } else emptyList()
            stats.putFamilies(fs.copy(matchups = pairs, leagues = (1..28).associate { "L$it" to StyleFamily.COMBO }), Meta.kept(players))
        }
        val fm = stats.encode().lines().filter { it.startsWith("fm ") }.sumOf { it.length + 1 }
        val im = on.book.encode().lines().sumOf { l -> Regex(" im=\\S+").find(l)?.value?.length ?: 0 }
        println("bytes at $players players: world.txt meta lines $world, stats.txt fm lines $fm (${stats.families.size} kept), roster im= $im (${on.book.alive.count { it.imitations > 0 }} imitators)")
    }
}
