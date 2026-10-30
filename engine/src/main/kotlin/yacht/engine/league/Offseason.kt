package yacht.engine.league

import yacht.engine.DiceStream

/**
 * The end of a season and the start of the next (docs/LEAGUE_SPEC.md section 5): titles,
 * promotion and relegation, the next divisions, and the next season's competitions, with hooks
 * for the other off-season steps (evolution, clubs, awards). A season of national leagues
 * ([LeagueConfig.national], [NationalLeagues]) has all of it per league, a legacy season (one
 * league of divisions or a regional pyramid) as before them. A national season is always
 * followed by a national one; a legacy one by national leagues once the config asks for them.
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
        /** The nation each AI plays in next season: its club's, else its [nation] ([NationalLeagues.home]). */
        val playNation: (String) -> String? = nation,
        /** AIs that moved to a foreign league this off-season (해외 이적): they start in its 1부. */
        val transfersIn: Set<String> = emptySet(),
        /** AIs that came home this off-season (귀국): the 1부 when rated at least its median, else the 2부. */
        val returned: Set<String> = emptySet(),
        /** The league ranking after the closing season, which the next season is played under (null: none yet). */
        val ranking: LeagueRanking? = null,
    )

    /** Seed of season [number] from the league's own seed. */
    fun seasonSeed(leagueSeed: Long, number: Int): Long = DiceStream.mix(leagueSeed, 0x5EA50, number.toLong())

    /**
     * The first season (numbered [number]: after any history kept), by rating: national leagues
     * ([NationalLeagues.first]) when the config has them, else divisions (or the pyramid's tiers).
     */
    fun first(leagueSeed: Long, ctx: Context, number: Int = 1): SeasonPlan {
        val ranking = ctx.keys.sortedByDescending { ctx.rating(it) }
        val seed = seasonSeed(leagueSeed, number)
        if (ctx.config.national) {
            val (divisions, leagues) = NationalLeagues.first(ranking, ctx)
            return start(number, seed, divisions, leagues, null, emptyList(), null, emptyList(), ctx)
        }
        val shape = Pyramid.shape(ranking.size, ctx.config)
        val (divisions, tiers) = if (shape == null) Season.split(ranking, ctx.config.divisions) to null else Pyramid.first(ranking, ctx, shape)
        return start(number, seed, divisions, null, tiers, emptyList(), null, emptyList(), ctx)
    }

    /**
     * Titles of a finished season: division champions (the title playoff's winner in a league's
     * 1부 when there was one), the regular-season leader, promotions and relegations, and every
     * competition's own honours. AIs in [gone] (deleted during the season) get none: the next
     * in the table takes their place. Division honours are by tier: "L2" is a 3부 title, one
     * per group in a pyramid, and moves are into a tier. A national season's carry their league
     * ([HonourKind]: "REG.KR", "L0.KR", "L1.KR"), and its moves are no honours (the season lines
     * and the close's news tell them).
     */
    fun honours(plan: SeasonPlan, moves: List<Move>, gone: Set<String> = emptySet()): List<Honour> {
        val n = plan.number
        val out = ArrayList<Honour>()
        val league = plan.league
        val leagues = league.leagues
        val playoff = plan.competition(Comp.TITLE_PLAYOFF)?.honours().orEmpty()
        for (d in league.divisions.indices) {
            val table = league.table(d).filter { it.key !in gone }
            if (table.isEmpty()) continue
            val lg = leagues.of(d)
            if (d == lg.top) {
                out += Honour(n, HonourKind.of("REG", lg.id), table.first().key)
                val champion = HonourKind.of("L0", lg.id)
                out += playoff.firstOrNull { it.kind == champion && it.key !in gone } ?: Honour(n, champion, table.first().key)
            } else {
                out += Honour(n, HonourKind.of("L${leagues.tier(d)}", lg.id), table.first().key)
            }
        }
        if (!leagues.national) for (m in moves) out += Honour(n, if (m.to < m.from) "UP${m.to}" else "DOWN${m.to}", m.key)
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
                Comp.TITLE_PLAYOFF -> out += c.honours().filter { HonourKind.base(it.kind) != "L0" && it.key !in gone }
                Comp.PROMOTION_PLAYOFF -> {}
                else -> out += c.honours().filter { it.key !in gone }
            }
        }
        return out
    }

    /** An AI moving between tiers ([from] and [to], 0 = top; a linear league's divisions) of its [league] ("" the legacy league). */
    data class Move(val key: String, val from: Int, val to: Int, val league: String = LeagueIds.LEGACY)

    /**
     * Promotion and relegation: per league, per boundary the places of [Pyramid.crossing] (a
     * linear league: the bottom [LeagueConfig.autoSwap] go down and the top as many come up);
     * the promotion playoff's winners (honours "UP<t>" from the PROMOTION_PLAYOFF competition,
     * keyed by the AI, of that league's ties) take the next place. AIs in [gone] (deleted during
     * the season) do not move; their places stay empty.
     */
    fun moves(plan: SeasonPlan, gone: Set<String> = emptySet()): List<Move> {
        val league = plan.league
        val cfg = plan.config
        val out = ArrayList<Move>()
        val promo = plan.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff
        val playoffWinners = promo?.honours().orEmpty().filter { it.kind.startsWith("UP") }
        // Table places, for the order of the moves (each division's own order, as tables run).
        val place = HashMap<String, Int>()
        for (d in league.divisions.indices) for ((i, s) in league.table(d).withIndex()) place[s.key] = d * 100_000 + i
        for (lg in league.leagues.list) {
            for (t in 0 until lg.tiers.count - 1) {
                // The places as the playoff drew them, from the full tables: a deleted AI's place
                // is left empty rather than passed on (the next one plays the 승강전).
                val c = Pyramid.crossing(league, cfg, lg, t)
                for (k in c.down.sortedBy { place[it] }) if (k !in gone) out += Move(k, t, t + 1, lg.id)
                for (k in c.up) if (k !in gone) out += Move(k, t + 1, t, lg.id)
                // Playoff: the ties played at this boundary; where the lower side won, the two swap.
                if (!cfg.promotionPlayoff) continue
                for (spot in promo?.spots.orEmpty().filter { it.league == lg.id && it.division == t }) {
                    if (playoffWinners.none { it.key == spot.lower && it.kind == "UP$t" }) continue
                    if (spot.lower !in gone) out += Move(spot.lower, t + 1, t, lg.id)
                    if (spot.upper !in gone) out += Move(spot.upper, t, t + 1, lg.id)
                }
            }
        }
        return out
    }

    /**
     * Next season's divisions and their tiers of a legacy league: a pyramid's ([Pyramid.next])
     * when the league is big enough for one ([Pyramid.shape]), else divisions with the moves
     * applied, departed AIs removed, newcomers at the bottom, re-split if the count changed.
     * National leagues have [NationalLeagues.next].
     */
    fun nextDivisions(plan: SeasonPlan, moves: List<Move>, ctx: Context): Pair<List<List<String>>, Tiers?> {
        val league = plan.league
        val shape = Pyramid.shape(ctx.keys.size, ctx.config)
        if (shape != null) return Pyramid.next(league, moves, ctx, shape)
        if (!flat(league).linear) {
            // From a pyramid back to divisions: its tiers in order, then split.
            val (lists, _) = Pyramid.next(league, moves, ctx, listOf(1))
            return Season.split(lists.flatten(), ctx.config.divisions) to null
        }
        return linearDivisions(plan, moves, ctx) to null
    }

    private fun linearDivisions(plan: SeasonPlan, moves: List<Move>, ctx: Context): List<List<String>> {
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

    /**
     * Ends [plan] and starts the next season: national leagues ([NationalLeagues.next]) after a
     * national season or when the config asks for them, else the legacy league's divisions.
     */
    fun next(plan: SeasonPlan, ctx: Context): Pair<SeasonPlan, List<Honour>> {
        val moves = moves(plan, ctx.gone)
        val honours = honours(plan, moves, ctx.gone)
        val lastTables = plan.league.divisions.indices.map { d -> plan.league.table(d).map { it.key }.filter { it !in ctx.gone } }
        val leagueSeed = plan.seed
        val n = plan.number + 1
        val seed = DiceStream.mix(leagueSeed, 0x5EA51, n.toLong())
        if (plan.league.leagues.national || ctx.config.national) {
            val (divisions, leagues) = NationalLeagues.next(plan, moves, ctx)
            return start(n, seed, divisions, leagues, null, lastTables, plan.league, honours, ctx) to honours
        }
        val (divisions, tiers) = nextDivisions(plan, moves, ctx)
        return start(n, seed, divisions, null, tiers, lastTables, plan.league, honours, ctx) to honours
    }

    /**
     * A season of [divisions] in national [leagues], or (null) in one legacy league of [tiers]
     * (null: one division a tier), with its competitions; [last] is the season before.
     */
    private fun start(
        number: Int,
        seed: Long,
        divisions: List<List<String>>,
        leagues: Leagues?,
        tiers: Tiers?,
        lastTables: List<List<String>>,
        last: Season?,
        lastHonours: List<Honour>,
        ctx: Context,
    ): SeasonPlan {
        val double = ctx.config.doubleRoundRobin
        val league = if (leagues != null) Season.start(number, seed, divisions, double, leagues) else Season.start(number, seed, divisions, double, tiers)
        val ranking = ctx.keys.sortedByDescending { ctx.rating(it) }
        val leagueRanking = ctx.ranking ?: LeagueRanking.EMPTY
        val start = SeasonStart(
            number, seed, ctx.config, ranking, ctx.rating, league.divisions, lastTables, lastHonours, ctx.family, ctx.hometown, ctx.history + lastHonours, ctx.nation,
            flat(league), last?.let(::flat) ?: Tiers.linear(lastTables.size),
            league.leagues, last?.leagues ?: Leagues.legacy(Tiers.linear(lastTables.size)), leagueRanking,
        )
        val comps = ctx.factories.mapNotNull { f -> runCatching { f(start) }.getOrNull() }.toMutableList()
        return SeasonPlan(league, ctx.config, comps).also { it.ranking = leagueRanking }
    }

    /** A legacy season's tiers; a national season's divisions as one ladder (what the competitions reading [SeasonStart.tiers] expect). */
    private fun flat(season: Season): Tiers = season.leagues.list.singleOrNull()?.tiers ?: Tiers.linear(season.divisions.size)
}
