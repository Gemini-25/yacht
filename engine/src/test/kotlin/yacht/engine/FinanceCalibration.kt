package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubRanks
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.Nations
import yacht.engine.league.Terms
import java.util.Locale
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The club economy's bands (design ENG 1.4, 1b.1) on a synthetic world, the skeleton the calibration
 * stage (P21) extends to real played seasons once the club off-season runs them: `FINANCE_CLUBS`
 * (127) clubs of 8 members in nations of 4-6 clubs, each league ranked by the order of [Nations.ALL]
 * (λ 1.6 → 0.6), play `FINANCE_SEASONS` (40) seasons whose club tables are drawn from each club's
 * hidden strength and luck; every season runs the accounts and the club-life step, and contracts
 * end every 3 seasons and are renewed at [ClubEconomy.wageFor] (so ω has wages to pull). No transfers,
 * coaches or arcs (the market's money is P7's). Every 5 seasons it prints the clubs in the red, in
 * 재정 위기 and in 법정관리, the dissolutions, the balance and the fans against their base (p10, p50,
 * p90), the leagues' ω range and the wage share, and at the end the bytes of the money's lines in
 * clubs.txt; `FINANCE_SEED` picks the world, `FINANCE_OUT` names a CSV file for the rows. ENG 1.4's targets (per season at 1000 players): red 3-10% of clubs, trouble
 * 0.5-2.5%, administration at most one every 2 seasons.
 *
 * Not part of the build's tests: it runs only with `FINANCE_CALIBRATION=1` (seconds).
 */
class FinanceCalibration {
    @Test
    fun theEconomyOnASyntheticWorld() {
        if (System.getenv("FINANCE_CALIBRATION") != "1") return
        val clubs = System.getenv("FINANCE_CLUBS")?.toInt() ?: 127
        val seasons = System.getenv("FINANCE_SEASONS")?.toInt() ?: 40
        val seed = System.getenv("FINANCE_SEED")?.toLong() ?: 5L
        val rng = Random(seed)
        // Nations of 4-6 clubs in Nations.ALL's order, 8 members each.
        val specs = ArrayList<Eco.Spec>()
        val leagues = ArrayList<String>()
        var n = 0
        while (specs.size < clubs && n < Nations.ALL.size) {
            val nation = Nations.ALL[n++]
            leagues += nation.code
            repeat(minOf(4 + rng.nextInt(3), clubs - specs.size)) { k ->
                val i = specs.size
                specs += Eco.Spec(ClubBook.idOf(i + 1), nation.code, (0 until 8).map { "m${i}_$it" }, budget = 50 + rng.nextInt(300), honours = rng.nextInt(4), city = k % ClubNames.cities(nation).size)
            }
        }
        val strength = specs.associate { it.id to rng.nextGaussian() }
        val value = HashMap<String, Int>()
        for (c in specs) for (k in c.members) value[k] = (20 + 10 * strength.getValue(c.id) + 8 * rng.nextGaussian()).toInt().coerceAtLeast(3)
        val economy = LeagueEconomy(leagues.withIndex().associate { (i, id) -> id to LeagueEconomy.lambda(i, leagues.size) }, emptyMap())
        val book = Eco.book(specs, seed)
        val start = ClubEconomy.migrate(book, 0, economy, emptyMap(), value::getValue)
        val w = book.world!!
        for (c in specs) for (k in c.members) w.terms[k] = Terms(1 + rng.nextInt(3), start.wages.getValue(k), 0, 0, 1, 0, null, null, 0)
        val rows = ArrayList<String>()
        rows += "season,red,trouble,admin,dissolved,bal_p10,bal_p50,bal_p90,fans_p10,fans_p50,fans_p90,omega_min,omega_max,wage_share"
        var dissolved = 0
        for (s in 1..seasons) {
            val tables = leagues.associateWith { lg ->
                val ids = specs.filter { it.nation == lg }.map { it.id }
                Eco.table(lg, ids.sortedByDescending { strength.getValue(it) + rng.nextGaussian() })
            }
            val plan = Eco.plan(s, leagues.map { lg -> lg to specs.filter { it.nation == lg }.flatMap { it.members } }, seed + s)
            val input = Eco.input(book, economy)
            ClubEconomy.accounts(book, plan, input, tables)
            val life = ClubEconomy.life(book, plan, input, tables)
            ClubRanks.record(book, s, value::getValue)
            dissolved += life.dissolve.size
            // Contracts at their end are renewed at the club's offer now.
            for (c in specs) for (k in c.members) {
                val t = w.terms.getValue(k)
                if (t.until <= s) w.terms[k] = t.copy(until = s + 3, wage = ClubEconomy.wageFor(book, c.id, value.getValue(k), c.nation, economy))
            }
            if (s % 5 != 0 && s != seasons) continue
            val ids = specs.map { it.id }
            fun q(xs: List<Double>, p: Double) = xs.sorted().let { it[((it.size - 1) * p).toInt()] }
            val ratio = ids.map { id -> w.clubs.getValue(id).balance / maxOf(1.0, ClubEconomy.income(book, id).toDouble()) }
            val fans = specs.map { c ->
                w.clubs.getValue(c.id).fans / ClubEconomy.fanBase(economy.lambdaOf(c.nation), ClubEconomy.cityIndex(book.club(c.id)!!))
            }
            val omegas = leagues.map { w.omega(it) }
            val share = ids.sumOf { ClubEconomy.wageBill(book, it).toLong() }.toDouble() / ids.sumOf { ClubEconomy.income(book, it).toLong() }
            val row = listOf(
                s.toString(), ids.count { w.clubs.getValue(it).balance < 0 }.toString(), ids.count { w.clubs.getValue(it).trouble > 0 }.toString(),
                ids.count { w.clubs.getValue(it).admin > 0 }.toString(), dissolved.toString(),
            ) + listOf(q(ratio, 0.1), q(ratio, 0.5), q(ratio, 0.9), q(fans, 0.1), q(fans, 0.5), q(fans, 0.9), omegas.minOrNull() ?: 1.0, omegas.maxOrNull() ?: 1.0, share)
                .map { String.format(Locale.ROOT, "%.2f", it) }
            rows += row.joinToString(",")
            println("finance s$s: " + row.joinToString(" "))
        }
        System.getenv("FINANCE_OUT")?.let { java.io.File(it).writeText(rows.joinToString("\n", postfix = "\n")) }
        // The bytes of the money's lines in clubs.txt (ENG 8.6's budget), by kind.
        val lines = book.encode().lines()
        val bytes = listOf("cl", "co", "cp", "cd", "ac", "ah", "rk").associateWith { p -> lines.filter { it.startsWith("$p ") }.sumOf { it.toByteArray().size + 1 } }
        println("finance bytes (${specs.sumOf { it.members.size }} members, ${specs.size} clubs): $bytes, all ${bytes.values.sum()}")
        assertTrue(rows.size > 1)
    }
}
