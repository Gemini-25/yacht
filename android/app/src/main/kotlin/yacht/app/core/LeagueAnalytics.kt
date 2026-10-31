package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.Tables
import yacht.engine.explain.Ko
import yacht.engine.league.AiStats
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.HonourKind
import yacht.engine.league.JobKind
import yacht.engine.league.LeaderEntry
import yacht.engine.league.LeaderStat
import yacht.engine.league.Leaders
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MatchRef
import yacht.engine.league.Nations
import yacht.engine.league.Origin
import yacht.engine.league.Preview
import yacht.engine.league.RecordChase
import yacht.engine.league.RecordGroup
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatches
import yacht.engine.league.TieRule
import yacht.engine.league.TieView
import yacht.engine.league.WatchTurn
import yacht.engine.league.byTeams
import yacht.engine.league.diff
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The league's analytics on request (`lg.analytics`): player pages, leaderboards (기록 › 리더 and
 * [Page.Leaderboard]), the records room, the hall of fame and its chronicle, the season reviews
 * and their list, fun facts and the league comparison, a nation's numbers; each loader keeps its
 * last results by key and reads the league under its lock off the main thread.
 */
class LeagueAnalytics internal constructor(private val host: LeagueHost) {
    /**
     * The player pages loaded last (at most [WorldPages.KEEP]), by [Page.Player.loadKey], the newest
     * last; all of one league ([source]). Compared by identity, like the competition pages.
     */
    var players by mutableStateOf<Map<String, PlayerPage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** 기록 › 리더 as loaded last ([loadLeaders]). */
    var leaders by mutableStateOf<LeadersView?>(null, referentialEqualityPolicy())
        private set

    /** Whole leaderboards loaded last (at most [WorldPages.KEEP]), by [boardKey]. */
    var boards by mutableStateOf<Map<String, LeaderBoardView>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** 기록 › 기록실, 기록 › 전당, the chronicle, 기록 › 결산 and the fun facts, as loaded last. */
    var room by mutableStateOf<RoomView?>(null, referentialEqualityPolicy())
        private set
    var hall by mutableStateOf<HallView?>(null, referentialEqualityPolicy())
        private set
    var chronicle by mutableStateOf<ChronicleView?>(null, referentialEqualityPolicy())
        private set
    var reviewList by mutableStateOf<ReviewList?>(null, referentialEqualityPolicy())
        private set
    var facts by mutableStateOf<FactsView?>(null, referentialEqualityPolicy())
        private set

    /** Season review pages loaded last (at most [WorldPages.KEEP]), by [Page.Review.loadKey]. */
    var reviews by mutableStateOf<Map<String, ReviewPage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /**
     * Groups' numbers this season and their sentence against everyone's, loaded last, by [groupKey]
     * ("n:KR" a nation's players, "l:KR" a league's members, "c:c07" a club's squad).
     */
    var groups by mutableStateOf<Map<String, Pair<AggregateStats, String>>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** The league everything above was read from: a view of a league replaced since (a reset, a seed, a restore) is not shown. */
    private var source: LeagueWorld? = null
    private val requests = AtomicInteger()

    /** The newest request per load key: an older one finishing later is dropped. */
    private val latest = ConcurrentHashMap<String, Int>()

    private fun fresh(): Boolean = source === host.world()

    /** [page]'s data, when it was loaded from the league in play (else null: still to load). */
    fun player(page: Page.Player): PlayerPage? = players[page.loadKey]?.takeIf { fresh() }

    /** 기록 › 리더 for [filter] when loaded from the league in play (else null). */
    fun leaders(filter: LeaderFilter): LeadersView? = leaders?.takeIf { fresh() && it.filter == filter }

    /** 기록 › 리더 as loaded last, whatever its filter (shown while another loads). */
    fun anyLeaders(): LeadersView? = leaders?.takeIf { fresh() }

    /** [kind]'s whole board for [filter], when loaded. */
    fun board(kind: LeaderKind, filter: LeaderFilter): LeaderBoardView? = boards[boardKey(kind, filter)]?.takeIf { fresh() }

    fun room(): RoomView? = room?.takeIf { fresh() }
    fun hall(): HallView? = hall?.takeIf { fresh() }
    fun chronicle(): ChronicleView? = chronicle?.takeIf { fresh() }
    fun reviewList(): ReviewList? = reviewList?.takeIf { fresh() }
    fun facts(): FactsView? = facts?.takeIf { fresh() }
    fun review(page: Page.Review): ReviewPage? = reviews[page.loadKey]?.takeIf { fresh() }
    fun group(key: String): Pair<AggregateStats, String>? = groups[key]?.takeIf { fresh() }

    /**
     * Builds [loadKey]'s view with [build] (off the main thread, under the league's lock) and hands
     * it to [store] on the main thread, unless a newer request of the same key came meanwhile or
     * the league was replaced. A part the league does not have in that shape leaves the view
     * unloaded (null from [build], or an exception), not the app down.
     */
    private fun <T : Any> load(loadKey: String, build: (LeagueWorld, LeagueView?) -> T?, store: (T) -> Unit) {
        val n = requests.incrementAndGet()
        latest[loadKey] = n
        host.scope.launch(Dispatchers.Default) {
            val view = host.view()
            val built = synchronized(host.lock) {
                val w = host.world() ?: return@synchronized null
                runCatching { build(w, view) }.getOrNull()?.let { w to it }
            } ?: run {
                latest.remove(loadKey, n)
                return@launch
            }
            host.scope.launch {
                val (w, v) = built
                if (latest[loadKey] != n) return@launch
                latest.remove(loadKey, n)
                if (w !== host.world()) return@launch
                if (w !== source) {
                    source = w
                    players = emptyMap()
                    leaders = null
                    boards = emptyMap()
                    room = null
                    hall = null
                    chronicle = null
                    reviewList = null
                    facts = null
                    reviews = emptyMap()
                    groups = emptyMap()
                }
                store(v)
            }
        }
    }

    /**
     * Loads [key]'s page with the numbers of [scope] into [players]: off the main thread, under the
     * league's lock, in O(the season's fixtures + the league's members). The page shown meanwhile
     * is the one loaded before, so a page opened again (back from a rival's) draws at once.
     * [center] marks the matches ▶ can play again (none without it).
     */
    fun loadPlayer(key: String, scope: StatScope, center: MatchCenter? = null) {
        val loadKey = Page.Player(key, scope = scope).loadKey
        load(loadKey, { w, view -> playerPage(w, view, key, scope, loadKey, center) }) { players = WorldPages.keep(players, loadKey, it) }
    }

    /** Loads 기록 › 리더 for [filter]: the top [HUB_TOP] of every board, O(boards × players of the scope). */
    fun loadLeaders(filter: LeaderFilter) {
        load("leaders", { w, view -> leadersOf(w, view, filter) }) { leaders = it }
    }

    /** Loads [kind]'s whole board for [filter] (its first [BOARD_TOP] lines and the followed players further down). */
    fun loadBoard(kind: LeaderKind, filter: LeaderFilter) {
        val k = boardKey(kind, filter)
        load(k, { w, view -> boardOf(w, view, kind, filter) }) { boards = WorldPages.keep(boards, k, it) }
    }

    /** Loads 기록 › 기록실: this season's new records, the records with their earlier holders, the chases, record news. */
    fun loadRoom() {
        load("room", { w, _ -> roomOf(w) }) { room = it }
    }

    /** Loads 기록 › 전당 (seasons by honours, the hall of fame, dynasties, most titles). */
    fun loadHall() {
        load("hall", { w, _ -> hallOf(w) }) { hall = it }
    }

    /** Loads the chronicle ([Page.Chronicle]). */
    fun loadChronicle() {
        load("chronicle", { w, _ -> chronicleOf(w) }) { chronicle = it }
    }

    /** Loads 기록 › 결산's list. */
    fun loadReviews() {
        load("reviews", { w, _ -> reviewListOf(w) }) { reviewList = it }
    }

    /** Loads season [season]'s review page; [center] tells which of its games ▶ can play again. */
    fun loadReview(season: Int, center: MatchCenter? = null) {
        val k = Page.Review(season).loadKey
        load(k, { w, _ -> reviewPageOf(w, season, k, center) }) { reviews = WorldPages.keep(reviews, k, it) }
    }

    /** Loads the fun facts and the league comparison ([Page.Facts]). */
    fun loadFacts() {
        load("facts", { w, _ -> factsOf(w) }) { facts = it }
    }

    /** Loads group [key]'s numbers this season and their sentence ([groups]; 국적 선수 통계, a league's or a club's style). */
    fun loadGroup(key: String) {
        load("g:$key", { w, _ -> groupOf(w, key) }) { groups = WorldPages.keep(groups, key, it) }
    }

    // ---- player page --------------------------------------------------------------------------------

