package yacht.engine.league

/**
 * Which tier each division of a season is in (docs/LEAGUE_SPEC.md 2.1). A linear league's
 * division d is tier d, alone in it; a regional pyramid ([Pyramid]) has one group in 1부, two in
 * 2부, four in 3부 and so on, each group a division of the season, numbered tier by tier. Tier
 * [tier] of division d (0 = top), [names] its region ("한국", "유럽", "아시아·유럽"; empty for a
 * linear league).
 */
class Tiers(private val of: IntArray, val names: List<String>) {
    init {
        require(names.size == of.size) { "a name per division" }
        require(of.isEmpty() || of[0] == 0) { "the first division is in the top tier" }
        for (i in 1 until of.size) require(of[i] == of[i - 1] || of[i] == of[i - 1] + 1) { "tiers in order" }
    }

    /** Divisions (groups) in all. */
    val size: Int get() = of.size

    /** Tiers in all. */
    val count: Int = if (of.isEmpty()) 0 else of.last() + 1

    /** Each tier one division: today's 1부, 2부, ... */
    val linear: Boolean = of.withIndex().all { (i, t) -> i == t }

    private val first = IntArray(count + 1).also { f ->
        var d = 0
        for (t in 0..count) {
            while (d < of.size && of[d] < t) d++
            f[t] = d
        }
    }

    fun tier(d: Int): Int = of[d]

    /** The divisions of tier [t], best group first. */
    fun groups(t: Int): IntRange = first[t] until first[t + 1]

    /** Groups in tier [t]. */
    fun groupCount(t: Int): Int = first[t + 1] - first[t]

    /** Division [d]'s place in its tier (0 = group A). */
    fun group(d: Int): Int = d - first[of[d]]

    /** "B조" for a division of a tier of several groups, else empty. */
    fun groupLabel(d: Int): String = if (groupCount(of[d]) > 1) letter(group(d)) + "조" else ""

    /** "3부" alone in its tier, else "3부 B조". */
    fun label(d: Int): String = tierLabel(of[d]) + groupLabel(d).let { if (it.isEmpty()) "" else " $it" }

    /** [label] and its region: "3부 B조 · 유럽". */
    fun fullLabel(d: Int): String = label(d) + names.getOrNull(d)?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()

    /** The saved line, `tiers 0,1,1,2 -|한국|유럽|-`. */
    fun encode(): String = "tiers ${of.joinToString(",")} ${names.joinToString("|") { it.ifEmpty { "-" } }}"

    override fun equals(other: Any?): Boolean = other is Tiers && other.of.contentEquals(of) && other.names == names

    override fun hashCode(): Int = of.contentHashCode() * 31 + names.hashCode()

    companion object {
        /** [n] divisions, each its own tier. */
        fun linear(n: Int) = Tiers(IntArray(n) { it }, List(n) { "" })

        /** Groups per tier ([shape]) as tiers, unnamed. */
        fun of(shape: List<Int>, names: List<String> = List(shape.sum()) { "" }): Tiers =
            Tiers(shape.withIndex().flatMap { (t, g) -> List(g) { t } }.toIntArray(), names)

        /** Reads a `tiers` line's fields (after the word); null when it does not fit [divisions]. */
        fun decode(f: List<String>, divisions: Int): Tiers? = runCatching {
            val of = f[0].split(',').map { it.toInt() }.toIntArray()
            val names = f.getOrNull(1)?.split('|')?.map { if (it == "-") "" else it } ?: List(of.size) { "" }
            Tiers(of, names).takeIf { it.size == divisions }
        }.getOrNull()

        fun tierLabel(t: Int): String = "${t + 1}부"

        /** A, B, ... Z, then 27, 28, ... */
        fun letter(g: Int): String = if (g < 26) ('A' + g).toString() else "${g + 1}"
    }
}

/**
 * 지역 리그 피라미드 (docs/LEAGUE_SPEC.md 2.1): a league of thousands in groups of about
 * [LeagueConfig.groupSize] players. 1부 is one group, 2부 two, 3부 four, ... and the last tier
 * takes the rest, so a season stays groupSize - 1 matchdays at any size. Each season the groups
 * of a tier are cut again by region (confederation, nation, hometown) and named after it. A
 * league of [MAX_LINEAR] groups or fewer stays linear ([LeagueConfig.divisions]).
 *
 * Between two tiers the top [LeagueConfig.groupSwap] of every lower group go up and as many go
 * down from the upper groups' bottom places ([crossing]); places left by players who retired are
 * filled by the best of the tier below ([next]).
 *
 * The tables, boundaries and tier rankings are per league ([LeagueDef]): a national season's
 * leagues are each a linear ladder, whose boundaries are a linear league's ([NationalLeagues]).
 */
