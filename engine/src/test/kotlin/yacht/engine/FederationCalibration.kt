package yacht.engine

import yacht.engine.league.Comp
import yacht.engine.league.Federation
import yacht.engine.league.FederationStats
import yacht.engine.league.Fixture
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueTables
import yacht.engine.league.Lever
import yacht.engine.league.Proposal
import yacht.engine.league.ProposalKind
import yacht.engine.league.WinEstimate
import yacht.engine.league.leagueAi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 요트협회's floors ([ProposalKind]) on real seasons: DEFAULT at `FEDERATION_PLAYERS` (1000)
 * generated players plays `FEDERATION_SEASONS` (10) seasons of real games through
 * [yacht.engine.league.LeagueWorld], the committee's adopted changes applied at every rollover as
 * the app applies them. Each season prints its statistics, every kind's pressure (also short of
 * its floor), the proposals tabled after it and the decisions on the ones before; the end checks
 * what the floors were calibrated for (docs/LEAGUE_SPEC.md 11.4): at least [ONE_SHARE] of the
 * assemblies table a proposal, none more than [Federation.MOST], no lever proposed in more than
 * half the seasons and no kind in every one. `FEDERATION_OFF=1` keeps the rules as they are (the
 * statistics of the rules alone, the calibration's input); `FEDERATION_RULES` starts from other
 * rules ("longKnockouts=0,autoSwap=3", as config.txt writes them); `FEDERATION_SEED` picks the
 * league; `FEDERATION_OUT` names a file for the statistics as CSV.
 *
 * Not part of the build's tests: it runs only with `FEDERATION_CALIBRATION=1` (about 40 s a
 * season on 4 cores). docs/LEAGUE_SPEC.md 11.4 and the S20 hand-off have its numbers.
 */
class FederationCalibration {
    @Test
    fun theFederationOnRealSeasons() {
        if (System.getenv("FEDERATION_CALIBRATION") != "1") return
        val players = System.getenv("FEDERATION_PLAYERS")?.toInt() ?: 1000
        val seasons = System.getenv("FEDERATION_SEASONS")?.toInt() ?: 10
        val seed = System.getenv("FEDERATION_SEED")?.toLong() ?: 5L
        val on = System.getenv("FEDERATION_OFF") != "1"
        // Other rules to start from, as "key=value,..." in config.txt's terms (the statistics under other lever values).
        val rules = System.getenv("FEDERATION_RULES").orEmpty().split(',').filter { '=' in it }
        val config = rules.fold(LeagueConfig.DEFAULT.copy(generated = players, guests = false, federation = on)) { c, kv ->
            c.with(kv.substringBefore('=').trim(), kv.substringAfter('=').trim()) ?: error("no such rule: $kv")
        }
        val run = WorldRun(config)
        run.w.openFirstSeason(seed)
        val tables = SolverTest.tables
        val extra = LeagueTables(tables.rules, threads = 4)
        val ais = ConcurrentHashMap<String, LeagueAi>()
        fun ai(k: String): LeagueAi? = ais[k] ?: run.w.players[k]?.let { r -> leagueAi(r, tables, extra).also { ais[k] = it } }
        val estimate = WinEstimate(tables)
        val pool = Executors.newFixedThreadPool(4)
        val rows = ArrayList<Row>()
        val csv = System.getenv("FEDERATION_OUT")?.let(::File)
        csv?.writeText(Row.HEADER + "\n")
        try {
            repeat(seasons) {
                val t0 = System.currentTimeMillis()
                var recorded = 0
                while (!run.w.seasonOver) {
                    // Jobs whose players are free, played side by side, recorded in order (as RatingCalibration).
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
                    for (r in batch.map { f -> pool.submit<JobResult> { LeagueMatch.playJob(tables.rules, f, ::ai, format, estimate)!! } }.map { it.get() }) {
                        run.w.recordJob(r)
                        // The app refits the whole ladder every few seconds; about as often here.
                        if (++recorded % 1500 == 0) run.w.ladder.fit()
                    }
                }
                val n = run.w.plan!!.number
                run.beforeClose()
                run.rollover()
                val f = run.w.federation
                val stats = f.stats(n) ?: error("no statistics of season $n")
                val row = Row(n, stats, Federation.candidates(stats, run.rules), f.of(n), f.of(n - 1), (System.currentTimeMillis() - t0) / 1000)
                rows += row
                println(row.text())
                csv?.appendText(row.csv() + "\n")
            }
        } finally {
            pool.shutdown()
        }
        report(rows, on)
    }

