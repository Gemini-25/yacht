package yacht.engine.league

import java.util.Random

/**
 * 올스타전 (docs/LEAGUE_SPEC.md 2.3): the 1st division's 5 best rated at the season start ("1부
 * 올스타", team id [TOP]) against the 5 best rated of the other divisions ("도전자 올스타",
 * [CHALLENGER]), one team match at league progress 0.5. A friendly: off the ladder. A level match
 * goes to the 1st division's side. Honour "ALLSTAR" keyed by the winning team id.
 *
 * News: `draw_allstar` [lineup] (team id) per side at the start, `final` [winner, loser] (boards)
 * with team ids as keys.
 */
class AllStar private constructor(
    val season: Int,
    private val seed: Long,
    /** The two lineups, board 1 (the best rated) first. */
    val top: List<String>,
    val challenger: List<String>,
    topRating: Double,
    challengerRating: Double,
) : ViewableCompetition {
    override val comp: Comp get() = Comp.ALL_STAR

    private val sides = DrawEntry(TOP, 1, topRating) to DrawEntry(CHALLENGER, 2, challengerRating)
    private val bracket = Knockout(listOf(sides), { 1 }, { Comp.ALL_STAR.label }, { OPENS })

    override fun next(p: Double, busy: Set<String>): Fixture? = bracket.next(p, busy) { t ->
        val n = minOf(top.size, challenger.size)
        Fixture(comp, t.id, TOP, CHALLENGER, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TEAM, lineupA = top.take(n), lineupB = challenger.take(n), rated = false, label = comp.label)
    }

    override fun release(f: Fixture) = bracket.release(f.id)

    // Ten of the best players at once: the other team competitions wait for it.
    override fun waiting(p: Double): Set<String> = bracket.waiting(p) { t -> Fixture(comp, t.id, TOP, CHALLENGER, 0L, JobKind.TEAM, lineupA = top, lineupB = challenger) }

    override fun stages(): List<Stage> = bracket.stages()

    override fun stageOf(f: Fixture): Int = bracket.roundOf(f.id)

    override fun stageCounts(): List<StageCount> = bracket.counts()

    override fun record(r: JobResult): List<LeagueEvent> {
        if (!Knockout.current(seed, comp, r)) return emptyList()
        val t = bracket.record(r.fixture.id, r.fixture.a, r.fixture.b, TieScore.of(r)) ?: return emptyList()
        return KnockoutNews.final(season, comp, t, null)
    }

    override val finished: Boolean get() = bracket.finished
    override val fixturesLeft: Int get() = bracket.fixturesLeft
    override val fixturesTotal: Int get() = bracket.fixturesTotal

    override fun honours(): List<Honour> = listOfNotNull(bracket.champion?.let { Honour(season, "ALLSTAR", it.key) })

    override fun opening(): List<LeagueEvent> = listOf(
        LeagueEvent("draw_allstar", season, top, listOf(TOP), comp),
        LeagueEvent("draw_allstar", season, challenger, listOf(CHALLENGER), comp),
    )

    override fun encode(out: StringBuilder) {
        out.append(HEADER).append('\n')
        for ((e, lineup) in listOf(sides.first to top, sides.second to challenger)) {
            out.append("t ").append(e.encode()).append(' ').append(lineup.joinToString(",")).append('\n')
        }
        bracket.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Bracket(comp.label, bracket.view(), mapOf(TOP to top, CHALLENGER to challenger))

    companion object {
        const val HEADER = "allstar 1"
        const val TOP = "TOP"
        const val CHALLENGER = "CHALLENGER"
        const val TOP_LABEL = "1부 올스타"
        const val CHALLENGER_LABEL = "도전자 올스타"

        /** Players per side. */
        const val SIDE = 5
        private const val OPENS = 0.5

        /**
         * Null when off, or without a 1st division and other divisions of 5 AIs or more each. In a
         * national season the 1st division side is the best of every league's 1부 ("모든 리그의
         * 1부"), the challengers the best of every other division.
         */
        fun create(start: SeasonStart): AllStar? {
            if (!start.config.allStar || start.divisions.size < 2) return null
            val (first, others) = if (start.leagues.national) {
                val tops = start.leagues.tops().toHashSet()
                start.divisions.indices.partition { it in tops }.let { (t, o) -> t.flatMap { start.divisions[it] } to o.flatMap { start.divisions[it] } }
            } else {
                start.divisions[0] to start.divisions.drop(1).flatten()
            }
            val top = first.sortedByDescending { start.rating(it) }.take(SIDE)
            val rest = others.sortedByDescending { start.rating(it) }.take(SIDE)
            if (top.size < SIDE || rest.size < SIDE) return null
            return AllStar(start.number, start.seed, top, rest, top.map(start.rating).average(), rest.map(start.rating).average())
        }

        val codec = CompetitionCodec { lines, season, seed ->
            require(lines.firstOrNull()?.trim() == HEADER) { "not an all-star section" }
            val sides = HashMap<String, Pair<DrawEntry, List<String>>>()
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] == "t") sides[f[1]] = DrawEntry.decode(f, 1) to f[4].split(',').filter { it.isNotEmpty() }
                }
            }
            val (a, top) = requireNotNull(sides[TOP]) { "no 1st division side" }
            val (b, rest) = requireNotNull(sides[CHALLENGER]) { "no challenger side" }
            require(top.isNotEmpty() && rest.isNotEmpty()) { "empty all-star lineups" }
            AllStar(season, seed, top, rest, a.rating, b.rating).also { s ->
                for (r in TieScore.Saved.parse(lines)) s.bracket.record(r.id, r.a, r.b, r.score)
            }
        }
    }
}