    /** [key]'s page (under the lock); [view] is the screen's snapshot (its ladder line and competitions). */
    private fun playerPage(w: LeagueWorld, view: LeagueView?, key: String, scope: StatScope, loadKey: String, center: MatchCenter?): PlayerPage {
        val plan = w.plan
        val season = plan?.league
        val d = season?.divisionOf(key)
        val league = d?.let { season.leagues.of(it) }
        // The league's members this season; everyone in the season for a player outside it.
        val members = when {
            season == null -> emptyList()
            league != null -> league.divisions.flatMap { season.divisions[it] }
            else -> season.divisions.flatten()
        }
        val statsOf: (String) -> AiStats? = if (scope == StatScope.SEASON) { k -> w.stats.season(k) } else { k -> w.stats.career(k) }
        val own = statsOf(key)?.takeIf { it.games > 0 }
        val others = members.mapNotNull { k -> statsOf(k)?.takeIf { it.games > 0 }?.let { k to it } }
        val profile = profileOf(w, key, host::name)

        // The league's boxes, bonus and scores from its members' sums (the meta for a player outside a season).
        val carded = others.sumOf { it.second.carded }
        val meta = profile.league
        fun leagueBox(c: Category) = if (carded > 0) others.sumOf { it.second.boxSums[c.ordinal] }.toDouble() / carded else meta?.boxAverage(c) ?: Double.NaN
        fun leagueZero(c: Category) = if (carded > 0) others.sumOf { it.second.boxZeros[c.ordinal] }.toDouble() / carded else meta?.zeroRate(c) ?: Double.NaN
        val box = if (own == null || own.carded == 0) emptyList() else Category.ALL.map { c -> BoxRow(c, own.boxAverage(c), leagueBox(c), own.zeroRate(c), leagueZero(c)) }
        val upper = Category.ALL.filter { it.isUpper }
        val games = others.sumOf { it.second.games }
        val leagueHistogram = IntArray(AiStats.BINS).also { h -> for ((_, s) in others) for (i in h.indices) h[i] += s.histogram[i] }
        val upperPair = own?.takeIf { box.isNotEmpty() }?.let { s -> upper.sumOf { s.boxAverage(it) } to upper.sumOf { leagueBox(it) } } ?: (Double.NaN to Double.NaN)
        val bonusPair = (own?.let { it.bonuses.toDouble() / it.games } ?: Double.NaN) to (if (games > 0) others.sumOf { it.second.bonuses }.toDouble() / games else Double.NaN)

        val matches = ArrayList<PlayerMatch>()
        val ids = ArrayList<Int>()
        var next: FollowMatch? = null
        if (season != null && d != null) {
            var nextDay = Int.MAX_VALUE
            for (id in 0 until season.fixturesTotal) {
                val f = season.fixture(id)
                if (f.a != key && f.b != key) continue
                ids += id
                val first = f.a == key
                val opp = if (first) f.b else f.a
                val ref = "${season.number}:${Comp.LEAGUE.code}:$id"
                val where = "리그 ${f.round + 1}라운드"
                val result = season.result(id)
                if (result != null) {
                    val r = TieRule.match(result, season.format).let { if (first) it else -it }
                    val score = result.joinToString(" · ") { g -> if (first) "${g.a}:${g.b}" else "${g.b}:${g.a}" }
                    val replay = center?.canReplay(MatchRef(season.number, Comp.LEAGUE, id), f.a, f.b) == true
                    matches += PlayerMatch(ref, f.matchday + 1, where, opp, host.shown(opp), host.badge(opp), r, score, replay, Comp.LEAGUE)
                } else if (!season.isPlayed(id) && f.matchday < nextDay) {
                    nextDay = f.matchday
                    val ra = w.ladder.rating(key)
                    val rb = w.ladder.rating(opp)
                    val chance = if (ra.isNaN() || rb.isNaN()) null else (100 * Preview.expected(ra, rb)).roundToInt()
                    next = FollowMatch(ref, f.matchday + 1, where, opp, host.shown(opp), null, null, false, chance)
                }
            }
        }
        matches.sortByDescending { it.week }
        // Its competition matches from the season (refs, ▶); without a season of the snapshot's, the snapshot's brackets.
        if (plan != null && season != null) {
            matches += competitionMatches(w, plan, key, ids.toIntArray(), center)
        } else if (view != null && view.clock?.season == season?.number) {
            matches += knockoutTies(view, key)
        }

        // A style needs a sample: a few games say more about the dice than about the player.
        val radar = if (own != null && own.carded >= STYLE_GAMES && box.isNotEmpty()) radarOf(box, bonusPair) else emptyList()
        val (style, styleNote) = if (own != null && radar.isNotEmpty()) styleOf(radar, own.scoreSd, pooledSd(others.map { it.second })) else (null to null)
        return PlayerPage(
            loadKey, profile, view?.rows?.firstOrNull { it.key == key }, host.badge(key), host.leagueLabel(key),
            d?.let { season.table(it).indexOfFirst { s -> s.key == key } + 1 }?.takeIf { it > 0 },
            if (own == null) emptyList() else statCells(own, others, key, scope),
            box,
            upperPair,
            bonusPair,
            own?.histogram?.copyOf(),
            matches,
            previousMatches(w, key, center),
            w.highlights.of(key).sortedByDescending { it.drama }.take(HIGHLIGHTS),
            w.news.about(key).take(NEWS),
            w.stats.records.all.filter { it.holder == key },
            w.stats.milestones.filter { it.keys.firstOrNull() == key },
            next,
            scope,
            division = d,
            tierLabel = d?.let { league?.tiers?.label(it - league.first) },
            median = median(leagueHistogram),
            rival = w.ladder.rival(key, w.members.toSet())?.let { (o, p, g) -> Matchup(o, host.name(o), p / g, g) },
            dynasties = w.archive.dynasties.filter { it.key == key },
            clubNames = profile.lines.mapNotNull { it.club }.distinct().associateWith { id -> w.clubs?.club(id)?.name ?: id },
            radar = radar,
            style = style,
            styleNote = styleNote,
            seats = season?.let { seatSplit(it, key) },
            ratingTrend = ratingTrend(profile, season?.number),
            clubHonours = clubHonours(w, profile),
        )
    }

    /** [p]'s clubs' top-3 places in their club tables in the seasons its lines name, newest first. */
    private fun clubHonours(w: LeagueWorld, p: ProfileView): List<String> {
        val b = w.clubs ?: return emptyList()
        return p.lines.asReversed().mapNotNull { l ->
            val id = l.club ?: return@mapNotNull null
            val place = b.places(l.season).firstNotNullOfOrNull { c -> c.clubs.indexOf(id).takeIf { it >= 0 } } ?: return@mapNotNull null
            "클럽 ${place + 1}위 (${b.nameOf(id) ?: id}, 시즌 ${l.season})"
        }
    }

    /**
     * [key]'s matches of this season's competitions ([SeasonMatches.ofPlayer], with its league
     * fixtures [ids] so the league is not scanned again): played ones, newest first, from its side
     * (a team match from its team's side, against the player of its board).
     */
    private fun competitionMatches(w: LeagueWorld, plan: yacht.engine.league.SeasonPlan, key: String, ids: IntArray, center: MatchCenter?): List<PlayerMatch> {
        val out = ArrayList<PlayerMatch>()
        for (m in SeasonMatches.ofPlayer(plan, key, ids)) {
            val f = m.fixture
            if (f.comp == Comp.LEAGUE || !m.played) continue
            val team = f.kind == JobKind.TEAM
            val onA = if (team) key in f.lineupA else f.a == key
            val opp = when {
                team -> (if (onA) f.lineupB else f.lineupA).getOrNull(m.ref.board) ?: (if (onA) f.b else f.a)
                else -> if (onA) f.b else f.a
            }
            val s = m.score
            val result = s?.winner?.let { if (it == 0) 0 else if ((it > 0) == onA) 1 else -1 }
            val score = s?.let { if (onA) "${halfText(it.scoreA)}:${halfText(it.scoreB)}" else "${halfText(it.scoreB)}:${halfText(it.scoreA)}" }.orEmpty()
            val where = (f.label.ifEmpty { f.comp.label }) + if (team) " · ${host.name(if (onA) f.a else f.b)} vs ${host.name(if (onA) f.b else f.a)}" else ""
            val replay = center?.canReplay(m.ref, f.a, f.b) == true
            out += PlayerMatch(m.ref.encode(), m.week, where, opp, host.shown(opp), host.badge(opp), result, score, replay, f.comp)
        }
        out.sortByDescending { if (it.week == SeasonCalendar.NONE) -1 else it.week }
        return out
    }

    /** [key]'s games of last season that are still kept to open (its highlights: finals and dramas), with ▶ where the log is kept. */
    private fun previousMatches(w: LeagueWorld, key: String, center: MatchCenter?): List<PlayerMatch> {
        val last = (w.plan?.number ?: return emptyList()) - 1
        if (last < 1) return emptyList()
        return w.highlights.allTime.filter { it.season == last && (it.a == key || it.b == key) }.distinctBy { it.id }.sortedByDescending { it.drama }.take(PREVIOUS).map { h ->
            val onA = h.a == key
            val opp = if (onA) h.b else h.a
            val (own, other) = if (onA) h.scoreA to h.scoreB else h.scoreB to h.scoreA
            val ref = MatchRef.decode(h.id)
            PlayerMatch(
                h.id, h.fixture.week, "시즌 $last · ${h.fixture.label.ifEmpty { h.fixture.comp.label }}", opp, host.shown(opp), host.badge(opp),
                own.compareTo(other).coerceIn(-1, 1), "$own:$other", ref != null && center?.canReplay(ref, h.a, h.b) == true, h.fixture.comp,
            )
        }
    }

    /** [key]'s knockout ties this season in the snapshot's competitions (a world without the season's plan: not opened). */
    private fun knockoutTies(view: LeagueView, key: String): List<PlayerMatch> = buildList {
        fun bracket(title: String, comp: Comp, rounds: List<yacht.engine.league.BracketRound>) {
            for (round in rounds.asReversed()) for (t in round.ties) {
                if (t.bye || (t.a != key && t.b != key) || !(t.played || t.winner != null)) continue
                add(tieMatch(t, key, "$title ${round.label}", comp))
            }
        }
        for ((comp, cv) in view.comps) {
            if (comp.byTeams) continue
            when (cv) {
                is CompetitionView.Bracket -> bracket(cv.title, comp, cv.rounds)
                is CompetitionView.Groups -> cv.knockout?.let { bracket(cv.title, comp, it.rounds) }
                is CompetitionView.Sections -> for (p in cv.parts) p.knockout?.let { bracket(p.title, comp, it.rounds) }
                else -> {}
            }
        }
    }

    private fun tieMatch(t: TieView, key: String, where: String, comp: Comp): PlayerMatch {
        val first = t.a == key
        val opp = (if (first) t.b else t.a).orEmpty()
        val result = when (t.winner) {
            null -> 0
            key -> 1
            else -> -1
        }
        val score = if (first) "${halfText(t.scoreA)}:${halfText(t.scoreB)}" else "${halfText(t.scoreB)}:${halfText(t.scoreA)}"
        return PlayerMatch(null, SeasonCalendar.NONE, where, opp, host.shown(opp), host.badge(opp), result, score, false, comp)
    }

    // ---- leaderboards -------------------------------------------------------------------------------

    /**
     * The players of [filter]'s area and scope, and the area's name: a league's members this
     * season (its 1부 only with [LeaderFilter.tierOnly]), everyone (over careers: every career
     * kept), a nation's players, a club's members. An area left open ([LeaderFilter.league] and
     * the rest null) takes one followed (or the shown league, Korea, the best player's club).
     */
    private fun areaOf(w: LeagueWorld, view: LeagueView?, filter: LeaderFilter): Pair<String, List<String>> {
        val season = w.plan?.league
        val favs = host.favourites()
        val members = season?.divisions?.flatten() ?: w.members
        fun firstTier(keys: List<String>) = if (!filter.tierOnly || season == null) keys else keys.filter { season.tierOf(it) == 0 }
        return when (filter.area) {
            LeaderArea.ALL -> "전체" to firstTier(if (filter.scope == StatScope.CAREER && !filter.recent) w.stats.keys.sorted() else members)
            LeaderArea.LEAGUE -> {
                val id = filter.league ?: view?.season?.league?.takeIf { it.isNotEmpty() }
                    ?: favs.firstOrNull { it.startsWith(LeagueSelector.LEAGUE) }?.removePrefix(LeagueSelector.LEAGUE)
                val lg = season?.leagues?.let { ls -> id?.let { ls.byId(it) } ?: ls.list.firstOrNull() }
                if (season == null || lg == null) "리그" to emptyList()
                else (if (lg.national) LeagueIds.label(lg.id) else "리그") to firstTier(lg.divisions.flatMap { season.divisions[it] })
            }
            LeaderArea.NATION -> {
                val code = filter.nation ?: favs.firstOrNull { it.startsWith(LeagueSelector.NATION) }?.removePrefix(LeagueSelector.NATION)
                    ?: view?.season?.league?.takeIf { Nations.of(it) != null } ?: Nations.KR.code
                val n = Nations.of(code)
                "${n?.flag ?: ""} ${n?.name ?: code} 선수".trim() to firstTier(members.filter { w.players[it]?.identity?.nation == code })
            }
            LeaderArea.CLUB -> {
                val clubs = w.clubs
                val id = filter.club ?: favs.firstOrNull { it.startsWith(LeagueSelector.CLUB) }?.removePrefix(LeagueSelector.CLUB)
                    ?: favs.firstNotNullOfOrNull { clubs?.clubOf(it) }
                    ?: members.maxByOrNull { w.ladder.rating(it).takeIf { r -> !r.isNaN() } ?: Double.NEGATIVE_INFINITY }?.let { clubs?.clubOf(it) }
                val club = id?.let { clubs?.club(it) }
                (club?.name ?: "클럽") to firstTier(club?.squad.orEmpty())
            }
        }
    }

