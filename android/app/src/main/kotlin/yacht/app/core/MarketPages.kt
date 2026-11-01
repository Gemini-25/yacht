package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.league.BoardOutlook
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubCheckpoint
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubWorld
import yacht.engine.league.Coach
import yacht.engine.league.CoachBook
import yacht.engine.league.CoachMarket
import yacht.engine.league.CoachRecord
import yacht.engine.league.CoachRecordKind
import yacht.engine.league.Derbies
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Market
import yacht.engine.league.MarketDeal
import yacht.engine.league.MarketStory
import yacht.engine.league.NotableMove
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StoryStage
import yacht.engine.league.Stories
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 이적 센터 on request (`lg.marketPages`, UX 6): the national clubs' kept moves and the last
 * window's numbers (what 이적·드래프트 shows; [WorldPages] loaded them before), and with a club
 * world the transfer centre's four tabs: the open stories and the last windows' outcomes, the
 * moves with their marks and the newest window's replay, the fee records, and the coaches (the hot
 * seat, the season's moves, the free pool, the records). Read under the lock off the main thread,
 * never in the 300 ms snapshot.
 */
class MarketPages internal constructor(private val host: LeagueHost) {
    private var loaded by mutableStateOf<MarketView?>(null)
    private val loads = PageLoads(host) { loaded = null }

    /** The view loaded last; null before, in a legacy season (its club league's market is in the snapshot) or once the league is replaced. */
    val view: MarketView? get() = loads.current(loaded)

    /** Loads the national clubs' moves into [view] (with a club world, the whole 이적 센터). */
    fun load() = loads.load("market", ::marketOf) { loaded = it }

    /**
     * ☀ 이적시장 다시 보기 stopped by "⏸ 멈추기" (the moves shown are the page's [Page.Market.replay]); a
     * replay started again plays on. Kept here, not in the page, so it holds while another page is on top.
     */
    var replayPaused by mutableStateOf(false)

