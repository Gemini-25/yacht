package yacht.engine.league

import java.util.Locale
import java.util.Random

/** A national team: [id] is the nation code, [squad] its 5 best rated players (board 1 first). */
data class NationTeam(val id: String, val squad: List<String>, val strength: Double)

/**
 * One tournament of a [NationCup]: the world cup has one, the continental cups one per
 * confederation. It plays groups [firstGroup] until [firstGroup] + [groupCount] of the cup's group
 * stage, then [knockout] teams (2 = the final only) go on to its own bracket.
 */
class NationSection(val confed: Confed?, val title: String, val firstGroup: Int, val groupCount: Int, val knockout: Int) {
    val groups: IntRange get() = firstGroup until firstGroup + groupCount
}

/**
 * A finished national cup for the records: [place] is how far each team went (0 champion,
 * 1 runner-up, 2 semi-finals, ...; group exits past the bracket), [records] each team's matches
 * won, drawn and lost and its boards for and against (in halves).
 */
class CupSummary(
    val comp: Comp,
    val season: Int,
    val host: String?,
    val teams: List<String>,
    val squads: Map<String, List<String>>,
    val place: Map<String, Int>,
    val records: Map<String, IntArray>,
    val podiums: List<Podium>,
    val mvp: String?,
    val scorer: String?,
    val lines: List<CupLine>,
) {
    class Podium(val confed: Confed?, val winner: String?, val runnerUp: String?, val semis: List<String>)
}

/** A player's line in one cup: team matches (boards) played, won, drawn, lost, and its games. */
class CupLine(val key: String, val team: String) {
    var matches = 0
    var won = 0
    var drawn = 0
    var lost = 0
    var games = 0
    var points = 0
    var best = 0

    /** Boards won, a level board counting half. */
    val boardPoints: Double get() = won + 0.5 * drawn
    val average: Double get() = if (games == 0) Double.NaN else points.toDouble() / games

    fun encode(): String = "p $key $team $matches $won $drawn $lost $games $points $best"
}

/** Extra lines a team competition's screen shows: the host, nations that sat out, leaders, captains. */
data class CompetitionNotes(
    val host: String? = null,
    /** Nations without enough players: (code, players). */
    val absent: List<Pair<String, Int>> = emptyList(),
    /** Best by board points (MVP race), then best by average score (득점왕 race). */
    val mvpRace: List<LeaderLine> = emptyList(),
    val scorerRace: List<LeaderLine> = emptyList(),
    /** Each team's captain (주장). */
    val captains: Map<String, String> = emptyMap(),
)

data class LeaderLine(val key: String, val team: String, val matches: Int, val boardPoints: Double, val average: Double)

/**
 * 국가대항전: [Comp.WORLD_CUP] (요트 월드컵, seasons 3, 7, 11, ...) or [Comp.CONTINENTAL] (대륙컵,
 * seasons 5, 9, 13, ...: one tournament per confederation, all at once). Every nation with 5
 * players or more in the league fields its 5 best rated as a team (board 1 the best); the others
 * sit out. Groups of 3 or 4 drawn from strength pots (the world cup's host leads group A, and no
 * group holds more than 2 nations of one confederation when the draw allows), a single round
 * robin of team matches, then the best [NationSection.knockout] of each tournament by place
 * (compared per match, as group sizes differ) go to a seeded bracket that keeps group mates apart
 * in its first round. A level knockout match goes to the better seed.
 *
 * Honours: "WC"/"WC2"/"WC4" (nations), "WCW" (each winning player: a title), "WCMVP" (most boards
 * won), "WCTOP" (best average score, 3 matches or more), "WCH" (the host); "CC"/"CC2" (nations,
 * one per confederation), "CCW" (winning players); "NSQ" for every squad member, so the next
 * edition can tell who was called up or dropped.
 *
 * News: `wc_open`/`cc_open`, `draw_wc`/`draw_cc` per group and `nation_squad` per changed squad at
 * the start; `upset` and `rivalry` in any match; `ko_set` and `eliminated` (pot 1) when a
 * tournament's groups end; `cup_round`, `final`, `title` and (world cup) `nation_awards` in the
 * knockout. Continental events carry their cup as a last "@아시아컵" argument.
 */
