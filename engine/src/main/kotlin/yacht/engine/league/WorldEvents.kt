package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap

/** A world-event arc's kind (돌발 세계 사건, design ENG 6.1); [major] arcs run one at a time. Saved by name. */
enum class ArcKind(val major: Boolean, val label: String) {
    SUPER_LEAGUE(true, "슈퍼리그 분리 위협"),
    INVESTOR(false, "투자자 인수"),
    CRISIS(true, "금융 위기"),
    MERGER(true, "리그 통합"),
    BOOM(true, "요트 붐"),
}

/** An arc's stage; [code] is saved. */
enum class ArcStage(val code: Char) {
    RUMOUR('R'), ANNOUNCED('A'), SHOWDOWN('S'), ACTIVE('X'), RECOVERY('V'), VOTE('O'), DONE('D');

    companion object {
        fun of(code: Char): ArcStage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * An arc under way (`ev`): its [kind] and [stage] since season [start]; [next] the season whose
 * close (or winter checkpoint, [atWinter]) takes the next step; its [subjects] (clubs, leagues or
 * nations) and [args]; [outcome] '-' until decided (C collapse, P compromise, S success, E exit,
 * M merged, F failed, N new league, L lapsed).
 *
 * Each kind's stages and args ([WorldEvents]):
 * - SUPER_LEAGUE: RUMOUR (from the close it starts at) → ANNOUNCED at the next winter checkpoint
 *   ([subjects] the founding clubs) → at the next close the showdown and the outcome C or P, when
 *   it ends; ended, [args] are the stayers (','-joined), the reform's old `clDirect` ("-": none) and
 *   the collapse's owner sales (';'-joined `club:type:ambition:patience`).
 * - INVESTOR: RUMOUR ([subjects] the target club) → ANNOUNCED (인수 협상) at the next winter, [args]
 *   the investor's owner type code and name → ACTIVE (the takeover) at that season's close → its
 *   verdict three closes later, outcome S, E (args + the new 지역 기업 owner's `ambition:patience`) or
 *   F (it stays); L when the club was gone by the takeover (no takeover) or by the verdict.
 * - CRISIS: ACTIVE ([subjects] the scope: a [Confed] name or [WorldEvents.WORLD]) → RECOVERY two
 *   closes on → ends a close later; [args] the clubs in administration during it (','-joined).
 * - MERGER: VOTE ([subjects] the two nations) → at the next close ACTIVE (merged; [args] yes and
 *   clubs of each nation) or ended F → ended M at the close it splits.
 * - BOOM: ACTIVE ([subjects] the nation; [args] its members then) → ended N (its own league next
 *   season) or L (after six closes).
 */
data class WorldArc(
    val id: Int, val kind: ArcKind, val stage: ArcStage, val start: Int,
    val next: Int,
    val atWinter: Boolean,
    val subjects: List<String>,
    val args: List<String>,
    val outcome: Char = '-',
)

/** An arc that ended (`evh`, the newest 30 kept) at the close of season [end], with the [args] it ended with ([WorldArc]). */
data class EndedArc(
    val id: Int, val kind: ArcKind, val start: Int, val end: Int, val outcome: Char, val subjects: List<String>,
    val args: List<String> = emptyList(),
)

/** A takeover an arc decides for the next off-season: club [club]'s new owner of [type] (named by the off-season when [name] is null). */
data class Takeover(val club: String, val type: OwnerType, val ambition: Int, val patience: Int, val name: String?, val arc: Int)

/**
 * What the rest of the engine reads of the arcs for a season (design ENG 6.1, 6.7): the arcs change
 * other books only through this, which the club off-season, the checkpoint, the formation of the
 * leagues, the rookie draw and the federation read. [NONE] is a world without arcs: today's output.
 *
 * The money factors ([sponsorFactor] … [walkoutFactor]) apply to the clubs of the nations in [scope]
 * (null: every nation): a reader takes [at] a club's nation before reading them.
 */
data class ArcEffects(
    val sponsorFactor: Double = 1.0, val tvFactor: Double = 1.0, val injectionFactor: Double = 1.0, val walkoutFactor: Double = 1.0,
    /** Nations without a league of their own ([NationalLeagues.plan]'s merged set). */
    val merged: Set<String> = emptySet(),
    val clPrizeFactor: Double = 1.0,
    /** Nation → factor on its rookie draw weight (요트 붐). */
    val rookieBoost: Map<String, Double> = emptyMap(),
    /** Takeovers; [fines] as a share of the (positive) balance, [injections] as a share of the operating income (owner money). */
    val takeovers: List<Takeover> = emptyList(), val fines: Map<String, Double> = emptyMap(), val injections: Map<String, Double> = emptyMap(),
    /** Club → mood change at the next off-season. */
    val moodShocks: Map<String, Int> = emptyMap(),
    /** The compromise's CL reform, upserted into the federation at every close (ENG 6.8). */
    val emergency: List<Proposal> = emptyList(),
    /** The nations the money factors apply to; null: every nation (a world-wide crisis, or none). */
    val scope: Set<String>? = null,
    /** Club → members its owner sells in the next window, the most valuable first (a 투자 펀드 leaving, ENG 6.3). */
    val sellOff: Map<String, Int> = emptyMap(),
) {
    /** These effects for a club of [nation]: the money factors neutral outside [scope]. */
    fun at(nation: String?): ArcEffects =
        if (scope == null || nation in scope) this else copy(sponsorFactor = 1.0, tvFactor = 1.0, injectionFactor = 1.0, walkoutFactor = 1.0, scope = null)

    companion object {
        val NONE = ArcEffects()
    }
}

/**
 * The world events' part of world.txt (design ENG 6.1): the arcs under way (`ev`), the ended ones
 * (`evh`), when each kind may start again (`cool`) and the leagues in the ranking's bottom quarter
 * of each season (`rb`, 5 seasons, for the merger). The arcs' logic is [WorldEvents]'.
 */
class ArcBook {
    internal val active = ArrayList<WorldArc>()
    internal val endedList = ArrayList<EndedArc>()
    internal val cooldowns = java.util.EnumMap<ArcKind, Int>(ArcKind::class.java)
    internal val bottom = TreeMap<Int, List<String>>()

    /** The arcs under way, by id. */
    val arcs: List<WorldArc> get() = active.sortedBy { it.id }

    /** The ended arcs kept, oldest first. */
    val ended: List<EndedArc> get() = endedList.toList()

    /** The season from which [kind] may start again (0: any time). */
    fun cooldown(kind: ArcKind): Int = cooldowns[kind] ?: 0

    /** The leagues in the bottom quarter of the ranking after [season]. */
    fun bottom(season: Int): List<String> = bottom[season].orEmpty()

    /** The arc of id [id], under way or ended (as an [EndedArc] when ended); null when not kept. */
    fun arc(id: Int): WorldArc? = active.firstOrNull { it.id == id }

    fun endedArc(id: Int): EndedArc? = endedList.firstOrNull { it.id == id }

    /** The next arc's id: one above every id kept (the newest ended arcs are kept, so ids never repeat). */
    internal fun nextId(): Int = maxOf(active.maxOfOrNull { it.id } ?: 0, endedList.maxOfOrNull { it.id } ?: 0) + 1

    /** [arc] in place of the one of its id. */
    internal fun put(arc: WorldArc) {
        active.removeAll { it.id == arc.id }
        active += arc
        active.sortBy { it.id }
    }

    /** Ends [arc] at the close of [season] with [outcome] and [args]; the newest [KEPT] ended arcs are kept. */
    internal fun end(arc: WorldArc, season: Int, outcome: Char, args: List<String> = arc.args) {
        active.removeAll { it.id == arc.id }
        endedList.removeAll { it.id == arc.id }
        endedList += EndedArc(arc.id, arc.kind, arc.start, season, outcome, arc.subjects, args)
        endedList.sortBy { it.id }
        while (endedList.size > KEPT) endedList.removeAt(0)
    }

    companion object {
        /** Ended arcs kept (`evh`), and the seasons of the ranking's bottom quarter (`rb`). */
        const val KEPT = 30
        const val BOTTOM_KEPT = 5

        private fun args(xs: List<String>) = if (xs.isEmpty()) "-" else xs.joinToString("|") { Field.esc(it) }

        private fun argsOf(t: String) = if (t == "-") emptyList() else t.split('|').map { Field.unesc(it) }

        private val EV = LineParts.of<WorldBook>("ev", { w, out ->
            for (a in w.events.active.sortedBy { it.id }) {
                out.append("ev ${a.id} ${a.kind.name} ${a.stage.code} ${a.start} ${a.next} ${LineParts.bit(a.atWinter)} ")
                out.append("${LineParts.list(a.subjects) { it }} ${args(a.args)} ${a.outcome}\n")
            }
        }) { w, f ->
            require(f.size >= 10)
            val stage = ArcStage.of(f[3].single()) ?: return@of false
            w.events.active += WorldArc(f[1].toInt(), ArcKind.valueOf(f[2]), stage, f[4].toInt(), f[5].toInt(), f[6] == "1", LineParts.items(f[7]), argsOf(f[8]), f[9].single())
            true
        }

        // `evh <id> <kind> <start> <end> <outcome> <subjects> [<args>]`: the args last (a line from before them has none).
        private val EVH = LineParts.of<WorldBook>("evh", { w, out ->
            for (a in w.events.endedList) {
                out.append("evh ${a.id} ${a.kind.name} ${a.start} ${a.end} ${a.outcome} ${LineParts.list(a.subjects) { it }}")
                if (a.args.isNotEmpty()) out.append(' ').append(args(a.args))
                out.append('\n')
            }
        }) { w, f ->
            require(f.size >= 7)
            w.events.endedList += EndedArc(f[1].toInt(), ArcKind.valueOf(f[2]), f[3].toInt(), f[4].toInt(), f[5].single(), LineParts.items(f[6]), f.getOrNull(7)?.let(::argsOf).orEmpty())
            true
        }

        private val COOL = LineParts.of<WorldBook>("cool", { w, out ->
            if (w.events.cooldowns.isNotEmpty()) out.append("cool ").append(w.events.cooldowns.entries.joinToString(",") { "${it.key.name}:${it.value}" }).append('\n')
        }) { w, f ->
            for (e in LineParts.items(f[1])) runCatching { w.events.cooldowns[ArcKind.valueOf(e.substringBefore(':'))] = e.substringAfter(':').toInt() }
            true
        }

        private val RB = LineParts.of<WorldBook>("rb", { w, out ->
            for ((s, ids) in w.events.bottom) out.append("rb $s ${LineParts.list(ids) { it }}\n")
        }) { w, f ->
            w.events.bottom[f[1].toInt()] = LineParts.items(f[2])
            true
        }

        /** `ev evh cool rb`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(EV, EVH, COOL, RB)
    }
}

/**
 * What an arc step reads besides the world book (design ENG 6). At a close: the closing [plan], the
 * club book as it was at the close (before its off-season, which runs at the open), the league
 * [ranking] after the closing season, every honour so far (the closing season's included), and
 * [counts], the members of each play nation for the season to come (`NationalLeagues.counts` of the
 * members after the close's evolution; the plan's own members by [nation] when not given). At the
 * winter checkpoint: the season in play and the book as it is then. [value] values a member
 * ([ClubBook.valuer]) for the clubs' values. Only reads its books.
 */
class ArcContext(
    val plan: SeasonPlan,
    /** The rules in force ([LeagueConfig.worldEvents], [LeagueConfig.leagueMin], [LeagueConfig.clDirect]). */
    val config: LeagueConfig,
    /** The national club book (null without one). */
    val clubs: ClubBook? = null,
    val ranking: LeagueRanking = plan.ranking,
    val honours: List<Honour> = emptyList(),
    val value: (String) -> Int = { 0 },
    /** A member's play nation (its club's, else its own); KR when unknown, as [NationalLeagues.home] has it. */
    val nation: (String) -> String? = { k -> clubs?.playNation(k) },
    val counts: Map<String, Int> = plan.league.standings.keys.groupingBy { k -> nation(k)?.takeIf { Nations.of(it) != null } ?: Nations.KR.code }.eachCount(),
) {
    /** Members in the league next season. */
    val members: Int get() = counts.values.sum()
}

/**
 * World events (돌발 세계 사건, design ENG 6): five rare arcs, each a small state machine kept in
 * world.txt ([ArcBook]) with one guard a step ([WorldBook.arcClose], [WorldBook.arcWinter]):
 * - [close] at every national close: the ranking's bottom quarter (`rb`), every arc due its next
 *   stage, then the triggers (with [LeagueConfig.worldEvents] on; arcs under way always finish);
 * - [winter] at the winter checkpoint: the super league's announcement and the investor's talks;
 * - [opening]: the news told at the open after a close (rumours, the super league's outcome);
 * - [effects]: what the arcs do in the window from a close to the next one (pure, by each arc's
 *   fixed schedule from its start and end, so it is the same before and after the close's own step
 *   for everything the close reads before it, and the same on a replay).
 * An arc step never changes another book: the club off-season, the formation of the leagues, the
 * rookie draw and the federation read [effects]. Asked again for a season it did, a step changes
 * nothing and returns its news rebuilt from the book.
 *
 * Triggers at close c draw from `Random(mix(plan.seed, 0xE7E7, c, kind.ordinal))`, in [ArcKind]
 * order; at most one major arc under way, at most one INVESTOR; nothing below [MAJOR_MEMBERS]
 * members but INVESTOR, CRISIS and BOOM; each kind waits out its cooldown (`cool`).
 */
object WorldEvents {
    /** A crisis's scope of every nation. */
    const val WORLD = "WORLD"

    /** Members the SUPER_LEAGUE and MERGER arcs ask for. */
    const val MAJOR_MEMBERS = 500

    // SUPER_LEAGUE (ENG 6.2)
    const val SL_CLUBS = 60
    const val SL_SEASON = 8
    const val SL_COOLDOWN = 15
    const val SL_CHANCE = 0.15
    const val SL_FOUNDERS = 12
    const val SL_VALUE_SHARE = 0.20
    const val SL_RICH_OWNERS = 8
    const val SL_COMPROMISE = 4
    const val SL_MOOD = -15
    const val SL_COLLAPSE_FINE = 0.10
    const val SL_COMPROMISE_FINE = 0.05
    const val SL_SELL = 0.4
    const val SL_PRIZE = 1.25
    const val SL_PRIZE_STEP = 0.05
    const val SL_PRIZE_SEASONS = 5

    // INVESTOR (ENG 6.3)
    const val INV_CHANCE = 0.35
    const val INV_LEAGUES = 16
    const val INV_NOISE = 0.2
    const val INV_FUND = 0.6
    const val INV_INJECTION = 0.8
    const val INV_STATE_INJECTION = 1.2
    const val INV_SEASONS = 3
    const val INV_EXIT = 0.5
    const val INV_SELL_OFF = 2

    // CRISIS (ENG 6.4): the active and recovery factors (sponsor deals, TV, owner money, sponsor walk-outs)
    const val CR_SEASON = 6
    const val CR_COOLDOWN = 12
    const val CR_CHANCE = 0.07
    const val CR_WORLD = 0.5
    val CR_ACTIVE = doubleArrayOf(0.7, 0.85, 0.5, 2.0)
    val CR_RECOVERY = doubleArrayOf(0.85, 0.93, 0.75, 1.5)

    // MERGER (ENG 6.5)
    const val MG_SEASON = 10
    const val MG_COOLDOWN = 20
    const val MG_CHANCE = 0.2
    const val MG_ROOM = 16
    const val MG_BOTTOM = 3
    const val MG_YES = 0.5
    const val MG_SEASONS = 6
    const val MG_SPLIT = 0.3
    const val MG_SPLIT_ROOM = 12

    // BOOM (ENG 6.6)
    const val BM_SEASON = 6
    const val BM_COOLDOWN = 10
    const val BM_CHANCE = 0.08
    const val BM_SHARE = 0.5
    const val BM_FACTOR = 2.0
    const val BM_SEASONS = 6

    private const val TRIGGER_SALT = 0xE7E7L
    private const val STEP_SALT = 0xE7E8L
    private const val NAME_SALT = 0xE7E9L

    /** Test-only: forces a trigger (NationalWorldTest, WinterReplayTest); null in the app. */
    @Volatile
    var forceTrigger: ((ArcKind, Int) -> Boolean)? = null

    // ---- the close ----------------------------------------------------------------------------

    /**
     * The arcs' close of the national season [ArcContext.plan], once (guard [WorldBook.arcClose]):
     * the ranking's bottom quarter after it (`rb`, the newest [ArcBook.BOTTOM_KEPT]), every arc's step
     * due at this close (a winter step it missed first), then the triggers. Returns the close's news
     * ([closeNews]), rebuilt from the book when asked again. To be called before the federation's
     * emergency upsert ([Federation.upsert] of [effects]' `emergency`) and its resolve, so the
     * super league's reform decided now is among this close's adopted proposals. A legacy season has
     * no arcs: its close only sets the guard.
     */
    fun close(book: WorldBook, ctx: ArcContext): List<LeagueEvent> {
        val c = ctx.plan.number
        if (book.arcClose >= c) return closeNews(book, c)
        if (ctx.plan.league.leagues.national) {
            recordBottom(book.events, ctx)
            for (a in book.events.arcs) step(book.events, a, ctx)
            if (ctx.config.worldEvents) trigger(book.events, ctx)
        }
        book.arcClose = c
        return closeNews(book, c)
    }

    /**
     * The arcs' winter step of the season in play [ArcContext.plan] (its winter checkpoint, guard
     * [WorldBook.arcWinter]): a super league's founders announce it, an investor opens talks. Returns
     * the news ([winterNews]), rebuilt when asked again; nothing once the season's close ran.
     */
    fun winter(book: WorldBook, ctx: ArcContext): List<LeagueEvent> {
        val s = ctx.plan.number
        if (book.arcWinter >= s || book.arcClose >= s) return winterNews(book, s)
        for (a in book.events.arcs) if (a.atWinter && a.next <= s) winterStep(book.events, a, ctx)
        book.arcWinter = s
        return winterNews(book, s)
    }

    /** The bottom quarter of the ranking after the closing season (at least one league), of the leagues the season had. */
    private fun recordBottom(e: ArcBook, ctx: ArcContext) {
        val rows = ctx.ranking.rows.filter { ctx.plan.league.leagues.byId(it.id) != null }.sortedBy { it.rank }
        if (rows.isEmpty()) return
        e.bottom[ctx.plan.number] = rows.takeLast(maxOf(1, rows.size / 4)).map { it.id }
        e.bottom.keys.removeAll { it > ctx.plan.number }
        while (e.bottom.size > ArcBook.BOTTOM_KEPT) e.bottom.pollFirstEntry()
    }

    private fun rng(ctx: ArcContext, arc: Int, salt: Long = STEP_SALT): Random = Random(DiceStream.mix(ctx.plan.seed, salt, ctx.plan.number.toLong(), arc.toLong()))

    /** [a]'s step at close c when due: its missed winter step first, then the kind's close stage. */
    private fun step(e: ArcBook, arc: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        var a = arc
        if (a.kind != ArcKind.CRISIS && a.next > c) return
        if (a.atWinter) {
            winterStep(e, a, ctx)
            a = e.arc(a.id) ?: return
        }
        when (a.kind) {
            ArcKind.SUPER_LEAGUE -> showdown(e, a, ctx)
            ArcKind.INVESTOR -> if (a.stage == ArcStage.ACTIVE) verdict(e, a, ctx) else takeover(e, a, ctx)
            ArcKind.CRISIS -> crisis(e, a, ctx)
            ArcKind.MERGER -> if (a.stage == ArcStage.VOTE) vote(e, a, ctx) else split(e, a, ctx)
            ArcKind.BOOM -> boom(e, a, ctx)
        }
    }

    private fun winterStep(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        when (a.kind) {
            ArcKind.SUPER_LEAGUE -> e.put(a.copy(stage = ArcStage.ANNOUNCED, subjects = founders(ctx), atWinter = false))
            ArcKind.INVESTOR -> {
                val (type, name) = investor(ctx, a.id)
                e.put(a.copy(stage = ArcStage.ANNOUNCED, args = listOf(type.code.toString(), name), atWinter = false))
            }
            else -> e.put(a.copy(atWinter = false))
        }
    }

    // ---- SUPER_LEAGUE -------------------------------------------------------------------------

    private fun superOwner(t: OwnerType) = t == OwnerType.TYCOON || t == OwnerType.FUND || t == OwnerType.STATE

    /** Clubs with a club-world state and their values ([ClubEconomy.clubValue] with the members valued by [ArcContext.value]), most valuable first. */
    private fun values(ctx: ArcContext): List<Pair<String, Int>> {
        val book = ctx.clubs ?: return emptyList()
        val w = book.world ?: return emptyList()
        return w.clubs.keys.filter { book.club(it) != null }.map { it to (ClubEconomy.clubValue(book, it, ctx.value) ?: 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenComparing({ it.first }, ClubBook.ID_ORDER))
    }

    private fun ownerOf(book: ClubBook, id: String): Owner = book.world?.economy?.owners?.get(id) ?: Owner.NONE

    /** The founding [SL_FOUNDERS]: the most valuable clubs, those of a 재벌, 투자 펀드 or 국부 펀드 first. */
    private fun founders(ctx: ArcContext): List<String> {
        val book = ctx.clubs ?: return emptyList()
        val (rich, rest) = values(ctx).partition { superOwner(ownerOf(book, it.first).type) }
        return (rich + rest).take(SL_FOUNDERS).map { it.first }
    }

    /**
     * The showdown at the close after the announcement: each founder still there stays with its
     * owner type's chance × (1 − 0.3 × mood / 100); [SL_COMPROMISE] or more stayers make a
     * compromise (the 챔피언스 리그 reform when `clDirect` can go up and the 요트협회's assembly is on,
     * prize money up), fewer a collapse (the stayers' owners sell with [SL_SELL]). The arc ends here.
     */
    private fun showdown(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val book = ctx.clubs
        val rng = rng(ctx, a.id)
        val stayers = ArrayList<String>()
        for (id in a.subjects) {
            val st = book?.world?.clubs?.get(id)?.takeIf { book.club(id) != null }
            val p = when (book?.let { ownerOf(it, id).type }) {
                OwnerType.TYCOON -> 0.55
                OwnerType.FUND -> 0.65
                OwnerType.STATE -> 0.75
                OwnerType.LOCAL -> 0.25
                OwnerType.FANS -> 0.05
                null -> 0.0
            } * (1 - 0.3 * (st?.mood ?: 50) / 100.0)
            if (rng.nextDouble() < p && st != null) stayers += id
        }
        val compromise = stayers.size >= SL_COMPROMISE
        val cl = ctx.config.clDirect
        // The reform is the 요트협회's decision: none with its assembly off (the rules then never change by themselves).
        val reform = if (compromise && ctx.config.federation && ctx.config.with(ProposalKind.CL_DIRECT_UP.lever.key, "${cl + 1}") != null) "$cl" else "-"
        val sales = ArrayList<String>()
        if (!compromise && book != null) for (id in stayers) {
            val old = ownerOf(book, id).type
            if (old == OwnerType.FANS || rng.nextDouble() >= SL_SELL) continue
            val type = when (old) {
                OwnerType.TYCOON -> OwnerType.LOCAL
                OwnerType.LOCAL -> OwnerType.TYCOON
                else -> if (rng.nextBoolean()) OwnerType.TYCOON else OwnerType.LOCAL
            }
            sales += "$id:${type.code}:${draw(ClubEconomy.ambitions(type), rng)}:${draw(ClubEconomy.patiences(type), rng)}"
        }
        e.end(a, ctx.plan.number, if (compromise) 'P' else 'C', listOf(stayers.joinToString(","), reform, sales.joinToString(";")))
    }

    private fun draw(r: IntRange, rng: Random): Int = r.first + rng.nextInt(r.last - r.first + 1)

    // ---- INVESTOR -----------------------------------------------------------------------------

    /** The investor of arc [arc]: a 투자 펀드 ([INV_FUND]) or 국부 펀드, named from the foreign prefixes, not an owner's name already. */
    private fun investor(ctx: ArcContext, arc: Int): Pair<OwnerType, String> {
        val rng = rng(ctx, arc, NAME_SALT)
        val type = if (rng.nextDouble() < INV_FUND) OwnerType.FUND else OwnerType.STATE
        val taken = ctx.clubs?.world?.economy?.owners?.values?.mapTo(HashSet()) { it.name }.orEmpty()
        val start = rng.nextInt(Sponsors.FOREIGN_PREFIXES.size)
        fun name(i: Int) = if (type == OwnerType.FUND) Sponsors.fund(start + i) else Sponsors.state(start + i)
        val i = (0 until Sponsors.FOREIGN_PREFIXES.size).firstOrNull { name(it) !in taken } ?: 0
        return type to name(i)
    }

    /** The takeover at the close after the rumour: the investor (from the talks) becomes the owner; lapsed when the club is gone. */
    private fun takeover(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val club = a.subjects.firstOrNull()
        if (club == null || ctx.clubs?.club(club) == null || ctx.clubs.world?.clubs?.get(club) == null) {
            e.end(a, ctx.plan.number, 'L')
            e.cooldowns[ArcKind.INVESTOR] = ctx.plan.number + 1
            return
        }
        e.put(a.copy(stage = ArcStage.ACTIVE, next = a.start + 1 + INV_SEASONS))
    }

    /**
     * The verdict three closes after the takeover: a club-table title or a member's 챔피언스 리그 /
     * 대륙 챔피언스 title in those seasons is a success; otherwise the investor leaves with
     * [INV_EXIT] (a new 지역 기업 owner; a 투자 펀드 sells its [INV_SELL_OFF] most valuable members),
     * else it stays. A club gone by then has no verdict: lapsed (L), as one gone by the takeover.
     */
    private fun verdict(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        val club = a.subjects.first()
        val book = ctx.clubs
        val seasons = (a.start + 2)..c
        var success = false
        if (book != null) {
            success = seasons.any { s -> s < c && club in book.champions(s) } ||
                (book.club(club) != null && ClubTable.all(ctx.plan.league, book, ctx.honours.filter { it.season == c }, splits = book.winterMoves(c), deductions = ClubEconomy.deductions(book))
                    .values.any { it.firstOrNull()?.club == club }) ||
                ctx.honours.any { h -> h.season in seasons && (h.kind == "CL" || HonourKind.base(h.kind) == "CCL") && book.clubOf(h.key) == club }
        }
        val rng = rng(ctx, a.id)
        val outcome = when {
            book != null && book.club(club) == null -> 'L'
            success -> 'S'
            book != null && rng.nextDouble() < INV_EXIT -> 'E'
            else -> 'F'
        }
        val args = if (outcome == 'E') a.args.take(2) + "${draw(ClubEconomy.ambitions(OwnerType.LOCAL), rng)}:${draw(ClubEconomy.patiences(OwnerType.LOCAL), rng)}" else a.args.take(2)
        e.end(a, c, outcome, args)
        e.cooldowns[ArcKind.INVESTOR] = c + 1
    }

    // ---- CRISIS -------------------------------------------------------------------------------

    /**
     * The crisis at every close after it began: the clubs that were in administration at the off-season
     * before it (or dissolved for money since it began) noted, RECOVERY after two closes, the end after
     * three. Most clubs in administration are rescued at that same off-season (their `admin` back to 0),
     * so the season's 법정관리 deduction tells them too.
     */
    private fun crisis(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        if (c <= a.start) return
        val book = ctx.clubs
        val scope = scopeNations(a.subjects.firstOrNull())
        val admin = LinkedHashSet(list(a.args.firstOrNull()))
        if (book != null) {
            book.world?.clubs?.values?.filter {
                (it.admin > 0 || it.deduction >= ClubEconomy.ADMIN_DEDUCTION) && book.club(it.id) != null && (scope == null || book.nationOf(it.id) in scope)
            }?.forEach { admin += it.id }
            // Dissolved at an off-season of the crisis: those after closes start .. c − 1 (a dissolution's season is the closing one).
            book.gone.filter { it.reason == 'F' && it.dissolved >= a.start && (scope == null || it.nation in scope) }.forEach { admin += it.id }
        }
        // None: no args at all, as the file gives them back.
        val args = if (admin.isEmpty()) emptyList() else listOf(admin.sortedWith(ClubBook.ID_ORDER).joinToString(","))
        when {
            c >= a.start + 3 -> e.end(a, c, '-', args)
            c >= a.start + 2 -> e.put(a.copy(stage = ArcStage.RECOVERY, next = a.start + 3, args = args))
            else -> e.put(a.copy(args = args))
        }
    }

    /** A crisis scope's nations: null for the world, else the confederation's. */
    private fun scopeNations(scope: String?): Set<String>? {
        if (scope == null || scope == WORLD) return null
        val confed = Confed.entries.firstOrNull { it.name == scope } ?: return null
        return Nations.ALL.filter { it.confed == confed }.mapTo(HashSet()) { it.code }
    }

    // ---- MERGER -------------------------------------------------------------------------------

    /**
     * The vote at the close after the proposal: each club of the two nations votes yes when the merged
     * league's λ (the better rank of the two) is above its own league's, else yes with [MG_YES]; a
     * majority of each nation's clubs merges them from the next season on.
     */
    private fun vote(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        val book = ctx.clubs
        val rng = rng(ctx, a.id)
        val n = ctx.ranking.rows.size
        val best = a.subjects.minOf { ctx.ranking.rank(it) }
        val tally = ArrayList<String>()
        var passed = true
        for (nation in a.subjects) {
            val clubs = book?.clubsOf(nation).orEmpty().map { it.id }.sortedWith(ClubBook.ID_ORDER)
            val own = LeagueEconomy.lambda(ctx.ranking.rank(nation), n)
            val merged = LeagueEconomy.lambda(best, n)
            val yes = clubs.count { merged > own || rng.nextDouble() < MG_YES }
            tally += "$yes"
            tally += "${clubs.size}"
            if (yes * 2 <= clubs.size) passed = false
        }
        if (passed) e.put(a.copy(stage = ArcStage.ACTIVE, next = a.start + 1 + MG_SEASONS, args = tally)) else e.end(a, c, 'F', tally)
    }

    /** From [MG_SEASONS] merged seasons on, at each close: the split with [MG_SPLIT] when either nation has leagueMin + [MG_SPLIT_ROOM] members. */
    private fun split(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        val room = ctx.config.leagueMin + MG_SPLIT_ROOM
        val big = a.subjects.any { (ctx.counts[it] ?: 0) >= room }
        if (rng(ctx, a.id).nextDouble() < MG_SPLIT && big) e.end(a, c, 'M') else e.put(a.copy(next = c + 1))
    }

    // ---- BOOM ---------------------------------------------------------------------------------

    /** At each close after it began: done (N) when the nation's members give it a league of its own next season, else lapsed (L) after [BM_SEASONS]. */
    private fun boom(e: ArcBook, a: WorldArc, ctx: ArcContext) {
        val c = ctx.plan.number
        val nation = a.subjects.first()
        val last = ctx.plan.league.leagues.list.map { it.id }.toSet()
        val own = NationalLeagues.plan(ctx.counts, ctx.config, last, effectsAt(e, c).merged)[nation] == nation
        when {
            own -> e.end(a, c, 'N')
            c >= a.start + BM_SEASONS -> e.end(a, c, 'L')
            else -> e.put(a.copy(next = c + 1))
        }
    }

    // ---- triggers -----------------------------------------------------------------------------

    /** The triggers of close c, in kind order (ENG 6.2-6.6), after every arc's step. */
    private fun trigger(e: ArcBook, ctx: ArcContext) {
        val c = ctx.plan.number
        val force = forceTrigger
        for (kind in ArcKind.entries) {
            val forced = force?.invoke(kind, c) == true
            if (kind.major && e.active.any { it.kind.major }) continue
            if (kind == ArcKind.INVESTOR && e.active.any { it.kind == ArcKind.INVESTOR }) continue
            if (!forced && c < e.cooldown(kind)) continue
            if (!forced && (kind == ArcKind.SUPER_LEAGUE || kind == ArcKind.MERGER) && ctx.members < MAJOR_MEMBERS) continue
            val rng = Random(DiceStream.mix(ctx.plan.seed, TRIGGER_SALT, c.toLong(), kind.ordinal.toLong()))
            val roll = rng.nextDouble()
            val arc = when (kind) {
                ArcKind.SUPER_LEAGUE -> superLeague(e, ctx, roll, forced)
                ArcKind.INVESTOR -> investorTarget(e, ctx, rng, roll, forced)
                ArcKind.CRISIS -> crisisScope(e, ctx, rng, roll, forced)
                ArcKind.MERGER -> mergerPair(e, ctx, rng, roll, forced)
                ArcKind.BOOM -> boomNation(e, ctx, rng, roll, forced)
            } ?: continue
            e.put(arc)
            when (kind) {
                ArcKind.SUPER_LEAGUE -> e.cooldowns[kind] = c + SL_COOLDOWN
                ArcKind.CRISIS -> e.cooldowns[kind] = c + CR_COOLDOWN
                ArcKind.MERGER -> e.cooldowns[kind] = c + MG_COOLDOWN
                ArcKind.BOOM -> e.cooldowns[kind] = c + BM_COOLDOWN
                ArcKind.INVESTOR -> {}
            }
        }
    }

    private fun newArc(e: ArcBook, kind: ArcKind, stage: ArcStage, c: Int, atWinter: Boolean, subjects: List<String>, args: List<String> = emptyList()) =
        WorldArc(e.nextId(), kind, stage, c, c + 1, atWinter, subjects, args)

    /** ≥ [SL_CLUBS] clubs, season ≥ [SL_SEASON], the [SL_FOUNDERS] most valuable holding ≥ [SL_VALUE_SHARE] of the value or ≥ [SL_RICH_OWNERS] of them rich owners; [SL_CHANCE]. */
    private fun superLeague(e: ArcBook, ctx: ArcContext, roll: Double, forced: Boolean): WorldArc? {
        val c = ctx.plan.number
        val book = ctx.clubs ?: return null
        // The chance first: the clubs' values cost a valuation of every member.
        if (!forced && (c < SL_SEASON || roll >= SL_CHANCE)) return null
        val values = values(ctx)
        if (values.size < SL_FOUNDERS) return null
        if (!forced) {
            if (values.size < SL_CLUBS) return null
            val top = values.take(SL_FOUNDERS)
            val total = values.sumOf { it.second.toLong() }
            val share = if (total <= 0) 0.0 else top.sumOf { it.second.toLong() }.toDouble() / total
            if (share < SL_VALUE_SHARE && top.count { superOwner(ownerOf(book, it.first).type) } < SL_RICH_OWNERS) return null
        }
        return newArc(e, ArcKind.SUPER_LEAGUE, ArcStage.RUMOUR, c, true, emptyList())
    }

    /**
     * The investor's target ([INV_CHANCE] a close): among 지역 기업 clubs and 팬 소유 clubs in the red,
     * out of money trouble and of other arcs, of the [INV_LEAGUES] best-ranked leagues, the one with the
     * most `fanPercentile × (1 − placePercentile) + U(0, 0.2)` (a big following, a poor season).
     */
    private fun investorTarget(e: ArcBook, ctx: ArcContext, rng: Random, roll: Double, forced: Boolean): WorldArc? {
        if (!forced && roll >= INV_CHANCE) return null
        val book = ctx.clubs ?: return null
        val w = book.world ?: return null
        val busy = e.active.flatMap { it.subjects }.toSet()
        val states = w.clubs.values.filter { book.club(it.id) != null }
        if (states.isEmpty()) return null
        val byFans = states.sortedWith(compareBy<ClubState> { it.fans }.thenComparing({ it.id }, ClubBook.ID_ORDER)).map { it.id }
        val fanPct = byFans.withIndex().associate { (i, id) -> id to if (byFans.size <= 1) 0.5 else i.toDouble() / (byFans.size - 1) }
        val tables = ClubTable.all(ctx.plan.league, book, ctx.honours.filter { it.season == ctx.plan.number }, splits = book.winterMoves(ctx.plan.number), deductions = ClubEconomy.deductions(book))
        val placePct = HashMap<String, Double>()
        val leagueOf = HashMap<String, String>()
        for ((league, table) in tables) for ((i, row) in table.withIndex()) {
            if (placePct.containsKey(row.club)) continue
            placePct[row.club] = if (table.size <= 1) 0.5 else (table.size - 1 - i).toDouble() / (table.size - 1)
            leagueOf[row.club] = league
        }
        var best: Pair<String, Double>? = null
        for (st in states.sortedWith(compareBy(ClubBook.ID_ORDER) { it.id })) {
            val owner = ownerOf(book, st.id).type
            val noise = rng.nextDouble() * INV_NOISE
            if (!(owner == OwnerType.LOCAL || (owner == OwnerType.FANS && st.balance < 0))) continue
            if (st.trouble > 0 || st.admin > 0 || st.id in busy) continue
            val league = leagueOf[st.id] ?: continue
            if (ctx.ranking.rank(league) >= INV_LEAGUES) continue
            val score = fanPct.getValue(st.id) * (1 - (placePct[st.id] ?: 0.5)) + noise
            if (best == null || score > best.second) best = st.id to score
        }
        val club = best?.first ?: return null
        return newArc(e, ArcKind.INVESTOR, ArcStage.RUMOUR, ctx.plan.number, true, listOf(club))
    }

    /** Season ≥ [CR_SEASON], [CR_CHANCE]; the world ([CR_WORLD]) or one confederation drawn by its clubs' debt. Needs a club world. */
    private fun crisisScope(e: ArcBook, ctx: ArcContext, rng: Random, roll: Double, forced: Boolean): WorldArc? {
        val c = ctx.plan.number
        if (!forced && (c < CR_SEASON || roll >= CR_CHANCE)) return null
        val book = ctx.clubs ?: return null
        val w = book.world ?: return null
        val debt = java.util.EnumMap<Confed, Long>(Confed::class.java)
        for (st in w.clubs.values) {
            if (st.balance >= 0 || book.club(st.id) == null) continue
            val confed = book.nationOf(st.id)?.let { Nations.confedOf(it) } ?: continue
            debt.merge(confed, -st.balance.toLong(), Long::plus)
        }
        val total = debt.values.sum()
        val scope = if (rng.nextDouble() < CR_WORLD || total <= 0) WORLD else {
            var x = rng.nextDouble() * total
            Confed.entries.firstOrNull { cf -> (debt[cf] ?: 0L).let { d -> x -= d; d > 0 && x < 0 } }?.name ?: WORLD
        }
        return newArc(e, ArcKind.CRISIS, ArcStage.ACTIVE, c, false, listOf(scope)).copy(next = c + 2)
    }

    /**
     * Two national leagues of one confederation in the ranking's bottom quarter in each of the last
     * [MG_BOTTOM] seasons, each below leagueMin + [MG_ROOM] members and together at least twice
     * leagueMin, both with clubs; season ≥ [MG_SEASON], [MG_CHANCE]; the pair drawn among those.
     */
    private fun mergerPair(e: ArcBook, ctx: ArcContext, rng: Random, roll: Double, forced: Boolean): WorldArc? {
        val c = ctx.plan.number
        if (!forced && (c < MG_SEASON || roll >= MG_CHANCE)) return null
        val book = ctx.clubs ?: return null
        val low = (c - MG_BOTTOM + 1..c).map { e.bottom(it).toSet() }.reduce { a, b -> a intersect b }
        val min = ctx.config.leagueMin
        val ids = Nations.ALL.map { it.code }.filter { id ->
            id in low && ctx.plan.league.leagues.byId(id) != null && (ctx.counts[id] ?: 0) < min + MG_ROOM && book.clubsOf(id).isNotEmpty()
        }
        val pairs = ArrayList<Pair<String, String>>()
        for ((i, a) in ids.withIndex()) for (b in ids.drop(i + 1)) {
            if (Nations.confedOf(a) == Nations.confedOf(b) && (ctx.counts[a] ?: 0) + (ctx.counts[b] ?: 0) >= 2 * min) pairs += a to b
        }
        if (pairs.isEmpty()) return null
        val (a, b) = pairs[rng.nextInt(pairs.size)]
        return newArc(e, ArcKind.MERGER, ArcStage.VOTE, c, false, listOf(a, b))
    }

    /**
     * A nation playing in a 연합 리그 with ≥ [BM_SHARE] × leagueMin members (drawn by members), and not
     * about to have a league of its own next season anyway; season ≥ [BM_SEASON], [BM_CHANCE].
     */
    private fun boomNation(e: ArcBook, ctx: ArcContext, rng: Random, roll: Double, forced: Boolean): WorldArc? {
        val c = ctx.plan.number
        if (!forced && (c < BM_SEASON || roll >= BM_CHANCE)) return null
        val leagues = ctx.plan.league.leagues
        val merged = effectsAt(e, c).merged
        val floor = BM_SHARE * ctx.config.leagueMin
        val next = NationalLeagues.plan(ctx.counts, ctx.config, leagues.list.map { it.id }.toSet(), merged)
        val nations = Nations.ALL.map { it.code }.filter { n ->
            leagues.byId(n) == null && n !in merged && (ctx.counts[n] ?: 0) >= floor && next[n] != n
        }
        if (nations.isEmpty()) return null
        var x = rng.nextDouble() * nations.sumOf { ctx.counts.getValue(it) }
        val nation = nations.firstOrNull { x -= ctx.counts.getValue(it); x < 0 } ?: nations.last()
        return newArc(e, ArcKind.BOOM, ArcStage.ACTIVE, c, false, listOf(nation), listOf("${ctx.counts.getValue(nation)}"))
    }

    // ---- effects ------------------------------------------------------------------------------

    /**
     * What the arcs of [book] do in the window from the close of season [season] to the next close
     * (pure): the rookies drawn at that close, the emergency upserted at it, the off-season after it
     * (the season's accounts and the club life: money factors, takeovers, injections, fines, mood
     * shocks; the leagues of season + 1: merged nations) and the winter checkpoint of season + 1.
     * By each arc's fixed schedule from its start and end, so a close reads the same before and after
     * its own step for what it reads before it (the rookie boost), and a replay reads the same:
     * - SUPER_LEAGUE ended at e: at e the fines (C 10%, P 5% of the stayers' balances), the mood
     *   shock of the founders, C's owner sales and P's emergency reform; P's prize factor at e + 1 ..
     *   e + 5;
     * - INVESTOR from s: the takeover at s + 1 and the injections at s + 1 .. s + 3; an exit (E) at
     *   its end: a 지역 기업 takeover (and a 투자 펀드's sell-off);
     * - CRISIS from s: active factors at s and s + 1, recovery at s + 2;
     * - MERGER from s, passed: merged at s + 1 .. (its end) − 1;
     * - BOOM from s: the rookie boost at s + 1 .. its end (at most s + [BM_SEASONS]).
     * Neutral ([ArcEffects.NONE]) without arcs.
     */
    fun effects(book: WorldBook, season: Int): ArcEffects = effectsAt(book.events, season)

    private fun effectsAt(e: ArcBook, c: Int): ArcEffects {
        if (e.active.isEmpty() && e.endedList.isEmpty()) return ArcEffects.NONE
        var out = ArcEffects.NONE
        for (r in records(e)) out = combine(out, effectsOf(r, c))
        return out
    }

    /** An arc under way or ended, as the news and the effects read it ([end] null while under way). */
    private class Rec(val id: Int, val kind: ArcKind, val start: Int, val end: Int?, val stage: ArcStage, val outcome: Char, val subjects: List<String>, val args: List<String>)

    private fun records(e: ArcBook): List<Rec> = (
        e.active.map { Rec(it.id, it.kind, it.start, null, it.stage, it.outcome, it.subjects, it.args) } +
            e.endedList.map { Rec(it.id, it.kind, it.start, it.end, ArcStage.DONE, it.outcome, it.subjects, it.args) }
        ).sortedBy { it.id }

    private fun list(s: String?): List<String> = LineParts.items(s.orEmpty().ifEmpty { "-" })

    /** An INVESTOR arc whose takeover happened (under way since it, or ended after it; not lapsed at it: the club was gone). */
    private fun tookOver(r: Rec): Boolean = r.stage == ArcStage.ACTIVE || (r.end != null && (r.outcome != 'L' || r.end > r.start + 1))

    private fun effectsOf(r: Rec, c: Int): ArcEffects = when (r.kind) {
        ArcKind.SUPER_LEAGUE -> {
            val e = r.end
            when {
                e == null -> ArcEffects.NONE
                c == e -> {
                    val stayers = list(r.args.getOrNull(0))
                    val compromise = r.outcome == 'P'
                    val fine = if (compromise) SL_COMPROMISE_FINE else SL_COLLAPSE_FINE
                    val sales = if (compromise) emptyList() else LineParts.items(r.args.getOrNull(2).orEmpty().ifEmpty { "-" }, ';').mapNotNull { s ->
                        val p = s.split(':')
                        val type = p.getOrNull(1)?.singleOrNull()?.let { OwnerType.of(it) } ?: return@mapNotNull null
                        Takeover(p[0], type, p.getOrNull(2)?.toIntOrNull() ?: 3, p.getOrNull(3)?.toIntOrNull() ?: 3, null, r.id)
                    }
                    val reform = r.args.getOrNull(1)?.toIntOrNull()?.takeIf { compromise }?.let { from ->
                        Proposal(e - 1, Proposal.EMERGENCY_NUMBER, ProposalKind.CL_DIRECT_UP, "$from", "${from + 1}", 1.0, listOf("${r.id}"), result = Proposal.YES, by = Proposal.EMERGENCY)
                    }
                    ArcEffects(
                        takeovers = sales, fines = stayers.associateWith { fine }, moodShocks = r.subjects.associateWith { SL_MOOD },
                        emergency = listOfNotNull(reform),
                    )
                }
                r.outcome == 'P' && c in e + 1..e + SL_PRIZE_SEASONS -> {
                    val s = list(r.args.getOrNull(0)).size
                    ArcEffects(clPrizeFactor = SL_PRIZE + SL_PRIZE_STEP * minOf(5, maxOf(0, s - SL_COMPROMISE)))
                }
                else -> ArcEffects.NONE
            }
        }
        ArcKind.INVESTOR -> {
            val club = r.subjects.firstOrNull()
            val type = r.args.getOrNull(0)?.singleOrNull()?.let { OwnerType.of(it) }
            val took = tookOver(r)
            when {
                club == null || type == null || !took -> ArcEffects.NONE
                c == r.end && r.outcome == 'E' -> {
                    val p = r.args.getOrNull(2)?.split(':')
                    ArcEffects(
                        takeovers = listOf(Takeover(club, OwnerType.LOCAL, p?.getOrNull(0)?.toIntOrNull() ?: 3, p?.getOrNull(1)?.toIntOrNull() ?: 3, null, r.id)),
                        sellOff = if (type == OwnerType.FUND) mapOf(club to INV_SELL_OFF) else emptyMap(),
                    )
                }
                c in r.start + 1..r.start + INV_SEASONS -> {
                    val injection = mapOf(club to if (type == OwnerType.STATE) INV_STATE_INJECTION else INV_INJECTION)
                    if (c == r.start + 1) ArcEffects(takeovers = listOf(Takeover(club, type, 5, 2, r.args.getOrNull(1), r.id)), injections = injection)
                    else ArcEffects(injections = injection)
                }
                else -> ArcEffects.NONE
            }
        }
        ArcKind.CRISIS -> {
            val f = when (c) {
                r.start, r.start + 1 -> CR_ACTIVE
                r.start + 2 -> CR_RECOVERY
                else -> null
            }
            if (f == null) ArcEffects.NONE else ArcEffects(f[0], f[1], f[2], f[3], scope = scopeNations(r.subjects.firstOrNull()))
        }
        ArcKind.MERGER -> {
            val passed = (r.end == null && r.stage == ArcStage.ACTIVE) || (r.end != null && r.outcome == 'M')
            if (passed && c >= r.start + 1 && c < (r.end ?: Int.MAX_VALUE)) ArcEffects(merged = r.subjects.toSet()) else ArcEffects.NONE
        }
        ArcKind.BOOM -> {
            val last = minOf(r.end ?: Int.MAX_VALUE, r.start + BM_SEASONS)
            val nation = r.subjects.firstOrNull()
            if (nation != null && c in r.start + 1..last) ArcEffects(rookieBoost = mapOf(nation to BM_FACTOR)) else ArcEffects.NONE
        }
    }

    /** Two arcs' effects together: factors multiplied, sets joined, the first takeover of a club kept, fines and sell-offs at the larger, injections and mood shocks added. */
    private fun combine(a: ArcEffects, b: ArcEffects): ArcEffects {
        if (b == ArcEffects.NONE) return a
        if (a == ArcEffects.NONE) return b
        fun <K> sum(x: Map<K, Double>, y: Map<K, Double>) = (x.keys + y.keys).associateWith { (x[it] ?: 0.0) + (y[it] ?: 0.0) }
        return ArcEffects(
            a.sponsorFactor * b.sponsorFactor, a.tvFactor * b.tvFactor, a.injectionFactor * b.injectionFactor, a.walkoutFactor * b.walkoutFactor,
            a.merged + b.merged, a.clPrizeFactor * b.clPrizeFactor,
            (a.rookieBoost.keys + b.rookieBoost.keys).associateWith { (a.rookieBoost[it] ?: 1.0) * (b.rookieBoost[it] ?: 1.0) },
            a.takeovers + b.takeovers.filter { t -> a.takeovers.none { it.club == t.club } },
            (a.fines.keys + b.fines.keys).associateWith { maxOf(a.fines[it] ?: 0.0, b.fines[it] ?: 0.0) },
            sum(a.injections, b.injections),
            (a.moodShocks.keys + b.moodShocks.keys).associateWith { (a.moodShocks[it] ?: 0) + (b.moodShocks[it] ?: 0) },
            a.emergency + b.emergency,
            a.scope ?: b.scope,
            (a.sellOff.keys + b.sellOff.keys).associateWith { maxOf(a.sellOff[it] ?: 0, b.sellOff[it] ?: 0) },
        )
    }

    // ---- news ---------------------------------------------------------------------------------

    /**
     * The news of close [season] from the book (season [season]; the templates are News.kt's):
     * - `crisis_begins` [] (scope: a [Confed] name or [WORLD]) and `boom_begins` [] (nation, members)
     *   for arcs begun at this close;
     * - `superleague_showdown` [stayers] (stayers, founders) for a super league decided now (its
     *   outcome is told at the open, [opening]);
     * - `investor_takeover` [club] (owner name, owner type code) and `investor_verdict` [club] (outcome
     *   S/E/F, owner name, owner type code);
     * - `crisis_ends` [] (seasons, administrations, scope);
     * - `league_merged` [] (A, B) or `merger_failed` [] (A, B, yes A, clubs A, yes B, clubs B) for the
     *   merger's vote, `league_split` [] (A, B) when it ends;
     * - `boom_ends` [] (nation, outcome N/L).
     */
    fun closeNews(book: WorldBook, season: Int): List<LeagueEvent> {
        val c = season
        val out = ArrayList<LeagueEvent>()
        fun news(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, c, keys, args.toList())
        }
        for (r in records(book.events)) when (r.kind) {
            ArcKind.SUPER_LEAGUE -> if (r.end == c) news("superleague_showdown", list(r.args.getOrNull(0)), "${list(r.args.getOrNull(0)).size}", "${r.subjects.size}")
            ArcKind.INVESTOR -> {
                val club = r.subjects.take(1)
                val name = r.args.getOrNull(1).orEmpty()
                val type = r.args.getOrNull(0).orEmpty()
                if (r.start == c - 1 && tookOver(r)) news("investor_takeover", club, name, type)
                if (r.end == c && r.outcome in "SEF") news("investor_verdict", club, r.outcome.toString(), name, type)
            }
            ArcKind.CRISIS -> {
                if (r.start == c) news("crisis_begins", emptyList(), r.subjects.firstOrNull() ?: WORLD)
                if (r.end == c) news("crisis_ends", emptyList(), "${r.end - r.start}", "${list(r.args.getOrNull(0)).size}", r.subjects.firstOrNull() ?: WORLD)
            }
            ArcKind.MERGER -> {
                val (a, b) = r.subjects.getOrElse(0) { "" } to r.subjects.getOrElse(1) { "" }
                if (r.start == c - 1 && r.outcome == 'F' && r.end == c) news("merger_failed", emptyList(), a, b, *r.args.toTypedArray())
                else if (r.start == c - 1 && (r.stage == ArcStage.ACTIVE || r.outcome == 'M')) news("league_merged", emptyList(), a, b)
                if (r.end == c && r.outcome == 'M') news("league_split", emptyList(), a, b)
            }
            ArcKind.BOOM -> {
                if (r.start == c) news("boom_begins", emptyList(), r.subjects.firstOrNull().orEmpty(), r.args.firstOrNull() ?: "0")
                if (r.end == c) news("boom_ends", emptyList(), r.subjects.firstOrNull().orEmpty(), r.outcome.toString())
            }
        }
        return out
    }

    /**
     * The news of the winter checkpoint of [season] (season [season]), once its winter step ran:
     * `superleague_announce` [founders] (founders) and `investor_talks` [club] (owner name, owner type code).
     */
    fun winterNews(book: WorldBook, season: Int): List<LeagueEvent> {
        if (book.arcWinter < season) return emptyList()
        val out = ArrayList<LeagueEvent>()
        for (r in records(book.events)) {
            if (r.start != season - 1) continue
            when (r.kind) {
                ArcKind.SUPER_LEAGUE -> if (r.subjects.isNotEmpty()) out += LeagueEvent("superleague_announce", season, r.subjects, listOf("${r.subjects.size}"))
                ArcKind.INVESTOR -> if (r.args.size >= 2) out += LeagueEvent("investor_talks", season, r.subjects.take(1), listOf(r.args[1], r.args[0]))
                else -> {}
            }
        }
        return out
    }

    /**
     * The news told at the open after close [closing] (season [closing] + 1): `superleague_rumour`
     * [] (founders to come), `investor_rumour` [club] and `merger_proposed` [] (A, B) for arcs begun at
     * that close, and `superleague_outcome` [stayers] (outcome C/P, the reform's `clDirect` from →
     * to as "7>8" or "-", the prize factor × 100 or "100") for a super league decided at it. Pure.
     */
    fun opening(book: WorldBook, closing: Int): List<LeagueEvent> {
        val s = closing + 1
        val out = ArrayList<LeagueEvent>()
        for (r in records(book.events)) when (r.kind) {
            ArcKind.SUPER_LEAGUE -> {
                if (r.start == closing) out += LeagueEvent("superleague_rumour", s, emptyList(), listOf("$SL_FOUNDERS"))
                if (r.end == closing) {
                    val stayers = list(r.args.getOrNull(0))
                    val reform = r.args.getOrNull(1)?.toIntOrNull()?.takeIf { r.outcome == 'P' }?.let { "$it>${it + 1}" } ?: "-"
                    val prize = effectsAt(book.events, s).clPrizeFactor
                    out += LeagueEvent("superleague_outcome", s, stayers, listOf(r.outcome.toString(), reform, "${Math.round(prize * 100)}"))
                }
            }
            ArcKind.INVESTOR -> if (r.start == closing) out += LeagueEvent("investor_rumour", s, r.subjects.take(1), emptyList())
            ArcKind.MERGER -> if (r.start == closing) out += LeagueEvent("merger_proposed", s, emptyList(), r.subjects)
            else -> {}
        }
        return out
    }
}