    /** The view of [w] (under the lock; [load]'s reader). */
    internal fun marketOf(w: LeagueWorld): MarketView? {
        val b = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION } ?: return null
        val p = w.plan
        val base = MarketView(p?.number ?: 0, b.history.asReversed(), b.digest)
        // Without a club world shown the page is today's 이적·드래프트 (UX 6.1; `clubWorld` off: it stands still).
        if (p == null || !shown(w, b) || !p.league.leagues.national) return base
        return Centre(w, b, p).view(base)
    }

    // ---- the board outlooks (once per closed week) ------------------------------------------------------

    /** What the outlooks were worked out from last: the plan (held weakly), its season and week, the book's version, the clubs. */
    private var outlookPlan: WeakReference<SeasonPlan>? = null
    private var outlookKey: List<Any>? = null
    private var outlookMap: Map<String, BoardOutlook> = emptyMap()

    /** How many times [outlooks] worked them out (a test reads it: once per closed week). */
    internal var outlookRuns = 0
        private set

    /**
     * The board outlooks ([CoachMarket.outlooks]) of the hot seat's clubs, the clubs of the
     * [HOT_LEAGUES] best-ranked leagues and the followed clubs, through the last closed week of
     * the season in play (UX 6.5, 13.4): worked out once per closed week and kept until the week, the
     * book's version or the followed clubs change, so the home's hot seat (P20) reads the same ones.
     * Empty without a club world shown or in a legacy season. Under the lock.
     */
    internal fun outlooks(w: LeagueWorld): Map<String, BoardOutlook> {
        val p = w.plan ?: return emptyMap()
        val b = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION && shown(w, it) } ?: return emptyMap()
        if (!p.league.leagues.national) return emptyMap()
        val drawn = drawnRatings(w, p)
        val top = Derbies.topLeagues(p, drawn).take(HOT_LEAGUES).toHashSet()
        val followed = host.favourites().filter { it.startsWith(LeagueSelector.CLUB) }.map { it.removePrefix(LeagueSelector.CLUB) }.toSet()
        val clubs = b.clubs.filter { c -> c.id in followed || c.squad.any { k -> p.league.leagueOf(k)?.id in top } }.map { it.id }.toSortedSet()
        val key = listOf(p.number, p.week, b.version, clubs)
        if (outlookPlan?.get() === p && outlookKey == key) return outlookMap
        outlookRuns++
        val map = CoachMarket.outlooks(p, b, p.week - 1, drawn, clubs, w.honours.of(p.number))
        outlookPlan = WeakReference(p)
        outlookKey = key
        outlookMap = map
        return map
    }

    /**
     * One load of the transfer centre (under the lock): the season in play [p] of world [w] with club
     * book [b], each club's badge made once, the members' leagues once.
     */
    private inner class Centre(val w: LeagueWorld, val b: ClubBook, val p: SeasonPlan) {
        val s = p.number
        val favourites = host.favourites()
        private val badges = HashMap<String, ClubBadge?>()

        /** Each club's league this season (its members'), for the leagues' records. */
        private val leagueOf: Map<String, String> by lazy {
            b.clubs.mapNotNull { c -> c.squad.firstNotNullOfOrNull { k -> p.league.leagueOf(k)?.id }?.let { c.id to it } }.toMap()
        }

        fun badge(id: String?): ClubBadge? = id?.let { badges.getOrPut(it) { badgeOf(b, it) } }

        fun story(st: MarketStory): StoryView = storyView(b, st, host.shown(st.key), favourites, ::badge)

        fun fee(season: Int, key: String, from: String?, to: String?, fee: Int, flags: String): FeeLine =
            FeeLine(season, key, if (key.isEmpty()) "–" else host.shown(key), badge(from), badge(to), fee, flags)

        fun fee(m: NotableMove): FeeLine = fee(m.season, m.key, m.from, m.to, m.fee, m.flags)

        /** A coach's name with its flag, active or retired; its id when the book has none. */
        fun coachName(id: String): String = b.coach(id)?.let { coachShown(it.nation, it.name) }
            ?: b.retiredCoaches.firstOrNull { it.id == id }?.let { coachShown(it.nation, it.name) } ?: id

        fun coachLine(c: Coach, o: BoardOutlook?): CoachLine =
            CoachLine(c.id, coachShown(c.nation, c.name), c.philosophy, c.since, c.until, c.reputation, if (c.club == null) null else o?.let(::pressureOf))

        fun view(base: MarketView): MarketView {
            // The newest off-season window is the one before the season in play (none in a club world's first season).
            val summer = Market.report(b, s - 1).takeIf { it.deals.isNotEmpty() || it.stories.isNotEmpty() }
            val window = if (summer != null) s - 1 else 0
            val winter = ClubCheckpoint.report(b, s)
            val history = b.history
            val (winterAt, flags) = marks(history, window, summer?.deals.orEmpty(), winter.deals)
            val n = history.size
            val replay = summer?.deals.orEmpty().map { d -> WindowMove(moveOf(window, d), d.flags, d.phase.label) }
            return base.copy(
                week = p.week,
                live = Stories.open(b, s).map(::story).sortedWith(LIVE_ORDER),
                ended = (winter.stories + summer?.stories.orEmpty()).map(::story).sortedWith(ENDED_ORDER),
                winterMoves = winterAt.mapTo(HashSet()) { n - 1 - it },
                rounds = rounds(replay),
                records = records(summer?.deals.orEmpty(), window),
                coaches = coaches(winter.sacked),
                flags = flags.entries.associate { (i, f) -> n - 1 - i to f },
                window = replay,
                windowSeason = window,
                rumoursFrom = maxOf(Stories.firstWeek(p), ClubWorld.winterWeek(p) + 1),
                winterWeek = ClubWorld.winterWeek(p),
            )
        }

        /**
         * The winter moves among [history] (oldest first; their indexes) and the flags of every move
         * that has some: the newest off-season window's deals ([summer], after season [window]) and the
         * winter's of the season in play ([winter]) carry theirs, the other moves those of their `tx` line.
         * A season's winter moves are its winter transfers (W) and the members let go to make room for
         * them (a release its `ws` lines name, logged before the season's last winter transfer: the
         * off-season after the season logs its own moves under the same season, later).
         */
        private fun marks(history: List<ClubMove>, window: Int, summer: List<MarketDeal>, winter: List<MarketDeal>): Pair<Set<Int>, Map<Int, String>> {
            val flags = HashMap<Int, String>()
            fun put(season: Int, deals: List<MarketDeal>) {
                val at = placeOf(history, season, deals) ?: return
                for ((j, d) in deals.withIndex()) if (d.flags.isNotEmpty()) flags[at[j]] = d.flags
            }
            put(window, summer)
            put(s, winter)
            val tx = HashMap<String, String>()
            for (m in b.notable) if (m.flags.isNotEmpty()) tx.putIfAbsent(txKey(m.season, m.key, m.from, m.to), m.flags)
            for ((i, m) in history.withIndex()) if (i !in flags) tx[txKey(m.season, m.ai, m.from, m.to)]?.let { flags[i] = it }
            val letGo = HashMap<Int, Set<Pair<String, String>>>()
            fun letGoOf(season: Int) = letGo.getOrPut(season) {
                b.winterMoves(season).filter { it.to == ClubCheckpoint.NONE }.mapTo(HashSet()) { it.key to it.from }
            }
            val lastWinter = HashMap<Int, Int>()
            for ((i, m) in history.withIndex()) if (m.type == ClubMoveType.WINTER) lastWinter[m.season] = i
            val winterAt = HashSet<Int>()
            for ((i, m) in history.withIndex()) {
                val last = lastWinter[m.season] ?: continue
                if (m.type == ClubMoveType.WINTER || (m.type == ClubMoveType.RELEASED && i < last && m.from != null && (m.ai to m.from) in letGoOf(m.season))) winterAt += i
            }
            return winterAt to flags
        }

        /**
         * The fee records (UX 6.4): the world record, its progression newest first (each world-record
         * move kept, the record itself too: the first fee a scope sees sets it without a flag), the
         * kept seasons' top [TOP_FEES], each of the [RECORD_LEAGUES] best-ranked leagues' record (the
         * last close's `rk` fee, its `tx` line when one is kept) and last summer's top [SUMMER_TOP]
         * (the newest window's [summer] deals after season [window]).
         */
        private fun records(summer: List<MarketDeal>, window: Int): FeeRecordsView {
            val tx = b.notable
            val txFlags = tx.associate { txKey(it.season, it.key, it.from, it.to) to it.flags }
            val world = b.worldRecord?.let { r -> fee(r.season, r.key, r.from, r.to, r.fee, txFlags[txKey(r.season, r.key, r.from, r.to)].orEmpty()) }
            val progression = (tx.filter { 'W' in it.flags }.map(::fee) + listOfNotNull(world))
                .distinctBy { Triple(it.season, it.key, it.fee) }
                .sortedWith(compareByDescending<FeeLine> { it.season }.thenByDescending { it.fee })
            val top = tx.sortedWith(compareByDescending<NotableMove> { it.fee }.thenByDescending { it.season }.thenBy { it.key }).take(TOP_FEES).map(::fee)
            val ranks = b.rankLines.maxByOrNull { it.season }?.leagueRecords.orEmpty()
            val leagues = Derbies.topLeagues(p, drawnRatings(w, p)).take(RECORD_LEAGUES).mapNotNull { lg ->
                val into = tx.filter { m -> m.to?.let { leagueOf[it] } == lg }
                val record = ranks[lg] ?: into.maxOfOrNull { it.fee } ?: return@mapNotNull null
                val line = into.filter { it.fee == record }.maxByOrNull { it.season }?.let(::fee) ?: fee(0, "", null, null, record, "")
                lg to line
            }
            val lastSummer = summer.filter { it.fee > 0 && it.to != null && it.type != ClubMoveType.RELEASED.code }
                .withIndex().sortedWith(compareByDescending<IndexedValue<MarketDeal>> { it.value.fee }.thenBy { it.index }).take(SUMMER_TOP)
                .map { (_, d) -> fee(window, d.key, d.from, d.to, d.fee, d.flags) }
            return FeeRecordsView(world, progression, top, leagues, lastSummer)
        }

        /**
         * 감독 (UX 6.5): the hot seat (the [outlooks]' coaches at [HOT_RATIO] of the threshold or more, by
         * ratio, ≤ [HOT_SEAT]), this season's coach moves (the winter checkpoint's [sacked] and who came in,
         * then the off-season before the season's), the free pool's best [FREE_TOP] by reputation and the
         * coach records.
         */
        private fun coaches(sacked: List<yacht.engine.league.WinterSacking>): CoachMarketView {
            val hot = outlooks(w).mapNotNull { (club, o) ->
                val coach = b.coachOf(club) ?: return@mapNotNull null
                val ratio = if (o.threshold > 0) o.projected / o.threshold else return@mapNotNull null
                if (ratio < HOT_RATIO) return@mapNotNull null
                val badge = badge(club) ?: return@mapNotNull null
                HotSeatLine(badge, coachLine(coach, o), o.target, o.place, ratio)
            }.sortedWith(compareByDescending<HotSeatLine> { it.ratio }.thenComparing({ it.club.id }, ClubBook.ID_ORDER)).take(HOT_SEAT)
            val moves = ArrayList<CoachMoveLine>()
            for (m in sacked) {
                moves += CoachMoveLine(s, true, 'S', m.coach, coachName(m.coach), badge(m.club), null)
                m.hired?.let { moves += CoachMoveLine(s, true, 'H', it, coachName(it), badge(m.club), null) }
            }
            val off = CoachMarket.report(b, s - 1)
            for (m in off.moves.sortedWith(compareBy<yacht.engine.league.CoachMove> { MOVE_ORDER.indexOf(it.kind) }.thenBy { it.club.orEmpty() }.thenComparing({ it.coach }, CoachBook.ID_ORDER))) {
                // A new coach from the players ('C') names the player it was in `from`, no club.
                moves += CoachMoveLine(s - 1, false, m.kind, m.coach, coachName(m.coach), badge(m.club), if (m.kind == 'C') null else badge(m.from))
            }
            for (r in off.retired) moves += CoachMoveLine(s - 1, false, 'R', r.id, coachShown(r.nation, r.name), null, null)
            val free = b.freeCoaches.sortedWith(compareByDescending<Coach> { it.reputation }.thenComparing({ it.id }, CoachBook.ID_ORDER)).take(FREE_TOP).map { coachLine(it, null) }
            val records = CoachMarket.records(b, s).map { r -> CoachRecordLine(r.kind.label, r.coach, coachName(r.coach), recordValue(r)) }
            return CoachMarketView(hot, moves, free, records)
        }
    }

    companion object {
        /** The leagues whose clubs the hot seat watches (with the followed clubs), and its rows. */
        const val HOT_LEAGUES = 8
        const val HOT_SEAT = 8

        /** A coach is on the hot seat from this share of its sacking threshold on (UX 6.5: ratio ≥ 0.5). */
        const val HOT_RATIO = 0.5

        /** 최고 이적료's lists: the top fees, the leagues' records and last summer's. */
        const val TOP_FEES = 20
        const val RECORD_LEAGUES = Market.RECORD_LEAGUES
        const val SUMMER_TOP = 10

        /** The free coaches shown (쉬고 있는 감독). */
        const val FREE_TOP = 5

        /** The off-season's coach moves in this order: sackings, contracts ended, poachings, hirings, players turned coaches. */
        private const val MOVE_ORDER = "SLPHC"

        /** 이적설: the followed stories first, then the biggest fee, then the older story. */
        private val LIVE_ORDER = compareByDescending<StoryView> { it.followed }.thenByDescending { it.fee }.thenBy { it.id }

        /** 지난 이적시장 결과: the winter's (newer) first, then done, hijacked, collapsed, then the biggest fee. */
        private val ENDED_ORDER = compareByDescending<StoryView> { it.winter }
            .thenBy { listOf(StoryStage.DONE, StoryStage.HIJACKED, StoryStage.COLLAPSED).indexOf(it.stage) }
            .thenByDescending { it.fee }.thenBy { it.id }

        /**
         * The tab [page] shows (UX 6.1): its own, else 이적 내역 before the rumour weeks and 이적설 from
         * them (or while stories are open: the winter's).
         */
        fun tabOf(page: Page.Market, v: MarketView): MarketTab =
            page.tab ?: if (v.live.isNotEmpty() || (v.rumoursFrom > 0 && v.week >= v.rumoursFrom)) MarketTab.LIVE else MarketTab.MOVES

        /** ☀ 다시 보기's rounds: each round's name and the index of its first move in [window]. */
        internal fun rounds(window: List<WindowMove>): List<Pair<String, Int>> =
            window.withIndex().filter { (i, m) -> i == 0 || window[i - 1].round != m.round }.map { (i, m) -> m.round to i }

        /** [d] of the window after season [season] as the move log keeps it. */
        private fun moveOf(season: Int, d: MarketDeal): ClubMove =
            ClubMove(season, ClubMoveType.of(d.type) ?: ClubMoveType.TRANSFER, d.key, d.from, d.to, d.fee)

        /**
         * The indexes in [history] of [deals], a window's of [season] as [Market.report] reads them (its moves
         * of that season from an offset, in order); null when the log no longer holds them all.
         */
        internal fun placeOf(history: List<ClubMove>, season: Int, deals: List<MarketDeal>): List<Int>? {
            if (deals.isEmpty()) return null
            val at = history.indices.filter { history[it].season == season }
            for (k in 0..at.size - deals.size) {
                if (deals.withIndex().all { (j, d) -> history[at[k + j]].let { m -> m.ai == d.key && m.from == d.from && m.to == d.to && m.amount == d.fee } }) {
                    return at.subList(k, k + deals.size)
                }
            }
            return null
        }

        private fun txKey(season: Int, key: String, from: String?, to: String?) = "$season $key ${from ?: "-"} ${to ?: "-"}"

        /** A coach record's value in words. */
        private fun recordValue(r: CoachRecord): String = when (r.kind) {
            CoachRecordKind.TENURE -> "${r.value}시즌"
            CoachRecordKind.CLUBS -> "${r.value}개 클럽"
            CoachRecordKind.CLUB_TITLES, CoachRecordKind.SACKED, CoachRecordKind.AWARDS -> "${r.value}회"
        }

        /** [b] shows its club world: it has one and the rules keep it on (as [WorldPages]' pages). */
        private fun shown(w: LeagueWorld, b: ClubBook): Boolean = b.world != null && w.config.clubWorld

        /** A club's badge (a gone club's from its `gone` line). */
        private fun badgeOf(b: ClubBook, id: String): ClubBadge? =
            b.club(id)?.let { ClubBadge(it.id, it.name, it.city, it.nation, it.emblem) } ?: b.gone(id)?.let { ClubBadge(it.id, it.name, "", it.nation, it.emblem) }
    }
}