object Pyramid {
    const val MAX_LINEAR = 6

    /**
     * Groups per tier for [n] players under [config], best tier first: null for a linear league
     * (pyramid off, or [MAX_LINEAR] groups or fewer).
     */
    fun shape(n: Int, config: LeagueConfig): List<Int>? {
        if (!config.pyramid) return null
        val k = Math.round(n.toDouble() / config.groupSize).toInt()
        if (k <= MAX_LINEAR) return null
        val out = ArrayList<Int>()
        var left = k
        var g = 1
        while (left >= g) {
            out += g
            left -= g
            g *= 2
        }
        // The rest: a tier of its own when it is at least half the one above, else that one's.
        if (left > 0) {
            if (2 * left >= out.last()) out += left else out[out.lastIndex] += left
        }
        return out
    }

    /** Sizes of the groups, tier by tier: [n] shared as evenly as can be, the higher groups first. */
    fun sizes(n: Int, shape: List<Int>): List<Int> {
        val k = shape.sum()
        return List(k) { n / k + if (it < n % k) 1 else 0 }
    }

    /** Who crosses a boundary: [up] from the lower groups, [down] from the upper ones, and the playoff ties (upper side, lower side). */
    class Crossing(val swap: Int, val up: List<String>, val down: List<String>, val ties: List<Pair<String, String>>)

    /**
     * The boundary between [upper] and [lower] (each a tier's group tables, best first): the
     * top s of every lower group go up, where s is [swap] at most a third of the smallest lower
     * group, and as many go down from the upper groups, their bottom places first (each
     * group's last, then each one's second last, ...; within a place the worse by [worse]
     * first). With [playoff], each lower group's next place plays the next upper one in that
     * order, the best of those lower places against the first. A linear league (one group on
     * each side) gets exactly its old places: the bottom s down, the top s up, and the tie of
     * the next place on each side.
     */
    fun crossing(upper: List<List<String>>, lower: List<List<String>>, swap: Int, playoff: Boolean, worse: Comparator<String>): Crossing {
        val u = upper.size
        val l = lower.size
        if (u == 0 || l == 0) return Crossing(0, emptyList(), emptyList(), emptyList())
        // The upper groups give up s * l places in all: a third of their smallest at most.
        val s = minOf(swap, lower.minOf { it.size } / 3, upper.minOf { it.size } * u / (3 * l)).coerceAtLeast(0)
        val up = lower.flatMap { it.take(s) }
        val order = dropOrder(upper, worse)
        val downs = s * l
        val down = order.take(downs)
        val ties = if (!playoff) emptyList() else {
            val lowerSide = lower.mapNotNull { it.getOrNull(s) }.sortedWith(worse.reversed())
            val upperSide = order.drop(downs).take(lowerSide.size)
            upperSide.zip(lowerSide)
        }
        return Crossing(s, up, down, ties)
    }

    /** The upper groups' players from the bottom up: every group's last place, then every second last, ... (worse first within a place). */
    private fun dropOrder(upper: List<List<String>>, worse: Comparator<String>): List<String> {
        val most = upper.maxOf { it.size }
        val out = ArrayList<String>()
        for (p in 0 until most) {
            out += upper.mapNotNull { g -> g.getOrNull(g.size - 1 - p) }.sortedWith(worse)
        }
        return out
    }

    /** The group tables of tier [t] of league [lg] of [season], best first each. */
    fun tables(season: Season, lg: LeagueDef, t: Int): List<List<String>> = lg.tiers.groups(t).map { d -> season.table(lg.first + d).map { it.key } }

    /** [tables] of a season's single league (a national season: all its divisions as one ladder, as [Season.tiers] has them). */
    @Deprecated("A national season has several leagues: pass the league's LeagueDef")
    fun tables(league: Season, t: Int): List<List<String>> = tables(league, flat(league), t)

    /** Worse first: fewer points per match, then a lower score difference and score per match, then by key. */
    fun worse(league: Season): Comparator<String> {
        fun s(k: String) = league.standings[k]
        fun per(x: Int, played: Int) = if (played == 0) 0.0 else x.toDouble() / played
        return compareBy<String> { k -> s(k)?.let { per(it.points, it.played) } ?: 0.0 }
            .thenBy { k -> s(k)?.let { per(it.diff, it.played) } ?: 0.0 }
            .thenBy { k -> s(k)?.let { per(it.scored, it.played) } ?: 0.0 }
            .thenByDescending { it }
    }