class NationCup private constructor(
    override val comp: Comp,
    val season: Int,
    private val seed: Long,
    val host: String?,
    /** Every team, strongest first. */
    val teams: List<NationTeam>,
    val sections: List<NationSection>,
    /** Every group, the sections' in order, each in pot order. */
    val groups: List<List<DrawEntry>>,
    val absent: List<Pair<String, Int>>,
    val captains: Map<String, String>,
    /** Nations that won this cup before (a first title is news). */
    private val winnersBefore: Set<String>,
    private val announced: List<LeagueEvent>,
) : ViewableCompetition {
    private val world = comp == Comp.WORLD_CUP
    private val byId = teams.associateBy { it.id }
    private val sectionOfGroup = IntArray(groups.size).also { a -> for ((s, sec) in sections.withIndex()) for (g in sec.groups) a[g] = s }
    private val stage = GroupStage(groups, double = false, teams = true, ::groupOpens).also { it.label = ::groupLabel }
    private val brackets = arrayOfNulls<Knockout>(sections.size)

    /** Every squad member's line in this cup. */
    val lines: Map<String, CupLine> = LinkedHashMap<String, CupLine>().also { m -> for (t in teams) for (k in t.squad) m[k] = CupLine(k, t.id) }

    /** The knockout stages of the widest tournament (16강, 8강, 4강, 결승). */
    private val koLabels: List<String> = KO_NAMES.takeLast(log2(sections.maxOf { it.knockout }))

    private fun rounds(s: Int) = log2(sections[s].knockout)

    /** Index in [koLabels] of round [r] of tournament [s]: smaller brackets play the last stages. */
    private fun koIndex(s: Int, r: Int) = koLabels.size - rounds(s) + r

    private fun groupTarget(k: Int) = GROUP_FIRST + GROUP_STEP * k
    private fun groupOpens(k: Int) = groupTarget(k) - EARLY
    private fun koTarget(j: Int) = groupTarget(stage.rounds - 1) + KO_GAP * (j + 1)

    fun groupLabel(g: Int): String {
        val sec = sections[sectionOfGroup[g]]
        val name = if (sec.groupCount == 1) "조별 리그" else "${'A' + (g - sec.firstGroup)}조"
        return if (world) name else "${sec.confed!!.label} $name"
    }

    private fun cupTag(s: Int): List<String> = if (world) emptyList() else listOf("@" + sections[s].title)

    private fun fixture(id: Int, a: String, b: String, label: String): Fixture {
        val x = byId.getValue(a).squad
        val y = byId.getValue(b).squad
        val n = minOf(x.size, y.size)
        return Fixture(comp, id, a, b, Knockout.fixtureSeed(seed, comp, id), JobKind.TEAM, lineupA = x.take(n), lineupB = y.take(n), label = label)
    }

    override fun next(p: Double, busy: Set<String>): Fixture? {
        stage.next(p, busy) { m -> fixture(m.id, m.a, m.b, stage.matchLabel(m)) }?.let { return it }
        for ((s, k) in brackets.withIndex()) {
            k ?: continue
            k.next(p, busy) { t -> fixture(t.id, t.a!!.key, t.b!!.key, (if (world) "" else sections[s].title + " ") + k.label(t.round)) }?.let { return it }
        }
        return null
    }

    override fun release(f: Fixture) {
        if (f.id in stage) stage.release(f.id) else brackets.getOrNull((f.id - KO_BASE) / KO_STEP)?.release(f.id)
    }

    override fun stages(): List<Stage> =
        List(stage.rounds) { k -> Stage("조별 ${k + 1}차전", groupOpens(k), groupTarget(k)) } +
            koLabels.mapIndexed { j, l -> Stage(l, koTarget(j) - EARLY, koTarget(j)) }

    override fun stageOf(f: Fixture): Int {
        if (f.id in stage) return stage.roundOf(f.id)
        val s = (f.id - KO_BASE) / KO_STEP
        val r = brackets.getOrNull(s)?.roundOf(f.id) ?: -1
        return if (r < 0) -1 else stage.rounds + koIndex(s, r)
    }

    override fun stageCounts(): List<StageCount> {
        val played = IntArray(koLabels.size)
        val total = IntArray(koLabels.size)
        for (s in sections.indices) {
            val k = brackets[s]
            val counts = k?.counts() ?: List(rounds(s)) { r -> StageCount(0, sections[s].knockout shr (r + 1)) }
            for ((r, c) in counts.withIndex()) {
                played[koIndex(s, r)] += c.played
                total[koIndex(s, r)] += c.total
            }
        }
        return stage.counts() + koLabels.indices.map { StageCount(played[it], total[it]) }
    }

    override fun record(r: JobResult): List<LeagueEvent> =
        if (Knockout.current(seed, comp, r)) put(r.fixture.id, r.fixture.a, r.fixture.b, TieScore.of(r), r.boards) else emptyList()

    /** Board lines of a team match between [a] and [b] (lineup A's player first on each board). */
    private fun count(boards: List<BoardResult>) {
        for (bd in boards) {
            val res = TieRule.match(bd.games)
            for ((key, sign) in listOf(bd.a to 1, bd.b to -1)) {
                val l = lines[key] ?: continue
                l.matches++
                when (res * sign) {
                    1 -> l.won++
                    -1 -> l.lost++
                    else -> l.drawn++
                }
                for (g in bd.games) {
                    val own = if (sign > 0) g.a else g.b
                    l.games++
                    l.points += own
                    l.best = maxOf(l.best, own)
                }
            }
        }
    }

    private fun put(id: Int, a: String, b: String, s: TieScore, boards: List<BoardResult>?): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        if (id in stage) {
            val m = stage.record(id, a, b, s) ?: return emptyList()
            boards?.let(::count)
            val sec = sectionOfGroup[m.group]
            matchNews(a, b, s, stage.matchLabel(m), sec, out)
            if (brackets[sec] == null && stage.matches.all { sectionOfGroup[it.group] != sec || it.result != null }) out += drawKnockout(sec)
            return out
        }
        val sec = (id - KO_BASE) / KO_STEP
        val k = brackets.getOrNull(sec) ?: return emptyList()
        val t = k.record(id, a, b, s) ?: return emptyList()
        boards?.let(::count)
        val w = t.winner!!.key
        val l = t.loser!!.key
        if (t.round == k.rounds.size - 1) {
            out += LeagueEvent("final", season, listOf(w, l), listOf(t.result!!.text()) + cupTag(sec), comp)
            val title = sections[sec].title
            out += LeagueEvent("title", season, listOf(w), listOfNotNull(title, if (w !in winnersBefore) "first" else null), comp)
            if (world) awardsNews()?.let { out += it }
        } else {
            out += LeagueEvent("cup_round", season, listOf(w, l), listOf(k.label(t.round), t.result!!.text()) + cupTag(sec), comp)
        }
        KnockoutNews.upset(season, comp, t.winner!!, t.loser!!)?.let { out += it.copy(args = it.args + cupTag(sec)) }
        Nations.rivalry(w, l)?.let { out += LeagueEvent("rivalry", season, listOf(w, l), listOf(it, t.result!!.text(), k.label(t.round)) + cupTag(sec), comp) }
        return out
    }

    /** News of a group match: an upset by 100 or more, a rivalry won. */
    private fun matchNews(a: String, b: String, s: TieScore, label: String, sec: Int, out: MutableList<LeagueEvent>) {
        if (s.winner == 0) return
        val (w, l) = if (s.winner > 0) a to b else b to a
        val x = stage.entry(w)
        val y = stage.entry(l)
        if (x != null && y != null) KnockoutNews.upset(season, comp, x, y)?.let { out += it.copy(args = it.args + cupTag(sec)) }
        Nations.rivalry(w, l)?.let { out += LeagueEvent("rivalry", season, listOf(w, l), listOf(it, s.text(), label) + cupTag(sec), comp) }
    }

    /** Tournament [s]'s bracket from its group tables; returns the news. */
    private fun drawKnockout(s: Int): List<LeagueEvent> {
        val sec = sections[s]
        val tables = sec.groups.map { stage.table(it) }
        fun per(x: Int, played: Int) = if (played == 0) 0.0 else x.toDouble() / played
        val across = compareByDescending<Pair<GroupStage.Row, Int>> { per(it.first.points, it.first.played) }
            .thenByDescending { per(stage.diff(it.first), it.first.played) }
            .thenByDescending { per(it.first.scoreDiff, it.first.played) }
            .thenBy { it.first.entry.seed }
        val picked = ArrayList<Pair<GroupStage.Row, Int>>()
        for (place in 0 until (tables.maxOfOrNull { it.size } ?: 0)) {
            val rows = tables.mapIndexedNotNull { g, t -> t.getOrNull(place)?.let { it to g } }.sortedWith(across)
            for (r in rows) if (picked.size < sec.knockout) picked += r
        }
        val seeds = picked.mapIndexed { i, (row, _) -> DrawEntry(row.entry.key, i + 1, row.entry.rating) }
        val group = picked.map { it.second }
        val order = Knockout.seedOrder(sec.knockout)
        // Seed pairs by position (0-based indices into seeds), then group mates apart: swap the
        // lower sides of the nearest tie that makes both pairs cross-group.
        val ties = (0 until sec.knockout / 2).map { intArrayOf(order[2 * it] - 1, order[2 * it + 1] - 1) }
        fun clash(x: Int, y: Int) = x < group.size && y < group.size && group[x] == group[y]
        for (i in ties.indices) {
            if (!clash(ties[i][0], ties[i][1])) continue
            val other = ties.indices.filter { it != i }.sortedBy { Math.abs(it - i) }.firstOrNull { j ->
                !clash(ties[i][0], ties[j][1]) && !clash(ties[j][0], ties[i][1])
            } ?: continue
            val b = ties[i][1]
            ties[i][1] = ties[other][1]
            ties[other][1] = b
        }
        val pairs = ties.map { (x, y) -> seeds.getOrNull(x) to seeds.getOrNull(y) }
        brackets[s] = Knockout(pairs, { 1 }, { r -> koLabels[koIndex(s, r)] }, { r -> koTarget(koIndex(s, r)) - EARLY }, idBase = KO_BASE + KO_STEP * s)
        val out = ArrayList<LeagueEvent>()
        out += LeagueEvent("ko_set", season, seeds.map { it.key }, listOf(koLabels[koIndex(s, 0)]) + cupTag(s), comp)
        // Pot-1 teams (each group's first) that went out: a shock.
        val qualified = seeds.map { it.key }.toSet()
        for (g in sec.groups) groups[g].firstOrNull()?.takeIf { it.key !in qualified }?.let { e ->
            out += LeagueEvent("eliminated", season, listOf(e.key), listOf("조별 리그") + cupTag(s), comp)
        }
        return out
    }

    override val finished: Boolean get() = brackets.all { it?.finished == true }
    override val fixturesTotal: Int get() = stage.fixturesTotal + sections.sumOf { it.knockout - 1 }
    override val fixturesLeft: Int
        get() = stage.fixturesLeft + sections.indices.sumOf { s -> brackets[s]?.fixturesLeft ?: (sections[s].knockout - 1) }

    /** How far each team went: 0 champion, 1 runner-up, 2 semi-finals, ...; past the bracket for group exits. */
    private fun finish(): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((s, k) in brackets.withIndex()) {
            for (t in teams) if (sections[s].groups.any { g -> groups[g].any { it.key == t.id } }) out[t.id] = rounds(s) + 1
            k ?: continue
            for ((r, round) in k.rounds.withIndex()) for (t in round) t.loser?.let { out[it.key] = k.rounds.size - r }
            k.champion?.let { out[it.key] = 0 }
        }
        return out
    }

    /**
     * What this cup leaves to the records ([NationBook]): each team's match record and how far
     * it went, each tournament's podium, the MVP and the top scorer.
     */
    fun summary(): CupSummary {
        val place = finish()
        val records = HashMap<String, IntArray>()
        fun count(a: String, b: String, s: TieScore) {
            val x = records.getOrPut(a) { IntArray(5) }
            val y = records.getOrPut(b) { IntArray(5) }
            when {
                s.winner > 0 -> { x[0]++; y[2]++ }
                s.winner < 0 -> { y[0]++; x[2]++ }
                else -> { x[1]++; y[1]++ }
            }
            // Boards won, in halves.
            x[3] += Math.round(2 * s.scoreA).toInt(); x[4] += Math.round(2 * s.scoreB).toInt()
            y[3] += Math.round(2 * s.scoreB).toInt(); y[4] += Math.round(2 * s.scoreA).toInt()
        }
        for (m in stage.matches) m.result?.let { count(m.a, m.b, it) }
        for (k in brackets) k?.rounds?.forEach { round -> round.forEach { t -> val r = t.result; val a = t.a; val b = t.b; if (r != null && a != null && b != null && !t.bye) count(a.key, b.key, r) } }
        val podiums = sections.indices.map { s ->
            val k = brackets[s]
            val semis = if (k != null && k.rounds.size >= 2) k.rounds[k.rounds.size - 2].mapNotNull { it.loser?.key } else emptyList()
            CupSummary.Podium(sections[s].confed, k?.champion?.key, k?.runnerUp?.key, semis)
        }
        return CupSummary(
            comp, season, host, teams.map { it.id }, teams.associate { it.id to it.squad }, place, records, podiums,
            mvp()?.key, scorer()?.key, lines.values.toList(),
        )
    }

    private fun mvp(): CupLine? {
        val place = finish()
        return lines.values.filter { it.matches > 0 }.maxWithOrNull(
            compareBy<CupLine> { it.boardPoints }.thenByDescending { place[it.team] ?: 99 }.thenBy { it.average }.thenByDescending { it.key },
        )
    }

    private fun scorer(): CupLine? {
        val min = minOf(TOP_MIN, lines.values.maxOfOrNull { it.matches } ?: 0).coerceAtLeast(1)
        return lines.values.filter { it.matches >= min }.maxWithOrNull(compareBy<CupLine> { it.average }.thenBy { it.games }.thenByDescending { it.key })
    }

    private fun awardsNews(): LeagueEvent? {
        val m = mvp() ?: return null
        val t = scorer() ?: return null
        return LeagueEvent("nation_awards", season, listOf(m.key, t.key), listOf(comp.label, num(m.boardPoints), String.format(Locale.ROOT, "%.1f", t.average)), comp)
    }

    override fun honours(): List<Honour> {
        if (!finished) return emptyList()
        val out = ArrayList<Honour>()
        for ((s, k) in brackets.withIndex()) {
            val w = k!!.champion ?: continue
            val tag = if (world) "WC" else "CC"
            out += Honour(season, tag, w.key)
            k.runnerUp?.let { out += Honour(season, tag + "2", it.key) }
            if (world && k.rounds.size >= 2) for (t in k.rounds[k.rounds.size - 2]) t.loser?.let { out += Honour(season, "WC4", it.key) }
            for (p in byId.getValue(w.key).squad) out += Honour(season, tag + "W", p)
            if (s == 0 && world) {
                mvp()?.let { out += Honour(season, "WCMVP", it.key) }
                scorer()?.let { out += Honour(season, "WCTOP", it.key) }
                host?.let { out += Honour(season, "WCH", it) }
            }
        }
        for (t in teams) for (p in t.squad) out += Honour(season, "NSQ", p)
        return out
    }

    override fun opening(): List<LeagueEvent> = announced

    override fun encode(out: StringBuilder) {
        out.append(if (world) WORLD_HEADER else CONT_HEADER).append('\n')
        out.append("host ${host ?: "-"}\n")
        for ((i, t) in teams.withIndex()) out.append("t ").append(DrawEntry(t.id, i + 1, t.strength).encode()).append(' ').append(t.squad.joinToString(",")).append('\n')
        for ((c, n) in absent) out.append("x $c $n\n")
        for ((t, k) in captains) out.append("c $t $k\n")
        for (w in winnersBefore.sorted()) out.append("w $w\n")
        for ((i, s) in sections.withIndex()) out.append("s $i ${s.confed?.name ?: "-"} ${s.firstGroup} ${s.groupCount} ${s.knockout}\n")
        for ((g, members) in groups.withIndex()) for (e in members) out.append("g $g ${e.key}\n")
        for (l in lines.values) if (l.matches > 0) out.append(l.encode()).append('\n')
        stage.encode(out)
        for (k in brackets) k?.encode(out)
    }

    override fun view(): CompetitionView {
        val place = finish()
        val race = lines.values.filter { it.matches > 0 }
        val notes = CompetitionNotes(
            host, absent,
            race.sortedWith(compareByDescending<CupLine> { it.boardPoints }.thenBy { place[it.team] ?: 99 }.thenByDescending { it.average }).take(5)
                .map { LeaderLine(it.key, it.team, it.matches, it.boardPoints, it.average) },
            race.filter { it.matches >= minOf(TOP_MIN, race.maxOfOrNull { l -> l.matches } ?: 0) }
                .sortedByDescending { it.average }.take(5).map { LeaderLine(it.key, it.team, it.matches, it.boardPoints, it.average) },
            captains,
        )
        fun part(s: Int): CompetitionView.Groups {
            val sec = sections[s]
            val k = brackets[s]
            val bracket = CompetitionView.Bracket(sec.title, k?.view() ?: Knockout.placeholder(sec.knockout / 2, { koLabels[koIndex(s, it)] }, { 1 }))
            val ids = sec.groups.flatMap { g -> groups[g].map { it.key } }.toSet()
            return CompetitionView.Groups(sec.title, sec.groups.map { stage.view(it) }, bracket, teams.filter { it.id in ids }.associate { it.id to it.squad }, notes)
        }
        return if (world) part(0) else CompetitionView.Sections(comp.label, sections.indices.map(::part), teams.associate { it.id to it.squad }, notes)
    }

    companion object {
        const val WORLD_HEADER = "worldcup 1"
        const val CONT_HEADER = "continental 1"
        const val SQUAD = 5
        const val MIN_TEAMS = 4

        /** Teams a continental tournament needs. */
        const val MIN_SECTION = 3
        const val TOP_MIN = 3
        private const val TRIES = 30
        private const val KO_BASE = 1000
        private const val KO_STEP = 100
        private const val GROUP_FIRST = 0.30
        private const val GROUP_STEP = 0.04
        private const val KO_GAP = 0.06
        private const val EARLY = 0.02
        private val KO_NAMES = listOf("16강", "8강", "4강", "결승")

        private fun log2(n: Int): Int = 31 - Integer.numberOfLeadingZeros(n)
        private fun num(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else x.toString()

        /** World cups: seasons 3, 7, 11, ...; continental cups: 5, 9, 13, ... */
        fun worldDue(season: Int): Boolean = season >= 3 && season % 4 == 3
        fun continentalDue(season: Int): Boolean = season >= 5 && season % 4 == 1

        /** Groups for [n] teams: one for 4-5, else groups of 3 or 4. */
        fun groupCount(n: Int): Int = if (n <= 5) 1 else (n + 3) / 4

        /** Teams in the bracket: the final only for 4-5, then 4, 8 and 16. */
        fun knockoutSize(n: Int): Int = when {
            n <= 5 -> 2
            n <= 11 -> 4
            n <= 20 -> 8
            else -> 16
        }

        /**
         * The teams of a season, strongest first: each nation's 5 best rated players in the
         * league ([SeasonStart.ranking] order), and the nations with fewer (code, players).
         */
        fun teams(start: SeasonStart): Pair<List<NationTeam>, List<Pair<String, Int>>> {
            val byNation = LinkedHashMap<String, MutableList<String>>()
            for (k in start.ranking) {
                val n = start.nation(k)?.takeIf { Nations.of(it) != null } ?: continue
                byNation.getOrPut(n) { ArrayList() } += k
            }
            val teams = byNation.filterValues { it.size >= SQUAD }.map { (n, keys) ->
                val squad = keys.take(SQUAD)
                NationTeam(n, squad, squad.map(start.rating).average())
            }.sortedWith(compareByDescending<NationTeam> { it.strength }.thenBy { it.id })
            val absent = byNation.filterValues { it.size < SQUAD }.map { it.key to it.value.size }.sortedByDescending { it.second }
            return teams to absent
        }

        private fun confedOf(code: String): Confed? = Nations.of(code)?.confed

        /** 요트 월드컵 of this season; null when off, not due, or with fewer than 4 teams. */
        fun world(start: SeasonStart): NationCup? {
            if (!start.config.nationCup || !worldDue(start.number)) return null
            val (teams, absent) = teams(start)
            if (teams.size < MIN_TEAMS) return null
            val rng = Random(Knockout.drawSeed(start.seed, Comp.WORLD_CUP))
            val host = host(start, teams, rng)
            val ordered = teams.filter { it.id == host } + teams.filter { it.id != host }
            val entries = ordered.mapIndexed { i, t -> DrawEntry(t.id, i + 1, t.strength) }
            val g = groupCount(teams.size)
            val groups = drawGroups(entries, g, rng)
            val sections = listOf(NationSection(null, Comp.WORLD_CUP.label, 0, g, knockoutSize(teams.size)))
            return build(Comp.WORLD_CUP, start, host, ordered, sections, groups, absent)
        }

        /** 대륙컵 of this season: one tournament per confederation with 3 teams or more; null when none. */
        fun continental(start: SeasonStart): NationCup? {
            if (!start.config.nationCup || !continentalDue(start.number)) return null
            val (teams, absent) = teams(start)
            val rng = Random(Knockout.drawSeed(start.seed, Comp.CONTINENTAL))
            val sections = ArrayList<NationSection>()
            val groups = ArrayList<List<DrawEntry>>()
            val ordered = ArrayList<NationTeam>()
            for (c in Confed.entries) {
                val ts = teams.filter { confedOf(it.id) == c }
                if (ts.size < MIN_SECTION) continue
                val entries = ts.mapIndexed { i, t -> DrawEntry(t.id, ordered.size + i + 1, t.strength) }
                val g = groupCount(ts.size)
                sections += NationSection(c, c.cup, groups.size, g, knockoutSize(ts.size))
                groups += FamilyCup.draw(entries, rng).let { gs -> if (gs.size == g) gs else listOf(entries) }
                ordered += ts
            }
            if (sections.isEmpty()) return null
            val playing = ordered.map { it.id }.toSet()
            // Nations of a confederation too small for a tournament sit out too.
            val out = absent + teams.filter { it.id !in playing }.map { it.id to SQUAD }
            return build(Comp.CONTINENTAL, start, null, ordered, sections, groups, out)
        }

        private fun build(
            comp: Comp,
            start: SeasonStart,
            host: String?,
            teams: List<NationTeam>,
            sections: List<NationSection>,
            groups: List<List<DrawEntry>>,
            absent: List<Pair<String, Int>>,
        ): NationCup {
            val kind = if (comp == Comp.WORLD_CUP) "WC" else "CC"
            val winners = start.history.filter { it.kind == kind && it.season < start.number }.map { it.key }.toSet()
            val selections = start.history.filter { it.kind == "NSQ" && it.season < start.number }.groupingBy { it.key }.eachCount()
            val captains = teams.associate { t -> t.id to (t.squad.maxByOrNull { selections[it] ?: 0 } ?: t.squad.first()) }
            val news = ArrayList<LeagueEvent>()
            val n = start.number
            if (comp == Comp.WORLD_CUP) {
                news += LeagueEvent("wc_open", n, listOfNotNull(host), listOf(teams.size.toString()), comp)
                val ko = KO_NAMES.takeLast(log2(sections[0].knockout)).first()
                for ((g, members) in groups.withIndex()) {
                    val label = if (sections[0].groupCount == 1) "조별 리그" else "${'A' + g}조"
                    news += LeagueEvent("draw_wc", n, members.map { it.key }, listOfNotNull(label, ko, if (g == 0) host else null), comp)
                }
            } else {
                news += LeagueEvent("cc_open", n, emptyList(), listOf(sections.joinToString(", ") { it.title }), comp)
                for (s in sections) for (g in s.groups) {
                    val label = if (s.groupCount == 1) "조별 리그" else "${'A' + (g - s.firstGroup)}조"
                    news += LeagueEvent("draw_cc", n, groups[g].map { it.key }, listOf(label, "@" + s.title), comp)
                }
            }
            news += squadNews(start, teams, if (comp == Comp.WORLD_CUP) comp.label else null, sections)
            return NationCup(comp, n, start.seed, host, teams, sections, groups, absent, captains, winners, news)
        }

        /**
         * `nation_squad` [new players..., dropped players...] (nation, cup, new count) for each
         * nation whose squad changed since the latest earlier national squads ("NSQ" honours);
         * a nation that had no team then has nobody to compare with.
         */
        private fun squadNews(start: SeasonStart, teams: List<NationTeam>, cup: String?, sections: List<NationSection>): List<LeagueEvent> {
            val earlier = start.history.filter { it.kind == "NSQ" && it.season < start.number }
            val last = earlier.maxOfOrNull { it.season } ?: return emptyList()
            val before = earlier.filter { it.season == last }.map { it.key }.toSet()
            val byNation = before.groupBy { start.nation(it) }
            val inLeague = start.ranking.toSet()
            val out = ArrayList<LeagueEvent>()
            for (t in teams) {
                val was = byNation[t.id] ?: continue
                val new = t.squad.filter { it !in before }
                val dropped = was.filter { it !in t.squad && it in inLeague }
                if (new.isEmpty() && dropped.isEmpty()) continue
                val label = cup ?: sections.first { s -> s.confed == confedOf(t.id) }.title
                out += LeagueEvent("nation_squad", start.number, new + dropped, listOf(t.id, label, new.size.toString()), if (cup != null) Comp.WORLD_CUP else Comp.CONTINENTAL)
            }
            return out
        }

        /**
         * The world cup's host: the confederations take turns (by earlier "WCH" honours), a nation
         * with a team that has not hosted one of the last 3, drawn; the next confederation when
         * that one has none.
         */
        private fun host(start: SeasonStart, teams: List<NationTeam>, rng: Random): String {
            val hosts = start.history.filter { it.kind == "WCH" && it.season < start.number }.sortedBy { it.season }.map { it.key }
            val recent = hosts.takeLast(3).toSet()
            for (c in 0 until Confed.entries.size) {
                val confed = Confed.entries[(hosts.size + c) % Confed.entries.size]
                val pick = teams.filter { confedOf(it.id) == confed && it.id !in recent }
                if (pick.isNotEmpty()) return pick[rng.nextInt(pick.size)].id
            }
            return teams.first().id
        }

        /**
         * Groups from pots of [groups] (pot 1: the host and the strongest), the host first in
         * group A; drawn again (up to [TRIES] times) while a group has 3 nations of one
         * confederation.
         */
        fun drawGroups(entries: List<DrawEntry>, groups: Int, rng: Random): List<List<DrawEntry>> {
            var last: List<List<DrawEntry>> = emptyList()
            repeat(TRIES) {
                val gs = List(groups) { ArrayList<DrawEntry>() }
                for ((p, pot) in entries.chunked(groups).withIndex()) {
                    val order = if (p == 0) listOf(pot.first()) + pot.drop(1).shuffled(rng) else pot.shuffled(rng)
                    for ((i, e) in order.withIndex()) gs[i] += e
                }
                last = gs
                if (gs.all { g -> g.groupingBy { confedOf(it.key) }.eachCount().values.all { it <= 2 } }) return gs
            }
            return last
        }

        private fun codec(comp: Comp, header: String) = CompetitionCodec { lines, season, seed ->
            require(lines.firstOrNull()?.trim() == header) { "not a $header section" }
            var host: String? = null
            val teams = ArrayList<Pair<DrawEntry, List<String>>>()
            val absent = ArrayList<Pair<String, Int>>()
            val captains = LinkedHashMap<String, String>()
            val winners = HashSet<String>()
            val sections = ArrayList<NationSection>()
            val placed = ArrayList<Pair<Int, String>>()
            val saved = ArrayList<List<String>>()
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    when (f[0]) {
                        "host" -> host = f[1].takeIf { it != "-" }
                        "t" -> teams += DrawEntry.decode(f, 1) to f[4].split(',').filter { it.isNotEmpty() }
                        "x" -> absent += f[1] to f[2].toInt()
                        "c" -> captains[f[1]] = f[2]
                        "w" -> winners += f[1]
                        "s" -> sections += NationSection(f[2].takeIf { it != "-" }?.let { Confed.valueOf(it) }, "", f[3].toInt(), f[4].toInt(), f[5].toInt())
                        "g" -> placed += f[1].toInt() to f[2]
                        "p" -> if (f.size >= 10) saved += f
                    }
                }
            }
            val entries = teams.filter { it.second.isNotEmpty() }.distinctBy { it.first.key }.sortedBy { it.first.seed }
            val byKey = entries.associate { it.first.key to it.first }
            val count = (placed.maxOfOrNull { it.first } ?: -1) + 1
            val groups = List(count) { g -> placed.filter { it.first == g }.mapNotNull { byKey[it.second] }.distinct() }
            require(sections.isNotEmpty() && groups.isNotEmpty() && groups.all { it.isNotEmpty() }) { "unusable $header groups" }
            require(sections.all { s -> s.groups.last < groups.size && s.knockout >= 2 && s.knockout and (s.knockout - 1) == 0 }) { "unusable $header sections" }
            val titled = sections.map { s -> NationSection(s.confed, s.confed?.cup ?: comp.label, s.firstGroup, s.groupCount, s.knockout) }
            val inGroups = groups.flatten().map { it.key }.toSet()
            val squads = entries.filter { it.first.key in inGroups }.map { (e, squad) -> NationTeam(e.key, squad, e.rating) }
            NationCup(comp, season, seed, host, squads, titled, groups, absent, captains, winners, emptyList()).also { c ->
                for (r in TieScore.Saved.parse(lines)) c.put(r.id, r.a, r.b, r.score, null)
                for (f in saved) {
                    val l = c.lines[f[1]] ?: continue
                    l.matches = f[3].toInt(); l.won = f[4].toInt(); l.drawn = f[5].toInt(); l.lost = f[6].toInt()
                    l.games = f[7].toInt(); l.points = f[8].toInt(); l.best = f[9].toInt()
                }
            }
        }

        val worldCodec: CompetitionCodec = codec(Comp.WORLD_CUP, WORLD_HEADER)
        val continentalCodec: CompetitionCodec = codec(Comp.CONTINENTAL, CONT_HEADER)
    }
}