/**
 * A team of a [TeamCup]: [id] is the [Family]'s name (계열 대항전) or a club id (클럽 대항전),
 * [squad] its best rated AIs (board 1 first).
 */
data class FamilyTeam(val id: String, val squad: List<String>, val strength: Double)

/**
 * A cup of teams of up to 5 (the 계열 대항전's format, which the 클럽 대항전 shares): 4 to 12
 * teams, strongest first. Groups of 3 or 4 drawn from strength pots (one group for 4-5 teams, 3
 * groups of 3 for 9), a single round robin of team matches inside each (rounds opening over league
 * progress 0.1..0.6), then the group winners and the best runners-up (by points per match) make 4
 * semi-finalists: semis at 0.75 and the final at 0.9, one team match each (with double
 * elimination six ties in four stages); a level knockout match goes to the better seed.
 *
 * Honours by [Kinds]: the winner's (and runner-up's) team id, and a squad honour for every squad
 * member or only the winners'. News: [announced] at the start; `qualified` [team] (4강, group
 * place) when the groups end; `cup_round`, `final` and `title` [winner] ([Comp.label]) with team
 * ids as keys.
 */
open class TeamCup protected constructor(
    final override val comp: Comp,
    val season: Int,
    private val seed: Long,
    /** Every team, strongest first (its seed). */
    val teams: List<FamilyTeam>,
    /** The groups, each in pot order. */
    val groups: List<List<DrawEntry>>,
    private val announced: List<LeagueEvent>,
    /** The knockout is double elimination (패자부활); saved. */
    private val double: Boolean,
    private val kinds: Kinds,
) : ViewableCompetition {
    /**
     * A cup's save header and honour kinds: [winner] and [runnerUp] (null: none) keyed by team id,
     * [squad] keyed by each squad member of every team, or of the winning team only ([winnersOnly]).
     */
    class Kinds(val header: String, val winner: String, val runnerUp: String?, val squad: String, val winnersOnly: Boolean)

    /** The knockout's stages: semis and final, or with the losers' bracket six ties in four stages. */
    private val koOpens: List<Double> = if (double) Knockout.spread(4, KO_OPENS.first(), KO_OPENS.last()) else KO_OPENS
    private val koTargets: List<Double> = if (double) Knockout.spread(4, KO_TARGETS.first(), KO_TARGETS.last()) else KO_TARGETS
    private val koSizes: List<Int> = Knockout.stageSizes(2, double)

    private val byId = teams.associateBy { it.id }
    private val stage = GroupStage(groups, double = false, teams = true, opensAt = ::groupOpens)

    /** The semi-finals and final, drawn once every group match is played. */
    private var bracket: Knockout? = null

    private fun groupOpens(k: Int): Double =
        if (stage.rounds <= 1) GROUP_FIRST else GROUP_FIRST + (GROUP_LAST - GROUP_FIRST) * k / (stage.rounds - 1)

    /** A team match: the two squads, cut to the shorter one so every board has two players. */
    private fun fixture(id: Int, a: String, b: String, label: String): Fixture {
        val x = byId.getValue(a).squad
        val y = byId.getValue(b).squad
        val n = minOf(x.size, y.size)
        return Fixture(comp, id, a, b, Knockout.fixtureSeed(seed, comp, id), JobKind.TEAM, lineupA = x.take(n), lineupB = y.take(n), label = label)
    }

    override fun next(p: Double, busy: Set<String>): Fixture? =
        stage.next(p, busy) { m -> fixture(m.id, m.a, m.b, stage.matchLabel(m)) }
            ?: bracket?.let { k -> k.next(p, busy) { t -> fixture(t.id, t.a!!.key, t.b!!.key, k.label(t)) } }

    override fun release(f: Fixture) {
        if (f.id in stage) stage.release(f.id) else bracket?.release(f.id)
    }

    override fun stages(): List<Stage> =
        List(stage.rounds) { k ->
            val t = if (stage.rounds <= 1) GROUP_TARGET else GROUP_TARGET + (GROUP_TARGET_LAST - GROUP_TARGET) * k / (stage.rounds - 1)
            Stage("조별 ${k + 1}차전", groupOpens(k), t)
        } + koOpens.indices.map { st -> Stage(bracket?.stageLabel(st) ?: koLabel(st), koOpens[st], koTargets[st]) }

    /** A knockout stage's label before the draw. */
    private fun koLabel(st: Int): String = if (!double) KO_LABELS[st] else DOUBLE_LABELS[st]

    override fun stageOf(f: Fixture): Int {
        if (f.id in stage) return stage.roundOf(f.id)
        val r = bracket?.roundOf(f.id) ?: -1
        return if (r < 0) -1 else stage.rounds + r
    }

    override fun stageCounts(): List<StageCount> = stage.counts() + (bracket?.counts() ?: koSizes.map { StageCount(0, it) })

    override fun record(r: JobResult): List<LeagueEvent> =
        if (Knockout.current(seed, comp, r)) put(r.fixture.id, r.fixture.a, r.fixture.b, TieScore.of(r)) else emptyList()

    private fun put(id: Int, a: String, b: String, s: TieScore): List<LeagueEvent> {
        if (id in stage) {
            stage.record(id, a, b, s) ?: return emptyList()
            return if (stage.finished && bracket == null) drawKnockout() else emptyList()
        }
        val k = bracket ?: return emptyList()
        val t = k.record(id, a, b, s) ?: return emptyList()
        return if (k.isFinal(t)) KnockoutNews.final(season, comp, t, comp.label) else listOf(KnockoutNews.round(season, comp, t, k.label(t)))
    }

    /** Puts saved results back, earliest first. */
    internal fun restore(results: List<TieScore.Saved>) {
        for (r in results) put(r.id, r.a, r.b, r.score)
    }

    /** Semi-finalists from the group tables; returns the qualification news. */
    private fun drawKnockout(): List<LeagueEvent> {
        val tables = groups.indices.map { stage.table(it) }
        // Group winners first, then the best runners-up, ... until 4. Groups may differ in size,
        // so teams from different groups are compared per match played.
        fun per(x: Int, played: Int) = if (played == 0) 0.0 else x.toDouble() / played
        val across = compareByDescending<Pair<GroupStage.Row, Int>> { per(it.first.points, it.first.played) }
            .thenByDescending { per(stage.diff(it.first), it.first.played) }
            .thenByDescending { per(it.first.scoreDiff, it.first.played) }
            .thenBy { it.first.entry.seed }
        val picked = ArrayList<Pair<GroupStage.Row, Int>>()
        for (place in 0 until (tables.maxOfOrNull { it.size } ?: 0)) {
            val rows = tables.mapIndexedNotNull { g, t -> t.getOrNull(place)?.let { it to g } }.sortedWith(across)
            for (r in rows) if (picked.size < SEMIS) picked += r
        }
        val seeds = picked.mapIndexed { i, (row, _) -> DrawEntry(row.entry.key, i + 1, row.entry.rating) }
        val group = picked.map { it.second }
        // 1v4 and 2v3, unless 1 and 4 come from the same group and 1v3, 2v4 avoids both rematches.
        val swap = group.size == SEMIS && group[0] == group[3] && group[0] != group[2] && group[1] != group[3]
        val pairs = if (swap) listOf(seeds[0] to seeds[2], seeds[1] to seeds[3]) else listOf(seeds[0] to seeds.getOrNull(3), seeds[1] to seeds.getOrNull(2))
        bracket = Knockout(pairs, { 1 }, { KO_LABELS[it] }, { koOpens[it] }, idBase = KO_BASE, double = double)
        return picked.map { (row, g) ->
            val place = tables[g].indexOf(row) + 1
            LeagueEvent("qualified", season, listOf(row.entry.key), listOf(bracket!!.label(0), "${stage.groupLabel(g)} ${place}위"), comp)
        }
    }

    override val finished: Boolean get() = bracket?.finished == true
    override val fixturesTotal: Int get() = stage.fixturesTotal + (bracket?.fixturesTotal ?: koSizes.sum())
    override val fixturesLeft: Int get() = stage.fixturesLeft + (bracket?.fixturesLeft ?: koSizes.sum())

    override fun honours(): List<Honour> {
        val k = bracket ?: return emptyList()
        val w = k.champion ?: return emptyList()
        val out = arrayListOf(Honour(season, kinds.winner, w.key))
        kinds.runnerUp?.let { kind -> k.runnerUp?.let { out += Honour(season, kind, it.key) } }
        for (t in teams) if (!kinds.winnersOnly || t.id == w.key) for (p in t.squad) out += Honour(season, kinds.squad, p)
        return out
    }

    override fun opening(): List<LeagueEvent> = announced

    override fun encode(out: StringBuilder) {
        out.append(kinds.header).append('\n')
        if (double) out.append(KnockoutLength.DOUBLE_LINE).append('\n')
        for ((i, t) in teams.withIndex()) out.append("t ").append(DrawEntry(t.id, i + 1, t.strength).encode()).append(' ').append(t.squad.joinToString(",")).append('\n')
        for ((g, members) in groups.withIndex()) for (e in members) out.append("g $g ${e.key}\n")
        stage.encode(out)
        bracket?.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Groups(
        comp.label,
        groups.indices.map { stage.view(it) },
        CompetitionView.Bracket(comp.label, bracket?.view() ?: Knockout.placeholder(2, { KO_LABELS[it] }, { 1 }, double)),
        teams.associate { it.id to it.squad },
    )

    companion object {
        /** AIs a team needs, and the most that play. */
        const val MIN_SQUAD = 3
        const val SQUAD = 5
        const val MIN_TEAMS = 4
        const val MAX_TEAMS = 12
        private const val SEMIS = 4
        private const val GROUP_FIRST = 0.1
        private const val GROUP_LAST = 0.6
        private const val KO_BASE = 100
        private val KO_LABELS = listOf("4강", "결승")
        private val DOUBLE_LABELS = listOf("승자조 4강", "승자조 결승 · 패자조 1R", "패자조 결승", Knockout.GRAND_LABEL)
        private val KO_OPENS = listOf(0.75, 0.9)
        private const val GROUP_TARGET = 0.12
        private const val GROUP_TARGET_LAST = 0.56
        private val KO_TARGETS = listOf(0.80, 0.90)

        /** Groups for [n] teams: one for 4-5, else groups of 3 or 4. */
        fun groupCount(n: Int): Int = if (n <= 5) 1 else (n + 3) / 4

        /** Groups drawn from pots of the seeds (pot 1 = the strongest), each group in pot order. */
        fun draw(entries: List<DrawEntry>, rng: Random): List<List<DrawEntry>> {
            val groups = List(groupCount(entries.size)) { ArrayList<DrawEntry>() }
            for (pot in entries.chunked(groups.size)) for ((i, e) in pot.shuffled(rng).withIndex()) groups[i] += e
            return groups
        }

        /** Reads a cup saved with [kinds]' header; [make] builds it from its teams, groups and double elimination. */
        internal fun codec(kinds: Kinds, make: (season: Int, seed: Long, teams: List<FamilyTeam>, groups: List<List<DrawEntry>>, double: Boolean) -> TeamCup) =
            CompetitionCodec { lines, season, seed ->
                require(lines.firstOrNull()?.trim() == kinds.header) { "not a ${kinds.header} section" }
                val teams = ArrayList<Pair<DrawEntry, List<String>>>()
                val placed = ArrayList<Pair<Int, String>>()
                for (l in lines) {
                    runCatching {
                        val f = l.trim().split(' ')
                        when (f[0]) {
                            "t" -> teams += DrawEntry.decode(f, 1) to f[4].split(',').filter { it.isNotEmpty() }
                            "g" -> placed += f[1].toInt() to f[2]
                        }
                    }
                }
                val entries = teams.filter { it.second.isNotEmpty() }.distinctBy { it.first.key }.sortedBy { it.first.seed }
                val byKey = entries.associate { it.first.key to it.first }
                val count = (placed.maxOfOrNull { it.first } ?: -1) + 1
                val groups = List(count) { g -> placed.filter { it.first == g }.mapNotNull { byKey[it.second] }.distinct().sortedBy { it.seed } }
                require(groups.isNotEmpty() && groups.all { it.isNotEmpty() } && groups.sumOf { it.size } >= MIN_TEAMS) { "unusable ${kinds.header} groups" }
                val inGroups = groups.flatten().map { it.key }.toSet()
                val squads = entries.filter { it.first.key in inGroups }.map { (e, squad) -> FamilyTeam(e.key, squad, e.rating) }
                make(season, seed, squads, groups, KnockoutLength.double(lines)).also { c -> c.restore(TieScore.Saved.parse(lines)) }
            }
    }
}

/**
 * 계열 대항전 (docs/LEAGUE_SPEC.md 2.3, seasons 2, 5, 8, ...): every family but MINE with at least
 * 3 AIs fields its 5 best rated as a team (4 to 12 teams, the strongest 12 when more), in the
 * [TeamCup] format. Honours "FAMILY" keyed by the family's name and "FSQ" keyed by every squad
 * member, so the next edition can tell who was called up or dropped.
 *
 * News: `draw_family` [team ids] (group) and `callup`/`dropped` [ai] (family label) against the
 * last edition's squads at the start; `qualified` [team] (4강, group place) when the groups end;
 * `cup_round`, `final` and `title` [winner] (계열 대항전) with team ids as keys.
 */
class FamilyCup private constructor(
    season: Int,
    seed: Long,
    teams: List<FamilyTeam>,
    groups: List<List<DrawEntry>>,
    announced: List<LeagueEvent>,
    double: Boolean = false,
) : TeamCup(Comp.FAMILY, season, seed, teams, groups, announced, double, KINDS) {
    companion object {
        const val HEADER = "family 1"
        private val KINDS = Kinds(HEADER, "FAMILY", null, "FSQ", winnersOnly = false)

        /** AIs a family needs to field a team, and the most that play. */
        const val MIN_SQUAD = TeamCup.MIN_SQUAD
        const val SQUAD = TeamCup.SQUAD
        const val MIN_TEAMS = TeamCup.MIN_TEAMS
        const val MAX_TEAMS = TeamCup.MAX_TEAMS

        /** True for the seasons that hold a family cup: 2, 5, 8, ... */
        fun due(season: Int): Boolean = season >= 2 && (season - 2) % 3 == 0

        /** Groups for [n] teams: one for 4-5, else groups of 3 or 4. */
        fun groupCount(n: Int): Int = TeamCup.groupCount(n)

        /** The teams of a season, strongest first. */
        fun teams(start: SeasonStart): List<FamilyTeam> {
            val byFamily = LinkedHashMap<Family, MutableList<String>>()
            for (k in start.ranking) {
                val f = start.family(k) ?: continue
                if (f != Family.MINE) byFamily.getOrPut(f) { ArrayList() } += k
            }
            return byFamily.filterValues { it.size >= MIN_SQUAD }.map { (f, keys) ->
                val squad = keys.sortedByDescending { start.rating(it) }.take(SQUAD)
                FamilyTeam(f.name, squad, squad.map(start.rating).average())
            }.sortedWith(compareByDescending<FamilyTeam> { it.strength }.thenBy { it.id }).take(MAX_TEAMS)
        }

        /** Groups drawn from pots of the seeds (pot 1 = the strongest), each group in pot order. */
        fun draw(entries: List<DrawEntry>, rng: Random): List<List<DrawEntry>> = TeamCup.draw(entries, rng)

        /** This season's family cup; null when off, not due, or with fewer than 4 teams. */
        fun create(start: SeasonStart): FamilyCup? {
            // A league of generated players only: their families are styles, not teams.
            if (!start.config.familyCup || !due(start.number) || start.ranking.none { Origin.of(it) == Origin.BASE }) return null
            val teams = teams(start)
            if (teams.size < MIN_TEAMS) return null
            val entries = teams.mapIndexed { i, t -> DrawEntry(t.id, i + 1, t.strength) }
            val groups = draw(entries, Random(Knockout.drawSeed(start.seed, Comp.FAMILY)))
            val news = ArrayList<LeagueEvent>()
            for ((g, members) in groups.withIndex()) news += LeagueEvent("draw_family", start.number, members.map { it.key }, listOf("${'A' + g}조"), Comp.FAMILY)
            news += squadNews(start, teams)
            return FamilyCup(start.number, start.seed, teams, groups, news, start.config.doubleElimination)
        }

        /** `callup` and `dropped` [ai] (family label) against the latest earlier squads ("FSQ" honours). */
        private fun squadNews(start: SeasonStart, teams: List<FamilyTeam>): List<LeagueEvent> {
            val earlier = start.history.filter { it.kind == "FSQ" && it.season < start.number }
            val last = earlier.maxOfOrNull { it.season } ?: return emptyList()
            val before = earlier.filter { it.season == last }.map { it.key }.groupBy { start.family(it)?.name }
            val inLeague = start.ranking.toSet()
            val out = ArrayList<LeagueEvent>()
            for (t in teams) {
                // A family that had no team last time has nobody to compare with.
                val was = before[t.id] ?: continue
                val label = Competitions.teamLabel(t.id)
                for (k in t.squad) if (k !in was) out += LeagueEvent("callup", start.number, listOf(k), listOf(label), Comp.FAMILY)
                for (k in was) if (k !in t.squad && k in inLeague) out += LeagueEvent("dropped", start.number, listOf(k), listOf(label), Comp.FAMILY)
            }
            return out
        }

        val codec: CompetitionCodec = TeamCup.codec(KINDS) { season, seed, teams, groups, double -> FamilyCup(season, seed, teams, groups, emptyList(), double) }
    }
}

/**
 * What the 클럽 대항전 reads of the clubs (the club book implements it): who won each league's
 * club table, and who plays for a club.
 */
interface ClubSource {
    /** The club-table champion of every league of season [season], club ids; empty when unknown. */
    fun champions(season: Int): List<String>

    /** Club [club]'s members now (any order); empty for a club that is gone. */
    fun members(club: String): List<String>
}

/**
 * 클럽 대항전 (national seasons only, [LeagueConfig.clubCup]): in even seasons from the second
 * national season, last season's club-table champion of every league ([ClubSource.champions]) with
 * its 5 best rated members still in the world, the 12 strongest clubs by their average rating, in
 * the [TeamCup] format. Team ids are club ids. Honours "CLUBCUP" and "CLUBCUP2" (club ids) and
 * "CLUBCUPW" (each member of the winning squad).
 *
 * News: `draw_clubcup` [club ids] (group) at the start, then as the [TeamCup].
 */
class ClubCup private constructor(
    season: Int,
    seed: Long,
    teams: List<FamilyTeam>,
    groups: List<List<DrawEntry>>,
    announced: List<LeagueEvent>,
    double: Boolean,
) : TeamCup(Comp.CLUB_CUP, season, seed, teams, groups, announced, double, KINDS) {
    companion object {
        const val HEADER = "clubcup 1"
        private val KINDS = Kinds(HEADER, "CLUBCUP", "CLUBCUP2", "CLUBCUPW", winnersOnly = true)

        /** True for the seasons that may hold a club cup: the even ones. */
        fun due(season: Int): Boolean = season >= 2 && season % 2 == 0

        /** The season factory, reading the clubs from [clubs] (null: no club book, no cup). */
        fun factory(clubs: () -> ClubSource?): (SeasonStart) -> Competition? = { start -> clubs()?.let { create(start, it) } }

        /** This season's club cup; null when off, not due, not after a national season, or with fewer than 4 clubs. */
        fun create(start: SeasonStart, clubs: ClubSource): ClubCup? {
            if (!start.config.clubCup || !due(start.number) || !start.leagues.national || !start.lastLeagues.national) return null
            val inWorld = start.ranking.toHashSet()
            val teams = clubs.champions(start.number - 1).distinct().mapNotNull { id ->
                val squad = clubs.members(id).filter { it in inWorld }.sortedWith(compareByDescending<String> { start.rating(it) }.thenBy { it }).take(SQUAD)
                if (squad.size < MIN_SQUAD) null else FamilyTeam(id, squad, squad.map(start.rating).average())
            }.sortedWith(compareByDescending<FamilyTeam> { it.strength }.thenBy { it.id }).take(MAX_TEAMS)
            if (teams.size < MIN_TEAMS) return null
            val entries = teams.mapIndexed { i, t -> DrawEntry(t.id, i + 1, t.strength) }
            val groups = TeamCup.draw(entries, Random(Knockout.drawSeed(start.seed, Comp.CLUB_CUP)))
            val news = groups.mapIndexed { g, members -> LeagueEvent("draw_clubcup", start.number, members.map { it.key }, listOf("${'A' + g}조"), Comp.CLUB_CUP) }
            return ClubCup(start.number, start.seed, teams, groups, news, start.config.doubleElimination)
        }

        val codec: CompetitionCodec = TeamCup.codec(KINDS) { season, seed, teams, groups, double -> ClubCup(season, seed, teams, groups, emptyList(), double) }
    }
}