    /**
     * The boundary below tier [t] of league [lg] of [season] under [config] (a linear ladder:
     * [LeagueConfig.autoSwap]; a pyramid's groups: [LeagueConfig.groupSwap]).
     */
    fun crossing(season: Season, config: LeagueConfig, lg: LeagueDef, t: Int): Crossing =
        crossing(tables(season, lg, t), tables(season, lg, t + 1), if (lg.tiers.linear) config.autoSwap else config.groupSwap, config.promotionPlayoff, worse(season))

    /** [crossing] of a season's single league (a national season: all its divisions as one ladder, as [Season.tiers] has them). */
    @Deprecated("A national season has several leagues: pass the league's LeagueDef")
    fun crossing(league: Season, config: LeagueConfig, t: Int): Crossing = crossing(league, config, flat(league), t)

    /**
     * Tier [t] of league [lg] from best to worst: every group's winner (the better by points per
     * match first), then every runner-up, ... ([interleave]). A linear tier: its table.
     */
    fun ranking(season: Season, lg: LeagueDef, t: Int): List<String> = interleave(tables(season, lg, t), worse(season).reversed())

    /** [ranking] of a season's single league (a national season: all its divisions as one ladder, as [Season.tiers] has them). */
    @Deprecated("A national season has several leagues: pass the league's LeagueDef")
    fun ranking(league: Season, t: Int): List<String> = ranking(league, flat(league), t)

    /**
     * [lists] position by position: every list's first (the [better] first among them), then
     * every list's second, and so on. Players are only ever compared with the same place of
     * another list, never across places.
     */
    fun interleave(lists: List<List<String>>, better: Comparator<String>): List<String> {
        val out = ArrayList<String>(lists.sumOf { it.size })
        for (p in 0 until (lists.maxOfOrNull { it.size } ?: 0)) out += lists.mapNotNull { it.getOrNull(p) }.sortedWith(better)
        return out
    }

    /** A season's single league; a national season's divisions as one ladder (the view of [Season.tiers]). */
    private fun flat(season: Season): LeagueDef =
        season.leagues.list.singleOrNull() ?: LeagueDef(LeagueIds.LEGACY, 0, Tiers.linear(season.divisions.size))

    /**
     * Next season's divisions and tiers for [shape] (groups per tier): the last season's
     * players by tier ([ranking]) with [moves] applied (promoted players at the bottom of their
     * new tier, relegated ones at the top), departed ones removed and newcomers in the last
     * tier; then each tier brought to its size from the top (places left free are filled by the
     * best of the tiers below who did not just come down; too many, and the worst who did not
     * just come up go down), and each tier cut into its groups by region.
     */
    fun next(league: Season, moves: List<Offseason.Move>, ctx: Offseason.Context, shape: List<Int>): Pair<List<List<String>>, Tiers> {
        val keys = ctx.keys.toSet()
        val lg = flat(league)
        val lists = (0 until lg.tiers.count).map { ranking(league, lg, it).toMutableList() }.toMutableList()
        for (m in moves) {
            if (m.from !in lists.indices || m.to !in lists.indices) continue
            lists[m.from].remove(m.key)
            if (m.to < m.from) lists[m.to].add(m.key) else lists[m.to].add(0, m.key)
        }
        for (l in lists) l.retainAll(keys)
        val placed = lists.flatten().toSet()
        val newcomers = ctx.keys.filter { it !in placed }.sortedByDescending { ctx.rating(it) }
        if (lists.isEmpty()) lists += ArrayList<String>()
        lists.last().addAll(newcomers)
        val relegated = moves.filter { it.to > it.from }.map { it.key }.toSet()
        val promoted = moves.filter { it.to < it.from }.associate { it.key to it.to }
        return fill(lists, ctx, shape, relegated, promoted)
    }

    /** The first season's tiers: [ranking] (best first) down the tiers, each cut into its groups by region. */
    fun first(ranking: List<String>, ctx: Offseason.Context, shape: List<Int>): Pair<List<List<String>>, Tiers> =
        fill(mutableListOf(ranking.toMutableList()), ctx, shape, emptySet(), emptyMap())

