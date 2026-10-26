package yacht.engine.league

import yacht.engine.DiceStream

/**
 * The end of a season and the start of the next (docs/LEAGUE_SPEC.md section 5): titles,
 * promotion and relegation, the next divisions, and the next season's competitions, with hooks
 * for the other off-season steps (evolution, clubs, awards).
 */
object Offseason {
    /** What the off-season knows about the league. */
    class Context(
        /** Every AI in the league next season (me excluded). */
        val keys: List<String>,
        val rating: (String) -> Double,
        val family: (String) -> Family?,
        val hometown: (String) -> String,
        /** The config for the next season (the player may have changed it). */
        val config: LeagueConfig,
        /** Builders of the next season's competitions: each may return null (not this season). */
        val factories: List<(SeasonStart) -> Competition?> = emptyList(),
        /** Every season's honours before this rollover (oldest first). */
        val history: List<Honour> = emptyList(),
        /** AIs of the closing season that left the league during it (deleted): they place nowhere. */
        val gone: Set<String> = emptySet(),
        /** Each AI's nation code (generated and evolved players; null for the others). */
        val nation: (String) -> String? = { null },
    )

    /** Seed of season [number] from the league's own seed. */
    fun seasonSeed(leagueSeed: Long, number: Int): Long = DiceStream.mix(leagueSeed, 0x5EA50, number.toLong())

    /** The first season (numbered [number]: after any history kept): divisions by rating. */
    fun first(leagueSeed: Long, ctx: Context, number: Int = 1): SeasonPlan {
        val ranking = ctx.keys.sortedByDescending { ctx.rating(it) }
        val divisions = Season.split(ranking, ctx.config.divisions)
        return start(number, seasonSeed(leagueSeed, number), divisions, emptyList(), emptyList(), ctx)
    }

    /**
     * Titles of a finished season: division champions (the title playoff's winner in division 1
     * when there was one), the regular-season leader, promotions and relegations, and every
     * competition's own honours. AIs in [gone] (deleted during the season) get none: the next
     * in the table takes their place.
     */
    fun honours(plan: SeasonPlan, moves: List<Move>, gone: Set<String> = emptySet()): List<Honour> {
        val n = plan.number
        val out = ArrayList<Honour>()
        val league = plan.league
        for (d in league.divisions.indices) {
            val table = league.table(d).filter { it.key !in gone }
            if (table.isEmpty()) continue
            if (d == 0) {
                out += Honour(n, "REG", table.first().key)
                val playoff = plan.competition(Comp.TITLE_PLAYOFF)?.honours()?.firstOrNull { it.kind == "L0" && it.key !in gone }
                out += playoff ?: Honour(n, "L0", table.first().key)
            } else {
                out += Honour(n, "L$d", table.first().key)
            }
        }
        for (m in moves) out += Honour(n, if (m.to < m.from) "UP${m.to}" else "DOWN${m.to}", m.key)
        // Season awards from the league tables: best average score (at least half the most
        // matches played) and most Yachts.
        val all = league.standings.values.filter { it.played > 0 && it.key !in gone }
        val minPlayed = (all.maxOfOrNull { it.played } ?: 0) / 2
        all.filter { it.played >= minPlayed }.maxByOrNull { it.avgScore }?.let { out += Honour(n, "SCORER", it.key) }
        all.maxByOrNull { it.yachts }?.takeIf { it.yachts > 0 }?.let { out += Honour(n, "YACHT", it.key) }
        // The title playoff's L0 is above (its runner-up L0R joins here); the promotion
        // playoff's winners are in the moves.
        for (c in plan.comps + plan.post) {
            when (c.comp) {
                Comp.TITLE_PLAYOFF -> out += c.honours().filter { it.kind != "L0" && it.key !in gone }
                Comp.PROMOTION_PLAYOFF -> {}
                else -> out += c.honours().filter { it.key !in gone }
            }
        }
        return out
    }

    /** An AI moving between divisions ([from] and [to], 0 = top). */
    data class Move(val key: String, val from: Int, val to: Int)

