package yacht.engine.league

/**
 * One league of a season: its divisions are [first] until first + [tiers].size of the season,
 * in [tiers]. Id [LeagueIds.LEGACY] ("") is the single league of a season before national
 * leagues (linear divisions or a regional pyramid); otherwise a nation code ("KR"), a
 * confederation's 연합 리그 ("XAS") or the 국제 연합 리그 ([LeagueIds.WORLD]), each a linear
 * ladder (one division a tier).
 */
data class LeagueDef(val id: String, val first: Int, val tiers: Tiers) {
    /** Divisions in all. */
    val size: Int get() = tiers.size
    val divisions: IntRange get() = first until first + tiers.size
    val national: Boolean get() = id.isNotEmpty()

    /** Its 1부 (tier 0, group A). */
    val top: Int get() = first
}

/**
 * The leagues of a season, in division order: one legacy league ([legacy]), or national leagues
 * ([national]) in league order (national leagues in [Nations.ALL] order, then the 연합 리그 of
 * each [Confed], then [LeagueIds.WORLD]: [LeagueIds.order]). [worldConfed] is the confederation
 * of the 국제 연합 리그 (its largest nation's), saved on its league line.
 */
class Leagues(val list: List<LeagueDef>, worldConfed: Confed? = null) {
    init {
        var at = 0
        for (lg in list) {
            require(lg.first == at) { "leagues in division order from 0" }
            at += lg.size
        }
        require(list.map { it.id }.toSet().size == list.size) { "a league id once" }
        require(list.size <= 1 || list.all { it.national }) { "the legacy league is the only one" }
        require(list.all { !it.national || (it.size > 0 && it.tiers.linear) }) { "a national league is a linear ladder" }
    }

    /** Divisions in all. */
    val size: Int = list.sumOf { it.size }

    /** National leagues (any league with an id); false for a legacy season. */
    val national: Boolean = list.any { it.national }

    /** The 국제 연합 리그's confederation; null without one. */
    val worldConfed: Confed? = worldConfed?.takeIf { list.any { it.id == LeagueIds.WORLD } }

    private val index = IntArray(size).also { ix -> for ((i, lg) in list.withIndex()) for (d in lg.divisions) ix[d] = i }
    private val byId = list.associateBy { it.id }

    /** The league of division [d]. */
    fun of(d: Int): LeagueDef = list[index[d]]

    fun byId(id: String): LeagueDef? = byId[id]

    /** Division [d]'s index inside its league. */
    fun local(d: Int): Int = d - of(d).first

    /** Division [d]'s tier inside its league (0 = 1부). */
    fun tier(d: Int): Int = of(d).let { it.tiers.tier(d - it.first) }

    /** "한국 1부", "국제 연합 2부"; a legacy league's "3부 B조". */
    fun label(d: Int): String = of(d).let { lg ->
        val own = lg.tiers.label(d - lg.first)
        if (lg.national) "${LeagueIds.short(lg.id)} $own" else own
    }

    /** "한국 리그 1부"; a legacy league's "3부 B조 · 유럽". */
    fun fullLabel(d: Int): String = of(d).let { lg ->
        if (lg.national) "${LeagueIds.label(lg.id)} ${lg.tiers.label(d - lg.first)}" else lg.tiers.fullLabel(d - lg.first)
    }

    /** The 1부 of every league, in league order. */
    fun tops(): List<Int> = list.filter { it.size > 0 }.map { it.top }

    /** The confederation of league [id] in this season ([worldConfed] for the 국제 연합 리그). */
    fun confedOf(id: String): Confed? = if (id == LeagueIds.WORLD) worldConfed else LeagueIds.confedOf(id)

    /**
     * The season file's lines: `league <id> <first> <count> [CONFED]` per national league (the
     * confederation only on [LeagueIds.WORLD]); a legacy league's `tiers` line when it is a
     * pyramid (nothing when linear, as before national leagues).
     */
    fun encode(out: StringBuilder) {
        if (!national) {
            list.singleOrNull()?.tiers?.takeIf { !it.linear }?.let { out.append(it.encode()).append('\n') }
            return
        }
        for (lg in list) {
            out.append("league ").append(lg.id).append(' ').append(lg.first).append(' ').append(lg.size)
            if (lg.id == LeagueIds.WORLD && worldConfed != null) out.append(' ').append(worldConfed.name)
            out.append('\n')
        }
    }