    private fun leadersOf(w: LeagueWorld, view: LeagueView?, filter: LeaderFilter): LeadersView {
        val (area, keys) = areaOf(w, view, filter)
        val recent = if (filter.recent) w.plan?.league?.let { recentStats(it, keys) } else null
        val boards = (HUB + OTHERS).map { k -> k to entriesOf(w, k, filter, keys, recent).take(HUB_TOP).map { lineOf(k, it, filter) } }
        val missing = if (filter.recent) (HUB + OTHERS).filter { it !in RECENT_BOARDS }.toSet() else if (filter.scope == StatScope.CAREER) setOf(LeaderKind.SWEEPS) else emptySet()
        return LeadersView(filter, boards, area, missing, w.plan?.number ?: 0)
    }

    private fun boardOf(w: LeagueWorld, view: LeagueView?, kind: LeaderKind, filter: LeaderFilter): LeaderBoardView {
        val (area, keys) = areaOf(w, view, filter)
        val recent = if (filter.recent) w.plan?.league?.let { recentStats(it, keys) } else null
        val all = entriesOf(w, kind, filter, keys, recent)
        val favs = host.favourites()
        val followed = all.withIndex().filter { (i, e) -> i >= BOARD_TOP && e.key in favs }.map { (i, e) -> i + 1 to lineOf(kind, e, filter) }
        val missing = (filter.recent && kind !in RECENT_BOARDS) || (filter.scope == StatScope.CAREER && !filter.recent && kind == LeaderKind.SWEEPS)
        return LeaderBoardView(kind, filter, ruleOf(kind, filter, w.plan?.finished == true), all.take(BOARD_TOP).map { lineOf(kind, it, filter) }, followed, area, all.size, missing)
    }

    /** [kind]'s board over [keys] in [filter]'s scope, best first ([recent]: the last league matches' numbers, for [LeaderFilter.recent]). */
    private fun entriesOf(w: LeagueWorld, kind: LeaderKind, filter: LeaderFilter, keys: List<String>, recent: Map<String, RecentStats>?): List<LeaderEntry> {
        val stat = statOf(kind)
        val ascending = kind == LeaderKind.UNLUCKY
        return when {
            filter.recent -> recentBoard(kind, recent.orEmpty())
            filter.scope == StatScope.CAREER -> Leaders.career(stat, w.stats, keys, ascending)
            kind == LeaderKind.MVP -> w.plan?.let { p -> if (p.finished) Leaders.season(stat, p, w.stats, keys) else mvpRace(w, p.league, keys) }.orEmpty()
            else -> w.plan?.let { Leaders.season(stat, it, w.stats, keys, ascending) }.orEmpty()
        }
    }

    /**
     * 평점왕 while the season is under way (the MVP race): the [mvpRacers] of [keys]; at the close
     * this becomes the award's rule (that share of its own scheduled matches, [Leaders.season]), so
     * the board is not empty for the first half of a season.
     */
    private fun mvpRace(w: LeagueWorld, league: Season, keys: List<String>): List<LeaderEntry> {
        val racers = mvpRacers(league, keys)
        return keys.mapNotNull { k ->
            if (k !in racers) return@mapNotNull null
            val st = w.stats.season(k)?.takeIf { !it.avgRating.isNaN() } ?: return@mapNotNull null
            LeaderEntry(k, st.avgRating, st.matches)
        }.sortedWith(compareByDescending<LeaderEntry> { it.value }.thenByDescending { it.games }.thenBy { it.key })
    }

    private fun lineOf(kind: LeaderKind, e: LeaderEntry, filter: LeaderFilter): LeaderLine {
        val (text, sub) = leaderText(kind, e, filter.scope == StatScope.CAREER && !filter.recent)
        return LeaderLine(e.key, host.shown(e.key), host.badge(e.key), host.leagueLabel(e.key), e.value, text, sub)
    }

    // ---- records room, hall, chronicle --------------------------------------------------------------

    private fun roomOf(w: LeagueWorld): RoomView {
        val st = w.stats
        val records = st.records
        val fresh = st.brokenThisSeason.filter { !it.record.kind.retired }
        val freshKinds = fresh.map { it.record.kind }.toSet()
        val groups = RecordGroup.entries.mapNotNull { g ->
            records.current.filter { it.kind.group == g }.map { RoomRecord(it, records.history(it.kind), it.kind in freshKinds) }.takeIf { it.isNotEmpty() }?.let { g to it }
        }
        val chase = runCatching { RecordChase.of(st, w.plan, w.members) }.getOrDefault(emptyList())
        val news = w.news.all.filter { it.kind == RECORD_NEWS }.take(ROOM_NEWS)
        return RoomView(w.plan?.number ?: 0, fresh, groups, chase, news, records.all.filter { it.kind.retired })
    }

    private fun hallOf(w: LeagueWorld): HallView {
        val seasons = closedSeasons(w, w.honours.seasons).map { hallSeason(w, it) }
        val most = w.honours.all.filter { HonourKind.isTopTitle(it.kind) }.groupingBy { it.key }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(MOST).map { it.key to it.value }
        return HallView(seasons, w.archive.hallOfFame, w.archive.dynasties, most)
    }

    /** [seasons] that have closed (a season under way already has 이 주의 선수 honours), newest first, each once. */
    private fun closedSeasons(w: LeagueWorld, seasons: Collection<Int>): List<Int> {
        val now = w.plan?.number ?: Int.MAX_VALUE
        return seasons.filter { it < now || (it == now && w.seasonOver) }.distinct().sortedDescending()
    }

    /** Season [s] of the hall: its champions by league (the best-ranked first) or by tier, its world titles and awards. */
    private fun hallSeason(w: LeagueWorld, s: Int): HallSeason {
        val hs = w.honours.of(s)
        val national = hs.any { HonourKind.leagueOf(it.kind) != null }
        val (champions, more) = championsOf(hs)
        return HallSeason(
            s, national, champions, more,
            hs.filter { HonourKind.base(it.kind) in WORLD_TITLES },
            hs.filter { it.kind in AWARDS },
            hs.count { it.kind.startsWith("UP") }, hs.count { it.kind.startsWith("DOWN") },
        )
    }

    /** A season's champions: a national season's league titles by the league's rank now (the first [HALL_LEAGUES]), a pyramid's 1부-3부 (all with up to 8 groups); and how many more. */
    private fun championsOf(hs: List<yacht.engine.league.Honour>): Pair<List<HallChampion>, Int> {
        val leagues = hs.filter { HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null }
        if (leagues.isNotEmpty()) {
            val ranked = leagues.sortedWith(compareBy<yacht.engine.league.Honour> { host.leagueRank(HonourKind.leagueOf(it.kind)!!) }.thenBy { it.kind })
            val shown = ranked.take(HALL_LEAGUES).map { h -> HonourKind.leagueOf(h.kind)!!.let { id -> HallChampion("${LeagueIds.icon(id)} ${LeagueIds.label(id)}", h.key, 0) } }
            return shown to (ranked.size - shown.size)
        }
        val divisions = hs.filter { HonourKind.isDivision(it.kind) && HonourKind.leagueOf(it.kind) == null }.sortedBy { HonourKind.tier(it.kind) ?: 0 }
        val shown = divisions.filter { (HonourKind.tier(it.kind) ?: 0) <= 2 || divisions.size <= 8 }
        return shown.map { h -> (HonourKind.tier(h.kind) ?: 0).let { t -> HallChampion("${t + 1}부", h.key, t) } } to (divisions.size - shown.size)
    }

    private fun chronicleOf(w: LeagueWorld): ChronicleView {
        val seasons = closedSeasons(w, w.honours.seasons + w.archive.summaries.map { it.season })
        // The ranking's no. 1 at each close the federation still keeps (5-season sums, as the ranking adds them).
        val ids = w.plan?.league?.leagues?.list?.filter { it.national }?.map { it.id }.orEmpty()
        val first = w.federation.coefficientSeasons.associateWith { s ->
            ids.maxByOrNull { Coefficients.values(w.federation, it, s).sum() }?.takeIf { id -> Coefficients.values(w.federation, id, s).sum() > 0 }
        }
        // Each season's best rating at its end (season lines).
        val best = HashMap<Int, Pair<String, Double>>()
        for (k in w.archive.lineKeys) for (l in w.archive.lines(k)) {
            if (l.rating.isNaN()) continue
            val b = best[l.season]
            if (b == null || l.rating > b.second || (l.rating == b.second && k < b.first)) best[l.season] = k to l.rating
        }
        val dynasties = w.archive.dynasties
        val rows = seasons.map { s ->
            val hs = w.honours.of(s)
            val lines = ArrayList<ChronicleLine>()
            val (champions, more) = championsOf(hs)
            for (c in champions.take(CHRONICLE_LEAGUES)) lines += ChronicleLine("👑", "${c.label} 우승 ${host.name(c.key)}", c.key)
            if (more + champions.size > CHRONICLE_LEAGUES) {
                val unit = if (hs.any { HonourKind.leagueOf(it.kind) != null }) "리그" else "조"
                lines += ChronicleLine("·", "외 ${more + champions.size - CHRONICLE_LEAGUES}개 $unit 우승")
            }
            for (h in hs.filter { it.kind in CHRONICLE_TITLES }) lines += ChronicleLine("🏆", "${TITLE_NAMES[h.kind] ?: h.kind} ${host.name(h.key)}", h.key.takeIf { h.kind != "FAMILY" && h.kind != "ALLSTAR" })
            hs.firstOrNull { it.kind == "MVP" }?.let { lines += ChronicleLine("⭐", "MVP ${host.name(it.key)}", it.key) }
            first[s]?.let { lines += ChronicleLine("🌍", "리그 랭킹 1위 ${LeagueIds.icon(it)} ${LeagueIds.label(it)}") }
            best[s]?.let { (k, r) -> lines += ChronicleLine("📈", "시즌 최고 레이팅 ${host.name(k)} (${grouped(r)})", k) }
            for (d in dynasties.filter { it.to == s && it.kind == "RUN" }) lines += ChronicleLine("🔥", "${host.name(d.key)} ${titleLabel(d.title)} ${d.count}연패", d.key)
            ChronicleSeason(s, lines)
        }.filter { it.lines.isNotEmpty() }
        val eras = ArrayList<ChronicleLine>()
        dynasties.filter { it.kind == "RUN" }.sortedWith(compareByDescending<yacht.engine.league.Dynasty> { it.count }.thenByDescending { it.to }).take(ERAS).forEach { d ->
            eras += ChronicleLine("👑", "${host.name(d.key)} · ${titleLabel(d.title)} ${d.count}연패 (시즌 ${d.from}–${d.to})", d.key)
        }
        w.honours.all.filter { HonourKind.isTopTitle(it.kind) }.groupingBy { it.key }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            ?.takeIf { it.value >= 2 }?.let { eras += ChronicleLine("🏅", "리그 우승 최다 ${host.name(it.key)} (${it.value}회)", it.key) }
        w.stats.records[yacht.engine.league.RecordKind.PEAK_RATING]?.let { r -> eras += ChronicleLine("📈", "역대 최고 레이팅 ${host.name(r.holder)} (${grouped(r.value)}, 시즌 ${r.season})", r.holder) }
        // The longest reign at the top of the league ranking.
        var run: Pair<String, Int>? = null
        var cur: String? = null
        var len = 0
        for (s in first.keys.sorted()) {
            val id = first[s]
            if (id != null && id == cur) len++ else {
                cur = id
                len = if (id == null) 0 else 1
            }
            if (cur != null && len > (run?.second ?: 0)) run = cur to len
        }
        run?.takeIf { it.second >= 2 }?.let { (id, n) -> eras += ChronicleLine("🌍", "${LeagueIds.icon(id)} ${LeagueIds.label(id)} · 리그 랭킹 1위 ${n}시즌 연속") }
        return ChronicleView(rows, eras)
    }