/**
 * How the page loaders ([MarketPages], [StoryPages], [EventPages], [EncPages]) read the league, as
 * [WorldPages] does: under the lock off the main thread, the result handed over on the main
 * thread, the newest request of each kind winning. The first result read from another league
 * than before (a reset, a restore) first calls [clear], also when there is nothing to show, and
 * [current] hides a view of a league that is no longer the host's.
 */
internal class PageLoads(private val host: LeagueHost, private val clear: () -> Unit) {
    /** The league the views were read from. */
    @Volatile
    private var source: LeagueWorld? = null

    /** The newest request per kind: an older one finishing later is dropped. */
    private val latest = ConcurrentHashMap<String, Int>()
    private val requests = AtomicInteger()

    /** [view] when it was read from the league the host has now, else null. */
    fun <T> current(view: T?): T? = view?.takeIf { source === host.world() }

    /**
     * [read] under the lock off the main thread, then [done] on the main thread with its result
     * (nothing when it gives null, or when a newer request of [kind] came meanwhile). An error
     * reaches the app's handler (the crash log); the page keeps waiting.
     */
    fun <T : Any> load(kind: String, read: (LeagueWorld) -> T?, done: (T) -> Unit) {
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
                if (w !== source) {
                    source = w
                    clear()
                }
                v?.let(done)
            }
        }
    }
}
