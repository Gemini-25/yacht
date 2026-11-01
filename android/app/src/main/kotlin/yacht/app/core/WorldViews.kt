package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.Category
import yacht.engine.explain.Ko
import yacht.engine.league.Academy
import yacht.engine.league.Accounts
import yacht.engine.league.Awards
import yacht.engine.league.BoardOutlook
import yacht.engine.league.BracketRound
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubHistory
import yacht.engine.league.ClubHistoryBook
import yacht.engine.league.ClubMoveKind
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubRanks
import yacht.engine.league.ClubRecordKind
import yacht.engine.league.ClubTable
import yacht.engine.league.Coach
import yacht.engine.league.CoachBook
import yacht.engine.league.CoachMarket
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Derbies
import yacht.engine.league.DerbyRecord
import yacht.engine.league.HonourBook
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MarketStory
import yacht.engine.league.Money
import yacht.engine.league.MoneyItem
import yacht.engine.league.Nations
import yacht.engine.league.NotableMove
import yacht.engine.league.Proposal
import yacht.engine.league.RetiredCoach
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.Tenure
import yacht.engine.league.TieRule
import yacht.engine.league.TieView
import yacht.engine.league.clubValue
import yacht.engine.league.health
import yacht.engine.league.rankings
import yacht.engine.league.transferBudget
import yacht.engine.league.troubleLevel
import yacht.engine.league.valuer
import yacht.engine.league.wageBill
import kotlin.math.roundToInt

/**
 * The world's pages on request (`lg.worldPages`): competitions, clubs, leagues, the league
 * ranking and the federation, and the club world's coaches, 클럽 랭킹 and players' contracts
 * (UX 4-5), each loader keeping its last results by key (the transfer market moved to [MarketPages]).
 * The competition pages come from the screen's snapshot; the others are read from the league
 * under its lock off the main thread (O(the league's members), or one pass over its fixtures for
 * a club's matches), and never in the 300 ms snapshot.
 */
class WorldPages internal constructor(private val host: LeagueHost, private val marketPages: MarketPages = MarketPages(host)) {
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

    /** 순위 › 랭킹 and the 요트협회's page, as loaded last. */
    var ranking by mutableStateOf<RankingView?>(null)
        private set
    var federation by mutableStateOf<FederationView?>(null)
        private set