    // ---- reviews ------------------------------------------------------------------------------------

    private fun reviewListOf(w: LeagueWorld): ReviewList {
        val kept = w.archive.reviewSeasons.toSet()
        val items = w.archive.summaries.sortedByDescending { it.season }.map { sum ->
            val hs = w.honours.of(sum.season).ifEmpty { sum.honours }
            val (champions, more) = championsOf(hs)
            val lines = buildList {
                val unit = if (hs.any { HonourKind.leagueOf(it.kind) != null }) "리그" else "조"
                champions.firstOrNull()?.let { c -> add("👑 ${c.label} ${host.name(c.key)}" + if (more + champions.size > 1) " 외 ${more + champions.size - 1}개 $unit" else "") }
                val world = hs.filter { it.kind == "CL" || it.kind == "CUP" }.joinToString(" · ") { "${TITLE_NAMES[it.kind]} ${host.name(it.key)}" }
                if (world.isNotEmpty()) add("🏆 $world")
                hs.firstOrNull { it.kind == "MVP" }?.let { add("⭐ MVP ${host.name(it.key)}") }
            }
            ReviewListItem(sum.season, sum.season in kept, lines, sum.avgScore, sum.games)
        }
        // A kept review whose summary has gone (older than the summaries): listed too.
        val extra = kept.filter { s -> items.none { it.season == s } }.map { s -> ReviewListItem(s, true, emptyList(), Double.NaN, 0) }
        return ReviewList((items + extra).sortedByDescending { it.season })
    }

    private fun reviewPageOf(w: LeagueWorld, season: Int, key: String, center: MatchCenter?): ReviewPage? {
        val review = w.archive.review(season)
        val summary = w.archive.summaries.firstOrNull { it.season == season }
        val hs = w.honours.of(season).ifEmpty { review?.champions.orEmpty() + review?.awards.orEmpty() + summary?.honours.orEmpty() }
        // A season the archive no longer sums up shows its honours alone (the hall still lists it).
        if (review == null && summary == null && hs.isEmpty()) return null
        val (champions, _) = championsOf(hs)
        val world = hs.filter { HonourKind.base(it.kind) in WORLD_TITLES }
        val keys = LinkedHashSet<String>()
        review?.let { r ->
            (r.champions + r.awards).forEach { keys += it.key }
            (r.risers + r.fallers).forEach { keys += it.key }
            r.records.forEach { keys += it.record.holder }
            r.leaders.forEach { keys += it.key }
            r.topRated?.let { keys += it.key; keys += it.other }
            r.upset?.let { keys += it.key; keys += it.other }
        }
        summary?.let { s -> s.honours.forEach { keys += it.key }; s.topScorer?.let { keys += it }; s.records.forEach { keys += it.record.holder } }
        champions.forEach { keys += it.key }
        world.forEach { keys += it.key }
        val names = keys.associateWith { host.shown(it) }
        val highlights = (w.highlights.thisSeason + w.highlights.allTime).associateBy { it.id }
        fun game(ref: String, note: String, a0: String? = null, b0: String? = null): ReviewGameView {
            val r = MatchRef.decode(ref)
            val h = highlights[ref]
            val log = r?.let { center?.logOf(it) }
            val a = a0 ?: h?.a ?: log?.a.orEmpty()
            val b = b0 ?: h?.b ?: log?.b.orEmpty()
            val g = log?.games?.firstOrNull { it.index == r.game } ?: log?.games?.firstOrNull()
            val score = when {
                h != null -> if (h.a == a) "${h.scoreA} : ${h.scoreB}" else "${h.scoreB} : ${h.scoreA}"
                g != null && log != null -> if (log.a == a) "${g.scoreA} : ${g.scoreB}" else "${g.scoreB} : ${g.scoreA}"
                else -> null
            }
            return ReviewGameView(ref, if (a.isEmpty()) "" else host.shown(a), if (b.isEmpty()) "" else host.shown(b), score, note, r != null && a.isNotEmpty() && center?.canReplay(r, a, b) == true)
        }
        val best = review?.best.orEmpty().map { id ->
            val h = highlights[id]
            game(id, h?.tags?.firstOrNull()?.label ?: "명승부")
        }
        val top = review?.topRated?.let { game(it.ref, "평점 ${String.format(Locale.ROOT, "%.1f", it.value)}", it.key, it.other) }
        val upset = review?.upset?.let { game(it.ref, "레이팅 ${grouped(it.value)}점 차 이변", it.key, it.other) }
        val followed = host.favourites().filter { ':' !in it }.sorted().mapNotNull { k ->
            val line = w.archive.lines(k).firstOrNull { it.season == season } ?: return@mapNotNull null
            FollowedSeason(
                k,
                "${LeagueIds.division(line.league, line.division)} ${line.position}위 · ${line.wins}승 ${line.draws}무 ${line.losses}패",
                listOfNotNull(
                    line.avgRating.takeIf { !it.isNaN() }?.let { "평점 ${String.format(Locale.ROOT, "%.2f", it)}" },
                    line.rating.takeIf { !it.isNaN() }?.let { "레이팅 ${grouped(it)}" },
                ).joinToString(" · "),
            )
        }.take(FOLLOWED)
        val leagueLabels = review?.ranking.orEmpty().associate { (id, _) -> id to "${LeagueIds.icon(id)} ${LeagueIds.label(id)}" }
        return ReviewPage(key, season, review, summary, names + followed.associate { it.key to host.shown(it.key) }, champions, world, best, top, upset, followed, leagueLabels)
    }

    // ---- fun facts, the league comparison, a nation's numbers -----------------------------------------

    private fun factsOf(w: LeagueWorld): FactsView {
        val plan = w.plan
        val season = plan?.league
        val members = season?.divisions?.flatten() ?: w.members
        val facts = ArrayList<FunFact>()
        if (season != null) facts += gameFacts(w, season)
        members.takeIf { it.isNotEmpty() }?.let { keys ->
            luckFacts(w, keys)?.let { facts += it }
            facts += pairFacts(w, keys.toSet())
            streakFact(w, keys)?.let { facts += it }
            if (season != null) expectationFact(w, season)?.let { facts += it }
        }
        val all = aggregateOf(w, members)
        val leagues = if (season == null) emptyList() else season.leagues.list.filter { it.national }.mapNotNull { lg ->
            val keys = lg.divisions.flatMap { season.divisions[it] }
            val agg = aggregateOf(w, keys) ?: return@mapNotNull null
            LeagueAggregate(lg.id, LeagueIds.label(lg.id), LeagueIds.icon(lg.id), agg, if (all == null) "" else styleSentence(agg, all, "리그"))
        }.sortedBy { host.leagueRank(it.id) }
        return FactsView(plan?.number ?: 0, facts.filter { it.lines.isNotEmpty() }, leagues, all)
    }