    /** Season [season]'s statistics, the [candidates] past their floors, P(season) [tabled] at the next open and P(season - 1) [decided] at its close. */
    private class Row(
        val season: Int, val stats: FederationStats, val candidates: List<Proposal>, val tabled: List<Proposal>, val decided: List<Proposal>, val seconds: Long,
    ) {
        fun text(): String = buildString {
            val s = stats
            append("season $season (${seconds}s) lv=${s.levers.joinToString("/")}\n")
            append("  elevator ${s.bounced}/${s.promoted} = ${pct(s.elevator)}, champPpm ${s.champPpm ?: "-"}, leaderLost ${s.leaderLost}/${s.playoffLeagues} = ${pct(s.leaderLostShare)}, ")
            append("runaway ${s.runaway}/${s.leagues} = ${pct(s.runawayShare)}, sweep ${s.sweeps}/${s.decided} = ${pct(s.sweepShare)}, rounds ${s.rounds} (proj ${s.projected} / legacy ${s.legacy}), ")
            append("koUpsets ${s.koUpsets}/${s.koTies} = ${pct(s.upsetShare)}, clEdge ${f2(s.clEdge)} (${s.champPoints}/${s.champMatches} vs ${s.cclPoints}/${s.cclMatches})\n")
            append("  pressure " + ProposalKind.entries.joinToString(" ") { k -> "${k.name}=${f2(Federation.pressure(k, x(k, s)))}" } + "\n")
            append("  candidates " + candidates.joinToString(" ") { "${it.kind.name}:${f2(it.pressure)}" }.ifEmpty { "-" } + "\n")
            append("  tabled " + tabled.joinToString(" ") { "${it.kind.name}:${f2(it.pressure)}" }.ifEmpty { "-" } + "\n")
            append("  decided " + decided.joinToString(" ") { "${it.kind.name}:${it.result}${it.by}${it.yes}/${it.no}" }.ifEmpty { "-" })
        }

        fun csv(): String {
            val s = stats
            return listOf(
                season, s.levers.joinToString("/"), s.promoted, s.bounced, s.champPpm ?: "", s.playoffLeagues, s.leaderLost, s.leagues, s.runaway,
                s.decided, s.sweeps, s.rounds, s.projected, s.legacy, s.koTies, s.koUpsets, s.champPoints, s.champMatches, s.cclPoints, s.cclMatches,
                candidates.joinToString(" ") { it.kind.name }, tabled.joinToString(" ") { it.kind.name }, decided.joinToString(" ") { "${it.kind.name}:${it.result}" },
            ).joinToString(",")
        }

        companion object {
            const val HEADER = "season,levers,promoted,bounced,champPpm,playoffLeagues,leaderLost,leagues,runaway,decided,sweeps,rounds,projected,legacy," +
                "koTies,koUpsets,champPoints,champMatches,cclPoints,cclMatches,candidates,tabled,decided"
        }
    }

    private fun report(rows: List<Row>, on: Boolean) {
        val n = rows.size
        println("summary over $n seasons:")
        for (k in ProposalKind.entries) {
            println("  ${k.name}: past its floor ${rows.count { r -> r.candidates.any { it.kind == k } }}, tabled ${rows.count { r -> r.tabled.any { it.kind == k } }}")
        }
        for (l in Lever.entries) println("  lever ${l.key}: proposed in ${rows.count { r -> r.tabled.any { it.lever == l } }} seasons")
        val sizes = rows.map { it.tabled.size }
        println("  tabled per assembly: $sizes; adopted ${rows.sumOf { r -> r.decided.count { it.result == Proposal.YES } }} of ${rows.sumOf { it.decided.size }} decided")
        println("  2 or more tabled in ${sizes.count { it >= Federation.TABLED }} of $n, none in ${sizes.count { it == 0 }}")
        if (!on || n < 4) return
        // What the floors were calibrated for (docs/LEAGUE_SPEC.md 11.4): most assemblies table
        // something, none more than 3, no lever in more than half the seasons, no kind in every one.
        assertTrue(sizes.count { it >= 1 } >= ONE_SHARE * n, "1 or more tabled in at least ${(ONE_SHARE * 100).toInt()}% of the assemblies: $sizes")
        assertTrue(sizes.all { it <= Federation.MOST })
        for (l in Lever.entries) assertTrue(rows.count { r -> r.tabled.any { it.lever == l } } * 2 <= n, "lever ${l.key} proposed in more than half the seasons")
        for (k in ProposalKind.entries) assertTrue(rows.count { r -> r.tabled.any { it.kind == k } } < n, "${k.name} tabled every season")
    }

    companion object {
        /**
         * Assemblies that should table at least one proposal (about two in three did in the
         * calibration runs, quiet seasons none). ENG 6.4 asked for 2 in 80%; with four
         * statistics that tell seasons apart and none of them past its floor in more than half
         * the seasons, two or more come in about two in five (docs/LEAGUE_SPEC.md 11.4).
         */
        const val ONE_SHARE = 0.5

        /** The statistic of [kind] in [s] (as [Federation.candidates] reads it). */
        fun x(kind: ProposalKind, s: FederationStats): Double = when (kind) {
            ProposalKind.SWAP_DOWN, ProposalKind.SWAP_UP -> s.elevator
            ProposalKind.TITLE_PO_OFF -> s.leaderLostShare
            ProposalKind.TITLE_PO_ON -> s.runawayShare
            ProposalKind.SWEEP_OFF, ProposalKind.SWEEP_ON -> s.sweepShare
            ProposalKind.SIZE_DOWN, ProposalKind.SIZE_UP -> s.rounds.toDouble()
            ProposalKind.LONG_KO_ON, ProposalKind.LONG_KO_OFF -> s.upsetShare
            ProposalKind.CL_DIRECT_UP, ProposalKind.CL_DIRECT_DOWN -> s.clEdge
        }

        private fun pct(x: Double) = String.format(Locale.ROOT, "%.0f%%", 100 * x)
        private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
    }
}
