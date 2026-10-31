package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.Category
import yacht.engine.league.Awards
import yacht.engine.league.BracketRound
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubTable
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Proposal
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import yacht.engine.league.TieView
import kotlin.math.roundToInt

/**
 * The world's pages on request (`lg.worldPages`): competitions, clubs, leagues, the league
 * ranking, the federation and the transfer market, each loader keeping its last results by key.
 * The competition pages come from the screen's snapshot; the others are read from the league
 * under its lock off the main thread (O(the league's members), or one pass over its fixtures for
 * a club's matches), and never in the 300 ms snapshot.
 */
class WorldPages internal constructor(private val host: LeagueHost) {
    /**
     * The competition pages loaded last (at most [KEEP]), by [Page.Competition.loadKey], the newest
     * last. Compared by identity: a page loaded again unchanged still moves to the end.
     */
    var competitions by mutableStateOf<Map<String, CompetitionPage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** 순위 › 클럽 of the league asked for last (null before, or in a legacy season: its club league is in the snapshot). */
    var clubTable by mutableStateOf<ClubTableView?>(null)
        private set

    /** The club and league pages loaded last (at most [KEEP] each), by their pages' load keys. */
    var clubs by mutableStateOf<Map<String, ClubPage>>(emptyMap(), referentialEqualityPolicy())
        private set
    var leagues by mutableStateOf<Map<String, LeaguePage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** 순위 › 랭킹, the 요트협회's page and the national clubs' moves, as loaded last. */
    var ranking by mutableStateOf<RankingView?>(null)
        private set
    var federation by mutableStateOf<FederationView?>(null)
        private set
    var market by mutableStateOf<MarketView?>(null)
        private set

    /** The world the views were read from: a view of another (a reset, a restore) is not shown. */
    @Volatile
    private var source: LeagueWorld? = null

    fun club(page: Page.Club): ClubPage? = clubs[page.loadKey]?.takeIf { source === host.world() }
    fun league(page: Page.League): LeaguePage? = leagues[page.loadKey]?.takeIf { source === host.world() }

    /**
     * Loads [comp]'s page at [stage] (an index of [competitionStages]; null or out of range: the
     * stage in play) from the latest snapshot; nothing when the season has no such competition.
     * Main thread; cheap (the snapshot holds the competition's view).
     */
    fun loadCompetition(comp: Comp, stage: Int? = null) {
        val view = host.view()?.comps?.firstOrNull { it.first == comp }?.second ?: return
        val stages = competitionStages(view)
        val at = stage?.takeIf { it in stages.indices } ?: stageInPlay(view, stages, host.favourites())
        val page = CompetitionPage(Page.Competition(comp).loadKey, comp, stages.map { it.label }, at, view)
        competitions = keep(competitions, page.key, page)
    }

    /** Loads national league [league]'s club table into [clubTable] (none in a legacy season). */
    fun loadClubTable(league: String) = load("clubs", { w -> clubTableOf(w, league) }) { clubTable = it }

    /** Loads club [id]'s page into [clubs]. */
    fun loadClub(id: String) = load(Page.Club(id).loadKey, { w -> clubPageOf(w, id) }) { clubs = keep(clubs, it.key, it) }

    /** Loads league [id]'s page into [leagues]. */
    fun loadLeague(id: String) = load(Page.League(id).loadKey, { w -> leaguePageOf(w, id) }) { leagues = keep(leagues, it.key, it) }

    /** Loads the league ranking the season is played under into [ranking]. */
    fun loadRanking() = load("ranking", { w -> rankingOf(w) }) { ranking = it }

    /** Loads the 요트협회's agenda, decisions and awards into [federation]. */
    fun loadFederation() = load("federation", { w -> federationOf(w) }) { federation = it }

    /** Loads the national clubs' moves into [market]. */
    fun loadMarket() = load("market", { w -> marketOf(w) }) { market = it }

    /**
     * The player's vote on the 요트협회's pending proposal [number] of season [season]: [approve]
     * 찬성 (true) or 반대 (false), null to take it back. Kept until the season's close decides it,
     * saved soon, and the page loaded again. Nothing for a proposal no longer on the vote.
     */
    fun chooseProposal(season: Int, number: Int, approve: Boolean?) {
        host.scope.launch(Dispatchers.Default) {
            val changed = synchronized(host.lock) { host.world()?.federation?.choose(season, number, approve) == true }
            if (changed) host.requestSave()
            loadFederation()
        }
    }