    /** This season's league games: the lowest winning score, the highest losing one, the widest margin, the most Yachts in one match. */
    private fun gameFacts(w: LeagueWorld, season: Season): List<FunFact> {
        data class G(val id: Int, val game: Int, val winner: String, val loser: String, val win: Int, val lose: Int)
        val games = ArrayList<G>()
        val yachts = ArrayList<Pair<Int, Int>>()
        for (id in 0 until season.fixturesTotal) {
            val r = season.result(id) ?: continue
            val f = season.fixture(id)
            var y = 0
            for ((i, g) in r.withIndex()) {
                if (g.aYacht) y++
                if (g.bYacht) y++
                if (g.a > g.b) games += G(id, i, f.a, f.b, g.a, g.b) else if (g.b > g.a) games += G(id, i, f.b, f.a, g.b, g.a)
            }
            if (y > 0) yachts += id to y
        }
        fun ref(g: G) = "${season.number}:${Comp.LEAGUE.code}:${g.id}:0:${g.game}"
        fun line(g: G, text: String) = FactLine(text, listOf(g.winner, g.loser), ref(g))
        val low = games.sortedWith(compareBy<G> { it.win }.thenBy { it.id }.thenBy { it.game }).take(FACT_LINES)
        val high = games.sortedWith(compareByDescending<G> { it.lose }.thenBy { it.id }.thenBy { it.game }).take(FACT_LINES)
        val wide = games.sortedWith(compareByDescending<G> { it.win - it.lose }.thenBy { it.id }.thenBy { it.game }).take(FACT_LINES)
        val most = yachts.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first }).take(FACT_LINES).filter { it.second >= 3 }
        val lowRecord = w.stats.records[yacht.engine.league.RecordKind.LOW_WIN]
        return listOf(
            FunFact(
                "🐢", "가장 낮은 점수로 이긴 판", "이번 시즌 리그 경기에서 이긴 판 중 점수가 가장 낮았던 판이에요.",
                low.map { line(it, "${host.name(it.winner)} ${it.win}점으로 ${host.name(it.loser)}(${it.lose}점)에게 승리") } +
                    listOfNotNull(lowRecord?.let { FactLine("역대 기록: ${host.name(it.holder)} ${it.value.roundToInt()}점 (시즌 ${it.season})", listOf(it.holder)) }),
            ),
            FunFact(
                "💔", "가장 높은 점수로 진 판", "잘 두고도 진 판이에요.",
                high.map { line(it, "${host.name(it.loser)} ${it.lose}점을 내고도 ${host.name(it.winner)}(${it.win}점)에게 패배").copy(keys = listOf(it.loser, it.winner)) },
            ),
            FunFact(
                "💥", "가장 큰 점수 차", "이번 시즌 리그 경기에서 가장 크게 벌어진 판이에요.",
                wide.map { line(it, "${host.name(it.winner)} ${it.win}:${it.lose} ${host.name(it.loser)} (${it.win - it.lose}점 차)") },
            ),
            FunFact(
                "🎲", "요트가 가장 많이 나온 경기", "한 경기(두 판)에서 두 선수가 만든 요트 수예요.",
                most.map { (id, n) -> season.fixture(id).let { f -> FactLine("${host.name(f.a)} vs ${host.name(f.b)} · 요트 ${n}번", listOf(f.a, f.b), "${season.number}:${Comp.LEAGUE.code}:$id") } },
            ),
        )
    }

    /** The luckiest and unluckiest this season (행운아 board both ways). */
    private fun luckFacts(w: LeagueWorld, keys: List<String>): FunFact? {
        val plan = w.plan ?: return null
        val luck = Leaders.season(LeaderStat.LUCK, plan, w.stats, keys)
        if (luck.isEmpty()) return null
        val lucky = luck.take(2).filter { it.value > 0 }
        val unlucky = luck.asReversed().take(2).filter { it.value < 0 }
        return FunFact(
            "🍀", "운이 따른 선수, 따르지 않은 선수", "실제 승점과 같은 주사위로 비교한 운 보정 승점의 차이예요.",
            lucky.map { FactLine("행운 ${host.name(it.key)} ${signedInt(it.value)}점 (운 보정 ${it.extra.roundToInt()}점)", listOf(it.key)) } +
                unlucky.map { FactLine("불운 ${host.name(it.key)} ${signedInt(it.value)}점 (운 보정 ${it.extra.roundToInt()}점)", listOf(it.key)) },
        )
    }

    /** Head-to-head over careers among [members]: the most lopsided pairs (천적) and the most frequent ones (라이벌), one pass over the ladder's pairs. */
    private fun pairFacts(w: LeagueWorld, members: Set<String>): List<FunFact> {
        data class P(val a: String, val b: String, val share: Double, val games: Int)
        val pairs = ArrayList<P>()
        for (k in members) for ((o, p, g) in w.ladder.opponents(k)) {
            if (o <= k || o !in members || g <= 0) continue
            pairs += P(k, o, p / g, g)
        }
        // The side that wins more first: "a가 b를 압도".
        val lopsided = pairs.filter { it.games >= NEMESIS_GAMES }.map { if (it.share >= 0.5) it else P(it.b, it.a, 1 - it.share, it.games) }
            .sortedWith(compareByDescending<P> { it.share }.thenByDescending { it.games }.thenBy { it.a }.thenBy { it.b }).take(FACT_LINES)
        val frequent = pairs.sortedWith(compareByDescending<P> { it.games }.thenBy { abs(it.share - 0.5) }.thenBy { it.a }.thenBy { it.b }).take(FACT_LINES)
        return listOf(
            FunFact(
                "😈", "천적 관계", "${NEMESIS_GAMES}판 이상 만난 사이 중 한쪽이 가장 많이 이긴 짝이에요.",
                lopsided.map { FactLine("${host.name(it.a)} → ${host.name(it.b)} · ${it.games}판 승점 ${pct(it.share)}", listOf(it.a, it.b)) },
            ),
            FunFact(
                "🤝", "가장 자주 만난 라이벌", "통산 가장 많이 맞붙은 짝이에요.",
                frequent.map { FactLine("${host.name(it.a)} vs ${host.name(it.b)} · ${it.games}판 (${host.name(it.a)} 승점 ${pct(it.share)})", listOf(it.a, it.b)) },
            ),
        )
    }

    /** The longest winning runs going on (rated games, as the ladder counts them). */
    private fun streakFact(w: LeagueWorld, keys: List<String>): FunFact? {
        val runs = keys.mapNotNull { k -> w.ladder.entry(k)?.streak?.takeIf { it >= STREAK_FROM }?.let { k to it } }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(FACT_LINES)
        return FunFact("🔥", "이어지고 있는 연승", "지금도 끊기지 않은 연승이에요 (레이팅이 걸린 모든 경기).", runs.map { (k, n) -> FactLine("${host.name(k)} ${n}연승 중", listOf(k)) })
    }

    /** Results against the ratings' expectation this season (league matches, today's ratings): the most above and the most below. */
    private fun expectationFact(w: LeagueWorld, season: Season): FunFact? {
        val diff = HashMap<String, Double>()
        val played = HashMap<String, Int>()
        val wins = HashMap<String, Double>()
        for (id in 0 until season.fixturesTotal) {
            val r = season.result(id) ?: continue
            val f = season.fixture(id)
            val ra = w.ladder.rating(f.a)
            val rb = w.ladder.rating(f.b)
            if (ra.isNaN() || rb.isNaN()) continue
            val e = Preview.expected(ra, rb)
            val s = when (TieRule.match(r, season.format)) {
                1 -> 1.0
                -1 -> 0.0
                else -> 0.5
            }
            diff.merge(f.a, s - e, Double::plus)
            diff.merge(f.b, e - s, Double::plus)
            wins.merge(f.a, s, Double::plus)
            wins.merge(f.b, 1 - s, Double::plus)
            played.merge(f.a, 1, Int::plus)
            played.merge(f.b, 1, Int::plus)
        }
        val ok = diff.filterKeys { (played[it] ?: 0) >= EXPECT_MATCHES }
        if (ok.isEmpty()) return null
        val sorted = ok.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        fun line(e: Map.Entry<String, Double>): FactLine {
            val won = wins[e.key] ?: 0.0
            return FactLine("${host.name(e.key)} 기대보다 ${signed1(e.value)}승 (${halfText(won)}승 · 기대 ${String.format(Locale.ROOT, "%.1f", won - e.value)}승)", listOf(e.key))
        }
        return FunFact(
            "📊", "레이팅보다 잘한 선수, 못한 선수", "지금 레이팅으로 본 이번 시즌 리그 기대 승수와 실제 승수의 차이예요 (${EXPECT_MATCHES}경기 이상).",
            sorted.take(2).filter { it.value > 0 }.map(::line) + sorted.asReversed().take(2).filter { it.value < 0 }.map(::line),
        )
    }

    /** Group [key]'s numbers against everyone's this season ([groups]); null for an unknown group or one without games. */
    private fun groupOf(w: LeagueWorld, key: String): Pair<AggregateStats, String>? {
        val season = w.plan?.league
        val members = season?.divisions?.flatten() ?: w.members
        val id = key.substringAfter(':')
        val (keys, noun) = when (key.substringBefore(':')) {
            "n" -> members.filter { w.players[it]?.identity?.nation == id } to "선수들"
            "l" -> season?.leagues?.byId(id)?.divisions?.flatMap { season.divisions[it] }.orEmpty() to "리그"
            "c" -> w.clubs?.club(id)?.squad.orEmpty() to "클럽"
            else -> return null
        }
        val agg = aggregateOf(w, keys) ?: return null
        val all = aggregateOf(w, members) ?: return null
        return agg to styleSentence(agg, all, noun)
    }

    companion object {
        /** Highlights and articles a page keeps; last season's games it lists. */
        const val HIGHLIGHTS = 6
        const val NEWS = 10
        const val PREVIOUS = 6

        /** Fewest games to be ranked over a career (averages, 꾸준함), for the bonus rate, for 꾸준함 in a season; fewest decider or close games for 클러치. */
        const val CAREER_GAMES = 200
        const val SEASON_BONUS = 20
        const val CAREER_BONUS = 100
        const val SEASON_CLUTCH = 8
        const val CAREER_CLUTCH = 30
        const val SEASON_STEADY = 40

        /** The share of its division's busiest player's league matches the MVP race asks while the season is under way. */
        const val MVP_RACE_SHARE = 0.6

        /** Games with both cards known a player needs for its style radar and label. */
        const val STYLE_GAMES = 20

        /** The hub's eight boards (amendment 7.2), in order, then "다른 부문". */
        val HUB = listOf(
            LeaderKind.SCORER, LeaderKind.YACHTS, LeaderKind.MVP, LeaderKind.SWEEPS,
            LeaderKind.BONUS, LeaderKind.COMEBACK, LeaderKind.CLUTCH, LeaderKind.GIANT,
        )
        val OTHERS = listOf(LeaderKind.LUCKY, LeaderKind.UNLUCKY, LeaderKind.STEADY, LeaderKind.OVER300)

        /** The boards "최근 10경기" can draw from league results alone (no 평점, P(win) or ratings kept per match). */
        val RECENT_BOARDS = setOf(
            LeaderKind.SCORER, LeaderKind.YACHTS, LeaderKind.SWEEPS, LeaderKind.BONUS, LeaderKind.CLUTCH,
            LeaderKind.LUCKY, LeaderKind.UNLUCKY, LeaderKind.STEADY, LeaderKind.OVER300,
        )

        /** Lines per hub card, per board page. */
        const val HUB_TOP = 3
        const val BOARD_TOP = 50

        /** The "최근 10경기" boards: matches a player needs, close games for 클러치, games for 꾸준함 and 보너스. */
        const val RECENT_MIN = 5
        const val RECENT_CLOSE = 3
        const val RECENT_GAMES = 10

        /** The engine's board behind each of the app's. */
        fun statOf(k: LeaderKind): LeaderStat = when (k) {
            LeaderKind.SCORER -> LeaderStat.SCORER
            LeaderKind.YACHTS -> LeaderStat.YACHTS
            LeaderKind.MVP -> LeaderStat.MVP
            LeaderKind.CLUTCH -> LeaderStat.CLUTCH
            LeaderKind.COMEBACK -> LeaderStat.COMEBACK
            LeaderKind.LUCKY, LeaderKind.UNLUCKY -> LeaderStat.LUCK
            LeaderKind.BONUS -> LeaderStat.BONUS
            LeaderKind.SWEEPS -> LeaderStat.SWEEPS
            LeaderKind.GIANT -> LeaderStat.GIANT
            LeaderKind.STEADY -> LeaderStat.STEADY
            LeaderKind.OVER300 -> LeaderStat.OVER300
        }

        /** A board's name ("평점왕", "불운"; the engine's, which the reviews use too). */
        fun title(k: LeaderKind): String = if (k == LeaderKind.UNLUCKY) "불운" else statOf(k).label

        /** A board page's first line for [filter]: the engine's rule, the 최근 10경기 note, 불운 read the other way. */
        fun ruleOf(k: LeaderKind, filter: LeaderFilter, finished: Boolean = false): String {
            val base = when {
                k == LeaderKind.MVP && filter.scope == StatScope.SEASON && !filter.recent && !finished ->
                    "공식 경기 평균 평점이에요. 같은 부에서 지금까지 가장 많이 뛴 선수의 60% 이상 뛰어야 올라가요. 시즌이 끝나면 리그 경기의 60% 이상 뛴 선수 중 1위가 MVP예요."
                k == LeaderKind.UNLUCKY -> "실제 승점에서 같은 주사위로 비교한 운 보정 승점을 뺀 값이 가장 작은 순이에요. 0보다 작으면 운이 따르지 않았어요."
                filter.recent && k == LeaderKind.CLUTCH -> "${AiStats.CLOSE}점 차 이내로 끝난 판의 승률이에요."
                filter.recent && k == LeaderKind.SCORER -> "리그 경기 평균 점수예요."
                else -> statOf(k).rule
            }
            return if (filter.recent) "$base 선수마다 이번 시즌 마지막 리그 ${LeaderFilter.RECENT}경기만 세요." else base
        }

        /** The cache key of a whole board. */
        internal fun boardKey(kind: LeaderKind, filter: LeaderFilter): String = "b:$kind:$filter"

        /** The 기록실's record news, the articles it shows; titles counted in the hall; leagues by name in a hall season and in a chronicle season. */
        private const val RECORD_NEWS = "record"
        private const val ROOM_NEWS = 5
        private const val MOST = 5
        private const val HALL_LEAGUES = 8
        private const val CHRONICLE_LEAGUES = 3
        private const val ERAS = 3

        /** Followed players a review lists; lines per fun fact; games a 천적 pair has met; the runs and matches that count. */
        private const val FOLLOWED = 6
        private const val FACT_LINES = 3
        private const val NEMESIS_GAMES = 8
        private const val STREAK_FROM = 3
        private const val EXPECT_MATCHES = 10

        /** The world's titles a hall season lists (bases: "CCL.ASIA" is CCL), the awards, and the titles a chronicle season names. */
        private val WORLD_TITLES = setOf("CL", "CUP", "SUPER", "CCL", "CLUBCUP", "CLUB", "FAMILY", "ALLSTAR", "WC", "CC")
        private val AWARDS = setOf("MVP", "ROOKIE", "MIP", "SCORER", "YACHT", "WCMVP")
        private val CHRONICLE_TITLES = listOf("CL", "CUP", "WC")
        private val TITLE_NAMES = mapOf("CL" to "챔피언스 리그 우승", "CUP" to "요트컵 우승", "WC" to "요트 월드컵 우승")

        /** The score below which half of the games in [h] (bins of [AiStats.BIN] points) end, NaN for none. */
        internal fun median(h: IntArray): Double {
            val n = h.sum()
            if (n == 0) return Double.NaN
            val half = n / 2.0
            var below = 0
            for (i in h.indices) {
                if (h[i] > 0 && below + h[i] >= half) return (i + (half - below) / h[i]) * AiStats.BIN
                below += h[i]
            }
            return Double.NaN
        }
    }
}