    override fun equals(other: Any?): Boolean = other is Leagues && other.list == list && other.worldConfed == worldConfed

    override fun hashCode(): Int = list.hashCode() * 31 + (worldConfed?.hashCode() ?: 0)

    override fun toString(): String = list.joinToString(prefix = "Leagues(", postfix = ")") { "${it.id.ifEmpty { "-" }}@${it.first}+${it.size}" }

    companion object {
        /** The single league of a season before national leagues. */
        fun legacy(t: Tiers): Leagues = Leagues(listOf(LeagueDef(LeagueIds.LEGACY, 0, t)))

        /** National leagues [ids] (league order) of [counts] divisions each, one a tier. */
        fun national(ids: List<String>, counts: List<Int>, worldConfed: Confed? = null): Leagues {
            require(ids.size == counts.size) { "a division count per league" }
            require(ids.all { it.isNotEmpty() }) { "national league ids" }
            var first = 0
            return Leagues(ids.indices.map { i -> LeagueDef(ids[i], first, Tiers.linear(counts[i])).also { first += counts[i] } }, worldConfed)
        }

        /**
         * The leagues of a saved season of [divisions] divisions, from its `league` lines (null when
         * they do not cover the divisions in order, or name a league this version does not know,
         * [LeagueIds.known]) or, without any, its legacy `tiers` line (linear without one, or with
         * one that does not fit).
         */
        fun decode(lines: List<String>, divisions: Int): Leagues? = runCatching {
            val defs = ArrayList<LeagueDef>()
            var world: Confed? = null
            var tierLine: List<String>? = null
            for (l in lines) {
                val f = l.split(' ')
                when (f[0]) {
                    "league" -> {
                        val count = f[3].toInt()
                        require(count > 0)
                        // A nation added by a newer version: no name, no archive code here (the season is unreadable, as any newer file).
                        require(LeagueIds.known(f[1])) { "league ${f[1]}" }
                        defs += LeagueDef(f[1], f[2].toInt(), Tiers.linear(count))
                        if (f[1] == LeagueIds.WORLD) f.getOrNull(4)?.let { world = Confed.valueOf(it) }
                    }
                    "tiers" -> tierLine = f.drop(1)
                }
            }
            if (defs.isEmpty()) {
                legacy(tierLine?.let { Tiers.decode(it, divisions) } ?: Tiers.linear(divisions))
            } else {
                Leagues(defs, world).takeIf { it.national && it.size == divisions }
            }
        }.getOrNull()
    }
}

/** League ids, their names and the archive's one-digit codes. */
object LeagueIds {
    /** The single league of a season before national leagues. */
    const val LEGACY = ""

    /** 국제 연합 리그: the nations left over when even their 연합 리그 would be too small. */
    const val WORLD = "XWO"

    private val REGIONS: Map<Confed, String> = mapOf(Confed.ASIA to "XAS", Confed.EUROPE to "XEU", Confed.AMERICAS to "XAM", Confed.AFRICA to "XAF")
    private val REGION_CONFED: Map<String, Confed> = REGIONS.entries.associate { (c, id) -> id to c }

    /** The 연합 리그 of a confederation's nations without a league of their own: ASIA "XAS", EUROPE "XEU", AMERICAS "XAM", AFRICA "XAF". */
    fun region(c: Confed): String = REGIONS.getValue(c)

    fun isRegion(id: String): Boolean = id in REGION_CONFED

    /** An id this version knows (every one [code] takes): the legacy league, a nation of [Nations.ALL], a 연합 리그, [WORLD]. */
    fun known(id: String): Boolean = id == LEGACY || id == WORLD || isRegion(id) || Nations.of(id) != null

    /**
     * The confederation of a nation's league or of a 연합 리그; null for [WORLD] (a season's
     * comes from [Leagues.confedOf]), the legacy league and an id this version does not know.
     */
    fun confedOf(id: String): Confed? = REGION_CONFED[id] ?: Nations.of(id)?.confed

    /** The nations that play in league [id] by [plan] (nation code to league id), in [Nations.ALL] order. */
    fun nations(id: String, plan: Map<String, String>): List<String> = Nations.ALL.map { it.code }.filter { plan[it] == id }

