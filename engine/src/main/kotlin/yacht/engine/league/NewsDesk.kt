package yacht.engine.league

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 뉴스 데스크 (design_ux 5.2): the in-season news diet. The engine writes the off-season's and the
 * close's digests at the source; the desk takes the season's match news and competition rounds
 * and writes each week's best of them when the week is over:
 * - the week's league matches and knockout ties it was offered ([offer]): at most [UPSETS]
 *   `league_upset` (a winner rated [Ladder.UPSET] or more below, in a 1부 or 2부) and
 *   [COMEBACKS] `comeback` (the deciding game won from a P(win) of [COMEBACK_P] or less);
 * - the club race of the best-ranked leagues (`club_race`), the international stage's points
 *   (`intl_week`, once a stage of the 챔피언스 리그 or the 대륙 챔피언스 is over), the players near a
 *   record (`record_chase`);
 * - the digests of what it holds back ([sift]): a stage of more than [SMALL_STAGE] ties of the
 *   요트컵 or the 대륙 챔피언스 (its sections together) keeps its [SINGLES] most notable ties as
 *   articles and makes one `cup_digest` when it ends; the CL and CCL group stages keep
 *   [GROUP_SINGLES] top seeds out as articles and make one `group_digest` of their qualifiers;
 *   the week's milestones keep [MILESTONE_SINGLES] as articles (the top of a ladder, a first
 *   300-point game) and make one `milestones_week`.
 * Everything pending is saved in news.txt ([encode], lines older readers skip) and a week is
 * closed once ([close]): read back after a kill, the desk goes on as it was. Pure and
 * deterministic given the results; not thread-safe (the world's lock).
 */
class NewsDesk {
    /** What a week's close reads. */
    class Context(
        val plan: SeasonPlan,
        val stats: StatsBook,
        /** The national clubs (null without them): the club race. */
        val clubs: ClubBook? = null,
        /** True for a match an article already links: a week played again after a kill is not written twice. */
        val written: (String) -> Boolean = { false },
    )

    /** A match the desk may write up when its week closes ([LeagueEvent.week]): bigger [weight] first. */
    data class Candidate(val event: LeagueEvent, val weight: Double)

    /** The season it collects for. */
    var season = 0
        private set

    /** The week being collected: every week before it is closed. */
    var week = 0
        private set

    private val pending = ArrayList<Candidate>()

    /** The big knockout stages held, by "competition code:stage". */
    private val stages = java.util.TreeMap<String, CupStage>()

    /** Held milestones by week, then "kind:value". */
    private val milestones = java.util.TreeMap<Int, MutableMap<String, MilestoneGroup>>()

    /** Held group qualifiers by competition, then week. */
    private val groups = java.util.TreeMap<String, Qualifiers>()

    /** Each international competition's stages over and its leagues' points when last told. */
    private val snapshots = java.util.TreeMap<Comp, Snapshot>()

    /** The club race: each watched league's leader and its lead (x 10) when last looked at. */
    private val races = java.util.TreeMap<String, Race>()

    /** Record chases told this season ("KIND:key"). */
    private val told = java.util.TreeSet<String>()

    /**
     * What this season's digests already told: the knockout stages released ("code:stage"), the
     * group stages ("code" + their section's tag) digested. A tie or a group stage's end played
     * again after a kill is not told twice.
     */
    private val released = java.util.TreeSet<String>()
    private val digested = java.util.TreeSet<String>()

    /** The refs of the matches waiting to be picked (the match center keeps their logs a while: NEWS). */
    val candidates: Set<String> get() = pending.mapTo(LinkedHashSet()) { MatchRef.decode(it.event.ref.orEmpty())?.whole?.encode() ?: it.event.ref.orEmpty() }

    /** Held events and candidates, for the size budget and the tests. */
    val held: Int get() = pending.size + stages.values.sumOf { s -> s.items.sumOf { it.events.size } } + milestones.values.sumOf { m -> m.values.sumOf { it.keys.size } } +
        groups.values.sumOf { it.keys.size + it.gone.size }

    // ---- the season ------------------------------------------------------------------------------

    /** Starts season [plan] afresh at the week it is in (what was pending of another season is dropped). */
    fun begin(plan: SeasonPlan) {
        season = plan.number
        week = runCatching { plan.week }.getOrDefault(1)
        pending.clear()
        stages.clear()
        milestones.clear()
        groups.clear()
        snapshots.clear()
        races.clear()
        told.clear()
        released.clear()
        digested.clear()
        // Points the international competitions start with (a 챔피언스 리그 place) are no week's news.
        if (plan.league.leagues.national) for (c in plan.comps) {
            if (c !is International) continue
            runCatching { snapshots[c.comp] = Snapshot(stagesDone(c), pointsByLeague(plan, c)) }
        }
    }

    /**
     * A finished official job of the season, recorded in the statistics but not yet on the ladder
     * ([rating] is the ladder's from before it): a league match won by a player rated
     * [Ladder.UPSET] or more below in a 1부 or 2부 is a `league_upset` candidate, and a league
     * match or knockout tie whose winner won its last game from a P(win) of [COMEBACK_P] or less
     * a `comeback` candidate.
     */
    fun offer(r: JobResult, plan: SeasonPlan, rating: (String) -> Double) {
        if (plan.number != season) return
        val f = r.fixture
        if (!f.rated || r.winner == 0 || f.kind == JobKind.TEAM || f.comp in UNOFFICIAL) return
        val b = r.boards.singleOrNull()?.takeIf { it.games.isNotEmpty() } ?: return
        val (w, l) = if (r.winner > 0) b.a to b.b else b.b to b.a
        // A late result of a week already closed goes into the week being collected.
        val at = if (f.week == SeasonCalendar.NONE) week else maxOf(f.week, week)
        val league = plan.league
        val d = if (f.comp == Comp.LEAGUE) league.divisionOf(w) else null
        val id = d?.let { league.leagues.of(it).id }
        val tier = d?.let { league.leagues.tier(it) }
        if (d != null && tier != null) {
            val gap = rating(l) - rating(w)
            if (!gap.isNaN() && gap >= Ladder.UPSET && tier < UPSET_TIERS) {
                val rank = if (league.leagues.national) plan.ranking.rank(id!!).takeIf { it != Int.MAX_VALUE }?.toString() ?: "-" else "0"
                val e = LeagueEvent(
                    "league_upset", season, listOf(w, l), listOf(gap.roundToInt().toString(), id!!.ifEmpty { "-" }, tier.toString(), score(r, b, w), rank),
                    Comp.LEAGUE, at, MatchRef(season, f.comp, f.id).encode(),
                )
                add(Candidate(e, gap))
            }
        }
        // The deciding game: the last, won by the match's winner.
        val (xs, ys) = runCatching { StatsBook.sides(b, pairs = f.kind == JobKind.TIE && f.firstTo > 0) }.getOrNull() ?: return
        val i = b.games.lastIndex
        val g = (if (w == b.a) xs else ys).getOrNull(i) ?: return
        if (g.own <= g.opp || g.minP.isNaN() || g.minP > COMEBACK_P) return
        val p = maxOf(1, (g.minP * 100).roundToInt())
        val e = LeagueEvent(
            "comeback", season, listOf(w, l),
            listOf(p.toString(), f.comp.code.toString(), f.label.ifBlank { "-" }, id?.ifEmpty { "-" } ?: "-", tier?.toString() ?: "-"),
            f.comp, at, MatchRef(season, f.comp, f.id, 0, i).encode(),
        )
        add(Candidate(e, 1.0 - g.minP))
    }

    /** "2-0", or a match level on games won on its totals as "합계 431-420", from the winner's side [w]. */
    private fun score(r: JobResult, b: BoardResult, w: String): String {
        val s = TieScore.of(r)
        if (s.scoreA != s.scoreB) return s.text()
        val (x, y) = if (w == b.a) b.totalA to b.totalB else b.totalB to b.totalA
        return "합계 $x-$y"
    }

    private fun add(c: Candidate) {
        pending.removeAll { it.event.ref == c.event.ref && it.event.kind == c.event.kind }
        pending += c
        // Each kind keeps its best.
        val same = pending.filter { it.event.kind == c.event.kind }
        if (same.size > MAX_CANDIDATES / 2) pending -= same.sortedWith(BEST).last()
    }

    /**
     * The events of [events] to write up now; the others wait for a digest. [f] is the fixture
     * they come from (none for the statistics' and the close's): the ties of a big stage of the
     * 요트컵 or the 대륙 챔피언스 wait for its end, which writes its most notable ties and its
     * `cup_digest` (returned here when [f] finished the stage). Milestones and the CL and CCL
     * group stages' qualifiers and top seeds out wait for the week's close.
     */
    fun sift(events: List<LeagueEvent>, plan: SeasonPlan?, f: Fixture? = null): List<LeagueEvent> {
        if (plan == null || plan.number != season) return events
        // Every stage of the 요트컵 is a knockout's; the 대륙 챔피언스' are once its ties are told (its group matchdays never are).
        val knockout = f != null && (f.comp == Comp.CUP || f.comp in STAGE_COMPS && events.any { it.kind == "cup_round" })
        val stage = if (knockout) runCatching { bigStage(plan, f!!) }.getOrNull() else null
        val out = ArrayList<LeagueEvent>(events.size)
        val tie = ArrayList<LeagueEvent>()
        for (e in events) {
            when {
                e.kind == "qualified" && e.comp in GROUP_COMPS -> holdGroup(e, out = false)
                e.kind == "eliminated" && e.comp in GROUP_COMPS && e.args.firstOrNull() == GROUP_STAGE -> holdGroup(e, out = true)
                stage != null && e.comp == f?.comp && e.kind in CUP_HELD -> tie += e
                e.kind == "milestone" && e.args.firstOrNull() in MILESTONE_KINDS -> holdMilestone(e)
                else -> out += e
            }
        }
        if (stage != null) {
            if (tie.isNotEmpty()) stage.add(tie, f!!, plan)
            if (runCatching { stageCount(plan, stage)?.done == true }.getOrDefault(false)) out += release(stage, plan)
        }
        return out
    }

    /** The stage of [f] when it has more than [SMALL_STAGE] ties, held from now on; null for a small stage or one already told. */
    private fun bigStage(plan: SeasonPlan, f: Fixture): CupStage? {
        val c = plan.competition(f.comp) ?: return null
        val s = c.stageOf(f).takeIf { it >= 0 } ?: return null
        val key = "${f.comp.code}:$s"
        if (key in released) return null
        val ties = c.stageCounts().getOrNull(s)?.total ?: return null
        if (ties <= SMALL_STAGE) return null
        return stages.getOrPut(key) {
            val label = c.stages().getOrNull(s)?.label ?: f.label
            val w = plan.calendar.week(f.comp, s).takeIf { it != SeasonCalendar.NONE } ?: f.week
            CupStage(f.comp, s, label, maxOf(w, week))
        }
    }

    private fun stageCount(plan: SeasonPlan, s: CupStage): StageCount? = plan.competition(s.comp)?.stageCounts()?.getOrNull(s.stage)

    /** Stage [s]'s most notable ties as they were told, then its `cup_digest`; the stage is let go. */
    private fun release(s: CupStage, plan: SeasonPlan): List<LeagueEvent> {
        stages.remove(s.key)
        released += s.key
        val ranked = s.items.sortedWith(NOTABLE)
        val singles = ranked.filter { it.gap >= KnockoutNews.UPSET_GAP || it.seed <= TOP_SEEDS }.take(SINGLES)
        val rest = ranked - singles.toSet()
        val out = ArrayList<LeagueEvent>()
        for (t in singles) out += t.events
        val ties = stageCount(plan, s)?.total ?: 0
        val next = plan.calendar.week(s.comp, s.stage + 1).takeIf { it != SeasonCalendar.NONE && it > 0 }
        val named = rest.mapNotNull { it.winner }.distinct().take(DIGEST_KEYS)
        out += LeagueEvent(
            "cup_digest", season, named, listOf(s.label, ties.toString(), s.upsets.toString(), s.out ?: "-", next?.toString() ?: "-"), s.comp, s.week,
        )
        return out
    }

    /** An in-season milestone: the top of its ladder and a first 300-point game are told alone (at most [MILESTONE_SINGLES] a week), the rest in the week's digest. */
    private fun holdMilestone(e: LeagueEvent) {
        val kind = e.args.getOrNull(0) ?: return
        val value = e.args.getOrNull(1)?.toIntOrNull() ?: return
        val key = e.keys.firstOrNull() ?: return
        val w = if (e.week == SeasonCalendar.NONE) week else maxOf(e.week, week)
        val byKind = milestones.getOrPut(w) { java.util.TreeMap() }
        val g = byKind.getOrPut("$kind:$value") { MilestoneGroup(kind, value) }
        if (key in g.keys) return
        if (byKind.values.sumOf { it.keys.size } >= MAX_HELD) g.extra++ else g.keys += key
    }

    /** True for a milestone told alone: the top of its ladder, or a first 300-point game. */
    private fun single(kind: String, value: Int): Boolean = when (kind) {
        "games" -> value >= TOP_GAMES
        "wins" -> value >= TOP_WINS
        "yachts" -> value >= TOP_YACHTS
        else -> kind == "300" && value == 1
    }

    /**
     * A group stage's qualifier, or a top seed out of it ([out]): the week's digest counts them and
     * its [GROUP_SINGLES] first top seeds out are told alone. Once a section: a replay's are dropped.
     */
    private fun holdGroup(e: LeagueEvent, out: Boolean) {
        val key = e.keys.firstOrNull() ?: return
        val c = e.comp ?: return
        val section = section(e)
        if (section in digested) return
        val w = if (e.week == SeasonCalendar.NONE) week else maxOf(e.week, week)
        val q = groups.getOrPut(c.code.toString()) { Qualifiers(c, w) }
        q.week = minOf(q.week, w)
        q.sections += section
        if (out) {
            if (q.gone.none { it.keys.firstOrNull() == key }) q.gone += e
            return
        }
        q.keys += key
        // Group winners are named first.
        if (e.args.getOrNull(1)?.endsWith(" 1위") == true) q.firsts += key
    }

    /** A group event's stage: its competition's code and its section's tag ("@아시아 챔피언스"; none for the 챔피언스 리그). */
    private fun section(e: LeagueEvent): String = (e.comp?.code?.toString() ?: "-") + (e.args.lastOrNull()?.takeIf { it.startsWith("@") } ?: "")

    // ---- the week's close ---------------------------------------------------------------------

    /**
     * Closes every week before [now] (the week the season is in): the week's picks, the club
     * race, the international stage, the digests of what it held, a record chase. Once per week:
     * called again for a week already closed it does nothing.
     */
    fun close(ctx: Context, now: Int): List<LeagueEvent> {
        val p = ctx.plan
        if (p.number != season || now <= week) return emptyList()
        val through = now - 1
        val out = ArrayList<LeagueEvent>()
        out += picks(ctx, through)
        out += runCatching { clubRace(ctx, through) }.getOrDefault(emptyList())
        out += runCatching { international(p, through) }.getOrDefault(emptyList())
        for (s in stages.values.toList()) if (runCatching { stageCount(p, s)?.done == true }.getOrDefault(true)) out += release(s, p)
        out += qualifiers(through)
        out += milestonesOf(through)
        out += runCatching { chase(ctx, through) }.getOrDefault(emptyList())
        week = now
        return out
    }

    /** The season's end: everything still pending is written (every held stage released), no race or chase (the season is over). */
    fun flush(ctx: Context): List<LeagueEvent> {
        val p = ctx.plan
        if (p.number != season) return emptyList()
        val last = runCatching { p.week }.getOrDefault(week)
        val out = ArrayList<LeagueEvent>()
        out += picks(ctx, Int.MAX_VALUE)
        out += runCatching { international(p, last) }.getOrDefault(emptyList())
        for (s in stages.values.toList()) out += release(s, p)
        out += qualifiers(Int.MAX_VALUE)
        out += milestonesOf(Int.MAX_VALUE)
        week = maxOf(week, last + 1)
        return out
    }

    /** The week's league upsets and comeback, biggest first; a match is told once (its upset before its comeback). */
    private fun picks(ctx: Context, through: Int): List<LeagueEvent> {
        val due = pending.filter { it.event.week <= through }
        if (due.isEmpty()) return emptyList()
        pending -= due.toSet()
        val out = ArrayList<LeagueEvent>()
        val taken = HashSet<String>()
        for ((kind, cap) in listOf("league_upset" to UPSETS, "comeback" to COMEBACKS)) {
            var n = 0
            for (c in due.filter { it.event.kind == kind }.sortedWith(BEST)) {
                if (n >= cap) break
                val ref = c.event.ref ?: continue
                val match = MatchRef.decode(ref)?.whole?.encode() ?: ref
                if (match in taken || ctx.written(ref)) continue
                taken += match
                out += c.event
                n++
            }
        }
        return out
    }

    /**
     * The club race of the [RACE_LEAGUES] best-ranked leagues (national clubs only): a new club
     * at the top with [LEAD_FROM] of its 1부 played, or the lead down to [CLOSE_GAP] points from
     * [CLOSE_FROM] on (as [News.titleRace]); at most one a week, best-ranked league first.
     */
    private fun clubRace(ctx: Context, through: Int): List<LeagueEvent> {
        val p = ctx.plan
        val book = ctx.clubs?.takeIf { it.format == ClubBook.AFFILIATION } ?: return emptyList()
        val league = p.league
        if (!league.leagues.national) return emptyList()
        val ids = NationalLeagues.bestRanked(p, RACE_LEAGUES)
        if (ids.isEmpty()) return emptyList()
        val honours = p.comps.filter { it.finished }.flatMap { c -> runCatching { c.honours() }.getOrDefault(emptyList()) }
        var lead: LeagueEvent? = null
        var close: LeagueEvent? = null
        val w = through.coerceAtMost(runCatching { p.week }.getOrDefault(through))
        for (id in ids) {
            val d = league.leagues.byId(id)?.top ?: continue
            val rounds = league.roundsOf(d)
            val progress = if (rounds > 0) (league.positions.getOrNull(d)?.size ?: 0).toDouble() / rounds else 0.0
            val table = ClubTable.of(league, book, id, honours)
            if (table.size < 2) continue
            val gap = ((table[0].points - table[1].points) * 10).roundToInt()
            val before = races.put(id, Race(table[0].club, gap)) ?: continue
            val reason = when {
                table[0].club != before.leader && progress >= LEAD_FROM && (gap > 0 || before.gap > 0) -> "lead"
                gap <= CLOSE_GAP * 10 && before.gap > CLOSE_GAP * 10 && progress >= CLOSE_FROM -> "close"
                else -> continue
            }
            val e = LeagueEvent("club_race", season, listOf(table[0].club, table[1].club), listOf(id, gap.toString(), reason), Comp.CLUB, w)
            if (reason == "lead") lead = lead ?: e else close = close ?: e
        }
        return listOfNotNull(lead ?: close)
    }

    /**
     * `intl_week` for each international competition with a stage over since it was last told:
     * the leagues whose players earned the most of their season's coefficient (points per entry,
     * [Coefficients]) in it, at most one a competition and week.
     */
    private fun international(p: SeasonPlan, through: Int): List<LeagueEvent> {
        if (!p.league.leagues.national) return emptyList()
        val out = ArrayList<LeagueEvent>()
        var sums: Map<String, IntArray>? = null
        for (c in p.comps) {
            if (c !is International) continue
            val done = stagesDone(c)
            val now = pointsByLeague(p, c)
            val snap = snapshots[c.comp]
            snapshots[c.comp] = Snapshot(done, now)
            if (snap == null || done <= snap.done) continue
            val all = sums ?: Coefficients.season(p).also { sums = it }
            val gains = now.mapNotNull { (id, pts) ->
                val entries = all[id]?.get(1)?.takeIf { it > 0 } ?: return@mapNotNull null
                val gain = (pts - (snap.points[id] ?: 0)).toDouble() / entries
                if (gain >= MIN_GAIN) id to gain else null
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { LeagueIds.order(it.first) }.thenBy { it.first }).take(3)
            if (gains.isEmpty()) continue
            val label = c.stages().getOrNull(done - 1)?.label ?: continue
            val leader = all.entries.maxWithOrNull(compareBy<Map.Entry<String, IntArray>> { Coefficients.value(it.value) }.thenByDescending { LeagueIds.order(it.key) })?.key
            val w = p.calendar.week(c.comp, done - 1).takeIf { it != SeasonCalendar.NONE }?.coerceAtMost(through) ?: through
            out += LeagueEvent(
                "intl_week", season, emptyList(),
                listOf(c.comp.code.toString(), label) + gains.map { (id, g) -> "$id:${String.format(Locale.ROOT, "%.1f", g)}" } + listOf(if (leader == gains[0].first) "lead" else "-"),
                c.comp, w,
            )
        }
        return out
    }

    /** Stages over from the first ([StageCount.done], a stage with fixtures). */
    private fun stagesDone(c: Competition): Int {
        val counts = c.stageCounts()
        var k = 0
        while (k < counts.size && counts[k].total > 0 && counts[k].done) k++
        return k
    }

    /** [c]'s coefficient points so far by its entrants' leagues. */
    private fun pointsByLeague(p: SeasonPlan, c: Competition): Map<String, Int> {
        val i = c as? International ?: return emptyMap()
        val points = i.coefficientPoints()
        val out = java.util.TreeMap<String, Int>()
        for (k in i.entrantKeys()) {
            val id = p.league.leagueOf(k)?.id ?: continue
            out.merge(id, points[k] ?: 0, Int::plus)
        }
        return out
    }

    /** The group stages held: [GROUP_SINGLES] top seeds out alone, then one `group_digest` per competition. */
    private fun qualifiers(through: Int): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        for ((code, q) in groups.entries.toList()) {
            if (q.week > through) continue
            groups.remove(code)
            digested += q.sections
            out += q.gone.take(GROUP_SINGLES)
            if (q.keys.isEmpty()) continue
            val named = (q.firsts + q.keys).distinct().take(DIGEST_KEYS)
            // Groups of 4, 2 through: as many stay behind as go through.
            out += LeagueEvent(
                "group_digest", season, named, listOf(GROUP_STAGE, q.keys.size.toString(), q.keys.size.toString(), q.gone.size.toString()), q.comp, q.week,
            )
        }
        return out
    }

    /** The held milestones: [MILESTONE_SINGLES] told alone, one `milestones_week` of the others (a lone one alone too). */
    private fun milestonesOf(through: Int): List<LeagueEvent> {
        val weeks = milestones.headMap(through, true)
        if (weeks.isEmpty()) return emptyList()
        val w = weeks.lastKey()
        val all = weeks.values.flatMap { it.values }
        weeks.clear()
        val events = all.flatMap { g -> g.keys.map { k -> LeagueEvent("milestone", season, listOf(k), listOf(g.kind, g.value.toString()), null, w) } }
        val singles = events.filter { single(it.args[0], it.args[1].toInt()) }
            .sortedWith(compareByDescending<LeagueEvent> { it.args[0] != "300" }.thenByDescending { it.args[1].toInt() }.thenBy { it.keys.first() }).take(MILESTONE_SINGLES)
        val rest = events - singles.toSet()
        val extra = all.sumOf { it.extra }
        if (rest.size + extra <= 1) return singles + rest
        // Counts by milestone, the biggest first (as the ladders go: games, wins, Yachts, 300).
        val counts = LinkedHashMap<String, Int>()
        for (g in all.sortedWith(compareBy<MilestoneGroup> { MILESTONE_KINDS.indexOf(it.kind) }.thenByDescending { it.value })) {
            val n = g.keys.count { k -> rest.any { it.keys.first() == k && it.args[0] == g.kind && it.args[1] == g.value.toString() } } + g.extra
            if (n > 0) counts.merge("${g.kind}:${g.value}", n, Int::plus)
        }
        val named = rest.map { it.keys.first() }.distinct().take(MILESTONE_KEYS)
        return singles + LeagueEvent("milestones_week", season, named, counts.map { (k, n) -> "$k:$n" }, null, w)
    }

    /**
     * One player near a record not told yet this season ([RecordChase.near]): runs first, then
     * paces, averages and career counts. Only a record of an earlier season is chased (one set
     * this season is no history yet).
     */
    private fun chase(ctx: Context, through: Int): List<LeagueEvent> {
        val p = ctx.plan
        if (ctx.stats.season != p.number) return emptyList()
        val lines = RecordChase.of(ctx.stats, p, p.league.standings.keys).flatMap { it.second }.filter { l ->
            RecordChase.near(l) && "${l.kind.name}:${l.key}" !in told && (ctx.stats.records[l.kind]?.season ?: p.number) < p.number
        }
        val pick = lines.minWithOrNull(compareBy<ChaseLine> { CHASE_ORDER.indexOf(group(it)) }.thenBy { it.kind.ordinal }.thenBy { it.key }) ?: return emptyList()
        told += "${pick.kind.name}:${pick.key}"
        val w = through.coerceAtMost(runCatching { p.week }.getOrDefault(through))
        return listOf(
            LeagueEvent(
                "record_chase", season, listOf(pick.key, pick.holder),
                listOf(pick.kind.name, chaseText(pick), pick.kind.plain(pick.value), pick.kind.plain(pick.record)), null, w,
            ),
        )
    }

    /** How far [l] is, in Korean ([ChaseLine.text]); a pace or an average level with the record says so. */
    private fun chaseText(l: ChaseLine): String = when {
        l.paced && kotlin.math.abs(l.gap) < 0.5 -> "기록과 같은 페이스"
        l.average && kotlin.math.abs(l.gap) < LEVEL_AVERAGE -> "기록과 거의 같은 수준"
        else -> l.text()
    }

    /** A chase's kind of news: a run, a pace, an average, a career count. */
    private fun group(l: ChaseLine): String = when {
        l.kind == RecordKind.STREAK || l.kind == RecordKind.YACHT_RUN -> "run"
        l.paced -> "pace"
        l.average -> "average"
        else -> "career"
    }

    // ---- saving --------------------------------------------------------------------------------

    /**
     * The desk's lines for news.txt, after the articles (older readers skip them): `desk`
     * (season, week), `c` candidates, `h` held stages with their `t` ties, `m` milestones, `q`
     * group stages with their `g` top seeds out, `p` snapshots, `r` races, `x` chases told, `y`
     * stages released, `z` group sections digested. At most [MAX_CANDIDATES] candidates,
     * [KEEP_TIES] ties a stage and [MAX_HELD] milestones a week: a few KB.
     */
    fun encode(): String = buildString {
        if (season <= 0) return@buildString
        append("desk ").append(season).append(' ').append(week).append('\n')
        for (c in pending) append("c ").append(Field.num(c.weight, 4)).append(' ').append(c.event.encode(withRef = true)).append('\n')
        for (s in stages.values) {
            append("h ").append(s.key).append(' ').append(s.week).append(' ').append(s.upsets).append(' ').append(s.out ?: "-").append(' ').append(s.outSeed)
            append(' ').append(Field.esc(s.label)).append('\n')
            for (t in s.items) {
                append("t ").append(s.key).append(' ').append(t.gap.roundToInt()).append(' ').append(t.seed).append(' ')
                append(t.events.joinToString(RS) { it.encode(withRef = true) }).append('\n')
            }
        }
        for ((w, byKind) in milestones) for (g in byKind.values) {
            append("m ").append(w).append(' ').append(g.kind).append(' ').append(g.value).append(' ').append(g.extra).append(' ').append(g.keys.joinToString(",").ifEmpty { "-" }).append('\n')
        }
        for (q in groups.values) {
            append("q ").append(q.comp.code).append(' ').append(q.week).append(' ').append(q.keys.joinToString(",")).append(' ').append(q.firsts.joinToString(",").ifEmpty { "-" })
            append(' ').append(q.sections.joinToString(",") { Field.esc(it) }.ifEmpty { "-" }).append('\n')
            for (e in q.gone) append("g ").append(q.comp.code).append(' ').append(e.encode(withRef = true)).append('\n')
        }
        for ((c, s) in snapshots) append("p ").append(c.code).append(' ').append(s.done).append(' ').append(s.points.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }).append('\n')
        for ((id, r) in races) append("r ").append(id).append(' ').append(r.leader).append(' ').append(r.gap).append('\n')
        if (told.isNotEmpty()) append("x ").append(told.joinToString(",")).append('\n')
        if (released.isNotEmpty()) append("y ").append(released.joinToString(",")).append('\n')
        if (digested.isNotEmpty()) append("z ").append(digested.joinToString(",") { Field.esc(it) }).append('\n')
    }

    /** The held ties of a big knockout stage of [comp], and its counts beyond those kept. */
    private inner class CupStage(val comp: Comp, val stage: Int, val label: String, val week: Int) {
        val key: String get() = "${comp.code}:$stage"
        val items = ArrayList<TieItem>()

        /** Giant killings in the stage (those dropped from [items] too). */
        var upsets = 0

        /** The best seed it knocked out of the cup, and its seed. */
        var out: String? = null
        var outSeed = Int.MAX_VALUE

        /** The events of one tie ([f]'s): kept with the [KEEP_TIES] most notable of the stage. */
        fun add(events: List<LeagueEvent>, f: Fixture, plan: SeasonPlan) {
            val round = events.firstOrNull { it.kind == "cup_round" }
            val gone = events.firstOrNull { it.kind == "eliminated" }?.keys?.firstOrNull()
            val loser = round?.keys?.getOrNull(1) ?: gone
            val winner = round?.keys?.firstOrNull() ?: listOf(f.a, f.b).firstOrNull { it != loser }
            val gap = events.firstOrNull { it.kind == "upset" }?.args?.firstOrNull()?.toDoubleOrNull() ?: 0.0
            val seeds = seedsOf(plan)
            val seed = minOf(winner?.let { seeds[it] } ?: Int.MAX_VALUE, loser?.let { seeds[it] } ?: Int.MAX_VALUE)
            put(TieItem(events, gap, seed, winner, loser), gone, gone?.let { seeds[it] })
        }

        /** [t] in the stage (a tie told again after a kill replaces itself); [gone] knocked out, of [goneSeed]. */
        fun put(t: TieItem, gone: String?, goneSeed: Int?) {
            if (items.removeAll { it.winner == t.winner && it.loser == t.loser }) {
                if (t.gap >= KnockoutNews.UPSET_GAP) upsets--
            }
            if (t.gap >= KnockoutNews.UPSET_GAP) upsets++
            if (gone != null && (goneSeed ?: Int.MAX_VALUE) < outSeed) {
                out = gone
                outSeed = goneSeed ?: Int.MAX_VALUE
            }
            items += t
            if (items.size > KEEP_TIES) items -= items.sortedWith(NOTABLE).last()
        }
    }

    /** The 요트컵's seeds, read once a season. */
    private var seeds: Map<String, Int>? = null
    private var seedsSeason = -1

    private fun seedsOf(plan: SeasonPlan): Map<String, Int> {
        if (seedsSeason == plan.number) seeds?.let { return it }
        val s = (plan.competition(Comp.CUP) as? BracketCompetition)?.entries?.associate { it.key to it.seed }.orEmpty()
        seeds = s
        seedsSeason = plan.number
        return s
    }

    /** One tie of a held stage: its events as the cup told them, the giant killing's rating [gap], the best [seed] in it. */
    private class TieItem(val events: List<LeagueEvent>, val gap: Double, val seed: Int, val winner: String?, val loser: String?)

    private class MilestoneGroup(val kind: String, val value: Int) {
        val keys = LinkedHashSet<String>()
        var extra = 0
    }

    private class Qualifiers(val comp: Comp, var week: Int) {
        val keys = LinkedHashSet<String>()
        val firsts = LinkedHashSet<String>()
        val sections = java.util.TreeSet<String>()

        /** Top seeds out of the group stage, as told. */
        val gone = ArrayList<LeagueEvent>()
    }

    private class Snapshot(val done: Int, val points: Map<String, Int>)

    private class Race(val leader: String, val gap: Int)

    companion object {
        /** Candidates kept (half a kind) and milestones held a week, at most. */
        const val MAX_CANDIDATES = 24
        const val MAX_HELD = 60

        /** A week's picks. */
        const val UPSETS = 2
        const val COMEBACKS = 1

        /** League upsets are news in the 1부 and 2부. */
        const val UPSET_TIERS = 2

        /** The winner's lowest P(win) in its deciding game that makes a comeback. */
        const val COMEBACK_P = 0.05

        /** A knockout stage of more ties is held for its digest; its most notable ties told alone, and kept. */
        const val SMALL_STAGE = 8
        const val SINGLES = 6
        const val KEEP_TIES = 14

        /** Top seeds out of a group stage told alone, a competition. */
        const val GROUP_SINGLES = 3

        /** A seed this good in a tie makes it notable. */
        const val TOP_SEEDS = 8

        /** Players a digest names. */
        const val DIGEST_KEYS = 8
        const val MILESTONE_KEYS = 20

        /** Milestones told alone a week, and the tops of their ladders. */
        const val MILESTONE_SINGLES = 2
        const val TOP_GAMES = 10_000
        const val TOP_WINS = 1_000
        const val TOP_YACHTS = 1_000

        /** The club race: the leagues watched, from when, and the lead that makes a close race. */
        const val RACE_LEAGUES = 4
        const val LEAD_FROM = 0.25
        const val CLOSE_FROM = 0.7
        const val CLOSE_GAP = 3

        /** An average this close to its record reads as level with it. */
        private const val LEVEL_AVERAGE = 0.005

        /** The least coefficient a league must have earned in a stage to be named. */
        const val MIN_GAIN = 0.05

        /** The group stage's name in a `group_digest`. */
        const val GROUP_STAGE = "조별 리그"

        /** Every kind the desk writes (besides the events it lets through). */
        val KINDS = setOf("league_upset", "comeback", "club_race", "intl_week", "cup_digest", "group_digest", "milestones_week", "record_chase")

        private val UNOFFICIAL = setOf(Comp.FRIENDLY, Comp.LAB, Comp.LEGEND, Comp.ALL_STAR)
        private val CUP_HELD = setOf("cup_round", "upset", "eliminated")

        /** Competitions whose big knockout stages are held: the 요트컵, the 대륙 챔피언스 (its sections' ties together). */
        private val STAGE_COMPS = setOf(Comp.CUP, Comp.CONT_CHAMPIONS)
        private val GROUP_COMPS = setOf(Comp.CHAMPIONS, Comp.CONT_CHAMPIONS)
        private val MILESTONE_KINDS = listOf("games", "wins", "yachts", "300")
        private val CHASE_ORDER = listOf("run", "pace", "average", "career")

        /** Separates a held tie's events on its line. */
        private const val RS = "\u001e"

        private val BEST = compareByDescending<Candidate> { it.weight }.thenBy { it.event.ref.orEmpty() }
        private val NOTABLE = compareBy<TieItem> { if (it.seed <= TOP_SEEDS) it.seed else Int.MAX_VALUE }
            .thenByDescending { it.gap }.thenBy { it.winner.orEmpty() }.thenBy { it.loser.orEmpty() }

        /** The desk of news.txt's lines (an empty desk without any; a bad line is skipped). */
        fun decode(text: String): NewsDesk {
            val d = NewsDesk()
            for (line in text.lines()) {
                runCatching {
                    val tag = line.substringBefore(' ')
                    val rest = line.substringAfter(' ', "")
                    val f = rest.split(' ')
                    when (tag) {
                        "desk" -> {
                            d.season = f[0].toInt()
                            d.week = f[1].toInt()
                        }
                        "c" -> LeagueEvent.decode(rest.substringAfter(' '))?.let { d.pending += Candidate(it, f[0].toDouble()) }
                        "h" -> {
                            val comp = Comp.of(f[0].substringBefore(':').single()) ?: return@runCatching
                            val s = d.CupStage(comp, f[0].substringAfter(':').toInt(), Field.unesc(f[5]), f[1].toInt())
                            s.upsets = f[2].toInt()
                            s.out = f[3].takeIf { it != "-" }
                            s.outSeed = f[4].toInt()
                            d.stages[s.key] = s
                        }
                        "t" -> {
                            val s = d.stages[f[0]] ?: return@runCatching
                            val events = rest.split(' ', limit = 4)[3].split(RS).mapNotNull { LeagueEvent.decode(it) }
                            val round = events.firstOrNull { it.kind == "cup_round" }
                            val gone = events.firstOrNull { it.kind == "eliminated" }?.keys?.firstOrNull()
                            val loser = round?.keys?.getOrNull(1) ?: gone
                            if (events.isNotEmpty()) s.items += TieItem(events, f[1].toDouble(), f[2].toInt(), round?.keys?.firstOrNull(), loser)
                        }
                        "m" -> {
                            val g = d.milestones.getOrPut(f[0].toInt()) { java.util.TreeMap() }.getOrPut("${f[1]}:${f[2].toInt()}") { MilestoneGroup(f[1], f[2].toInt()) }
                            g.extra = f[3].toInt()
                            if (f[4] != "-") g.keys += f[4].split(',').filter { it.isNotEmpty() }
                        }
                        "q" -> {
                            val c = Comp.of(f[0].single()) ?: return@runCatching
                            val q = Qualifiers(c, f[1].toInt())
                            q.keys += f[2].split(',').filter { it.isNotEmpty() }
                            if (f[3] != "-") q.firsts += f[3].split(',').filter { it.isNotEmpty() }
                            f.getOrNull(4)?.takeIf { it != "-" }?.let { q.sections += it.split(',').map(Field::unesc) }
                            d.groups[f[0]] = q
                        }
                        "g" -> LeagueEvent.decode(rest.substringAfter(' '))?.let { d.groups[f[0]]?.gone?.add(it) }
                        "p" -> {
                            val c = Comp.of(f[0].single()) ?: return@runCatching
                            val points = if (f[2] == "-") emptyMap() else f[2].split(',').associate { it.substringBefore(':') to it.substringAfter(':').toInt() }
                            d.snapshots[c] = Snapshot(f[1].toInt(), points)
                        }
                        "r" -> d.races[f[0]] = Race(f[1], f[2].toInt())
                        "x" -> d.told += f[0].split(',').filter { it.isNotEmpty() }
                        "y" -> d.released += f[0].split(',').filter { ':' in it }
                        "z" -> d.digested += f[0].split(',').map(Field::unesc)
                    }
                }
            }
            return d
        }
    }
}