/**
 * The player page's stat grid from [own] ([key]'s numbers of [scope]; [others]: the league's
 * members', [key] among them): the six first (승률, 평균 점수, 요트, 보너스율, 클러치, 역전승), then
 * the rest. A ranked cell has its place among the members (1 + those strictly better) when [key]
 * is one of them and eligible; [StatCell.board] is the leaderboard it opens. [own] has games.
 */
internal fun statCells(own: AiStats, others: List<Pair<String, AiStats>>, key: String, scope: StatScope): List<StatCell> {
    val season = scope == StatScope.SEASON
    val member = others.any { it.first == key }
    val most = others.maxOfOrNull { it.second.games } ?: own.games
    // Who is ranked by an average: in a season, half the most games of the league; over careers, the boards' minimum.
    val regular: (AiStats) -> Boolean = { if (season) it.games * 2 >= most else it.games >= LeagueAnalytics.CAREER_GAMES }
    fun cell(
        label: String, text: String, board: LeaderKind?, ranked: Boolean = board != null, higher: Boolean = true,
        eligible: (AiStats) -> Boolean = { true }, value: (AiStats) -> Double = { 0.0 },
    ): StatCell {
        if (!ranked || !member || !eligible(own)) return StatCell(label, text, null, board)
        val v = value(own)
        val above = others.count { (k, s) -> k != key && eligible(s) && value(s).let { x -> if (higher) x > v else x < v } }
        return StatCell(label, text, above + 1, board)
    }
    val clutchGames: (AiStats) -> Int = { it.deciderWins + it.deciderLosses + it.closeWins + it.closeLosses }
    val clutch: (AiStats) -> Double = { (it.deciderWins + it.closeWins + 1.0) / (clutchGames(it) + 2.0) }
    val luck: (AiStats) -> Double = { (3 * it.matchWins + it.matchDraws - it.aggPoints).toDouble() }
    return listOf(
        cell("승률", "%.1f%%".format(100 * own.winRate), null, ranked = true, eligible = regular) { it.winRate },
        cell("평균 점수", "%.1f".format(own.avgScore), LeaderKind.SCORER, eligible = regular) { it.avgScore },
        cell("요트", "${own.yachts}개 · %.0f%%".format(100.0 * own.yachts / own.games), LeaderKind.YACHTS) { it.yachts.toDouble() },
        cell("보너스율", "%.0f%%".format(100.0 * own.bonuses / own.games), LeaderKind.BONUS, eligible = { it.games >= if (season) LeagueAnalytics.SEASON_BONUS else LeagueAnalytics.CAREER_BONUS }) {
            it.bonuses.toDouble() / it.games
        },
        cell(
            "클러치", if (clutchGames(own) == 0) "–" else "%.0f%%".format(100 * clutch(own)), LeaderKind.CLUTCH,
            eligible = { clutchGames(it) >= if (season) LeagueAnalytics.SEASON_CLUTCH else LeagueAnalytics.CAREER_CLUTCH }, value = clutch,
        ),
        cell("역전승", "${own.comebacks}번", LeaderKind.COMEBACK) { it.comebacks.toDouble() },
        cell("꾸준함", "±%.1f점".format(own.scoreSd), LeaderKind.STEADY, higher = false, eligible = { it.games >= if (season) LeagueAnalytics.SEASON_STEADY else LeagueAnalytics.CAREER_GAMES }) { it.scoreSd },
        cell("300점", "${own.over300}번", LeaderKind.OVER300) { it.over300.toDouble() },
        cell("역전패", "${own.blownLeads}번", null),
        cell("거인 사냥", "${own.giantKills}번", LeaderKind.GIANT) { it.giantKills.toDouble() },
        cell("운 지수", "%+d점".format(luck(own).toInt()), LeaderKind.LUCKY, eligible = regular, value = luck),
        cell("더비", "${own.derbyWins}승 ${own.derbyLosses}패", null),
    )
}

/**
 * [key]'s profile (under the league's lock), as the controller's `loadProfile` reads it; [name]
 * names a key as the screens do. The player page keeps its own copy per page.
 */
internal fun profileOf(w: LeagueWorld, key: String, name: (String) -> String): ProfileView {
    val b = w.players
    val alive = b[key]
    val past = if (alive == null) b.retiredRecord(key) else null
    val (best, worst) = matchupsOf(w, key, name)
    return ProfileView(
        key, name(key), if (past != null) past.origin else Origin.of(key), alive?.hometown ?: past?.hometown ?: w.hometown(key),
        alive?.born ?: past?.born, past?.retired, b.legend(key) != null,
        b.ancestors(key).map { gen -> gen.map { k -> k?.let(name) } }, b.descendants(key).map { gen -> gen.map(name) },
        w.honours.of(key).sortedByDescending { it.season }, w.honours.titles(key),
        (alive?.spec ?: b.legend(key)?.spec)?.describe(),
        best = best, worst = worst,
        season = w.stats.season(key)?.copy(), career = w.stats.career(key)?.copy(),
        league = runCatching { w.stats.liveMeta() }.getOrNull() ?: w.stats.meta,
        lines = w.archive.lines(key).sortedBy { it.season }, hallOfFame = w.archive.inHallOfFame(key),
        identity = runCatching { identityOf(w, key, name) }.getOrNull(),
        ratingHistory = w.ladder.entry(key)?.history?.toList().orEmpty(),
    )
}

/**
 * [key]'s best and worst opponents with at least [min] games against them (under the lock): up to
 * three it beats more often than not, and three it loses to more often than not.
 */
internal fun matchupsOf(w: LeagueWorld, key: String, name: (String) -> String, min: Int = 4): Pair<List<Matchup>, List<Matchup>> {
    val l = w.ladder
    val all = l.keys.filter { it != key }.mapNotNull { o ->
        val (p, g) = l.headToHead(key, o)
        if (g < min) null else Matchup(o, name(o), p / g, g)
    }
    return all.filter { it.score > 0.5 }.sortedByDescending { it.score }.take(3) to all.filter { it.score < 0.5 }.sortedBy { it.score }.take(3)
}

/** [key]'s identity view (under the lock), null for a player without one (base AIs and mine). */
internal fun identityOf(w: LeagueWorld, key: String, name: (String) -> String): IdentityView? {
    val b = w.players
    val season = w.plan?.number ?: (b.evolvedSeason + 1)
    val alive = b[key]
    val legend = b.legend(key)
    val past = if (alive == null) b.retiredRecord(key) else null
    val id = alive?.identity ?: legend?.identity
    val code = id?.nation ?: past?.nation ?: return null
    val nat = yacht.engine.league.Nations.of(code) ?: return null
    val honours = w.honours.of(key)
    val age = when {
        alive != null && id != null -> id.age(season, alive.born)
        past != null && past.retiredAge > 0 -> past.retiredAge
        else -> null
    }
    val spec = alive?.spec ?: legend?.spec
    val gene = spec?.play
    val record = alive ?: legend?.asPlayer()
    val chips = buildList {
        when (spec?.mistake) {
            yacht.engine.league.CustomMistake.FEEL -> add("🎲 감으로 두는 편")
            yacht.engine.league.CustomMistake.SLIP -> add("🎯 가끔 실수")
            yacht.engine.league.CustomMistake.NONE -> add("💎 실수 없음")
            null -> {}
        }
        spec?.temper?.takeIf { it != yacht.engine.league.Temper.NONE }?.let { add("🌀 ${it.label}") }
        spec?.belief?.let { add("🤔 " + (yacht.engine.league.CustomAiSpec.BELIEFS[it] ?: "규칙 착각")) }
    }
    val notes = buildList {
        gene?.let { g ->
            yacht.engine.league.PlayStyles.recipe(g.primary)?.let { add(it.desc) }
            g.secondary?.let { s -> yacht.engine.league.PlayStyles.recipe(s)?.let { add("+${it.name}: ${it.desc}") } }
            g.overlay?.let { o -> yacht.engine.league.PlayStyles.overlay(o)?.let { add("${it.name}: ${it.desc}") } }
            // Its own tastes and situational habits (generator v2; none for older genes).
            addAll(g.notes())
        }
    }
    val cap = w.nations.cap(key)
    val compatriots = b.alive.filter { it.identity?.nation == code }
    val rank = if (alive == null) null else compatriots.map { it.key }.sortedByDescending { w.ladder.rating(it).takeIf { r -> !r.isNaN() } ?: 0.0 }.indexOf(key).takeIf { it >= 0 }?.plus(1)
    val members = w.members.toSet()
    return IdentityView(
        nat.flag, code, nat.name, (id?.gender ?: past?.gender)?.label ?: "", age, age?.let { yacht.engine.league.Profiles.stage(it) }.takeIf { alive != null },
        // A fixed age (영원히 현역) leaves no meaningful debut age.
        id?.takeIf { it.fixedAge == null }?.debutAge ?: past?.debutAge?.takeIf { it > 0 },
        nickname = record?.let { yacht.engine.league.Nicknames.of(it, honours, season) },
        abilities = id?.abilities?.toList(),
        change = if (alive != null && id != null) id.abilities.diff(id.lastAbl) else emptyList(),
        peak = id?.peakAbl?.toList(),
        // The ceiling it grows toward: never shown below what it already is.
        potential = id?.let { maxOf(it.potential, it.abilities.overall) },
        outlook = if (alive != null && id != null && age != null) yacht.engine.league.Profiles.outlook(id, age) else null,
        tendencies = gene?.let { yacht.engine.league.Tendencies.of(it.style()).toList() },
        style = gene?.label, styleNotes = notes, chips = chips,
        school = yacht.engine.league.Schools.of(code).motto.takeIf { it.isNotEmpty() },
        rival = w.ladder.rival(key, members)?.let { (o, p, g) -> Triple(name(o), p, g) },
        caps = cap?.caps ?: 0, capTitles = cap?.titles ?: 0, capBoards = cap?.let { Triple(it.won, it.drawn, it.lost) },
        nationRank = rank, nationPlayers = compatriots.size,
        weeks = honours.count { it.kind == "POTW" },
    )
}