    /** The newest request per loader and key: an older one finishing later is dropped. */
    private val latest = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    /**
     * [read] under the lock off the main thread, then [done] on the main thread with its result
     * (nothing when it gives null, or when a newer request of [kind] came meanwhile). An error
     * reaches the app's handler (the crash log); the page keeps waiting.
     */
    private fun <T : Any> load(kind: String, read: (LeagueWorld) -> T?, done: (T) -> Unit) {
        val n = requests.incrementAndGet()
        latest[kind] = n
        host.scope.launch(Dispatchers.Default) {
            val (w, v) = synchronized(host.lock) {
                val w = host.world() ?: return@launch
                w to (read(w) ?: return@launch)
            }
            host.scope.launch {
                if (latest[kind] != n || w !== host.world()) return@launch
                latest.remove(kind, n)
                // Another league now (a reset, a restore): what was read from the old one goes.
                if (w !== source) {
                    source = w
                    clubTable = null
                    clubs = emptyMap()
                    leagues = emptyMap()
                    ranking = null
                    federation = null
                    market = null
                }
                done(v)
            }
        }
    }

    // ---- the readers (under the lock) -------------------------------------------------------

    /** The national clubs' book; null without one (a legacy league's is a club league's). */
    private fun book(w: LeagueWorld): ClubBook? = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION }

    private fun badgeOf(b: ClubBook, id: String): ClubBadge? =
        b.club(id)?.let { ClubBadge(it.id, it.name, it.city, it.nation, it.emblem) } ?: b.gone(id)?.let { ClubBadge(it.id, it.name, "", it.nation, it.emblem) }

    /** League [id]'s club table with each club's week ([weekOf]); empty for a league the season lacks. */
    private fun standings(w: LeagueWorld, b: ClubBook, p: SeasonPlan, id: String): List<ClubStandingRow> {
        val table = ClubTable.of(p.league, b, id, w.honours.of(p.number))
        val week = weekOf(p)
        return table.mapIndexedNotNull { i, st ->
            val badge = badgeOf(b, st.club) ?: return@mapIndexedNotNull null
            ClubStandingRow(badge, i + 1, st.points, st.leaguePart, st.bonus, st.counted, st.members, week(b.members(st.club)))
        }
    }

    /**
     * Each player's league results in the week in play, as a club's line counts them ("3승 1무 2패";
     * "" without one): one pass over the week's played fixtures.
     */
    private fun weekOf(p: SeasonPlan): (Collection<String>) -> String {
        val s = p.league
        val day = p.week - 1
        val won = HashMap<String, IntArray>()
        if (day in 0 until s.matchdays) {
            for (id in 0 until s.fixturesTotal) {
                val f = s.fixture(id)
                if (f.matchday != day) continue
                val r = s.result(id)?.let { TieRule.match(it, s.format) } ?: continue
                won.getOrPut(f.a) { IntArray(3) }[if (r > 0) 0 else if (r == 0) 1 else 2]++
                won.getOrPut(f.b) { IntArray(3) }[if (r < 0) 0 else if (r == 0) 1 else 2]++
            }
        }
        return { keys ->
            val t = IntArray(3)
            for (k in keys) won[k]?.let { for (i in 0..2) t[i] += it[i] }
            if (t.sum() == 0) "" else "${t[0]}승 ${t[1]}무 ${t[2]}패"
        }
    }

    private fun clubTableOf(w: LeagueWorld, league: String): ClubTableView? {
        val p = w.plan ?: return null
        val b = book(w) ?: return null
        val lg = p.league.leagues.byId(league)?.takeIf { it.national } ?: return null
        val nations = lg.divisions.flatMap { p.league.divisions[it] }.mapNotNull { k -> b.playNation(k) }.toSet()
        val all = nations.flatMap { b.clubsOf(it) }
            .sortedWith(compareByDescending<yacht.engine.league.Club> { it.honours }.thenByDescending { it.budget }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            .map { ClubListRow(ClubBadge(it.id, it.name, it.city, it.nation, it.emblem), it.squad.size, it.budget, it.honours) }
        return ClubTableView(league, p.number, p.week, standings(w, b, p, league), all)
    }

    /**
     * Club [id]'s page: its standing in its league's club table, its members (the counted ones
     * first, by what they bring), its kept places, moves and members' matches, its numbers.
     */
    private fun clubPageOf(w: LeagueWorld, id: String): ClubPage? {
        val b = book(w) ?: return null
        val badge = badgeOf(b, id) ?: return null
        val gone = b.gone(id)
        val club = b.club(id)
        val members = club?.squad.orEmpty()
        val p = w.plan
        val s = p?.league
        val lg = s?.let { members.firstNotNullOfOrNull { k -> it.leagueOf(k) } }?.takeIf { it.national }
        val standing = if (p != null && lg != null) standings(w, b, p, lg.id).firstOrNull { it.badge.id == id } else null
        val now = p?.let { w.honours.of(it.number) }.orEmpty()
        val bonus = HashMap<String, Int>()
        for (h in now) ClubTable.bonusOf(h.kind).takeIf { it != 0 }?.let { bonus.merge(h.key, it, Int::plus) }
        val rows = host.view()?.rows.orEmpty().associateBy { it.key }
        val list = members.map { k ->
            val d = s?.divisionOf(k)
            val tier = d?.let { s.leagues.tier(it) }
            val st = s?.standings?.get(k)
            val counted = standing?.counted?.contains(k) == true
            ClubMember(
                k, host.shown(k),
                d?.let { s.leagues.label(it) },
                d?.let { s.table(it).indexOfFirst { t -> t.key == k } + 1 }?.takeIf { it > 0 },
                if (counted && tier != null) Math.round(((st?.points ?: 0) * ClubTable.weight(tier) + (bonus[k] ?: 0)) * 10) / 10.0 else null,
                rows[k]?.rating,
                st?.let { "${it.wins}승 ${it.draws}무 ${it.losses}패" }.orEmpty(),
                w.players[k]?.identity?.nation?.let { it != club?.nation } == true,
            )
        }.sortedWith(compareBy<ClubMember>({ it.contribution == null }, { -(it.contribution ?: 0.0) }, { -(it.rating ?: Int.MIN_VALUE) }))
        val last = (p?.number ?: ((b.season) + 1)) - 1
        val places = (last downTo maxOf(1, last - PLACE_SEASONS + 1)).mapNotNull { n ->
            b.places(n).firstNotNullOfOrNull { c -> c.clubs.indexOf(id).takeIf { it >= 0 }?.let { n to it + 1 } }
        }
        return ClubPage(
            Page.Club(id).loadKey, badge, lg?.id.orEmpty(), lg?.let { LeagueIds.label(it.id) }.orEmpty(), standing,
            club?.budget ?: 0, club?.honours ?: gone?.honours ?: 0, club?.founded ?: gone?.founded ?: 0,
            list, p?.let { weekOf(it)(members) }.orEmpty(), places, b.history(id).asReversed().take(CLUB_MOVES),
            p?.let { recentOf(it, members.toSet()) }.orEmpty(), aggregate(w, members), gone?.dissolved,
        )
    }

    /** [keys]' newest played league matches this season, newest first, each from its member's side. */
    private fun recentOf(p: SeasonPlan, keys: Set<String>): List<PlayerMatch> {
        val s = p.league
        val out = ArrayList<PlayerMatch>()
        for (id in s.fixturesTotal - 1 downTo 0) {
            val f = s.fixture(id)
            val own = if (f.a in keys) f.a else if (f.b in keys) f.b else continue
            val g = s.result(id) ?: continue
            val first = own == f.a
            val opp = if (first) f.b else f.a
            val r = TieRule.match(g, s.format).let { if (first) it else -it }
            val score = g.joinToString(" · ") { if (first) "${it.a}:${it.b}" else "${it.b}:${it.a}" }
            out += PlayerMatch(
                "${p.number}:${Comp.LEAGUE.code}:$id", f.matchday + 1, "${host.shown(own)} · ${f.round + 1}라운드", opp, host.shown(opp), host.badge(opp), r, score, false, Comp.LEAGUE,
            )
        }
        return out.sortedByDescending { it.week }.take(RECENT)
    }

    /** This season's numbers of [keys] together (their season statistics). */
    private fun aggregate(w: LeagueWorld, keys: Collection<String>): AggregateStats? {
        val stats = keys.mapNotNull { w.stats.season(it) }
        val games = stats.sumOf { it.games }
        if (games == 0) return null
        val carded = stats.sumOf { it.carded }
        val ratings = stats.sumOf { it.ratingCount }
        val elo = keys.map { w.ladder.rating(it) }.filter { !it.isNaN() }
        val s = w.plan?.league
        return AggregateStats(
            keys.size, games,
            stats.sumOf { it.gameWins + 0.5 * it.gameDraws } / games,
            stats.sumOf { it.scoreSum }.toDouble() / games,
            stats.sumOf { it.yachts }.toDouble() / games,
            stats.sumOf { it.bonuses }.toDouble() / games,
            if (ratings == 0) Double.NaN else stats.sumOf { it.ratingSum } / ratings,
            elo.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            stats.sumOf { it.over300 }, stats.sumOf { it.comebacks },
            keys.sumOf { s?.standings?.get(it)?.sweeps ?: 0 },
            Category.ALL.map { c -> if (carded == 0) Double.NaN else stats.sumOf { it.boxSums[c.ordinal] }.toDouble() / carded },
        )
    }

    private fun leaguePageOf(w: LeagueWorld, id: String): LeaguePage? {
        val p = w.plan ?: return null
        val s = p.league
        val lg = s.leagues.byId(id)?.takeIf { it.national } ?: return null
        val info = host.view()?.season?.leagues?.firstOrNull { it.id == id } ?: return null
        val b = book(w)
        fun row(st: yacht.engine.league.Standing) = SeasonRow(
            st.key, host.shown(st.key), st.played, st.wins, st.draws, st.losses, st.points, st.diff, st.avgScore, st.yachts, 0, st.aggPoints, null, st.sweeps,
            b?.clubOf(st.key),
        )
        val keys = lg.divisions.flatMap { s.divisions[it] }
        val rows = host.view()?.rows.orEmpty().associateBy { it.key }
        val stars = keys.mapNotNull { rows[it] }.filter { it.rating != null }.sortedByDescending { it.rating }.take(TOP)
        val champions = w.honours.all.filter { it.kind == HonourKind.of("L0", id) }.sortedByDescending { it.season }.take(CHAMPIONS).map { it.season to host.shown(it.key) }
        // The last window's moves between leagues that touch this one's clubs.
        val nations = keys.mapNotNull { b?.playNation(it) }.toSet()
        val last = p.number - 1
        val moves = b?.history.orEmpty().filter { m ->
            m.season == last && (m.type == ClubMoveType.ABROAD || m.type == ClubMoveType.HOME) &&
                (m.from?.let { b?.nationOf(it) } in nations || m.to?.let { b?.nationOf(it) } in nations)
        }.asReversed()
        val tiers = lg.divisions.mapNotNull { d -> aggregate(w, s.divisions[d])?.let { s.leagues.label(d) to it } }
        return LeaguePage(
            Page.League(id).loadKey, info, p.ranking.of(id), s.table(lg.top).take(TOP).map(::row),
            if (b != null) standings(w, b, p, id).take(TOP) else emptyList(), stars, champions, moves, tiers,
        )
    }

    private fun rankingOf(w: LeagueWorld): RankingView? {
        val p = w.plan ?: return null
        if (!p.league.leagues.national) return RankingView(p.number, emptyList(), byStrength = false, published = 0)
        val live = Coefficients.season(p)
        val rows = p.ranking.rows.map { r ->
            RankRow(r, LeagueIds.label(r.id), LeagueIds.short(r.id), LeagueIds.icon(r.id), live[r.id]?.takeIf { it[1] > 0 }?.let(Coefficients::value))
        }
        val published = w.federation.coefficientSeasons.filter { it < p.number }.maxOrNull() ?: 0
        return RankingView(p.number, rows, byStrength = published == 0, published = published)
    }

    private fun federationOf(w: LeagueWorld): FederationView? {
        val p = w.plan ?: return null
        val f = w.federation
        // The last closed season's awards: the season's, then the league MVPs of its best-ranked leagues.
        val closed = w.honours.seasons.lastOrNull { it < p.number } ?: 0
        val top = f.top(closed).orEmpty()
        val honours = w.honours.of(closed)
        val awards = AWARDS.mapNotNull { k -> honours.firstOrNull { it.kind == k } } +
            top.mapNotNull { id -> honours.firstOrNull { it.kind == HonourKind.of(Awards.LMVP, id) } }
        return FederationView(
            w.config.federation,
            f.pending().map(::proposalView),
            f.proposals.filter { !it.pending }.asReversed().map(::proposalView),
            awards.map { it.kind to host.shown(it.key) },
            p.number,
            closed,
        )
    }

    private fun marketOf(w: LeagueWorld): MarketView? {
        val b = book(w) ?: return null
        return MarketView(w.plan?.number ?: 0, b.history.asReversed(), b.digest)
    }

    companion object {
        /** Results kept per loader. */
        const val KEEP = 4

        /** A page's lists: a league's first rows, its champions, a club's moves and members' matches, the seasons of its places. */
        private const val TOP = 5
        private const val CHAMPIONS = 10
        private const val CLUB_MOVES = 20
        private const val RECENT = 10
        private const val PLACE_SEASONS = 10

        /** The season's awards the 요트협회 hands out (시상), in order. */
        private val AWARDS = listOf("MVP", "ROOKIE", "MIP", "SCORER", "YACHT")

        /** [map] with [value] put last under [key], the oldest dropped beyond [KEEP]. */
        internal fun <V> keep(map: Map<String, V>, key: String, value: V): Map<String, V> {
            val m = LinkedHashMap(map)
            m.remove(key)
            m[key] = value
            while (m.size > KEEP) m.remove(m.keys.first())
            return m
        }
    }
}