    /**
     * Promotion and relegation: per boundary the bottom [LeagueConfig.autoSwap] go down and the
     * top as many come up; the promotion playoff's winners (honours "UP<d>" from the
     * PROMOTION_PLAYOFF competition, keyed by the AI) take the next place. AIs in [gone]
     * (deleted during the season) do not move; their places stay empty.
     */
    fun moves(plan: SeasonPlan, gone: Set<String> = emptySet()): List<Move> {
        val league = plan.league
        val cfg = plan.config
        val out = ArrayList<Move>()
        val promo = plan.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff
        val playoffWinners = promo?.honours().orEmpty().filter { it.kind.startsWith("UP") }
        for (d in 0 until league.divisions.size - 1) {
            // The places as the playoff drew them, from the full tables: a deleted AI's place
            // is left empty rather than passed on (the next one plays the 승강전).
            val upper = league.table(d).map { it.key }
            val lower = league.table(d + 1).map { it.key }
            val swap = minOf(cfg.autoSwap, upper.size / 3, lower.size / 3)
            for (k in upper.takeLast(swap)) if (k !in gone) out += Move(k, d, d + 1)
            for (k in lower.take(swap)) if (k !in gone) out += Move(k, d + 1, d)
            // Playoff: the tie played at this boundary; the lower side won, so the two swap.
            val spot = promo?.spots?.firstOrNull { it.division == d } ?: continue
            if (!cfg.promotionPlayoff || playoffWinners.none { it.key == spot.lower && it.kind == "UP$d" }) continue
            if (spot.lower !in gone) out += Move(spot.lower, d + 1, d)
            if (spot.upper !in gone) out += Move(spot.upper, d, d + 1)
        }
        return out
    }

    /** Next season's divisions: moves applied, departed AIs removed, newcomers at the bottom, re-split if the count changed. */
    fun nextDivisions(plan: SeasonPlan, moves: List<Move>, ctx: Context): List<List<String>> {
        val keys = ctx.keys.toSet()
        val league = plan.league
        val count = league.divisions.size
        val next = (0 until count).map { d -> league.table(d).map { it.key }.toMutableList() }
        for (m in moves) {
            next[m.from].remove(m.key)
            // Promoted AIs join the bottom of the upper table, relegated ones the top of the lower.
            if (m.to < m.from) next[m.to].add(m.key) else next[m.to].add(0, m.key)
        }
        for (d in next) d.retainAll(keys)
        val placed = next.flatten().toSet()
        val newcomers = ctx.keys.filter { it !in placed }.sortedByDescending { ctx.rating(it) }
        next.last().addAll(newcomers)
        val wanted = ctx.config.divisions
        val sizes = next.map { it.size }
        val unbalanced = sizes.isNotEmpty() && sizes.max() > 2 * maxOf(1, sizes.min())
        if (wanted != count || unbalanced) return Season.split(next.flatten(), wanted)
        return next
    }

    /** Ends [plan] and starts the next season. */
    fun next(plan: SeasonPlan, ctx: Context): Pair<SeasonPlan, List<Honour>> {
        val moves = moves(plan, ctx.gone)
        val honours = honours(plan, moves, ctx.gone)
        val divisions = nextDivisions(plan, moves, ctx)
        val lastTables = plan.league.divisions.indices.map { d -> plan.league.table(d).map { it.key }.filter { it !in ctx.gone } }
        val leagueSeed = plan.seed
        val n = plan.number + 1
        return start(n, DiceStream.mix(leagueSeed, 0x5EA51, n.toLong()), divisions, lastTables, honours, ctx) to honours
    }

    private fun start(
        number: Int,
        seed: Long,
        divisions: List<List<String>>,
        lastTables: List<List<String>>,
        lastHonours: List<Honour>,
        ctx: Context,
    ): SeasonPlan {
        val league = Season.start(number, seed, divisions, ctx.config.doubleRoundRobin)
        val ranking = ctx.keys.sortedByDescending { ctx.rating(it) }
        val start = SeasonStart(number, seed, ctx.config, ranking, ctx.rating, divisions, lastTables, lastHonours, ctx.family, ctx.hometown, ctx.history + lastHonours, ctx.nation)
        val comps = ctx.factories.mapNotNull { f -> runCatching { f(start) }.getOrNull() }.toMutableList()
        return SeasonPlan(league, ctx.config, comps)
    }
}
