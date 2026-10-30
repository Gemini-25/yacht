package yacht.engine.league

/**
 * 국가 리그 (docs/LEAGUE_SPEC.md 2.1): which leagues a season has and who plays in which. Every
 * member plays in the league of its play nation ([home]): its club's nation, else its
 * nationality, else KR (the guest, the hand-made AIs, mine and any key without an identity). A
 * nation with [LeagueConfig.leagueMin] members or more has a league of its own; the others play
 * in their confederation's 연합 리그, and what is still too small in the 국제 연합 리그 or the
 * smallest league of its confederation ([plan]). Each league is a linear ladder of divisions of
 * about [LeagueConfig.divisionSize] ([tierCount]); next season's ladders follow this season's by
 * tier and place ([next]). Pure and deterministic: nations go in [Nations.ALL] order, leagues in
 * [LeagueIds.order], and nothing is drawn at random.
 */
object NationalLeagues {
    /** The smallest division. */
    const val MIN_DIVISION = 10

    const val MAX_TIERS = 12

    /** A division holds at most divisionSize + OVER. */
    const val OVER = 2

    /** A league that exists survives down to leagueMin - HYSTERESIS members. */
    const val HYSTERESIS = 4

    /** The nation [key] plays in ([Offseason.Context.playNation]); KR without one this version knows. */
    fun home(key: String, ctx: Offseason.Context): String = ctx.playNation(key)?.takeIf { Nations.of(it) != null } ?: Nations.KR.code

    /** Members per play nation ([home]). */
    fun counts(keys: Collection<String>, ctx: Offseason.Context): Map<String, Int> = keys.groupingBy { home(it, ctx) }.eachCount()

    /**
     * The league of every nation with members ([counts]: nation code to members), as nation
     * code to league id; [last] holds last season's league ids (an existing league keeps going
     * down to [HYSTERESIS] below [LeagueConfig.leagueMin]):
     * 1. a nation of [LeagueConfig.leagueMin] members or more plays its own league;
     * 2. the rest of each confederation (in [Confed] order) form its 연합 리그 when they are as
     *    many;
     * 3. what is left forms the 국제 연합 리그 when it is as many, or when there is no league
     *    yet; otherwise each nation joins the smallest league of its confederation (of any
     *    league when its confederation has none), ties by league order.
     */
    fun plan(counts: Map<String, Int>, config: LeagueConfig, last: Set<String>): Map<String, String> {
        val min = config.leagueMin
        val keep = min - HYSTERESIS
        fun enough(n: Int, id: String) = n >= min || (id in last && n >= keep)
        val nations = Nations.ALL.map { it.code }.filter { (counts[it] ?: 0) > 0 }
        val out = LinkedHashMap<String, String>()
        for (c in nations) if (enough(counts.getValue(c), c)) out[c] = c
        for (confed in Confed.entries) {
            val rest = nations.filter { it !in out && Nations.confedOf(it) == confed }
            val pool = rest.sumOf { counts.getValue(it) }
            val id = LeagueIds.region(confed)
            if (pool > 0 && enough(pool, id)) for (c in rest) out[c] = id
        }
        val left = nations.filter { it !in out }
        if (left.isEmpty()) return out
        if (out.isEmpty() || enough(left.sumOf { counts.getValue(it) }, LeagueIds.WORLD)) {
            for (c in left) out[c] = LeagueIds.WORLD
            return out
        }
        val size = HashMap<String, Int>()
        for ((c, id) in out) size.merge(id, counts.getValue(c), Int::plus)
        for (c in left) {
            val confed = Nations.confedOf(c)
            val own = size.keys.filter { LeagueIds.confedOf(it) == confed }
            val into = own.ifEmpty { size.keys.toList() }.minWith(compareBy<String> { size.getValue(it) }.thenBy { LeagueIds.order(it) })
            out[c] = into
            size.merge(into, counts.getValue(c), Int::plus)
        }
        return out
    }