    /** "리그" (legacy), "한국 리그", "아시아 연합 리그", "국제 연합 리그". */
    fun label(id: String): String = if (id == LEGACY) "리그" else "${short(id)} 리그"

    /** "" (legacy), "한국", "아시아 연합", "국제 연합". */
    fun short(id: String): String = when {
        id == LEGACY -> ""
        id == WORLD -> "국제 연합"
        isRegion(id) -> "${REGION_CONFED.getValue(id).label} 연합"
        else -> Nations.of(id)?.short ?: id
    }

    /** A nation's flag, a confederation's globe, 🌐 for [WORLD]; empty for the legacy league. */
    fun icon(id: String): String = when {
        id == WORLD -> "🌐"
        isRegion(id) -> REGION_CONFED.getValue(id).icon
        else -> Nations.of(id)?.flag.orEmpty()
    }

    /** "한국 1부" for tier 0 of league "KR"; the legacy league's "1부". */
    fun division(id: String, tier: Int): String = if (id == LEGACY) Tiers.tierLabel(tier) else "${short(id)} ${Tiers.tierLabel(tier)}"

    /**
     * The order of leagues in a season (and of their divisions): nations in [Nations.ALL] order,
     * then the 연합 리그 in [Confed] order, then [WORLD]; the legacy league first, unknown ids last.
     */
    fun order(id: String): Int = when {
        id == LEGACY -> -1
        id == WORLD -> 200
        isRegion(id) -> 100 + REGION_CONFED.getValue(id).ordinal
        else -> Nations.ALL.indexOfFirst { it.code == id }.let { if (it < 0) 300 else it }
    }

    /**
     * One base-36 digit for the archive: the legacy league 0, nation i of [Nations.ALL] i + 1
     * (1..30), [WORLD] 31, XAS 32, XEU 33, XAM 34, XAF 35. Throws for an id this version does not
     * know ([Nations.ALL] is append-only and has room for 30 nations).
     */
    fun code(id: String): Int = when {
        id == LEGACY -> 0
        id == WORLD -> 31
        isRegion(id) -> 32 + REGION_CONFED.getValue(id).ordinal
        else -> Nations.ALL.indexOfFirst { it.code == id }.also { require(it in 0 until 30) { "no code for league $id" } } + 1
    }

    /** The league id of a [code]; the legacy league for a code this version does not know. */
    fun ofCode(code: Int): String = when (code) {
        in 1..Nations.ALL.size -> Nations.ALL[code - 1].code
        31 -> WORLD
        in 32..35 -> region(Confed.entries[code - 32])
        else -> LEGACY
    }
}

/** One row of the published league ranking (리그 랭킹). */
data class LeagueRank(
    val id: String,
    /** 0-based place in the world. */
    val rank: Int,
    /** 0-based place among the leagues of its confederation. */
    val confedRank: Int,
    /** The ranking's sum of the last 5 seasons' coefficients (리그 계수, 국제 성적 점수). */
    val coefficient: Double,
    /** Each season's coefficient, newest first, up to 5. */
    val seasons: List<Double>,
    /** The average rating of its best 5 players. */
    val strength: Double,
    /** Places in its confederation's 대륙 챔피언스. */
    val cclSlots: Int,
    /** Its champion goes straight into the 챔피언스 리그. */
    val clDirect: Boolean,
    /** Places gained since the ranking of the season before (negative: lost). */
    val change: Int,
    /** Its confederation (the 국제 연합 리그 takes its largest nation's, [Leagues.confedOf]). */
    val confed: Confed? = LeagueIds.confedOf(id),
)

/** The league ranking published after season [season] closed (best first). */
class LeagueRanking(val season: Int, val rows: List<LeagueRank>) {
    private val byId = rows.associateBy { it.id }

    fun of(id: String): LeagueRank? = byId[id]

    /** 0-based world rank of league [id]; [Int.MAX_VALUE] when it is not ranked. */
    fun rank(id: String): Int = byId[id]?.rank ?: Int.MAX_VALUE

    /** The leagues of confederation [c], best first. */
    fun confed(c: Confed): List<LeagueRank> = rows.filter { it.confed == c }

    companion object {
        /** No ranking yet (a legacy season, or before the first national close): no league counts as best-ranked. */
        val EMPTY = LeagueRanking(0, emptyList())
    }
}