    /**
     * [lists] (players by tier, best first) brought to [shape]'s tier sizes from the top and cut
     * into groups. A tier short of players takes the best of the tiers below (the next one
     * first; any one further down when it is empty) who were not [relegated] this season; a tier
     * with too many sends its worst down, those [promoted] into it last (a player announced as
     * promoted keeps its place unless nobody else is left). [NationalLeagues.next] fills each
     * national league's ladder with it, by [oldTier] (see [lift]).
     */
    internal fun fill(
        lists: MutableList<MutableList<String>>,
        ctx: Offseason.Context,
        shape: List<Int>,
        relegated: Set<String>,
        promoted: Map<String, Int>,
        oldTier: ((String) -> Int)? = null,
    ): Pair<List<List<String>>, Tiers> {
        val n = lists.sumOf { it.size }
        val sizes = sizes(n, shape)
        val targets = ArrayList<Int>()
        var g = 0
        for (count in shape) {
            targets += sizes.subList(g, g + count).sum()
            g += count
        }
        // As many lists as tiers: missing ones empty at the bottom, extra ones into the last.
        while (lists.size < shape.size) lists += ArrayList<String>()
        while (lists.size > shape.size) lists[shape.size - 1].addAll(lists.removeAt(shape.size))
        for (t in 0 until shape.size - 1) {
            val cur = lists[t]
            if (cur.size > targets[t]) {
                // The worst first, those just promoted into this tier only when nobody else is left.
                val extra = cur.size - targets[t]
                val stayers = cur.indices.reversed().filter { promoted[cur[it]] != t }
                val newcomers = cur.indices.reversed().filter { promoted[cur[it]] == t }
                val out = (stayers + newcomers).take(extra).toHashSet()
                val down = cur.filterIndexed { i, _ -> i in out }
                cur.removeAll(down.toSet())
                lists[t + 1].addAll(0, down)
            }
            var u = t + 1
            while (cur.size < targets[t] && u < lists.size) {
                val below = lists[u]
                if (below.isEmpty()) {
                    u++
                    continue
                }
                cur += below.removeAt(lift(below, relegated, oldTier))
            }
        }
        val divisions = ArrayList<List<String>>()
        val names = ArrayList<String>()
        g = 0
        for ((t, count) in shape.withIndex()) {
            val groups = regroup(lists[t], sizes.subList(g, g + count), ctx)
            g += count
            for (grp in groups) {
                divisions += grp
                names += if (count > 1) regionName(grp, ctx) else ""
            }
        }
        return divisions to Tiers.of(shape, names)
    }

    /**
     * Which of [below] goes up into the short tier above: the first not [relegated] (the first
     * of all when every one was). With [oldTier] (a national ladder, whose lower tiers can hold
     * several of last season's tiers one after another), a relegated player before it goes
     * first when its old tier is the better: it is passed only by those of its own old tier, so
     * nobody of a lower old tier goes up past it.
     */
    private fun lift(below: List<String>, relegated: Set<String>, oldTier: ((String) -> Int)?): Int {
        val i = below.indexOfFirst { it !in relegated }
        if (i <= 0 || oldTier == null) return maxOf(i, 0)
        val t = oldTier(below[i])
        return (0 until i).filter { oldTier(below[it]) < t }.minByOrNull { oldTier(below[it]) } ?: i
    }

    /** [members] cut into groups of [sizes] in region order: confederation, nation, hometown. */
    fun regroup(members: List<String>, sizes: List<Int>, ctx: Offseason.Context): List<List<String>> {
        if (sizes.size == 1) return listOf(members)
        val nationOrder = Nations.ALL.withIndex().associate { (i, n) -> n.code to i }
        fun nation(k: String) = ctx.nation(k) ?: Nations.KR.code
        val sorted = members.sortedWith(
            compareBy<String> { Nations.confedOf(nation(it)).ordinal }.thenBy { nationOrder[nation(it)] ?: Int.MAX_VALUE }
                .thenBy { ctx.hometown(it) }.thenBy { it },
        )
        // The sizes are for the whole tier: a tier short of players fills its groups in turn.
        val out = ArrayList<List<String>>()
        val total = sorted.size
        val want = sizes.sum().coerceAtLeast(1)
        var start = 0
        for ((i, size) in sizes.withIndex()) {
            val end = if (i == sizes.lastIndex) total else minOf(total, start + Math.round(size.toDouble() * total / want).toInt())
            out += sorted.subList(start, end)
            start = end
        }
        return out
    }

    /** A group's region: its nation when half its players or more are of it, else its confederation, else its two biggest confederations. */
    fun regionName(group: List<String>, ctx: Offseason.Context): String {
        if (group.isEmpty()) return ""
        val nations = group.groupingBy { ctx.nation(it) ?: Nations.KR.code }.eachCount()
        val (code, most) = nations.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })!!
        if (2 * most >= group.size) return Nations.of(code)?.short ?: code
        val confeds = nations.entries.groupBy({ Nations.confedOf(it.key) }, { it.value }).mapValues { it.value.sum() }
        val ranked = confeds.entries.sortedWith(compareByDescending<Map.Entry<Confed, Int>> { it.value }.thenBy { it.key.ordinal })
        if (2 * ranked[0].value >= group.size || ranked.size == 1) return ranked[0].key.label
        return ranked.take(2).map { it.key }.sortedBy { it.ordinal }.joinToString("·") { it.label }
    }
}