    /** The 국제 연합 리그's confederation by [plan]: its largest nation's (ties by [Nations.ALL] order); null without one. */
    fun worldConfed(plan: Map<String, String>, counts: Map<String, Int>): Confed? =
        Nations.ALL.filter { plan[it.code] == LeagueIds.WORLD }.maxByOrNull { counts[it.code] ?: 0 }?.confed

    /**
     * Divisions of a league of [n] members at about [size] a division: 1 below 2 ×
     * [MIN_DIVISION] (no promotion), else at least 2, as many as [size] asks (half up), more
     * while a division would hold more than [size] + [OVER] (up to [MAX_TIERS]), and never one
     * below [MIN_DIVISION].
     */
    fun tierCount(n: Int, size: Int): Int {
        if (n < 2 * MIN_DIVISION) return 1
        var t = maxOf(2, Math.round(n.toDouble() / size).toInt())
        while (t < MAX_TIERS && (n + t - 1) / t > size + OVER) t++
        return minOf(t, n / MIN_DIVISION)
    }

    /** The division sizes of a ladder of [tiers] for [n] members: even shares, the upper divisions take the extra ones. */
    fun sizes(n: Int, tiers: Int): List<Int> = Pyramid.sizes(n, List(tiers) { 1 })

    /** The first season's divisions: [ranking] (best first) by league, each league's members cut into its ladder in that order. */
    fun first(ranking: List<String>, ctx: Offseason.Context): Pair<List<List<String>>, Leagues> {
        val counts = counts(ranking, ctx)
        val formed = plan(counts, ctx.config, emptySet())
        val ladders = ranking.groupBy { formed.getValue(home(it, ctx)) }.mapValues { (_, keys) ->
            val out = ArrayList<List<String>>()
            var at = 0
            for (s in sizes(keys.size, tierCount(keys.size, ctx.config.divisionSize))) {
                out += keys.subList(at, at + s)
                at += s
            }
            out
        }
        return divisions(ladders, worldConfed(formed, counts))
    }

