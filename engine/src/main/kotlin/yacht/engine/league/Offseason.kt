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
    )

    /** Seed of season [number] from the league's own seed. */
    fun seasonSeed(leagueSeed: Long, number: Int): Long = DiceStream.mix(leagueSeed, 0x5EA50, number.toLong())

    /** The first season: divisions by rating. */
    fun first(leagueSeed: Long, ctx: Context): SeasonPlan {
        val ranking = ctx.keys.sortedByDescending { ctx.rating(it) }
        val divisions = Season.split(ranking, ctx.config.divisions)
        return start(1, seasonSeed(leagueSeed, 1), divisions, emptyList(), emptyList(), ctx)
    }

    /**
     * Titles of a finished season: division champions (the title playoff's winner in division 1
     * when there was one), the regular-season leader, promotions and relegations, and every
     * competition's own honours.
     */
    fun honours(plan: SeasonPlan, moves: List<Move>): List<Honour> {
        val n = plan.number
        val out = ArrayList<Honour>()
        val league = plan.league
        for (d in league.divisions.indices) {
            val table = league.table(d)
            if (table.isEmpty()) continue
            if (d == 0) {
                out += Honour(n, "REG", table.first().key)
                val playoff = plan.competition(Comp.TITLE_PLAYOFF)?.honours()?.firstOrNull { it.kind == "L0" }
                out += playoff ?: Honour(n, "L0", table.first().key)
            } else {
                out += Honour(n, "L$d", table.first().key)
            }
        }
        for (m in moves) out += Honour(n, if (m.to < m.from) "UP${m.to}" else "DOWN${m.to}", m.key)
        // Season awards from the league tables: best average score (at least half the most
        // matches played) and most Yachts.
        val all = league.standings.values.filter { it.played > 0 }
        val minPlayed = (all.maxOfOrNull { it.played } ?: 0) / 2
        all.filter { it.played >= minPlayed }.maxByOrNull { it.avgScore }?.let { out += Honour(n, "SCORER", it.key) }
        all.maxByOrNull { it.yachts }?.takeIf { it.yachts > 0 }?.let { out += Honour(n, "YACHT", it.key) }
        // The title playoff's L0 is above (its runner-up L0R joins here); the promotion
        // playoff's winners are in the moves.
        for (c in plan.comps + plan.post) {
            when (c.comp) {
                Comp.TITLE_PLAYOFF -> out += c.honours().filter { it.kind != "L0" }
                Comp.PROMOTION_PLAYOFF -> {}
                else -> out += c.honours()
            }
        }
        return out
    }

    /** An AI moving between divisions ([from] and [to], 0 = top). */
    data class Move(val key: String, val from: Int, val to: Int)

    /**
     * Promotion and relegation: per boundary the bottom [LeagueConfig.autoSwap] go down and the
     * top as many come up; the promotion playoff's winners (honours "UP<d>" from the
     * PROMOTION_PLAYOFF competition, keyed by the AI) take the next place.
     */
    fun moves(plan: SeasonPlan): List<Move> {
        val league = plan.league
        val cfg = plan.config
        val out = ArrayList<Move>()
        val playoffWinners = plan.competition(Comp.PROMOTION_PLAYOFF)?.honours().orEmpty()
            .filter { it.kind.startsWith("UP") || it.kind.startsWith("STAY") }
        for (d in 0 until league.divisions.size - 1) {
            val upper = league.table(d).map { it.key }
            val lower = league.table(d + 1).map { it.key }
            val swap = minOf(cfg.autoSwap, upper.size / 3, lower.size / 3)
            for (k in upper.takeLast(swap)) out += Move(k, d, d + 1)
            for (k in lower.take(swap)) out += Move(k, d + 1, d)
            // Playoff: the upper side's next-from-bottom against the lower side's next-from-top.
            val upperSpot = upper.getOrNull(upper.size - swap - 1)
            val lowerSpot = lower.getOrNull(swap)
            if (upperSpot != null && lowerSpot != null && cfg.promotionPlayoff) {
                val won = playoffWinners.firstOrNull { it.key == lowerSpot && it.kind == "UP$d" }
                if (won != null) {
                    out += Move(lowerSpot, d + 1, d)
                    out += Move(upperSpot, d, d + 1)
                }
            }
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
        val moves = moves(plan)
        val honours = honours(plan, moves)
        val divisions = nextDivisions(plan, moves, ctx)
        val lastTables = plan.league.divisions.indices.map { d -> plan.league.table(d).map { it.key } }
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
        val start = SeasonStart(number, seed, ctx.config, ranking, ctx.rating, divisions, lastTables, lastHonours, ctx.family, ctx.hometown, ctx.history + lastHonours)
        val comps = ctx.factories.mapNotNull { f -> runCatching { f(start) }.getOrNull() }.toMutableList()
        return SeasonPlan(league, ctx.config, comps)
    }
}