/** [p] as the 요트협회's page shows it (its title and evidence in Korean, the player's choice as a Boolean). */
internal fun proposalView(p: Proposal): ProposalView = ProposalView(
    p.season, p.number, p.title, p.evidence, p.lever.label, p.from, p.to,
    when (p.choice) {
        Proposal.YES -> true
        Proposal.NO -> false
        else -> null
    },
    p.result, p.by, p.yes, p.no, p.appliesFrom,
)

/**
 * One entry of a competition's stage list: its section ([part], 0 without sections), and its
 * groups ([round] -1) or one round of its bracket ([round] indexes the bracket's rounds).
 */
data class CompetitionStage(val part: Int, val round: Int, val label: String)

/**
 * [view]'s stages as one list that folds section, side and round ("조별 리그", "승자조 8강",
 * "패자조 2R", "최종 결승"; with sections "아시아컵 · 조별 리그", "아시아컵 · 8강").
 */
fun competitionStages(view: CompetitionView): List<CompetitionStage> {
    fun groups(g: CompetitionView.Groups, part: Int, prefix: String): List<CompetitionStage> = buildList {
        if (g.groups.isNotEmpty()) add(CompetitionStage(part, -1, prefix + "조별 리그"))
        g.knockout?.rounds?.forEachIndexed { i, r -> add(CompetitionStage(part, i, prefix + r.label)) }
    }
    return when (view) {
        is CompetitionView.Bracket -> view.rounds.mapIndexed { i, r -> CompetitionStage(0, i, r.label) }
        is CompetitionView.Groups -> groups(view, 0, "")
        is CompetitionView.Sections -> view.parts.flatMapIndexed { p, g -> groups(g, p, g.title + " · ") }
        // A kind of view this screen does not know yet: shown whole.
        else -> emptyList()
    }
}