    /** The coach pages loaded last (at most [KEEP]), by [Page.Coach.loadKey]. */
    var coaches by mutableStateOf<Map<String, CoachPage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** 클럽 랭킹 of the scope asked for last (its [ClubRankingView.key] is the page's load key). */
    var clubRankings by mutableStateOf<ClubRankingView?>(null)
        private set

    /** The players' 💼 lines loaded last (at most [KEEP]), by "pc:{key}". */
    var playerClubs by mutableStateOf<Map<String, PlayerClubCard>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** The national clubs' moves: [MarketPages.view], for the callers from before it moved there. */
    val market: MarketView? get() = marketPages.view

    /** The world the views were read from: a view of another (a reset, a restore) is not shown. */
    @Volatile
    private var source: LeagueWorld? = null

    fun club(page: Page.Club): ClubPage? = clubs[page.loadKey]?.takeIf { source === host.world() }
    fun league(page: Page.League): LeaguePage? = leagues[page.loadKey]?.takeIf { source === host.world() }
    fun coach(page: Page.Coach): CoachPage? = coaches[page.loadKey]?.takeIf { source === host.world() }
    fun clubRanking(page: Page.ClubRanking): ClubRankingView? = clubRankings?.takeIf { it.key == page.loadKey && source === host.world() }

    /** Player [key]'s 💼 line (null without a club world, or before it is loaded). */
    fun playerClub(key: String): PlayerClubCard? = playerClubs[playerClubKey(key)]?.takeIf { source === host.world() }

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

    /** Loads coach [id]'s page into [coaches] (an active or a retired coach; nothing without a club world). */
    fun loadCoach(id: String) = load(Page.Coach(id).loadKey, { w -> coachPageOf(w, id) }) { coaches = keep(coaches, it.key, it) }

    /** Loads 클럽 랭킹 of [scope] (a national league's id; null: the world) into [clubRankings]. */
    fun loadClubRanking(scope: String?) = load("clubRanking", { w -> clubRankingOf(w, scope) }) { clubRankings = it }

    /** Loads player [key]'s 💼 line into [playerClubs] (nothing without a club world). */
    fun loadPlayerClub(key: String) = load(playerClubKey(key), { w -> playerClubOf(w, key) }) { playerClubs = keep(playerClubs, it.key, it) }

    /** Loads league [id]'s page into [leagues]. */
    fun loadLeague(id: String) = load(Page.League(id).loadKey, { w -> leaguePageOf(w, id) }) { leagues = keep(leagues, it.key, it) }

    /** Loads the league ranking the season is played under into [ranking]. */
    fun loadRanking() = load("ranking", { w -> rankingOf(w) }) { ranking = it }

    /** Loads the 요트협회's agenda, decisions and awards into [federation]. */
    fun loadFederation() = load("federation", { w -> federationOf(w) }) { federation = it }

    /** [MarketPages.load], for the callers from before it moved there. */
    fun loadMarket() = marketPages.load()

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
                w to read(w)
            }
            host.scope.launch {
                if (latest[kind] != n || w !== host.world()) return@launch
                latest.remove(kind, n)
                // Another league now (a reset, a restore): what was read from the old one goes,
                // also when the new one has nothing to show yet.
                if (w !== source) {
                    source = w
                    clubTable = null
                    clubs = emptyMap()
                    leagues = emptyMap()
                    ranking = null
                    federation = null
                    coaches = emptyMap()
                    clubRankings = null
                    playerClubs = emptyMap()
                }
                v?.let(done)
            }
        }
    }

    // ---- the readers (under the lock) -------------------------------------------------------

    /**
     * [b] when the club pages show its club world (UX 4.1): it has one and the rules keep it on (with
     * `clubWorld` off the world stands still and the pages are today's); else null.
     */
    private fun shown(w: LeagueWorld, b: ClubBook): ClubBook? = b.takeIf { it.world != null && w.config.clubWorld }

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
            .map {
                // With a club world: its money as it is, its health and whether its fans are angry (😡).
                val life = shown(w, b)?.life(it.id)
                ClubListRow(
                    ClubBadge(it.id, it.name, it.city, it.nation, it.emblem), it.squad.size, it.budget, it.honours,
                    it.balance, life?.let { _ -> b.health(it.id) }, life != null && life.mood < ANGRY,
                )
            }
        return ClubTableView(league, p.number, p.week, standings(w, b, p, league), all)
    }

    /**
     * Club [id]'s page: its standing in its league's club table, its members (the counted ones
     * first, by what they bring), its kept places, moves and members' matches, its numbers; with a
     * club world its life (개요, 선수단, 구단: [lifeOf]), its history ([historyOf], a gone club's
     * too), the members' contracts and those it has out on loan.
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
        val world = shown(w, b)?.let { WorldRead(w, b) }
        val life = world?.let { lifeOf(it, id, members) }
        fun terms(k: String) = if (life != null) world.terms(k, life.captain) else null
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
                terms(k),
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
            w.honours.all.count { it.kind == "CLUBCUP" && it.key == id },
            life,
            world?.let { historyOf(it, id, places) },
            if (life == null) emptyList() else ClubHistory.loanedOut(b, id).map { k -> loanedOut(w, b, k, world.terms(k, null)) },
        )
    }

    /** A member of another club on loan from this one (↔ 임대 보낸 선수): its place where it plays. */
    private fun loanedOut(w: LeagueWorld, b: ClubBook, k: String, terms: TermsView?): ClubMember {
        val s = w.plan?.league
        val d = s?.divisionOf(k)
        return ClubMember(
            k, host.shown(k), d?.let { s.leagues.label(it) },
            d?.let { s.table(it).indexOfFirst { t -> t.key == k } + 1 }?.takeIf { it > 0 },
            null, host.view()?.rows?.firstOrNull { it.key == k }?.rating, "", false, terms,
        )
    }

    // ---- the club world (UX 4-5) ---------------------------------------------------------------

    /**
     * What the club world's readers share in one load (under the lock): the season in play, its
     * money multipliers, every member's market value (each valued once) and the rankings, made on
     * first use.
     */
    private inner class WorldRead(val w: LeagueWorld, val b: ClubBook) {
        val p: SeasonPlan? = w.plan
        val season: Int = p?.number ?: (b.season + 1)
        val value: (String) -> Int by lazy {
            val v = b.valuer({ w.ladder.rating(it) }, { k -> w.players[k]?.age(season) }, { k -> w.players[k]?.identity?.potential }, { k -> w.players[k]?.identity?.abilities?.overall })
            val cache = HashMap<String, Int>()
            val f: (String) -> Int = { k -> cache.getOrPut(k) { v(k) } }
            f
        }
        val ranks by lazy { b.rankings(value) }
        val economy: LeagueEconomy by lazy { p?.let { LeagueEconomy.of(it, drawnRatings(w, it)) } ?: LeagueEconomy.NEUTRAL }
        val favourites = host.favourites()

        /**
         * Whether the board's next checkpoint is the winter's (UX 4.2's warning): its week ⌊R / 2⌋ + 1 of
         * the R league weeks still to close and its checkpoint not yet run (else the season's end is next).
         */
        val winterNext: Boolean by lazy {
            val plan = p ?: return@lazy false
            (b.world?.checkpoint ?: 0) < plan.number && plan.week <= winterWeek(plan.calendar.leagueWeeks)
        }

        fun badge(id: String?): ClubBadge? = id?.let { badgeOf(b, it) }

        /** A coach's name with its flag ("🇰🇷 이민수"), active or retired; its id when the book has none. */
        fun coachName(id: String): String = b.coach(id)?.let { coachShown(it.nation, it.name) }
            ?: b.retiredCoaches.firstOrNull { it.id == id }?.let { coachShown(it.nation, it.name) } ?: id

        /** Member [k]'s contract as the 선수단 rows and the 💼 details show it ([captain]: its club's); null without terms. */
        fun terms(k: String, captain: String?): TermsView? {
            val t = b.terms(k) ?: return null
            val club = b.clubOf(k)
            return TermsView(t.number, k == captain, t.until, t.wage, value(k), t.clause, t.academy != null && t.academy == club, t.gradSeason, badge(t.loanFrom), t.since, badge(t.academy))
        }

        /** The board's outlook of club [club] through the last closed week of the season in play (none in a legacy season). */
        fun outlook(club: String): BoardOutlook? {
            val plan = p?.takeIf { it.league.leagues.national } ?: return null
            return CoachMarket.outlook(plan, b, club, plan.week - 1, drawnRatings(w, plan), w.honours.of(plan.number))
        }

        fun coachLine(c: Coach, o: BoardOutlook?): CoachLine =
            CoachLine(c.id, coachShown(c.nation, c.name), c.philosophy, c.since, c.until, c.reputation, if (c.club == null) null else o?.let(::pressureOf))

        fun story(s: MarketStory): StoryView = storyView(b, s, host.shown(s.key), favourites) { badge(it) }

        fun tenure(coach: String, t: Tenure): TenureView = TenureView(coach, coachName(coach), badge(t.club), t.from, t.to, t.best, t.titles, t.end)
    }

    /**
     * Club [id]'s club-world parts (UX 4.2-4.4): null without a club world, in a legacy season and
     * for a gone club. The club's money, owner, sponsor, stadium, fans, its coach and the board's
     * outlook, captain, rivals with their records (all time and this season), the next rival match
     * in the week in play or the next, the open stories it buys or sells in, its academy's
     * graduates playing.
     */
    private fun lifeOf(r: WorldRead, id: String, members: List<String>): ClubLifeView? {
        val b = r.b
        val club = b.club(id) ?: return null
        val life = b.life(id) ?: return null
        val p = r.p
        val ranks = r.ranks
        val leagueId = members.firstNotNullOfOrNull { k -> p?.league?.leagueOf(k)?.id } ?: club.nation
        val (incomes, expenses) = life.last?.let(::accountItems) ?: (emptyList<AccountItem>() to emptyList())
        val stadium = life.stadium
        val attendance = ClubEconomy.attendance(b, id, r.economy.lambdaOf(leagueId))
        val seats = ClubEconomy.seats(stadium).coerceAtLeast(1)
        val coach = b.coachOf(id)
        val board = coach?.let { r.outlook(id) }
        val byFans = ranks.fans.map { it.first }
        val nation = byFans.filter { b.nationOf(it) == club.nation }
        // This season's series through the closed weeks, and the all-time records with them added
        // ([Derbies.allTime], from the one pass over the season's matches [seriesOf] keeps).
        val series = p?.let { seriesOf(b, it) }.orEmpty()
        val rivals = life.rivals.mapNotNull { rv ->
            val badge = r.badge(rv.club) ?: return@mapNotNull null
            val season = series[pairKey(id, rv.club)]
            val all = p?.let { allTime(b.derby(id, rv.club), season, it.number, b.isRival(id, rv.club)) } ?: b.derby(id, rv.club)
            val now = season?.of(id)
            RivalView(badge, rv.kind, Derbies.name(b, id, rv.club).orEmpty(), rv.since, all, now?.let { listOf(it.first, it.second, it.third) } ?: listOf(0, 0, 0))
        }
        val stories = b.stories(r.season).filter { (it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS) && (it.buyer == id || it.seller == id) }
            .sortedWith(compareBy<MarketStory> { it.stage != StoryStage.TALKS }.thenByDescending { it.fee }.thenBy { it.id }).take(STORIES)
        return ClubLifeView(
            life.nickname, life.colours, life.foundedYear,
            b.health(id) ?: 'C', b.troubleLevel(id) ?: 0,
            life.balance, b.clubValue(id, r.value) ?: 0, ranks.value.indexOfFirst { it.first == id }.takeIf { it >= 0 }?.plus(1),
            b.transferBudget(id, r.w.config.ffp), b.wageBill(id), ClubEconomy.income(b, id).takeIf { it > 0 || life.last != null },
            life.last, incomes.take(TOP_ITEMS), expenses.take(TOP_ITEMS), life.history.take(YEARS),
            life.owner, life.sponsor, StadiumView(stadium.name, stadium.capacity, attendance, attendance.toDouble() / seats, stadium.project, stadium.naming),
            life.training, life.academy,
            life.fans, byFans.indexOf(id) + 1, nation.indexOf(id) + 1, life.mood,
            coach?.let { r.coachLine(it, board) }, board, life.captain,
            rivals, p?.let { nextRivalMatch(r, it, id, members) }, stories.map(r::story),
            Academy.graduates(b, id).size,
            ClubEconomy.squadValue(b, id, r.value), members.mapNotNull { k -> r.w.players[k]?.age(r.season)?.takeIf { it > 0 } }.average().takeIf { !it.isNaN() } ?: 0.0,
            r.winterNext,
        )
    }

    /**
     * The season's series of rival pairs read last ([seriesOf]) and what it was read from: the plan, its
     * week, the book's version and the matches played (under the lock).
     */
    private var series: Pair<List<Any>, Map<String, DerbyRecord>>? = null

    /**
     * [Derbies.series] of [p] through its closed weeks: one pass over every match of the season, the
     * dearest part of a club page (≈ 5 ms at 300 players), so kept for the next club pages until a
     * result comes in, the week moves on or the book changes.
     */
    private fun seriesOf(b: ClubBook, p: SeasonPlan): Map<String, DerbyRecord> {
        val played = p.league.fixturesPlayed + (p.comps + p.post).sumOf { c -> c.stageCounts().sumOf { it.played } }
        val key = listOf(p, p.week, b.version, played)
        series?.takeIf { it.first == key }?.let { return it.second }
        return Derbies.series(b, p, p.week - 1).also { series = key to it }
    }

    /** A rival match of club [id]'s members in the week in play or the next (UX 4.2: 다음 라이벌전), the earliest. */
    private fun nextRivalMatch(r: WorldRead, p: SeasonPlan, id: String, members: List<String>): FollowMatch? {
        val s = p.league
        if (r.b.rivals(id).isEmpty() || !s.leagues.national) return null
        val own = members.toHashSet()
        val rivals = Derbies.matches(r.b, drawnRatings(r.w, p))
        var best: FollowMatch? = null
        for (fid in 0 until s.fixturesTotal) {
            val f = s.fixture(fid)
            if (f.matchday !in p.week - 1..p.week || s.result(fid) != null) continue
            val mine = if (f.a in own) f.a else if (f.b in own) f.b else continue
            val name = rivals.name(s, f.a, f.b) ?: continue
            if (best != null && best.week <= f.matchday + 1) continue
            best = FollowMatch(
                "${p.number}:${Comp.LEAGUE.code}:$fid", f.matchday + 1, name, if (mine == f.a) f.b else f.a,
                "${host.shown(f.a)} vs ${host.shown(f.b)}", null, null, false, null,
            )
        }
        return best
    }

    /**
     * Club [id]'s 역사 (UX 4.5), also of a gone club: the seasons of its club-table titles that its
     * kept [places] show, its 클럽 대항전 titles and the major titles its members won there, its
     * legends, records, coaches (≤ [TENURES], newest first) and biggest transfers (≤ [NOTABLE], by
     * fee). Null without a club world or its state.
     */
    private fun historyOf(r: WorldRead, id: String, places: List<Pair<Int, Int>>): ClubHistoryView? {
        val b = r.b
        val life = b.life(id) ?: return null
        val w = r.w
        val retired = life.retired.associateBy { it.key }
        val legends = life.legends.map { l ->
            LegendView(l.key, host.shown(l.key), retired[l.key]?.number ?: l.number.takeIf { it > 0 }, l.seasons, l.titles, l.oneClub, l.key in retired)
        }
        val records = life.records.sortedBy { it.kind.ordinal }.map { c ->
            val value = when (c.kind) {
                ClubRecordKind.APPS -> "${c.value}경기"
                ClubRecordKind.TITLES -> "${c.value}회"
                ClubRecordKind.SIGNING, ClubRecordKind.SALE -> Money.text(c.value)
            }
            ClubRecordView(c.kind.label, c.key, c.key?.let { host.shown(it) } ?: "–", value, c.season)
        }
        val major: (Int, String) -> Boolean = w.federation::isMajor
        val memberTitles = w.honours.all.count { h ->
            HonourBook.isMajor(h, major) && w.archive.lines(h.key).firstOrNull { it.season == h.season }?.club == id
        }
        val tenures = b.tenuresAt(id).take(TENURES).map { (coach, t) -> r.tenure(coach, t) }
        val notable = b.notable.filter { it.from == id || it.to == id }
            .sortedWith(compareByDescending<NotableMove> { it.fee }.thenByDescending { it.season }).take(NOTABLE)
            .map { m -> FeeLine(m.season, m.key, host.shown(m.key), r.badge(m.from), r.badge(m.to), m.fee, m.flags) }
        return ClubHistoryView(
            places.filter { it.second == 1 }.map { it.first }, w.honours.all.count { it.kind == "CLUBCUP" && it.key == id }, memberTitles,
            legends, records, tenures, notable,
        )
    }

    /** Coach [id]'s page (UX 5): an active coach's (with a club or free) or a retired one's; null without a club world or such a coach. */
    private fun coachPageOf(w: LeagueWorld, id: String): CoachPage? {
        val b = book(w)?.takeIf { it.world != null } ?: return null
        val r = WorldRead(w, b)
        val now = r.season
        val key = Page.Coach(id).loadKey
        val major: (Int, String) -> Boolean = w.federation::isMajor
        b.coach(id)?.let { c ->
            val board = c.club?.let { r.outlook(it) }
            // The kept spells' seasons; the rest of its seasons in charge are the older spells' (never below 0: a
            // spell counts its first and last season whole, a winter sacking's included).
            val kept = c.tenures.sumOf { it.seasons(now) }
            val free = b.freeCoaches.sortedWith(compareByDescending<Coach> { it.reputation }.thenComparing({ it.id }, CoachBook.ID_ORDER))
            return CoachPage(
                key, c.id, coachShown(c.nation, c.name), c.age(now), c.gender, c.philosophy, c.dev, c.reputation,
                r.badge(c.club), c.since, c.until, c.wage, board, if (c.club == null) null else board?.let(::pressureOf),
                c.player, c.player?.let { playerSeasons(w, it) } ?: 0, c.player?.let { w.honours.majorTitles(it, major) } ?: 0,
                c.tenures.map { r.tenure(c.id, it) }, maxOf(0, c.spells - c.tenures.size), if (c.spells > c.tenures.size) maxOf(0, c.seasons - kept) else 0,
                c.clubTitles, c.leagueTitles, c.awards, c.sacked, signings(w, b, c.tenures, now), null,
                if (c.club == null) free.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) else null,
                c.name, c.cups, r.winterNext,
                c.club?.let { club -> b.members(club).firstNotNullOfOrNull { k -> w.plan?.league?.leagueOf(k)?.id }?.let(LeagueIds::label) }.orEmpty(),
            )
        }
        val rc: RetiredCoach = b.retiredCoaches.firstOrNull { it.id == id } ?: return null
        return CoachPage(
            key, rc.id, coachShown(rc.nation, rc.name), rc.retired - rc.born, rc.gender, rc.philosophy, 0, rc.reputation,
            null, 0, 0, 0, null, null,
            rc.player, rc.player?.let { playerSeasons(w, it) } ?: 0, rc.player?.let { w.honours.majorTitles(it, major) } ?: 0,
            emptyList(), rc.count("clubs"), rc.count("seasons"), rc.count("CT"), rc.count("L0"), rc.count("COY"), rc.count("sacked"),
            emptyList(), rc.retired, null, rc.name, rc.count("IC"),
        )
    }

    /** The seasons player [key] played: its archive lines, else its retired record's. */
    private fun playerSeasons(w: LeagueWorld, key: String): Int =
        w.archive.lines(key).size.takeIf { it > 0 }
            ?: w.players.retired.firstOrNull { it.key == key }?.seasons
            ?: w.players.legends.firstOrNull { it.key == key }?.record?.seasons ?: 0

    /**
     * The signings of a coach's clubs while it was in charge (UX 5 🎯 영입 성향), by the style
     * family of each player signed (a transfer into the club in an off-season before one of its
     * seasons there), most first; players whose genes the roster no longer keeps are left out.
     */
    private fun signings(w: LeagueWorld, b: ClubBook, tenures: List<Tenure>, now: Int): List<Pair<StyleFamily, Int>> {
        val counts = java.util.EnumMap<StyleFamily, Int>(StyleFamily::class.java)
        for (m in b.history) {
            if (m.kind != ClubMoveKind.TRANSFER || m.to == null) continue
            if (tenures.none { t -> t.club == m.to && m.season + 1 in t.from..(if (t.to == 0) now else t.to) }) continue
            val f = StyleFamily.of(w.players[m.ai]?.spec?.play) ?: continue
            counts.merge(f, 1, Int::plus)
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<StyleFamily, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key to it.value }
    }

    /**
     * 클럽 랭킹 of [scope] (UX 4.8): its clubs (a national league's nations', or the world's) in the
     * five lists, each the first [RANK_ROWS] and then the followed clubs further down, with the
     * moves against the last close's top 10 (value and results; the world's lists only) and the
     * clubs of each health grade. Empty lists without a club world; null for a league the season lacks.
     */
    private fun clubRankingOf(w: LeagueWorld, scope: String?): ClubRankingView? {
        val b = book(w) ?: return null
        val p = w.plan
        val key = Page.ClubRanking(scope = scope).loadKey
        val nations = scope?.let { id ->
            val lg = p?.league?.leagues?.byId(id)?.takeIf { it.national } ?: return null
            lg.divisions.flatMap { p.league.divisions[it] }.mapNotNull { k -> b.playNation(k) }.toSet()
        }
        if (shown(w, b) == null) return ClubRankingView(key, p?.number ?: 0, emptyMap(), List(GRADES.length) { 0 })
        val r = WorldRead(w, b)
        val inScope: (String) -> Boolean = { c -> nations == null || b.nationOf(c) in nations }
        val ranks = r.ranks
        val before = if (scope == null) ClubRanks.previous(b, r.season) else null
        fun <T> list(rows: List<Pair<String, T>>, moves: List<String>?, text: (String, T) -> String): List<ClubRankLine> {
            val out = ArrayList<ClubRankLine>()
            for ((i, row) in rows.filter { inScope(it.first) }.withIndex()) {
                val followed = LeagueSelector.CLUB + row.first in r.favourites
                if (i >= RANK_ROWS && !followed) continue
                val badge = r.badge(row.first) ?: continue
                out += ClubRankLine(i + 1, badge, text(row.first, row.second), moves?.let { ClubRanks.move(it, row.first, i) }, followed)
            }
            return out
        }
        val lists = linkedMapOf(
            ClubRankKind.VALUE to list(ranks.value, before?.value) { _, v -> Money.text(v) },
            ClubRankKind.RESULTS to list(ranks.results, before?.results) { _, v -> "${points(v)}점 (${ClubRanks.SEASONS}시즌)" },
            ClubRankKind.RICH to list(ranks.rich, null) { _, v -> Money.text(v) },
            ClubRankKind.FANS to list(ranks.fans, null) { _, v -> "팬 ${"%,d".format(v)}명" },
            ClubRankKind.HEALTH to list(ranks.health, null) { c, g -> "$g · 자금 ${Money.text(b.club(c)?.balance ?: 0)}" },
        )
        val grades = GRADES.map { g -> ranks.health.count { it.second == g && inScope(it.first) } }
        val moved = if (before == null) emptySet() else setOf(ClubRankKind.VALUE, ClubRankKind.RESULTS)
        return ClubRankingView(key, r.season, lists, grades, moved)
    }

    /**
     * Player [key]'s 💼 line (UX 4.7): its club and contract, an open story about it, its club's
     * coach, the coach it became (also after it retired) and its style family; null without a club
     * world, or for a player with neither a club nor a coach's job.
     */
    private fun playerClubOf(w: LeagueWorld, key: String): PlayerClubCard? {
        val b = book(w)?.let { shown(w, it) } ?: return null
        val r = WorldRead(w, b)
        val club = b.clubOf(key)
        val retired = b.retiredCoaches.firstOrNull { it.player == key }
        val became = b.coaches.firstOrNull { it.player == key }?.id ?: retired?.id
        if (club == null && became == null) return null
        val story = b.stories(r.season).filter { it.key == key && (it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS) }
            .maxWithOrNull(compareBy<MarketStory> { it.stage == StoryStage.TALKS }.thenBy { it.id })
        return PlayerClubCard(
            playerClubKey(key), r.badge(club), club?.let { r.terms(key, b.life(it)?.captain) }, story?.let(r::story),
            club?.let { b.coachOf(it)?.id }, became, StyleFamily.of(w.players[key]?.spec?.play), r.badge(became?.let { b.coach(it)?.club }),
            if (became != null && b.coach(became) == null) retired?.retired ?: 0 else 0,
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
        // No season yet (a new league before its start): none, as season 0.
        val p = w.plan ?: return RankingView(0, emptyList(), byStrength = true, published = 0)
        if (!p.league.leagues.national) return RankingView(p.number, emptyList(), byStrength = false, published = 0)
        val live = Coefficients.season(p)
        val rows = p.ranking.rows.map { r ->
            RankRow(r, LeagueIds.label(r.id), LeagueIds.short(r.id), LeagueIds.icon(r.id), live[r.id]?.takeIf { it[1] > 0 }?.let(Coefficients::value))
        }
        val published = w.federation.coefficientSeasons.filter { it < p.number }.maxOrNull() ?: 0
        return RankingView(p.number, rows, byStrength = published == 0, published = published)
    }

    private fun federationOf(w: LeagueWorld): FederationView {
        // A new league before its first season: its first assembly comes after season 1.
        val number = w.plan?.number ?: 1
        val f = w.federation
        // The last closed season's awards: the season's, then the league MVPs of its best-ranked leagues.
        val closed = w.honours.seasons.lastOrNull { it < number } ?: 0
        val top = f.top(closed).orEmpty()
        val honours = w.honours.of(closed)
        val awards = AWARDS.mapNotNull { k -> honours.firstOrNull { it.kind == k } } +
            top.mapNotNull { id -> honours.firstOrNull { it.kind == HonourKind.of(Awards.LMVP, id) } }
        return FederationView(
            w.config.federation,
            f.pending().map(::proposalView),
            f.proposals.filter { !it.pending }.asReversed().map(::proposalView),
            awards.map { it.kind to host.shown(it.key) },
            number,
            closed,
        )
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

        /** The club world's lists: open stories on a club's 개요, its accounts' items folded, money seasons, coaches, transfers, ranking rows. */
        private const val STORIES = 3
        private const val TOP_ITEMS = 3
        private const val YEARS = 5
        private const val TENURES = 10
        private const val NOTABLE = 10
        private const val RANK_ROWS = 50

        /**
         * The winter checkpoint's week of a season of [leagueWeeks] league weeks (ENG 3.3: it runs once week
         * `⌊R / 2⌋ + 1` closes, 22 at R = 42; ⌈0.6 R⌉ is when the rumours start, not the checkpoint).
         */
        internal fun winterWeek(leagueWeeks: Int): Int = leagueWeeks / 2 + 1

        /** Fans this unhappy (below 25: 😡, as [yacht.app.ui.moodWord] reads it) mark their club on the club table. */
        const val ANGRY = 25

        /** The health grades, best first. */
        private const val GRADES = "ABCDE"

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

/** The load key of player [key]'s 💼 line ([WorldPages.playerClubs]). */
internal fun playerClubKey(key: String) = "pc:$key"

/**
 * The ratings national season [p] was drawn with, as the board's targets and the rival matches
 * read them: the start ratings the stats keep for it, else the ladder's in whole points (the
 * engine's `LeagueWorld.drawn`, which is private until P17 exposes it as `drawnRatings`; the cup's
 * seeds it reads first are those same start ratings).
 */
internal fun drawnRatings(w: LeagueWorld, p: SeasonPlan): (String) -> Double {
    val started = w.stats.season == p.number
    return { k ->
        (if (started) w.stats.startRating(k) else Double.NaN).takeIf { !it.isNaN() }
            ?: w.ladder.rating(k).let { if (it.isNaN()) it else Math.rint(it) }
    }
}

/** The board's pressure in a level (UX 5): the pressure the next checkpoint would leave over the sacking threshold. */
internal fun pressureOf(o: BoardOutlook): PressureLevel = PressureLevel.of(if (o.threshold > 0) o.projected / o.threshold else Double.NaN)

/** A coach as the screens name it: its nation's flag and its name. */
internal fun coachShown(nation: String, name: String): String = Nations.of(nation)?.flag?.let { "$it $name" } ?: name

/**
 * Story [s] of book [b] as the screens show it (UX 6.2): [shown] its player's name, followed when the
 * player or either club is among [favourites], [badge] each club's badge (null for none or unknown).
 */
internal fun storyView(b: ClubBook, s: MarketStory, shown: String, favourites: Set<String>, badge: (String?) -> ClubBadge?): StoryView {
    val followed = s.key in favourites || LeagueSelector.CLUB + s.buyer in favourites || LeagueSelector.CLUB + s.seller in favourites
    return StoryView(
        s.id, s.stage, s.key, shown, badge(s.buyer), badge(s.seller), s.fee, s.rumourWeek, s.talksWeek,
        storyReason(s.reason, s.by?.let { b.nameOf(it) }), badge(s.by), s.winter, b.isRival(s.buyer, s.seller), followed,
    )
}

/** Why a transfer story ended ([MarketStory.reason], UX 6.2) in words ([by]: the club that took the player); null while it goes on ('-'). */
internal fun storyReason(reason: Char, by: String?): String? = when (reason) {
    'P' -> "가격이 맞지 않았어요"
    'S' -> "선수가 잔류를 택했어요"
    'H' -> by?.let { "${Ko.i(it)} 가로챘어요" } ?: "다른 클럽이 가로챘어요"
    'M' -> "감독이 바뀌면서 없던 일이 됐어요"
    'F' -> "구단 재정이 발목을 잡았어요"
    'R' -> "규정에 막혔어요"
    else -> null
}

/**
 * Accounts [a]'s items by side, biggest first and zeros left out (UX 4.4): income (중계권, 상금,
 * …, a positive 구단주 지원) and expenses (선수 연봉, …, a fund's dividend as 배당).
 */
internal fun accountItems(a: Accounts): Pair<List<AccountItem>, List<AccountItem>> {
    val income = ArrayList<AccountItem>()
    val expenses = ArrayList<AccountItem>()
    for (item in MoneyItem.entries) {
        val x = item.of(a)
        when {
            x == 0 -> {}
            item == MoneyItem.OWNER && x < 0 -> expenses += AccountItem(MoneyItem.DIVIDEND, -x)
            item.income -> income += AccountItem(item.label, x)
            else -> expenses += AccountItem(item.label, x)
        }
    }
    return income.sortedByDescending { it.amount } to expenses.sortedByDescending { it.amount }
}

/**
 * [Derbies.allTime] from the pair's series this season ([now], [Derbies.series]'s): the [stored] record
 * with this season's matches added, unless it holds season [season] already or the clubs are no
 * longer [rival]s (the close folds only rivals); null without a stored record.
 */
internal fun allTime(stored: DerbyRecord?, now: DerbyRecord?, season: Int, rival: Boolean): DerbyRecord? {
    if (stored == null || now == null || stored.last >= season || !rival) return stored
    return stored.copy(winsA = stored.winsA + now.winsA, draws = stored.draws + now.draws, winsB = stored.winsB + now.winsB, games = stored.games + now.games, last = season)
}

/** The key of rival pair [a]-[b] in [Derbies.series] (its ids in the book's order). */
private fun pairKey(a: String, b: String): String = ClubHistoryBook.pair(a, b).let { "${it.first} ${it.second}" }

/** Club-ranking points to a tenth ("12" or "12.5"). */
private fun points(x: Double): String = (Math.round(x * 10) / 10.0).let { if (it == Math.floor(it)) "%.0f".format(it) else "%.1f".format(it) }

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
