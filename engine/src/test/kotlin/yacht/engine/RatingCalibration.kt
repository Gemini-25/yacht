package yacht.engine

import yacht.engine.league.Awards
import yacht.engine.league.Comp
import yacht.engine.league.Fixture
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueTables
import yacht.engine.league.WinEstimate
import yacht.engine.league.leagueAi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * How 평점 2.0 ([yacht.engine.league.GameFacts.rating]) spreads in a real league: DEFAULT at
 * `RATING_PLAYERS` (1000) generated players, `RATING_SEASONS` (2) seasons of real games through
 * [yacht.engine.league.LeagueWorld], printing each season's scale, the games' ratings and the
 * eligible players' season averages by tier. Not part of the build's tests: it runs only with
 * `RATING_CALIBRATION=1` (about a minute a season on 4 cores). The S15 hand-off has its numbers.
 */
class RatingCalibration {
    @Test
    fun ratingsOfARealLeague() {
        if (System.getenv("RATING_CALIBRATION") != "1") return
        val players = System.getenv("RATING_PLAYERS")?.toInt() ?: 1000
        val seasons = System.getenv("RATING_SEASONS")?.toInt() ?: 2
        val config = LeagueConfig.DEFAULT.copy(generated = players, guests = false)
        val run = WorldRun(config)
        run.w.openFirstSeason(5L)
        val tables = SolverTest.tables
        val extra = LeagueTables(tables.rules, threads = 4)
        val ais = ConcurrentHashMap<String, LeagueAi>()
        fun ai(k: String): LeagueAi? = ais[k] ?: run.w.players[k]?.let { r -> leagueAi(r, tables, extra).also { ais[k] = it } }
        val estimate = WinEstimate(tables)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(seasons) {
                val scale = run.w.stats.ratingScale
                while (!run.w.seasonOver) {
                    // Jobs whose players are free, played side by side, recorded in order.
                    val busy = HashSet<String>()
                    val batch = ArrayList<Fixture>()
                    var cups = 0
                    while (batch.size < 48) {
                        val f = run.w.next(busy, cups, 8) ?: break
                        batch += f
                        busy += f.players
                        if (f.comp != Comp.LEAGUE) cups++
                    }
                    check(batch.isNotEmpty()) { "stuck" }
                    val format = run.w.plan!!.config.matchFormat
                    for (r in batch.map { f -> pool.submit<JobResult> { LeagueMatch.playJob(tables.rules, f, ::ai, format, estimate)!! } }.map { it.get() }) run.w.recordJob(r)
                }
                report(run, if (scale == run.w.stats.ratingScale) "$scale" else "measured as it went, now ${run.w.stats.ratingScale}")
                run.beforeClose()
                run.rollover(config)
            }
        } finally {
            pool.shutdown()
        }
    }

    private fun report(run: WorldRun, scale: String) {
        val p = run.w.plan!!
        val league = p.league
        val stats = run.w.stats
        println("season ${p.number}: scale $scale")
        val all = league.standings.keys.mapNotNull { k -> stats.season(k)?.let { k to it } }
        println("  games' average ${"%.3f".format(all.sumOf { it.second.ratingSum } / all.sumOf { it.second.ratingCount })}")
        fun eligible(k: String) = league.standings.getValue(k).let { it.played > 0 && it.played >= Awards.MVP_SHARE * league.fixturesOf(k) }
        val averages = all.filter { (k, s) -> eligible(k) && !s.avgRating.isNaN() }.map { (k, s) -> league.tierOf(k) to s.avgRating }
        for (tier in averages.map { it.first }.distinct().sortedBy { it ?: -1 } + listOf<Int?>(-2)) {
            val xs = averages.filter { tier == -2 || it.first == tier }.map { it.second }.sorted()
            if (xs.isEmpty()) continue
            val m = xs.average()
            val sd = sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
            val name = if (tier == -2) "all" else "${(tier ?: 0) + 1}부"
            println("  $name: ${xs.size} players, mean ${"%.2f".format(m)}, SD ${"%.3f".format(sd)}, ${"%.2f".format(xs.first())}..${"%.2f".format(xs.last())} (p10 ${"%.2f".format(xs[xs.size / 10])}, p90 ${"%.2f".format(xs[xs.size * 9 / 10])})")
        }
    }
}