/** A tie both of whose sides are known, still to play. */
private fun ready(t: TieView): Boolean = !t.played && t.winner == null && t.a != null && t.b != null

/**
 * The round in play among [rounds]: the earliest stage with a ready tie, else the latest with a
 * played one, else the first; null for none.
 */
internal fun roundInPlay(rounds: List<BracketRound>): Int? {
    if (rounds.isEmpty()) return null
    val order = compareBy<Int>({ rounds[it].stage }, { it })
    return rounds.indices.filter { i -> rounds[i].ties.any(::ready) }.minWithOrNull(order)
        ?: rounds.indices.filter { i -> rounds[i].ties.any { it.played } }.maxWithOrNull(order)
        ?: 0
}

/**
 * The stage a competition page opens on: in the section with a followed player (else the first),
 * the bracket's round in play once its first ties are drawn, else the groups.
 */
private fun stageInPlay(view: CompetitionView, stages: List<CompetitionStage>, favourites: Set<String>): Int {
    if (stages.isEmpty()) return 0
    fun inPart(g: CompetitionView.Groups, part: Int): Int {
        val k = g.knockout?.rounds.orEmpty()
        val drawn = k.any { r -> r.ties.any { it.a != null || it.b != null } }
        val round = if (drawn || g.groups.isEmpty()) roundInPlay(k) ?: -1 else -1
        return stages.indexOfFirst { it.part == part && it.round == round }.coerceAtLeast(0)
    }
    return when (view) {
        is CompetitionView.Bracket -> roundInPlay(view.rounds) ?: 0
        is CompetitionView.Groups -> inPart(view, 0)
        is CompetitionView.Sections -> {
            fun followed(g: CompetitionView.Groups) = g.groups.any { t -> t.rows.any { it.key in favourites } } ||
                g.knockout?.rounds.orEmpty().any { r -> r.ties.any { it.a in favourites || it.b in favourites } }
            val part = view.parts.indexOfFirst(::followed).coerceAtLeast(0)
            view.parts.getOrNull(part)?.let { inPart(it, part) } ?: 0
        }
        else -> 0
    }
}