    /**
     * Next season's divisions after [plan] (national, or a legacy season migrating) with
     * [moves] applied:
     * 1. each old league's tiers as [Pyramid.next] has them: its tier rankings with the moves
     *    applied (promoted players at the bottom of their new tier, relegated ones at the top);
     * 2. the leagues of next season by [ctx]'s members ([plan], last season's leagues kept by
     *    the hysteresis), each with its [tierCount];
     * 3. every member still in the league goes to its [home] league at its old tier (the last
     *    tier when the new ladder is shorter), old tiers one after another, the better first;
     *    only the same old tier of two old leagues meeting in one is merged, place by place
     *    ([Pyramid.interleave]), so a lower tier's winner never goes above a higher tier's
     *    players;
     * 4. transfers in ([Offseason.Context.transfersIn]) join the 1부 and keep their place;
     * 5. players back home ([Offseason.Context.returned]) join the middle of the 1부 when rated
     *    at least its median, else the middle of the 2부 (not protected either way);
     * 6. every other newcomer joins the last tier, best rated first;
     * 7. each ladder is brought to its sizes from the top ([Pyramid.fill]: promoted players
     *    keep their tier, relegated ones do not go straight back up; a tier short of players
     *    takes them by old tier, so only those of their own old tier pass the relegated ones).
     */
    fun next(plan: SeasonPlan, moves: List<Offseason.Move>, ctx: Offseason.Context): Pair<List<List<String>>, Leagues> {
        val season = plan.league
        val keys = ctx.keys.toSet()
        val elsewhere = ctx.transfersIn + ctx.returned
        val old = season.leagues.list.map { lg ->
            val lists = (0 until lg.tiers.count).map { Pyramid.ranking(season, lg, it).toMutableList() }
            for (m in moves) {
                if (m.league != lg.id || m.from !in lists.indices || m.to !in lists.indices) continue
                lists[m.from].remove(m.key)
                if (m.to < m.from) lists[m.to].add(m.key) else lists[m.to].add(0, m.key)
            }
            lists.map { l -> l.filter { it in keys && it !in elsewhere } }
        }
        val counts = counts(ctx.keys, ctx)
        val formed = this.plan(counts, ctx.config, season.leagues.list.map { it.id }.toSet())
        fun leagueOf(k: String) = formed.getValue(home(k, ctx))
        val members = HashMap<String, Int>()
        for ((c, n) in counts) members.merge(formed.getValue(c), n, Int::plus)
        val lists = members.mapValues { (_, n) -> MutableList<MutableList<String>>(tierCount(n, ctx.config.divisionSize)) { ArrayList() } }
        // Old tiers in order; one old league a source, merged place by place where two meet.
        // Each member's old tier (the tier it joined, for those from outside) keeps the order
        // when the ladders are filled.
        val placed = HashSet<String>()
        val oldTier = HashMap<String, Int>()
        val better = Pyramid.worse(season).reversed()
        for (t in 0 until (old.maxOfOrNull { it.size } ?: 0)) {
            val sources = LinkedHashMap<String, MutableList<List<String>>>()
            for (tiers in old) {
                val tier = tiers.getOrNull(t) ?: continue
                // Each member once (one the moves left in two lists stays in the first).
                val fresh = tier.filter { placed.add(it) }
                for (k in fresh) oldTier[k] = t
                for ((id, ks) in fresh.groupBy(::leagueOf)) sources.getOrPut(id) { ArrayList() } += ks
            }
            for ((id, src) in sources) {
                val ladder = lists.getValue(id)
                ladder[minOf(t, ladder.lastIndex)] += src.singleOrNull() ?: Pyramid.interleave(src, better)
            }
        }
        val promoted = HashMap<String, Int>()
        for (m in moves) if (m.to < m.from && m.key in placed) promoted[m.key] = minOf(m.to, lists.getValue(leagueOf(m.key)).lastIndex)
        val relegated = moves.filter { it.to > it.from }.map { it.key }.toSet()
        fun best(ks: Collection<String>) = ctx.keys.filter { it in ks && it !in placed }.sortedByDescending { ctx.rating(it) }
        for (k in best(ctx.transfersIn)) {
            lists.getValue(leagueOf(k))[0] += k
            promoted[k] = 0
            oldTier[k] = 0
            placed += k
        }
        // The median of each 1부 before anyone comes home. Into the middle of the tier: a full
        // tier sends its last places down first, a short one lifts the top of the next.
        val median = lists.mapValues { (_, l) -> median(l[0].map(ctx.rating)) }
        for (k in best(ctx.returned)) {
            val id = leagueOf(k)
            val ladder = lists.getValue(id)
            val m = median.getValue(id)
            val at = if (ladder.size == 1 || m == null || ctx.rating(k) >= m) 0 else 1
            ladder[at].add(ladder[at].size / 2, k)
            oldTier[k] = at
            placed += k
        }
        for (k in best(keys)) lists.getValue(leagueOf(k)).last() += k
        val rank = { k: String -> oldTier[k] ?: Int.MAX_VALUE }
        val ladders = lists.mapValues { (_, l) -> Pyramid.fill(l, ctx, List(l.size) { 1 }, relegated, promoted, rank).first }
        return divisions(ladders, worldConfed(formed, counts))
    }

    /**
     * The [n] best-ranked leagues of [plan]'s season by [SeasonPlan.ranking], best first: those
     * whose news is told one by one (none before the first national close, when the ranking is
     * empty).
     */
    fun bestRanked(plan: SeasonPlan, n: Int): List<String> =
        plan.ranking.rows.sortedBy { it.rank }.map { it.id }.filter { plan.league.leagues.byId(it) != null }.take(n)

    private fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Every league's ladder in league order, and the season's [Leagues]. */
    private fun divisions(ladders: Map<String, List<List<String>>>, world: Confed?): Pair<List<List<String>>, Leagues> {
        val ids = ladders.keys.sortedBy { LeagueIds.order(it) }
        return ids.flatMap { ladders.getValue(it) } to Leagues.national(ids, ids.map { ladders.getValue(it).size }, world)
    }
}