/**
 * The players of [keys] in the MVP race of [league] while it is under way: those who have played
 * [share] of the league matches the busiest player of their own division has played so far (a
 * national season's divisions play different numbers of rounds, so the busiest of all would
 * leave a small league's players out until its season ends).
 */
internal fun mvpRacers(league: Season, keys: Collection<String>, share: Double = LeagueAnalytics.MVP_RACE_SHARE): Set<String> {
    val most = HashMap<Int, Int>()
    val out = HashSet<String>()
    for (k in keys) {
        val played = league.standings[k]?.played ?: continue
        if (played == 0) continue
        val d = league.divisionOf(k) ?: continue
        val busiest = most.getOrPut(d) { league.divisions[d].maxOf { league.standings[it]?.played ?: 0 } }
        if (played >= share * busiest) out += k
    }
    return out
}

// ---- leaderboards over the last league matches ("최근 10경기") --------------------------------------

/** A player's last league matches of the season summed (the "최근 10경기" boards): what the league's results keep. */
internal class RecentStats {
    var matches = 0
    var wins = 0
    var draws = 0
    var games = 0
    var scoreSum = 0L
    var scoreSquares = 0L
    var yachts = 0
    var bonuses = 0
    var over300 = 0
    var sweeps = 0
    var aggPoints = 0
    var closeWins = 0
    var closeLosses = 0

    val avgScore: Double get() = if (games == 0) 0.0 else scoreSum.toDouble() / games
    val scoreSd: Double get() = if (games < 2) 0.0 else sqrt(maxOf(0.0, scoreSquares.toDouble() / games - avgScore * avgScore))

    /** Adds league match [games] (scores from side A) of [result] from side A's view (+1, 0, -1), seen from A ([onA]) or B. */
    fun add(games: List<yacht.engine.league.GameScore>, result: Int, onA: Boolean) {
        matches++
        val r = if (onA) result else -result
        if (r > 0) wins++ else if (r == 0) draws++
        var own = 0
        var opp = 0
        var won = 0
        for (g in games) {
            val o = if (onA) g.a else g.b
            val p = if (onA) g.b else g.a
            this.games++
            scoreSum += o
            scoreSquares += o.toLong() * o
            if (if (onA) g.aYacht else g.bYacht) yachts++
            if (if (onA) g.aBonus else g.bBonus) bonuses++
            if (o >= 300) over300++
            if (o != p && abs(o - p) <= AiStats.CLOSE) if (o > p) closeWins++ else closeLosses++
            if (o > p) won++
            own += o
            opp += p
        }
        if (games.size >= 2 && won == games.size) sweeps++
        aggPoints += if (own > opp) 3 else if (own == opp) 1 else 0
    }
}

/**
 * The last [n] played league matches of each of [keys] this season (by matchday, the latest
 * first), summed; one pass over the season's fixtures. Players without a played match are left out.
 */
internal fun recentStats(season: Season, keys: Collection<String>, n: Int = LeaderFilter.RECENT): Map<String, RecentStats> {
    val want = keys.toHashSet()
    val ids = HashMap<String, ArrayList<Int>>()
    for (id in 0 until season.fixturesTotal) {
        if (season.result(id) == null) continue
        val f = season.fixture(id)
        if (f.a in want) ids.getOrPut(f.a) { ArrayList() } += id
        if (f.b in want) ids.getOrPut(f.b) { ArrayList() } += id
    }
    val out = HashMap<String, RecentStats>()
    for ((k, list) in ids) {
        val s = RecentStats()
        for (id in list.sortedWith(compareByDescending<Int> { season.fixture(it).matchday }.thenByDescending { it }).take(n)) {
            val games = season.result(id) ?: continue
            s.add(games, TieRule.match(games, season.format), season.fixture(id).a == k)
        }
        out[k] = s
    }
    return out
}

/** [kind]'s "최근 10경기" board from [stats] (players with [LeagueAnalytics.RECENT_MIN] matches), best first; empty for a board the results cannot tell. */
internal fun recentBoard(kind: LeaderKind, stats: Map<String, RecentStats>): List<LeaderEntry> {
    val out = ArrayList<LeaderEntry>()
    for ((k, s) in stats) {
        if (s.matches < LeagueAnalytics.RECENT_MIN) continue
        val close = s.closeWins + s.closeLosses
        out += when (kind) {
            LeaderKind.SCORER -> LeaderEntry(k, s.avgScore, s.matches)
            LeaderKind.YACHTS -> LeaderEntry(k, s.yachts.toDouble(), s.matches, s.yachts.toDouble() / maxOf(1, s.games))
            LeaderKind.SWEEPS -> LeaderEntry(k, s.sweeps.toDouble(), s.matches)
            LeaderKind.BONUS -> if (s.games < LeagueAnalytics.RECENT_GAMES) continue else LeaderEntry(k, s.bonuses.toDouble() / s.games, s.games, s.bonuses.toDouble())
            LeaderKind.CLUTCH -> if (close < LeagueAnalytics.RECENT_CLOSE) continue else LeaderEntry(k, (s.closeWins + 1.0) / (close + 2.0), close, s.closeWins.toDouble())
            LeaderKind.LUCKY, LeaderKind.UNLUCKY -> LeaderEntry(k, (3 * s.wins + s.draws - s.aggPoints).toDouble(), s.matches, s.aggPoints.toDouble())
            LeaderKind.STEADY -> if (s.games < LeagueAnalytics.RECENT_GAMES) continue else LeaderEntry(k, s.scoreSd, s.games)
            LeaderKind.OVER300 -> if (s.over300 == 0) continue else LeaderEntry(k, s.over300.toDouble(), s.games)
            LeaderKind.MVP, LeaderKind.COMEBACK, LeaderKind.GIANT -> continue
        }
    }
    val low = kind == LeaderKind.STEADY || kind == LeaderKind.UNLUCKY
    val byValue = if (low) compareBy<LeaderEntry> { it.value } else compareByDescending { it.value }
    val tie = if (kind == LeaderKind.YACHTS) byValue.thenBy { it.games } else byValue.thenByDescending { it.games }
    return out.sortedWith(tie.thenBy { it.key })
}

/** A board line's value and its sample in words ("248.3점" / "38경기"); [career] for career boards (games are 판 there). */
internal fun leaderText(kind: LeaderKind, e: LeaderEntry, career: Boolean): Pair<String, String> {
    val n = e.games
    fun count(v: Double) = "${v.roundToInt()}번"
    return when (kind) {
        LeaderKind.SCORER -> fmt1(e.value) + "점" to if (career) "${grouped(n.toDouble())}판" else "${n}경기"
        LeaderKind.YACHTS -> "${grouped(e.value)}개" to "요트율 ${pct(e.extra)}"
        LeaderKind.MVP -> String.format(Locale.ROOT, "%.2f", e.value) to "${n}경기"
        LeaderKind.SWEEPS -> count(e.value) to "${n}경기"
        LeaderKind.BONUS -> pct(e.value) to "${e.extra.roundToInt()}/${grouped(n.toDouble())}판"
        LeaderKind.COMEBACK -> count(e.value) to "승리 중 ${pct(e.extra)}"
        LeaderKind.CLUTCH -> pct(e.value) to "${e.extra.roundToInt()}승 ${n - e.extra.roundToInt()}패"
        LeaderKind.GIANT -> count(e.value) to "${n}경기"
        LeaderKind.LUCKY, LeaderKind.UNLUCKY -> "${signedInt(e.value)}점" to "승점 ${(e.value + e.extra).roundToInt()} · 운 보정 ${e.extra.roundToInt()}"
        LeaderKind.STEADY -> "±${fmt1(e.value)}점" to "${grouped(n.toDouble())}판"
        LeaderKind.OVER300 -> count(e.value) to "${grouped(n.toDouble())}판"
    }
}

// ---- the player page's analytics ------------------------------------------------------------------

/**
 * The style radar's six axes from the box table [box] and the bonus rate [bonus] (player, league):
 * 윗칸, 보너스, 초이스, 포카·풀하우스, 스트레이트, 요트, each the player's average over the league's,
 * drawn so the league sits at 0.5 ([RADAR_SPANS]: a box whose averages vary little is stretched).
 */
internal fun radarOf(box: List<BoxRow>, bonus: Pair<Double, Double>): List<RadarAxis> {
    fun sum(cs: Set<Category>): Pair<Double, Double> = box.filter { it.category in cs }.let { rows -> rows.sumOf { it.avg } to rows.sumOf { it.league } }
    val parts = listOf(
        "윗칸" to sum(Category.ALL.filter { it.isUpper }.toSet()),
        "보너스" to bonus,
        "초이스" to sum(setOf(Category.CHOICE)),
        "포카·풀하우스" to sum(setOf(Category.FOUR_OF_A_KIND, Category.FULL_HOUSE)),
        "스트레이트" to sum(setOf(Category.SMALL_STRAIGHT, Category.LARGE_STRAIGHT)),
        "요트" to sum(setOf(Category.YACHT)),
    )
    return parts.mapNotNull { (label, p) ->
        val (own, league) = p
        if (own.isNaN() || league.isNaN() || league <= 0) return@mapNotNull null
        val ratio = own / league
        RadarAxis(label, (0.5 + (ratio - 1) * (RADAR_SPANS[label] ?: 1.0)).coerceIn(0.05, 1.0), 0.5, "×" + String.format(Locale.ROOT, "%.2f", ratio))
    }
}

/** How far each radar axis stretches a ratio (ratio 1 + 0.5 / span reaches the rim). */
private val RADAR_SPANS = mapOf("윗칸" to 3.0, "보너스" to 1.0, "초이스" to 3.0, "포카·풀하우스" to 2.0, "스트레이트" to 2.0, "요트" to 1.0)

/**
 * A player's style in a word and a sentence from its [radar] (the ratios in its texts) and its
 * game scores' spread [sd] against the league's [leagueSd]: the trait furthest past its threshold
 * (요트 사냥꾼, 보너스형, 스트레이트 장인, 포카·풀하우스형, 안정형, 공격형), else 균형형.
 */
internal fun styleOf(radar: List<RadarAxis>, sd: Double, leagueSd: Double): Pair<String, String> {
    fun ratio(label: String) = radar.firstOrNull { it.label == label }?.text?.removePrefix("×")?.toDoubleOrNull() ?: 1.0
    val yacht = ratio("요트")
    val bonus = ratio("보너스")
    val straight = ratio("스트레이트")
    val kinds = ratio("포카·풀하우스")
    val spread = if (leagueSd > 0 && sd > 0) sd / leagueSd else 1.0
    val traits = listOf(
        Triple((yacht - 1) / 0.15, "요트 사냥꾼", "요트 칸에서 리그 평균의 ${fmt1(yacht)}배를 얻어요."),
        Triple((bonus - 1) / 0.15, "보너스형", "윗칸 보너스를 리그보다 ${percentMore(bonus)} 더 자주 받아요."),
        Triple((straight - 1) / 0.08, "스트레이트 장인", "스트레이트 칸 점수가 리그보다 ${percentMore(straight)} 높아요."),
        Triple((kinds - 1) / 0.08, "포카·풀하우스형", "포카와 풀하우스 점수가 리그보다 ${percentMore(kinds)} 높아요."),
        Triple((1 - spread) / 0.08, "안정형", "판마다 점수 편차가 리그보다 ${percentMore(2 - spread)} 작아요."),
        Triple((spread - 1) / 0.08, "공격형", "판마다 점수가 크게 오르내려요. 편차가 리그보다 ${percentMore(spread)} 커요."),
    )
    val best = traits.maxByOrNull { it.first }
    return if (best == null || best.first < 1) "균형형" to "칸마다 리그 평균과 비슷하게 점수를 얻어요." else best.second to best.third
}

/** The spread of the game scores of [stats] together. */
internal fun pooledSd(stats: List<AiStats>): Double {
    val games = stats.sumOf { it.games }
    if (games < 2) return 0.0
    val mean = stats.sumOf { it.scoreSum }.toDouble() / games
    return sqrt(maxOf(0.0, stats.sumOf { it.scoreSquares }.toDouble() / games - mean * mean))
}

/**
 * [key]'s league games this season by seat and by game of the match: in a league match side A
 * moves first in game 1 and side B in game 2 (deciders: A on even ones, as the match is dealt).
 * Null before its first played match.
 */
internal fun seatSplit(season: Season, key: String): SeatSplit? {
    val acc = Array(4) { IntArray(3) }
    val sums = LongArray(4)
    for (id in 0 until season.fixturesTotal) {
        val f = season.fixture(id)
        if (f.a != key && f.b != key) continue
        val games = season.result(id) ?: continue
        val onA = f.a == key
        for ((i, g) in games.withIndex()) {
            val aFirst = if (i < 2) i == 0 else (i - 2) % 2 == 0
            val own = if (onA) g.a else g.b
            val opp = if (onA) g.b else g.a
            val seat = if (aFirst == onA) 0 else 1
            for (slot in listOfNotNull(seat, (2 + i).takeIf { i < 2 })) {
                acc[slot][0]++
                if (own > opp) acc[slot][1]++ else if (own == opp) acc[slot][2]++
                sums[slot] += own.toLong()
            }
        }
    }
    if (acc[0][0] + acc[1][0] == 0) return null
    fun line(i: Int) = SeatLine(acc[i][0], acc[i][1], acc[i][2], if (acc[i][0] == 0) Double.NaN else sums[i].toDouble() / acc[i][0])
    return SeatSplit(line(0), line(1), line(2), line(3))
}

/** [p]'s average 평점 season by season, oldest first, this season's ([season]) last while it is under way; the last [TREND_SEASONS]. */
internal fun ratingTrend(p: ProfileView, season: Int?): List<Pair<Int, Double>> {
    val lines = p.lines.filter { !it.avgRating.isNaN() }.map { it.season to it.avgRating }
    val now = p.season?.avgRating?.takeIf { !it.isNaN() && season != null && lines.none { (s, _) -> s == season } }?.let { season!! to it }
    return (lines + listOfNotNull(now)).takeLast(TREND_SEASONS)
}

private const val TREND_SEASONS = 20

// ---- aggregates ------------------------------------------------------------------------------------

/** Sums of [keys]' statistics this season (UX 4.5; null before their first game): O(keys). */
internal fun aggregateOf(w: LeagueWorld, keys: Collection<String>): AggregateStats? {
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

/**
 * [agg] against [base] (everyone's) in one plain sentence, for a league, club or nation page
 * ([noun]: "리그", "클럽", "선수들"): the average score's gap, then the trait that stands out (Yachts,
 * the upper bonus) when one does.
 */
internal fun styleSentence(agg: AggregateStats, base: AggregateStats, noun: String): String {
    val d = agg.avgScore - base.avgScore
    val score = when {
        abs(d) < 1 -> "평균 ${fmt1(agg.avgScore)}점으로 전체와 비슷해요."
        d > 0 -> "평균 ${fmt1(agg.avgScore)}점으로 전체보다 ${fmt1(d)}점 높아요."
        else -> "평균 ${fmt1(agg.avgScore)}점으로 전체보다 ${fmt1(-d)}점 낮아요."
    }
    val yacht = if (base.yachtRate > 0) agg.yachtRate / base.yachtRate else 1.0
    val bonus = if (base.bonusRate > 0) agg.bonusRate / base.bonusRate else 1.0
    val trait = when {
        yacht >= 1.1 && yacht - 1 >= bonus - 1 -> "요트를 자주 만드는 ${Ko.ieyo(noun)}."
        bonus >= 1.1 -> "윗칸 보너스를 잘 챙기는 ${Ko.ieyo(noun)}."
        yacht <= 0.9 && bonus <= 0.9 -> "요트도 보너스도 드문 ${Ko.ieyo(noun)}."
        else -> null
    }
    return if (trait == null) score else "$score $trait"
}

// ---- 정확도 ---------------------------------------------------------------------------------------

/**
 * 정확도 (amendment 6): each keep and box decision of a game played back against the
 * expected-score best (the advisor's max-expectation plan of the turn, as its 추천 reads it), on
 * demand (a match report's game played back), never during league play. A decision's loss is the
 * best option's expected final score less the chosen one's; [REFERENCE] expected points given away
 * read as 0%.
 */
object Accuracies {
    /**
     * Expected points a game may give away for 0%: measured on generated players' and the
     * hand-made AIs' games (S18 hand-off) so that the league's best read about 95-98% (they give
     * away 2-8 points a game), its middle about 90% and weak players 80% or less.
     */
    const val REFERENCE = 200.0

    /** A decision giving away this many expected points or more is a 실수. */
    const val MISTAKE = 2.0

    /** The first player's and the second's accuracy over [turns] (one game played back; [WatchTurn.side] 0 moved first). */
    fun of(tables: Tables, turns: List<WatchTurn>): Pair<Accuracy, Accuracy> {
        val advisor = Advisor(tables)
        val boards = arrayOf(Board(tables.rules), Board(tables.rules))
        val lost = DoubleArray(2)
        val decisions = IntArray(2)
        val mistakes = IntArray(2)
        val mistakeLoss = DoubleArray(2)
        fun add(side: Int, loss: Double) {
            val l = maxOf(0.0, loss)
            lost[side] += l
            decisions[side]++
            if (l >= MISTAKE) {
                mistakes[side]++
                mistakeLoss[side] += l
            }
        }
        for (t in turns) {
            val plan = advisor.planTurn(boards[t.side], null, Mode.EXPECTED_SCORE)
            for ((i, dice) in t.throws.withIndex()) {
                val rolls = i + 1
                val last = i == t.throws.lastIndex
                val advice = plan.advise(rolls, dice)
                if (rolls < 3) {
                    // A throw kept whole (all five held) ends the turn: the player stopped there.
                    val held = (if (last) dice else t.held[i]).sorted()
                    val chosen = advice.keeps.firstOrNull { it.held.sorted() == held }
                    if (chosen != null) add(t.side, advice.keeps.maxOf { it.expectedFinal } - chosen.expectedFinal)
                }
                if (last) {
                    val chosen = advice.categories.firstOrNull { it.category == t.category }
                    if (chosen != null) add(t.side, advice.categories.maxOf { it.expectedFinal } - chosen.expectedFinal)
                }
            }
            boards[0] = t.first
            boards[1] = t.second
        }
        fun side(s: Int) = Accuracy(percent(lost[s]), lost[s], decisions[s], mistakes[s], mistakeLoss[s])
        return side(0) to side(1)
    }

    /** [lost] expected points as a percentage (100 for none, 0 at [REFERENCE] or more). */
    fun percent(lost: Double): Double = (100.0 * (1.0 - lost / REFERENCE)).coerceIn(0.0, 100.0)
}

// ---- formatting ------------------------------------------------------------------------------------

private fun fmt1(x: Double): String = String.format(Locale.ROOT, "%.1f", x)

private fun grouped(x: Double): String = String.format(Locale.ROOT, "%,d", x.roundToInt())

/** A share (0..1) as a whole percent: "41%". */
private fun pct(x: Double): String = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.0f%%", 100 * x)

/** A ratio above 1 as "24%" (1.24 → 24%). */
private fun percentMore(ratio: Double): String = String.format(Locale.ROOT, "%.0f%%", 100 * abs(ratio - 1))

private fun signedInt(x: Double): String = x.roundToInt().let { if (it > 0) "+$it" else "$it" }

private fun signed1(x: Double): String = (if (x > 0) "+" else if (x < 0) "-" else "") + fmt1(abs(x))

private fun halfText(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

/** A title's name for the chronicle: a league's ("한국 리그 우승", "1부 우승"), the cups'; else the kind. */
private fun titleLabel(kind: String): String = when {
    HonourKind.isDivision(kind) -> HonourKind.leagueOf(kind)?.let { id ->
        if (HonourKind.tier(kind) == 0) "${LeagueIds.label(id)} 우승" else "${LeagueIds.division(id, HonourKind.tier(kind) ?: 0)} 우승"
    } ?: "${(HonourKind.tier(kind) ?: 0) + 1}부 우승"
    kind == "CUP" -> "요트컵"
    kind == "CL" -> "챔피언스 리그"
    kind == "SUPER" -> "슈퍼컵"
    else -> kind
}
