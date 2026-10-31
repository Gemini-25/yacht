package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.explain.AiStep
import yacht.engine.explain.Commentary
import yacht.engine.explain.Ko
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Drama
import yacht.engine.league.DramaContext
import yacht.engine.league.Fixture
import yacht.engine.league.GameFacts
import yacht.engine.league.GameLogs
import yacht.engine.league.GameScore
import yacht.engine.league.Highlight
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueWorld
import yacht.engine.league.LogClass
import yacht.engine.league.LogSink
import yacht.engine.league.LoggedGame
import yacht.engine.league.MatchLog
import yacht.engine.league.MatchLogBook
import yacht.engine.league.MatchRef
import yacht.engine.league.Preview
import yacht.engine.league.RatingScale
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatch
import yacht.engine.league.SeasonMatches
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import yacht.engine.league.WatchTurn
import yacht.engine.league.byTeams
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** The watch screen's own pace (saved in ui.txt); the game screen's [AiPace] is not touched. */
enum class WatchPace(val ms: Long, val label: String) { SLOW(1800L, "느리게"), NORMAL(1100L, "보통"), FAST(500L, "빠르게") }

/**
 * Watching real matches (`lg.center`, UX design 3.2-3.9): the match log book
 * (league/matchlogs.bin) the league fills as it plays ([offer]) and rolls over at every close
 * ([closing]); match reports ([loadReport]); replays from a log, a re-simulation or a highlight's
 * genes ([resolve]); the watch screen ([watch], [watchHighlight], [watchFriendly],
 * [startBroadcast]); and the broadcast channel's picks ([refreshPicks]). The book and whatever is
 * read from the league are used under the league's lock; the screen's state is Compose state.
 */
class MatchCenter internal constructor(private val host: LeagueHost) {
    // ---- the log book (under the league's lock) ------------------------------------------------------

    private var book = MatchLogBook()

    /** Boards played again for a report or a replay (not saved), by fixture: the last [RESIMS]. */
    private val resims = object : LinkedHashMap<MatchRef, List<MatchLog>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MatchRef, List<MatchLog>>?): Boolean = size > RESIMS
    }

    /** The current season's boards put in the book (some may have been evicted since), oldest first. */
    private val logged = LinkedHashSet<MatchRef>()

    /** Watched friendlies are told apart by refs of their own (negative ids). */
    private var friendlies = 0

    /** The season and week the broadcast picks were made at last. */
    private var pickedAt: Pair<Int, Int>? = null

    /** The candidates of each week the picks were chosen from (in memory; made again after a restart), of [candidatesSeason]. */
    private val candidates = HashMap<Int, List<BroadcastPicks.Candidate>>()
    private var candidatesSeason = 0

    /** The members the current block's budget was set for. */
    private var capsMembers = -1

    /** Followed players and the best members of followed clubs ([LogClass.FOLLOWED]), and what they were worked out from. */
    private var followedKeys: Set<String> = emptySet()
    private var followedFor: Triple<Set<String>, Int, Int>? = null

    /**
     * Takes the saved book (league/matchlogs.bin; null: none) for the league just read (under the
     * lock): blocks of another league are dropped, a book saved before the season's close is rolled
     * over now, and the picks saved with it stand for their week.
     */
    internal fun load(bytes: ByteArray?) {
        val w = host.world()
        val p = w?.plan
        book = if (p == null) MatchLogBook() else MatchLogBook.decode(bytes).also { it.dropForeign(p.number, p.seed) }
        // Without a season the file cannot be checked: it is left as it is (a backup takes it from disk).
        onDisk = bytes != null && p != null
        forget()
        if (w == null || p == null) return
        ensure(w, p)
        if (book.picks.firstOrNull()?.first == p.week) pickedAt = p.number to p.week
    }

    /** A book file is there for this league (read, or written since): an emptied book is written over it. */
    private var onDisk = false

    /**
     * The binary files saved with the league's text files (under the lock): the book, from its first
     * record (a new league's empty book writes no file, so a reset leaves none).
     */
    internal fun files(): Map<String, ByteArray> {
        if (host.world() == null) return emptyMap()
        val empty = book.picks.isEmpty() && listOf(MatchLogBook.CURRENT, MatchLogBook.PREVIOUS, MatchLogBook.ARCHIVE).all { book.count(it) == 0 }
        if (empty && !onDisk) return emptyMap()
        onDisk = true
        return mapOf(LOGS to book.encode())
    }

    /**
     * Forgets what belongs to a league being replaced (a reset, a seed, a restore): the book, the
     * replays, the reports and the watch screen. Safe under the lock.
     */
    internal fun clear() {
        book = MatchLogBook()
        // The league's file is deleted with it.
        onDisk = false
        forget()
        host.scope.launch {
            stop()
            reports = emptyMap()
            broadcast = null
            watched.clear()
        }
    }

    private fun forget() {
        resims.clear()
        lineups.clear()
        logged.clear()
        candidates.clear()
        pickedAt = null
        followedFor = null
        capsMembers = -1
    }

    /** The book on [p]'s season (under the lock): one a close behind is rolled over first, any other started anew. */
    private fun ensure(w: LeagueWorld, p: SeasonPlan) {
        if (book.season != p.number || book.seed != p.seed) {
            if (book.season > 0 && book.season == p.number - 1 && MatchLogBook.nextSeed(book.seed, p.number) == p.seed) {
                roll(w, book.season, book.seed, p.number, p.seed, closingHighlights(w, book.season))
            }
            book.begin(p.number, p.seed)
            logged.clear()
            pickedAt = null
            capsMembers = -1
        }
        val members = w.members.size
        if (members != capsMembers) {
            capsMembers = members
            book.caps = MatchLogBook.caps(members)
        }
    }

    /**
     * Logs the boards of [r] (just recorded, under the lock) that are worth keeping (UX 3.4: finals,
     * highlights, broadcast picks, adaptive sides, late knockouts; followed players' in memory only),
     * from the decisions [sink] took while it was played.
     */
    internal fun offer(r: JobResult, sink: LogSink?) {
        if (sink == null || sink.boardCount == 0) return
        val f = r.fixture
        if (f.comp == Comp.FRIENDLY || f.comp == Comp.LAB) return
        val w = host.world() ?: return
        val p = w.plan ?: return
        ensure(w, p)
        ensurePicks(w, p)
        // A job of the season before (taken before a close, recorded after) is not this season's match.
        val m = SeasonMatches.of(p, f.comp, f.id) ?: return
        if (m.fixture.seed != f.seed) return
        val common = classesOf(p, f, book.picks)
        val highlights = w.highlights.let { it.thisSeason + it.allTime }.filter { it.season == p.number && it.fixture.comp == f.comp && it.fixture.id == f.id }
        val followed = followed(w)
        val tag = tagOf(p, f)
        for ((i, bd) in r.boards.withIndex()) {
            var c = common
            if (highlights.any { it.board == i }) c = c or LogClass.HIGHLIGHT.bit
            if (host.adaptive(bd.a) || host.adaptive(bd.b)) c = c or LogClass.ADAPTIVE.bit
            if (bd.a in followed || bd.b in followed) c = c or LogClass.FOLLOWED.bit
            if (c == 0) continue
            val log = MatchLog.of(p.number, r, sink, i, c, tag) ?: continue
            if (book.put(log)) logged += log.ref
        }
    }

    /**
     * The classes every board of [f] has (UX 3.4): [LogClass.FINAL], [LogClass.BROADCAST] (one of
     * its week's [picks]) and [LogClass.KO] (a knockout tie from the 8강 on, a CL or CCL knockout,
     * a title playoff of the [KO_LEAGUES] best-ranked leagues).
     */
    internal fun classesOf(p: SeasonPlan, f: Fixture, picks: List<Pair<Int, List<MatchRef>>>): Int {
        var c = 0
        if (Drama.isFinal(f)) c = c or LogClass.FINAL.bit
        if (picks.any { (_, refs) -> refs.any { it.comp == f.comp && it.id == f.id } }) c = c or LogClass.BROADCAST.bit
        val ko = when {
            f.kind != JobKind.TIE -> false
            f.comp == Comp.CHAMPIONS || f.comp == Comp.CONT_CHAMPIONS -> true
            f.comp == Comp.TITLE_PLAYOFF -> !p.league.leagues.national || p.league.leagueOf(f.a)?.let { host.leagueRank(it.id) < KO_LEAGUES } == true
            else -> KO_LABEL.containsMatchIn(f.label)
        }
        if (ko) c = c or LogClass.KO.bit
        return c
    }

    /** A final's archive tag (its place in the archive after the highlights); 0 for any other match. */
    private fun tagOf(p: SeasonPlan, f: Fixture): Int = if (!Drama.isFinal(f)) 0 else when (f.comp) {
        Comp.CHAMPIONS -> MatchLogBook.TAG_CL_FINAL
        Comp.CUP -> MatchLogBook.TAG_CUP_FINAL
        Comp.SUPER -> MatchLogBook.TAG_SUPER
        // One a confederation's section.
        Comp.CONT_CHAMPIONS -> MatchLogBook.TAG_CCL_FINAL + (p.league.leagueOf(f.a)?.let { p.league.leagues.confedOf(it.id)?.ordinal } ?: 0).coerceIn(0, 3)
        else -> 0
    }

    /** The followed players (the first [FOLLOWED_PLAYERS] by rating) and the best [CLUB_BEST] members of the first [FOLLOWED_CLUBS] followed clubs; under the lock. */
    private fun followed(w: LeagueWorld): Set<String> {
        val favs = host.favourites()
        val p = w.plan
        val key = Triple(favs, p?.number ?: 0, p?.week ?: 0)
        if (followedFor == key) return followedKeys
        fun rating(k: String) = w.rating(k).takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY
        val players = favs.filter { ':' !in it }.sortedByDescending(::rating).take(FOLLOWED_PLAYERS)
        val clubs = favs.filter { it.startsWith(LeagueSelector.CLUB) }.take(FOLLOWED_CLUBS).flatMap { id ->
            w.clubs?.members(id.removePrefix(LeagueSelector.CLUB)).orEmpty().sortedByDescending(::rating).take(CLUB_BEST)
        }
        followedKeys = (players + clubs).toSet()
        followedFor = key
        return followedKeys
    }

    /**
     * The close of season [p] (under the lock, between the world's `closeSeason` and `openSeason`):
     * the book's current block becomes last season's (finals, highlights, broadcast picks first),
     * the season's best single games go to the archive, and the next season's block starts.
     */
    internal fun closing(w: LeagueWorld, p: SeasonPlan) {
        if (book.season != p.number || book.seed != p.seed) return
        roll(w, p.number, p.seed, p.number + 1, MatchLogBook.nextSeed(p.seed, p.number + 1), w.highlights.thisSeason.filter { it.season == p.number })
        forget()
    }

    private fun roll(w: LeagueWorld, closing: Int, closingSeed: Long, next: Int, nextSeed: Long, highlights: List<Highlight>) {
        // The season's highlights by drama (1-3 are archived first), each with its own game.
        val ranked = highlights.sortedByDescending { it.drama }.take(ARCHIVED_HIGHLIGHTS)
        book.rollover(
            closing, closingSeed, next, nextSeed, MatchLogBook.caps(w.members.size),
            keep = { log ->
                when {
                    log.has(LogClass.FINAL) -> 0
                    log.has(LogClass.HIGHLIGHT) -> 1
                    log.has(LogClass.BROADCAST) -> 2
                    log.has(LogClass.NEWS) -> 3
                    else -> -1
                }
            },
            archive = { log ->
                val own = ranked.withIndex().filter { (_, h) -> h.fixture.comp == log.ref.comp && h.fixture.id == log.ref.id && h.board == maxOf(0, log.ref.board) }
                own.map { (i, h) -> MatchLogBook.ArchiveGame(i + 1, h.game) } + if (log.tag >= MatchLogBook.TAG_CL_FINAL) listOf(MatchLogBook.ArchiveGame(log.tag)) else emptyList()
            },
        )
    }

    /** The highlights of a season already closed (its highlights put aside when the next one opened), for a rollover made late. */
    private fun closingHighlights(w: LeagueWorld, season: Int): List<Highlight> {
        val now = w.highlights.thisSeason.filter { it.season == season }
        if (now.isNotEmpty()) return now
        return w.highlights.archiveLines(season).mapNotNull { line -> Highlight.decode(line.substringAfter(' ').split(' ')) }
    }

    /**
     * True when the match of [ref] between [a] and [b] can be played again (the ▶ rule, UX 3.3):
     * its log is kept, or it is this season's official match and neither side adapts (and both are
     * in the league). Under the lock.
     */
    internal fun canReplay(ref: MatchRef, a: String, b: String): Boolean {
        if (ref.comp == Comp.FRIENDLY || ref.comp == Comp.LAB || ref.comp == Comp.LEGEND) return book[ref] != null
        if (book[ref] != null) return true
        val p = host.world()?.plan ?: return false
        if (ref.season != p.number) return false
        // A team match is played again whole: a side of any of its boards that adapts or left rules it out.
        val keys = if (ref.board < 0) listOf(a, b) else lineups.getOrPut(ref.whole) {
            SeasonMatches.of(p, ref)?.fixture?.let { f -> if (f.kind == JobKind.TEAM) f.lineupA + f.lineupB else listOf(f.a, f.b) } ?: listOf(a, b)
        }
        return keys.none { host.adaptive(it) } && keys.all { host.ai(it) != null }
    }

    /** Every board's sides of the team matches [canReplay] was asked about (this season's), the last [LINEUPS]. */
    private val lineups = object : LinkedHashMap<MatchRef, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MatchRef, List<String>>?): Boolean = size > LINEUPS
    }

    /** The current season's boards in the book (under the lock): for the harness. */
    internal fun loggedBoards(): List<MatchLog> = logged.mapNotNull { book[it] }

    /** The book's bytes of [kind] ([MatchLogBook.CURRENT], [MatchLogBook.PREVIOUS], [MatchLogBook.ARCHIVE]) and its budget now (under the lock). */
    internal fun bookBytes(kind: Int): Int = book.bytes(kind)
    internal fun bookCaps(): MatchLogBook.Caps = book.caps

    /** The record of [ref]'s board in the book (under the lock), for the harness and tests. */
    internal fun logOf(ref: MatchRef): MatchLog? = book[ref]

    /** The broadcast picks of the week in play and the next (under the lock), as the book keeps them. */
    internal fun picks(): List<Pair<Int, List<MatchRef>>> = book.picks

    /** Every match of the season in play (under the lock it takes): for the harness. */
    internal fun matchesNow(): List<SeasonMatch> = synchronized(host.lock) { host.world()?.plan?.let { SeasonMatches.all(it).toList() }.orEmpty() }

    /** Runs [block] under the league's lock (the harness reads the book and the season with it). */
    internal fun <T> locked(block: () -> T): T = synchronized(host.lock, block)

    /** The broadcast channel's view now and the candidates of [week] (under the lock they take): for tests. */
    internal fun broadcastNow(): BroadcastView? = synchronized(host.lock) { broadcastView() }
    internal fun candidatesNow(week: Int): List<BroadcastPicks.Candidate> = synchronized(host.lock) {
        val w = host.world() ?: return@synchronized emptyList()
        w.plan?.let { candidatesOf(w, it, week) }.orEmpty()
    }

    /** True when [key] adapts to its opponent (its matches are played again only from a log). */
    internal fun adaptive(key: String): Boolean = host.adaptive(key)

    /**
     * [ref]'s fixture of this season played again now, on the caller's thread (the harness compares
     * it with the kept logs): its boards' logs once it ends as stored, else null.
     */
    internal fun replayNow(ref: MatchRef): List<MatchLog>? {
        val t = host.tables() ?: return null
        val (m, format) = synchronized(host.lock) { host.world()?.plan?.let { p -> SeasonMatches.of(p, ref) to p.config.matchFormat } } ?: return null
        if (m == null || !m.played) return null
        val sink = LogSink()
        val r = LeagueMatch.playJob(t.rules, m.fixture, { host.ai(it) }, format, null, log = sink) ?: return null
        if (!SeasonMatches.verify(m, r)) return null
        return r.boards.indices.mapNotNull { MatchLog.of(ref.season, r, sink, it, 0) }
    }

    // ---- replays: from a log, a re-simulation or a highlight's genes ---------------------------------

    /** Re-simulations run here, one at a time, behind the league's workers. */
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(null, job, "yacht-replay", WORKER_STACK).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    /** A board played back: its log and each stored game's turns (one entry per [MatchLog.games]). */
    private class Played(val log: MatchLog, val turns: List<List<WatchTurn>>)

    /** What [resolve] found: the boards played back by [source] (a highlight's one game for [ReplaySource.SNAPSHOT]), or why none. */
    private class Resolved(val source: ReplaySource, val boards: List<Played> = emptyList(), val note: String? = null)

    /** What [resolve] reads from the league under the lock. */
    private class Facts(
        val fixture: Fixture?,
        val match: SeasonMatch?,
        val current: Boolean,
        val logs: List<MatchLog>,
        val highlight: Highlight?,
        val adaptive: Boolean,
        val present: Boolean,
        val format: yacht.engine.league.MatchFormat?,
    )

    private fun facts(ref: MatchRef, highlight: Highlight? = null): Facts {
        val w = host.world() ?: return Facts(null, null, false, emptyList(), null, false, false, null)
        val p = w.plan
        val whole = ref.whole
        val current = p != null && ref.season == p.number
        val m = if (current) SeasonMatches.of(p!!, whole) else null
        val logs = book.boards(whole).ifEmpty { resims[whole].orEmpty() }
        val h = highlight ?: highlightOf(w, ref)
        val f = m?.fixture ?: logs.firstOrNull()?.fixture() ?: h?.fixture
        val keys = f?.let { if (it.kind == JobKind.TEAM) it.lineupA + it.lineupB else listOf(it.a, it.b) }.orEmpty()
        return Facts(f, m, current, logs, h, keys.any { host.adaptive(it) }, keys.isNotEmpty() && keys.all { host.ai(it) != null }, p?.config?.matchFormat)
    }

    /** [ref]'s highlight (its board and game when given, else the fixture's best) among the season's and the all-time ones. */
    private fun highlightOf(w: LeagueWorld, ref: MatchRef): Highlight? {
        val all = (w.highlights.thisSeason + w.highlights.allTime).filter { it.season == ref.season && it.fixture.comp == ref.comp && it.fixture.id == ref.id }
        return all.firstOrNull { (ref.board < 0 || it.board == ref.board) && (ref.game < 0 || it.game == ref.game) } ?: all.firstOrNull()
    }

    /**
     * Plays [ref]'s fixture back (UX 3.3), the first that works: its kept log (checked against the
     * stored scores, and this season's against the season's result); a re-simulation of this
     * season's fixture with no adaptive side (checked the same way, at most [RESIM_MS]); a
     * highlight's game from its genes. On the replay worker; [cancelled] stops a re-simulation.
     */
    private suspend fun resolve(ref: MatchRef, highlight: Highlight? = null, snapshotFirst: Boolean = false, cancelled: () -> Boolean = { false }): Resolved = withContext(worker) {
        val t = host.tables() ?: return@withContext Resolved(ReplaySource.NONE, note = NO_TABLES)
        val rules = t.rules
        val x = synchronized(host.lock) { facts(ref, highlight) }
        val f = x.fixture ?: return@withContext Resolved(ReplaySource.NONE, note = NOT_FOUND)
        if (f.comp == Comp.FRIENDLY || f.comp == Comp.LAB) {
            if (x.logs.isEmpty()) return@withContext Resolved(ReplaySource.NONE, note = FRIENDLY_TEXT)
        }
        val m = x.match
        val resim = x.current && m != null && m.played && !x.adaptive && x.present && x.format != null
        // A team match is played back whole: boards logged alone (a highlight's) are re-simulated with the rest.
        val partial = f.kind == JobKind.TEAM && x.logs.size < f.lineupA.size && resim
        // A highlight's game the kept boards lack (last season's series keeps its deciding game only): its genes first.
        val lacks = snapshotFirst && highlight != null && x.logs.none { l -> maxOf(0, l.ref.board) == highlight.board && l.games.any { it.index == highlight.game } }
        if (x.logs.isNotEmpty() && !partial && !lacks) {
            val boards = x.logs.map { log -> log.games.map { g -> log.turns(rules, g.index) } to log }
            if (boards.any { (turns, _) -> turns.any { it == null } }) return@withContext Resolved(ReplaySource.NONE, note = CORRUPT)
            if (x.current && x.match != null && !sameResult(x.match, x.logs)) return@withContext Resolved(ReplaySource.NONE, note = CORRUPT)
            val source = if (synchronized(host.lock) { book.boards(ref.whole).isNotEmpty() }) ReplaySource.LOG else ReplaySource.RESIM
            return@withContext Resolved(source, boards.map { (turns, log) -> Played(log, turns.map { it!! }) })
        }
        if (snapshotFirst) x.highlight?.let { h -> snapshot(h, rules)?.takeIf { it.source != ReplaySource.NONE }?.let { return@withContext it } }
        if (resim) {
            val deadline = System.nanoTime() + RESIM_MS * 1_000_000L
            val sink = LogSink()
            val r = try {
                LeagueMatch.playJob(rules, m.fixture, { host.ai(it) }, x.format, null, { cancelled() || System.nanoTime() > deadline }, log = sink)
            } catch (e: CancellationException) {
                if (cancelled()) throw e
                return@withContext Resolved(ReplaySource.NONE, note = SLOW)
            } ?: return@withContext Resolved(ReplaySource.NONE, note = LEFT)
            if (!SeasonMatches.verify(m, r)) return@withContext Resolved(ReplaySource.NONE, note = CHANGED)
            val logs = r.boards.indices.mapNotNull { MatchLog.of(ref.season, r, sink, it, 0) }
            val played = logs.map { log -> Played(log, log.games.map { g -> log.turns(rules, g.index) ?: return@withContext Resolved(ReplaySource.NONE, note = CORRUPT) }) }
            synchronized(host.lock) { resims[ref.whole] = logs }
            return@withContext Resolved(ReplaySource.RESIM, played)
        }
        x.highlight?.let { h -> snapshot(h, rules)?.let { return@withContext it } }
        Resolved(ReplaySource.NONE, note = whyNot(x))
    }

    /** [h]'s game played again from its genes (players without genes as they are now): a board of that one game, or why it cannot be. */
    private fun snapshot(h: Highlight, rules: yacht.engine.RuleSet): Resolved? {
        fun aiOf(k: String) = h.snapshot(k)?.let { host.aiFrom(k, it) } ?: host.ai(k)
        if (aiOf(h.a) == null || aiOf(h.b) == null) return Resolved(ReplaySource.NONE, note = LEFT)
        if (h.specA == null && h.specB == null && (host.adaptive(h.a) || host.adaptive(h.b))) return null
        val models = listOf(h.a, h.b).associateWith { host.oppModel(it) }
        val r = h.replay(rules, ::aiOf, scout = { models[it] }) ?: return Resolved(ReplaySource.NONE, note = LEFT)
        if (!r.same) return Resolved(ReplaySource.NONE, note = CHANGED)
        val f = h.fixture
        val log = MatchLog(
            MatchRef(h.season, f.comp, f.id, h.board), f.kind, h.perMatch, f.legs, f.firstTo, f.seed, h.a, h.b,
            if (f.kind == JobKind.TEAM) f.a else null, if (f.kind == JobKind.TEAM) f.b else null, f.label, f.week, 0, 0,
            listOf(LoggedGame(h.game, h.scoreA, h.scoreB, GameLogs.of(r.turns, h.deal))),
        )
        return Resolved(ReplaySource.SNAPSHOT, listOf(Played(log, listOf(r.turns))))
    }

    /** The reason a match with no log cannot be played again (UX 3.3's texts). */
    private fun whyNot(x: Facts): String = when {
        x.fixture?.comp == Comp.FRIENDLY || x.fixture?.comp == Comp.LAB -> FRIENDLY_TEXT
        !x.current -> PAST
        x.match?.played == false -> NOT_PLAYED
        x.adaptive -> ADAPTIVE_TEXT
        !x.present -> LEFT
        else -> CHANGED
    }

    /** True when the logs end as the season stored [m]: a league fixture game for game, a tie or team match on its score (every board logged). */
    private fun sameResult(m: SeasonMatch, logs: List<MatchLog>): Boolean {
        if (!m.played) return false
        m.games?.let { g -> return logs.size == 1 && logs[0].games.map { it.scoreA to it.scoreB } == g.map { it.a to it.b } }
        val s = m.score ?: return true
        val f = m.fixture
        if (f.kind == JobKind.TEAM) {
            if (logs.size != f.lineupA.size) return true
            return logs.sumOf { l -> l.games.sumOf { it.scoreA } } == s.totalA && logs.sumOf { l -> l.games.sumOf { it.scoreB } } == s.totalB
        }
        val games = logs.single().games
        return games.sumOf { it.scoreA } == s.totalA && games.sumOf { it.scoreB } == s.totalB
    }

    // ---- match reports --------------------------------------------------------------------------------

    /** The reports loaded last (at most [WorldPages.KEEP]), by [Page.Match.loadKey], the newest last; all of one league ([reportsOf]). */
    var reports by mutableStateOf<Map<String, MatchReport>>(emptyMap(), referentialEqualityPolicy())
        private set
    private var reportsOf: LeagueWorld? = null
    private val reportRequests = AtomicInteger()
    private val latest = ConcurrentHashMap<String, Int>()
    private val details = ConcurrentHashMap<String, Job>()

    /** [page]'s report when it was loaded from the league in play (else null: still to load). */
    fun report(page: Page.Match): MatchReport? = reports[page.loadKey]?.takeIf { reportsOf === host.world() }

    /**
     * Loads [page]'s report (UX 3.2) in two phases: what the league stores (under the lock, at once),
     * then the games played back (a log decoded or the fixture re-simulated on the replay worker) for
     * the 판별·칸별 점수, the win curves and the 평점. [closeReport] stops the second.
     */
    fun loadReport(page: Page.Match) {
        val key = page.loadKey
        val n = reportRequests.incrementAndGet()
        latest[key] = n
        details.remove(key)?.cancel()
        val job = host.scope.launch(Dispatchers.Default) {
            val view = host.view()
            val (w, quick) = synchronized(host.lock) {
                val w = host.world() ?: return@launch
                w to (runCatching { quickReport(w, key, page.key, view) }.getOrNull() ?: return@launch)
            }
            put(w, key, n, quick)
            if (!quick.loading) return@launch
            val ref = MatchRef.decode(page.key)?.whole ?: return@launch
            val job = coroutineContext[Job]
            val r = runCatching { resolve(ref, cancelled = { job?.isActive != true }) }.getOrElse { e ->
                if (e is CancellationException) return@launch
                Resolved(ReplaySource.NONE, note = CORRUPT)
            }
            val full = runCatching { detailed(quick, r) }.getOrElse { quick.copy(loading = false, note = CORRUPT) }
            put(w, key, n, full)
        }
        details[key] = job
        job.invokeOnCompletion { details.remove(key, job) }
    }

    /** Stops [page]'s report being worked out (its page closed). */
    fun closeReport(page: Page.Match) {
        details.remove(page.loadKey)?.cancel()
    }

    private fun put(w: LeagueWorld, key: String, n: Int, r: MatchReport) {
        host.scope.launch {
            if (latest[key] != n || w !== host.world()) return@launch
            if (w !== reportsOf) {
                reportsOf = w
                reports = emptyMap()
            }
            reports = WorldPages.keep(reports, key, r)
        }
    }

    /** The stored part of [key]'s report (under the lock): the fixture, both sides, the result, the stored games, stakes, preview and news. */
    private fun quickReport(w: LeagueWorld, loadKey: String, key: String, view: LeagueView?): MatchReport {
        val ref = MatchRef.decode(key)?.whole ?: return missing(loadKey, key)
        val x = facts(ref, null)
        val f = x.fixture ?: return fromFeed(loadKey, ref, x, view) ?: missing(loadKey, key)
        val p = w.plan
        val s = p?.league?.takeIf { x.current }
        val m = x.match
        val live = x.current && view?.live?.any { it.comp == ref.comp && it.id == ref.id } == true
        val played = m?.played ?: (x.logs.isNotEmpty() || x.highlight != null)
        val state = when {
            played -> ReportState.PLAYED
            live -> ReportState.LIVE
            else -> ReportState.SCHEDULED
        }
        val team = f.kind == JobKind.TEAM
        fun side(k: String) = if (team || f.comp.byTeams) teamName(k) else host.shown(k)
        fun rating(k: String) = if (team) null else w.rating(k).takeIf { !it.isNaN() }?.roundToInt()
        fun place(k: String): String? {
            if (f.comp != Comp.LEAGUE || s == null) return null
            val d = s.divisionOf(k) ?: return null
            val pos = s.table(d).indexOfFirst { it.key == k }.takeIf { it >= 0 } ?: return null
            return "${s.leagues.label(d)} ${pos + 1}위"
        }
        val pairs = when {
            team && f.lineupA.isNotEmpty() -> f.lineupA.zip(f.lineupB)
            team -> x.logs.sortedBy { it.ref.board }.map { it.a to it.b }
            else -> listOf(f.a to f.b)
        }
        val logs = x.logs.associateBy { maxOf(0, it.ref.board) }
        val final = Drama.isFinal(f)
        val boards = pairs.mapIndexed { i, (a, b) ->
            val games = if (state != ReportState.PLAYED) null else m?.games?.takeIf { !team }?.mapIndexed { g, sc -> storedGame(f, g, sc, a, b, logs[i], x.format) }
                ?: logs[i]?.games?.map { g -> storedGame(f, g.index, GameScore(g.scoreA, g.scoreB), a, b, logs[i], x.format) }
            BoardReport(i, a, b, host.name(a), host.name(b), games, games?.sumOf { it.scoreA ?: 0 } ?: 0, games?.sumOf { it.scoreB ?: 0 } ?: 0, games?.let { boardResult(it, x.format) } ?: 0)
        }
        val (result, winner) = resultOf(f, m, boards, x.format)
        val canReplay = played && (x.logs.isNotEmpty() || (x.current && m != null && !x.adaptive && x.present && f.comp != Comp.FRIENDLY) || x.highlight != null)
        val note = when {
            state == ReportState.LIVE -> LIVE_TEXT
            state == ReportState.SCHEDULED -> NOT_PLAYED
            !canReplay -> whyNot(x)
            else -> null
        }
        // What is at stake is told as things stand now: for a match to come or of the week in play only.
        val now = x.current && (state != ReportState.PLAYED || (p != null && f.week >= p.week))
        val preview = if (team || !now) null else host.preview(f.a, f.b, f)
        val stakes = if (!now) emptyList() else buildList {
            if (preview != null && f.comp != Comp.FRIENDLY) addAll(preview.stakes)
            if (f.comp == Comp.LEAGUE && s?.bonus == true && !team) add("2:0 완승으로 승점 ${WIN_POINTS + 1}점을 챙겨요.")
        }
        // Articles of the match's week about both players (the article of the match itself among them).
        val news = if (team) emptyList() else w.news.about(f.a).filter { it.season == ref.season && f.b in it.keys && (f.week == SeasonCalendar.NONE || it.week == f.week) }.take(NEWS)
        val delta = view?.feed?.firstOrNull { it.ref?.let { r -> MatchRef.decode(r)?.whole } == ref && !team }
        return MatchReport(
            loadKey, ref.encode(), titleOf(p, f, ref.season), state, f.a, f.b, side(f.a), side(f.b), host.badge(f.a), host.badge(f.b),
            rating(f.a), rating(f.b), result, winner, boards, if (x.logs.isNotEmpty()) ReplaySource.LOG else ReplaySource.NONE,
            canReplay, loading = played && canReplay, note = note, stakes = stakes, preview = preview.takeIf { state != ReportState.PLAYED },
            news = news, comp = f.comp, label = f.label, season = ref.season, week = f.week, placeA = place(f.a), placeB = place(f.b),
            team = team, firstTo = f.firstTo,
            deltaA = delta?.let { if (it.a == f.a) it.deltaA else it.deltaB }, deltaB = delta?.let { if (it.a == f.a) it.deltaB else it.deltaA },
        )
    }

    /**
     * A match the league no longer holds (a past season's with no log or highlight kept), as the
     * results feed still shows it: its sides and games, and why it cannot be played again.
     */
    private fun fromFeed(loadKey: String, ref: MatchRef, x: Facts, view: LeagueView?): MatchReport? {
        val item = view?.feed?.firstOrNull { it.ref?.let { r -> MatchRef.decode(r)?.whole } == ref } ?: return null
        val comp = item.comp ?: ref.comp
        val f = Fixture(comp, ref.id, item.a, item.b, 0L, label = item.label, week = item.week)
        val games = item.games.mapIndexed { i, g -> GameReport(i, "${i + 1}판", g.a, g.b, i % 2 == 0, g.aYacht, g.bYacht, g.aBonus, g.bBonus, null, null, null, null, null, null, emptyList()) }
        val won = games.sumOf { won(it) }
        val result = boardResult(games, null)
        return MatchReport(
            loadKey, ref.encode(), titleOf(null, f, ref.season), ReportState.PLAYED, item.a, item.b, item.aName, item.bName, null, null, null, null,
            if (games.isEmpty()) "" else "${num(won)} : ${num(games.size - won)}", result,
            listOf(BoardReport(maxOf(0, ref.board), item.a, item.b, item.aName, item.bName, games, games.sumOf { it.scoreA ?: 0 }, games.sumOf { it.scoreB ?: 0 }, result)),
            ReplaySource.NONE, false, false, if (x.current) NOT_FOUND else PAST, emptyList(), null, emptyList(), comp = comp, label = item.label,
            season = ref.season, week = item.week, deltaA = item.deltaA, deltaB = item.deltaB,
        )
    }

    private fun missing(loadKey: String, key: String) = MatchReport(
        loadKey, key, "경기 리포트", ReportState.PLAYED, "", "", "", "", null, null, null, null, "", 0, emptyList(), ReplaySource.NONE,
        false, false, NOT_FOUND, emptyList(), null, emptyList(),
    )

    /** The report with its games played back by [r]: per game the boxes, the win curve, the decisive turn, the 평점 and the drama tags. */
    private fun detailed(quick: MatchReport, r: Resolved): MatchReport {
        val est = host.estimate()
        val ratings = synchronized(host.lock) { host.world()?.let { w -> quick.boards.flatMap { listOf(it.a, it.b) }.associateWith { w.rating(it) } } }.orEmpty()
        // 평점 on the season's scale, as the stats rate it (a past season's game on this one's).
        val scale = synchronized(host.lock) { host.world()?.stats?.ratingScale } ?: RatingScale.FALLBACK
        val boards = quick.boards.map { bd ->
            val played = r.boards.firstOrNull { maxOf(0, it.log.ref.board) == bd.board } ?: return@map bd
            val log = played.log
            val games = log.games.mapIndexed { i, g -> gameReport(log, g, played.turns[i], est, ratings, scale) }
            // A past season's record may keep only some games: the stored ones not played back stay as they were.
            val all = (games + bd.games.orEmpty().filter { o -> games.none { it.index == o.index } }).sortedBy { it.index }
            // The stored result stands (it was decided by the season's format); a tie's from its games.
            bd.copy(games = all, totalA = all.sumOf { it.scoreA ?: 0 }, totalB = all.sumOf { it.scoreB ?: 0 }, result = if (bd.games != null) bd.result else boardResult(all, null))
        }
        return quick.copy(boards = boards, source = r.source, replayable = r.source != ReplaySource.NONE, loading = false, note = r.note)
    }

    /** A game of [f] as the season stores it (scores and flags, from side A); its label and first player from the deal. */
    private fun storedGame(f: Fixture, index: Int, s: GameScore, a: String, b: String, log: MatchLog?, format: yacht.engine.league.MatchFormat?): GameReport {
        val per = log?.perMatch ?: if (f.firstTo > 0 || format?.mirrored != false) 2 else 1
        val aFirst = runCatching { LeagueMatch.deal(f, maxOf(0, log?.ref?.board ?: 0), index, per).aFirst }.getOrDefault(index % 2 == 0)
        return GameReport(
            index, gameLabel(f, index, per, host.name(if (aFirst) a else b)), s.a, s.b, aFirst, s.aYacht, s.bYacht, s.aBonus, s.bBonus,
            null, null, null, null, null, null, emptyList(),
        )
    }

    private fun gameReport(
        log: MatchLog,
        g: LoggedGame,
        turns: List<WatchTurn>,
        est: yacht.engine.league.WinEstimate?,
        ratings: Map<String, Double>,
        scale: RatingScale,
    ): GameReport {
        val deal = log.deal(g.index)
        val last = turns.last()
        val (ba, bb) = if (deal.aFirst) last.first to last.second else last.second to last.first
        val facts = est?.let { runCatching { GameFacts.of(turns.map { t -> t.first to t.second }, it) }.getOrNull() }
        val first = if (deal.aFirst) log.a else log.b
        val second = if (deal.aFirst) log.b else log.a
        val f = log.fixture()
        val tags = facts?.let {
            val ctx = DramaContext(ratings[first] ?: Double.NaN, ratings[second] ?: Double.NaN, final = Drama.isFinal(f), decider = g.index >= matchGames(f, log.perMatch))
            Drama.of(it, ctx).tags
        }.orEmpty()
        val (ra, rb) = facts?.ratings(scale)?.let { (x, y) -> if (deal.aFirst) x to y else y to x } ?: (null to null)
        return GameReport(
            g.index, gameLabel(f, g.index, log.perMatch, host.name(first)), g.scoreA, g.scoreB, deal.aFirst,
            (ba[Category.YACHT] ?: 0) > 0, (bb[Category.YACHT] ?: 0) > 0, ba.bonus > 0, bb.bonus > 0,
            facts?.curve, facts?.decisiveHalfTurn, boxes(ba), boxes(bb), ra, rb, tags,
        )
    }

    /** "2 : 0" (games, a drawn game half), a series "3 : 1 (3선승)", a team match "보드 3 : 2"; and the winner (+1 A, -1 B, 0 none). */
    private fun resultOf(f: Fixture, m: SeasonMatch?, boards: List<BoardReport>, format: yacht.engine.league.MatchFormat?): Pair<String, Int> {
        val s = m?.score
        if (s != null) {
            val text = "${num(s.scoreA)} : ${num(s.scoreB)}"
            return when {
                f.kind == JobKind.TEAM -> "보드 $text"
                f.firstTo > 0 -> "$text (${f.firstTo}선승)"
                else -> text
            } to s.winner
        }
        if (boards.all { it.games == null }) return "" to 0
        if (f.kind == JobKind.TEAM) {
            val a = boards.count { it.result > 0 } + boards.count { it.result == 0 } / 2.0
            val b = boards.count { it.result < 0 } + boards.count { it.result == 0 } / 2.0
            return "보드 ${num(a)} : ${num(b)}" to a.compareTo(b)
        }
        val games = boards.first().games.orEmpty()
        val a = games.sumOf { g -> won(g) }
        val b = games.size - a
        return "${num(a)} : ${num(b)}" to boardResult(games, format)
    }

    private fun won(g: GameReport): Double {
        val a = g.scoreA ?: return 0.5
        val b = g.scoreB ?: return 0.5
        return if (a > b) 1.0 else if (a == b) 0.5 else 0.0
    }

    /** A board's winner from its games: more games won, else the totals. */
    private fun boardResult(games: List<GameReport>, format: yacht.engine.league.MatchFormat?): Int {
        val scores = games.mapNotNull { g -> if (g.scoreA != null && g.scoreB != null) GameScore(g.scoreA, g.scoreB) else null }
        if (scores.isEmpty()) return 0
        return if (format != null) TieRule.match(scores, format) else TieRule.match(scores)
    }

    // ---- the watch screen -----------------------------------------------------------------------------

    /** The watch screen's game, or null when nothing is watched. */
    var watch by mutableStateOf<WatchView?>(null)
        private set

    /** Matches watched on the channel this season (its picks are marked ✓), by ref text. */
    private val watched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val watchLock = Any()
    private var session: Session? = null
    private var loop: Job? = null
    private var loading: Job? = null

    /** One game to watch: who moved first, its turns, its score from side A, its facts. */
    private class WGame(
        val index: Int,
        val label: String,
        val first: String,
        val second: String,
        val aFirst: Boolean,
        val turns: List<WatchTurn>,
        val scoreA: Int,
        val scoreB: Int,
        val facts: GameFacts?,
        val decider: Boolean,
        val mirrored: Boolean,
    )

    /** One board of the match ([board] its number in the fixture, 0 for a one-on-one match) and its games as stored. */
    private class WBoard(val board: Int, val a: String, val b: String, val games: List<WGame>)

    /** A match to watch: its boards' games and what the broadcaster says around them. */
    private class WMatch(
        val kind: WatchKind,
        val ref: MatchRef?,
        val title: String?,
        val boards: List<WBoard>,
        val intro: List<String>,
        val firstTo: Int,
        val team: Boolean,
        val teamA: String?,
        val teamB: String?,
        /** A league match of a season with the 2-0 sweep bonus. */
        val bonus: Boolean,
        val ratings: Map<String, Double>,
        val source: ReplaySource,
        /** How its boards were decided: a knockout tie (level goes to side A), else by the season's [format] (null: not known). */
        val tie: Boolean = false,
        val format: yacht.engine.league.MatchFormat? = null,
    ) {
        /** [b]'s winner (+1 A, -1 B, 0 level) as the match decided it. */
        fun winner(b: WBoard): Int {
            val scores = b.games.map { GameScore(it.scoreA, it.scoreB) }
            return when {
                tie -> TieRule.match(scores).let { if (it == 0) 1 else it }
                format != null -> TieRule.match(scores, format)
                else -> TieRule.match(scores)
            }
        }
    }

    /** One game's steps as they are worked out ([done] once all are). */
    private class Steps {
        @Volatile var list: List<WatchStep> = emptyList()

        @Volatile var done = false
        var job: Job? = null
    }

    /** A session of the match [match] (pick [at] of [channel] on the broadcast channel). */
    private inner class Session(val match: WMatch, var board: Int, var game: Int, val channel: Channel?, val at: Int) {
        val steps = HashMap<Triple<Int, Int, Boolean>, Steps>()

        /** Ticks the end of the game shown, and its broadcaster's lines before it, have been held for. */
        var hold = 0
        var introHold = 0
        var summary = channel?.summary ?: false
    }

    /**
     * One run of the broadcast channel: its picks ([queue], refs in pick order), the ones shown or
     * passed over in this run ([done]), the ones watched before it (not played again, [skip]),
     * and whether it plays in 요약.
     */
    private class Channel(val queue: List<String>, val skip: Set<String>) {
        val done: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        @Volatile var summary = true

        /** Pick [i] is still to be shown in this run. */
        fun open(i: Int): Boolean = i !in done && queue[i] !in skip
    }

    /** The channel playing (under [watchLock]), and the pick it waits for or works out while no session plays. */
    private var channel: Channel? = null
    private var pending = 0

    /** Plays [key]'s match again (▶ 다시 보기) from game [game] of board [board] (-1: the key's board, else the first). */
    fun watch(key: String, board: Int = -1, game: Int = 0) {
        val full = MatchRef.decode(key) ?: return
        val ref = full.whole
        val at = if (board >= 0) board else maxOf(0, full.board)
        open(WatchKind.REPLAY, null)
        loading = host.scope.launch(Dispatchers.Default) {
            val m = matchOf(ref, WatchKind.REPLAY)
            synchronized(watchLock) {
                if (!isActive || watch?.kind != WatchKind.REPLAY || session != null) return@launch
                val match = m.first ?: return@launch fail(m.second ?: CHANGED)
                val i = match.boards.indexOfFirst { it.board == at }.takeIf { it >= 0 } ?: 0
                begin(Session(match, i, 0, null, 0), game)
            }
        }
    }

    /** Plays a highlight's game again: its log when kept, else from the genes it keeps. */
    fun watchHighlight(h: Highlight) {
        open(WatchKind.HIGHLIGHT, h.caption)
        loading = host.scope.launch(Dispatchers.Default) {
            val ref = MatchRef(h.season, h.fixture.comp, h.fixture.id, h.board)
            val job = coroutineContext[Job]
            val r = try {
                resolve(ref, h, snapshotFirst = true, cancelled = { job?.isActive == false })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resolved(ReplaySource.NONE, note = CORRUPT)
            }
            val played = r.boards.firstOrNull { maxOf(0, it.log.ref.board) == h.board }
            val i = played?.log?.games?.indexOfFirst { it.index == h.game } ?: -1
            synchronized(watchLock) {
                if (!isActive || watch?.kind != WatchKind.HIGHLIGHT || session != null) return@launch
                if (played == null || i < 0) return@launch fail(r.note ?: CHANGED)
                val g = wGame(played.log, played.log.games[i], played.turns[i], host.estimate())
                val m = WMatch(WatchKind.HIGHLIGHT, ref, h.caption, listOf(WBoard(h.board, h.a, h.b, listOf(g))), emptyList(), 0, false, null, null, false, emptyMap(), r.source)
                begin(Session(m, 0, 0, null, 0), 0)
            }
        }
    }

    /**
     * A rated friendly of [a] against [b] (🤝 친선 경기 보기): one game played now on a seeded deal
     * through the league's own code, recorded on the ladder as the watched games always were, logged
     * in memory, and shown throw by throw.
     */
    fun watchFriendly(a: String, b: String) {
        open(WatchKind.FRIENDLY, "🤝 친선 경기")
        loading = host.scope.launch(Dispatchers.Default) {
            val m = runCatching { friendly(a, b) }.getOrNull()
            synchronized(watchLock) {
                // Left (or another friendly asked for) meanwhile: this one is on the ladder all the same.
                if (!isActive || watch?.kind != WatchKind.FRIENDLY || session != null) return@launch
                if (m == null) return@launch fail("친선 경기를 둘 수 없어요. 선수가 리그에 없어요.")
                begin(Session(m, 0, 0, null, 0), 0)
            }
            host.publish()
            host.requestSave()
        }
    }

    private fun friendly(a: String, b: String): WMatch? {
        val t = host.tables() ?: return null
        val x = host.ai(a) ?: return null
        val y = host.ai(b) ?: return null
        val job = Fixture(Comp.FRIENDLY, -1, a, b, Random().nextLong(), label = LeagueController.WATCH_LABEL)
        val deal = LeagueMatch.deal(job, 0, 0, 1)
        // A 상대 맞춤 side reads its opponent's record as it stands now.
        val models = mapOf(a to (if (host.adaptive(b)) host.oppModel(a) else null), b to (if (host.adaptive(a)) host.oppModel(b) else null))
        val g = LeagueMatch.playGame(t.rules, deal, x, y, host.estimate(), watch = true, scout = { models[it] })
        val ratings = synchronized(host.lock) {
            val w = host.world() ?: return null
            val l = w.ladder
            if (l.entry(a) != null && l.entry(b) != null) {
                l.record(a, b, listOf(g.score), comp = Comp.FRIENDLY, label = LeagueController.WATCH_LABEL, season = w.plan?.number ?: 0)
            }
            w.plan?.let { p ->
                ensure(w, p)
                val log = MatchLog(
                    MatchRef(p.number, Comp.FRIENDLY, -(++friendlies), 0), JobKind.MATCH, 1, 1, 0, job.seed, a, b, null, null, job.label, 0,
                    LogClass.FRIENDLY.bit, 0, listOf(LoggedGame(0, g.score.a, g.score.b, GameLogs.of(g.turns, deal))),
                )
                book.put(log)
            }
            listOf(a, b).associateWith { w.rating(it) }
        }
        val log = MatchLog(MatchRef(0, Comp.FRIENDLY, -1, 0), JobKind.MATCH, 1, 1, 0, job.seed, a, b, null, null, job.label, 0, 0, 0, listOf(LoggedGame(0, g.score.a, g.score.b, ByteArray(0))))
        val game = wGame(log, log.games[0], g.turns, host.estimate())
        return WMatch(WatchKind.FRIENDLY, null, "🤝 친선 경기", listOf(WBoard(0, a, b, listOf(game))), emptyList(), 0, false, null, null, false, ratings, ReplaySource.NONE)
    }

    /**
     * '📺 이번 주 중계' from pick [from] (a ref; null: the first played pick not yet watched): the
     * played picks in pick order, each followed by a countdown to the next, then the ones still to
     * be played, waited for ("곧 시작해요"); picks watched before are passed over. 요약 중계 by default.
     */
    fun startBroadcast(from: String? = null) {
        open(WatchKind.BROADCAST, "📺 이번 주 중계")
        loading = host.scope.launch(Dispatchers.Default) {
            val v = synchronized(host.lock) { runCatching { broadcastView() }.getOrNull() }
            host.scope.launch { broadcast = v }
            val picks = v?.picks.orEmpty()
            if (picks.isEmpty()) {
                synchronized(watchLock) { if (watch?.kind == WatchKind.BROADCAST && session == null) fail("이번 주 중계할 경기가 아직 없어요.") }
                return@launch
            }
            val queue = picks.map { it.ref }
            val before = picks.filter { it.watched && it.ref != from }.map { it.ref }.toSet()
            // Every pick watched already: the channel plays them all again.
            val ch = Channel(queue, if (queue.all { it in before }) emptySet() else before)
            val start = from?.let { queue.indexOf(it) }?.takeIf { it >= 0 } ?: synchronized(host.lock) { nextOf(ch, -1) } ?: 0
            synchronized(watchLock) {
                if (!isActive || watch?.kind != WatchKind.BROADCAST || session != null) return@launch
                channel = ch
            }
            playPick(ch, start)
        }
    }

    /**
     * [ch]'s next pick after pick [after] (under the lock): the first one still to be shown that
     * is played (pick order, from [after] round to it), else the first one still to be played;
     * null when every pick was shown.
     */
    private fun nextOf(ch: Channel, after: Int): Int? {
        val p = host.world()?.plan
        return BroadcastPicks.next(ch.queue.size, after, ch::open) { playedNow(p, ch.queue[it]) }
    }

    /** True when the match of [key] is played (one of a season closed since counts as played). */
    private fun playedNow(p: SeasonPlan?, key: String): Boolean {
        val ref = MatchRef.decode(key)?.whole ?: return false
        if (p == null) return false
        if (ref.season != p.number) return ref.season < p.number
        return SeasonMatches.of(p, ref)?.played == true
    }

    /**
     * Pick [first] of [ch]: played, it is worked out and shown; not yet, the screen waits for it
     * (and says how to get it played), unless another pick still to be shown is played by then. A
     * pick that cannot be shown is passed over (its reason told when no pick is left).
     */
    private suspend fun playPick(ch: Channel, first: Int) {
        val me = currentCoroutineContext()[Job]
        var i = first
        while (true) {
            while (true) {
                val (played, other) = synchronized(host.lock) {
                    val p = host.world()?.plan
                    val now = playedNow(p, ch.queue[i])
                    now to (if (now) null else nextOf(ch, i)?.takeIf { it != i && playedNow(p, ch.queue[it]) })
                }
                if (played) break
                if (other != null) {
                    i = other
                    continue
                }
                synchronized(watchLock) {
                    if (me?.isActive == false || !waits(ch, i, if (host.running()) "곧 시작해요 · 지금 ${host.view()?.live?.size ?: 0}경기 진행 중" else UNPLAYED_PICK)) return
                }
                delay(1_000)
            }
            // Played: worked out (a re-simulation may take seconds), then shown.
            synchronized(watchLock) { if (me?.isActive == false || !waits(ch, i, COMPUTING)) return }
            val m = matchOf(MatchRef.decode(ch.queue[i])?.whole ?: return, WatchKind.BROADCAST)
            val shown = synchronized(watchLock) {
                if (me?.isActive == false || watch?.kind != WatchKind.BROADCAST || channel !== ch) return
                ch.done += i
                val match = m.first ?: return@synchronized false
                watched += ch.queue[i]
                begin(Session(match, 0, 0, ch, i), 0)
                true
            }
            if (shown) break
            i = synchronized(host.lock) { nextOf(ch, i) } ?: return synchronized(watchLock) { if (me?.isActive != false && channel === ch) fail(m.second ?: CHANGED) }
        }
        refreshPicks()
    }

    /** The screen waits for pick [i] of [ch] ([text]: why), no game shown; false when the channel was left (under [watchLock]). */
    private fun waits(ch: Channel, i: Int, text: String): Boolean {
        val w = watch ?: return false
        if (w.kind != WatchKind.BROADCAST || channel !== ch) return false
        session?.steps?.values?.forEach { it.job?.cancel() }
        session = null
        pending = i
        watch = w.copy(
            waiting = text, steps = emptyList(), shown = 0, complete = false, message = null, ended = false,
            queue = BroadcastQueue(i, ch.queue.size, null, null), ref = MatchRef.decode(ch.queue[i])?.whole?.encode(),
        )
        return true
    }

    /** The match of [ref] to watch, resolved and named; or why it cannot be shown. */
    private suspend fun matchOf(ref: MatchRef, kind: WatchKind): Pair<WMatch?, String?> {
        // The screen left (or another match asked for): the re-simulation stops and nothing is shown.
        val job = currentCoroutineContext()[Job]
        val r = try {
            resolve(ref, cancelled = { job?.isActive == false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resolved(ReplaySource.NONE, note = CORRUPT)
        }
        if (r.source == ReplaySource.NONE || r.boards.isEmpty()) return null to (r.note ?: CHANGED)
        val est = host.estimate()
        return synchronized(host.lock) {
            val w = host.world() ?: return null to NOT_FOUND
            val logs = r.boards.sortedBy { it.log.ref.board }
            val any = logs.first().log
            val f = any.fixture()
            val team = f.kind == JobKind.TEAM
            val boards = logs.map { pb -> WBoard(maxOf(0, pb.log.ref.board), pb.log.a, pb.log.b, pb.log.games.mapIndexed { i, g -> wGame(pb.log, g, pb.turns[i], est) }) }
            val keys = boards.flatMap { listOf(it.a, it.b) }
            val ratings = keys.associateWith { w.rating(it) }
            val p = w.plan
            val bonus = f.comp == Comp.LEAGUE && p?.number == ref.season && p.league.bonus
            WMatch(
                kind, ref, titleOf(p, f, ref.season), boards, intro(w, f, ref, boards, ratings, kind), f.firstTo, team,
                any.teamA?.let { teamName(it) }, any.teamB?.let { teamName(it) }, bonus, ratings, r.source,
                tie = f.kind == JobKind.TIE, format = p?.takeIf { it.number == ref.season }?.config?.matchFormat,
            ) to null
        }
    }

    private fun wGame(log: MatchLog, g: LoggedGame, turns: List<WatchTurn>, est: yacht.engine.league.WinEstimate?): WGame {
        val deal = log.deal(g.index)
        val f = log.fixture()
        val first = if (deal.aFirst) log.a else log.b
        val second = if (deal.aFirst) log.b else log.a
        val facts = est?.let { runCatching { GameFacts.of(turns.map { t -> t.first to t.second }, it) }.getOrNull() }
        return WGame(
            g.index, gameLabel(f, g.index, log.perMatch, host.name(first)), first, second, deal.aFirst, turns, g.scoreA, g.scoreB, facts,
            g.index >= matchGames(f, log.perMatch), log.perMatch == 2,
        )
    }

    /** The broadcaster's lines before the match: what it is, both sides, the expected winner, the stakes. */
    private fun intro(w: LeagueWorld, f: Fixture, ref: MatchRef, boards: List<WBoard>, ratings: Map<String, Double>, kind: WatchKind): List<String> {
        if (kind == WatchKind.HIGHLIGHT || kind == WatchKind.FRIENDLY) return emptyList()
        val team = f.kind == JobKind.TEAM
        val a = if (team) teamName(f.a) else host.name(f.a)
        val b = if (team) teamName(f.b) else host.name(f.b)
        val p = w.plan?.takeIf { it.number == ref.season }
        val title = titleOf(w.plan, f, ref.season)
        val out = ArrayList<String>()
        out += (if (kind == WatchKind.BROADCAST) "📺 " else "▶ ") + "$title — $a 대 $b"
        if (!team) {
            for (k in listOf(f.a, f.b)) {
                val s = p?.league
                val d = s?.divisionOf(k)
                val where = d?.let { div -> s.table(div).indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { "${s.leagues.label(div)} ${it + 1}위" } }
                val r = ratings[k]?.takeIf { !it.isNaN() }?.roundToInt()
                listOfNotNull(where, r?.let { "레이팅 $it" }).takeIf { it.isNotEmpty() }?.let { out += "${host.name(k)}: ${it.joinToString(" · ")}" }
            }
            val ra = ratings[f.a]?.takeIf { !it.isNaN() }
            val rb = ratings[f.b]?.takeIf { !it.isNaN() }
            if (ra != null && rb != null) out += "예상 승률 $a ${(100 * Preview.expected(ra, rb)).roundToInt()}%"
            host.preview(f.a, f.b, f)?.stakes?.take(2)?.forEach { out += "🔥 $it" }
            val clubs = w.clubs
            val club = clubs?.clubOf(f.a)
            if (club != null && club == clubs.clubOf(f.b)) out += "🤝 같은 클럽 맞대결이에요."
        } else if (boards.size > 1) {
            out += "보드 ${boards.size}개로 겨뤄요. 1번 보드부터 차례로 보여 드려요."
        }
        return out
    }

    /** Opens the watch screen on [kind] while its match is worked out. */
    private fun open(kind: WatchKind, title: String?) {
        stop()
        watch = WatchView(
            "", "", "", "", emptyList(), false, 0, true, title, kind = kind,
            pace = host.watchPace(), waiting = if (kind == WatchKind.FRIENDLY) "경기를 준비하는 중…" else COMPUTING,
        )
    }

    /** The watch screen says why the match cannot be shown (under [watchLock]). */
    private fun fail(note: String) {
        session = null
        loop?.cancel()
        watch = watch?.copy(message = note, playing = false, complete = true, waiting = null)
    }

    /** Starts [s] at its board's game of index [game] (its first when not stored) (under [watchLock]). */
    private fun begin(s: Session, game: Int) {
        session = s
        val games = s.match.boards[s.board].games
        show(s, s.board, games.indexOfFirst { it.index == game }.takeIf { it >= 0 } ?: 0, playing = true)
        startLoop()
    }

    /** Shows game [game] of board [board] from its start (under [watchLock]); its steps (and the next game's) are worked out meanwhile. */
    private fun show(s: Session, board: Int, game: Int, playing: Boolean) {
        s.board = board
        s.game = game
        s.hold = 0
        s.introHold = 0
        val m = s.match
        val bd = m.boards[board]
        val g = bd.games[game]
        val short = short(s, board, game)
        val st = steps(s, board, game, short)
        // The next game's steps meanwhile (the next board's first after a board's last).
        if (game < bd.games.lastIndex) steps(s, board, game + 1, short(s, board, game + 1))
        else if (board < m.boards.lastIndex) steps(s, board + 1, 0, short(s, board + 1, 0))
        val names = host.name(g.first) to host.name(g.second)
        watch = WatchView(
            g.first, g.second, names.first, names.second, st.list, st.done, 0, playing,
            title = m.title, kind = m.kind, ref = m.ref?.encode(), game = g.index,
            games = bd.games.map { WatchGameInfo(it.index, it.label, it.first, it.second, it.scoreA, it.scoreB) },
            board = board, boards = m.boards.size,
            boardLines = if (m.boards.size > 1) m.boards.map { b -> "${b.board + 1}번 보드 ${host.name(b.a)} ${boardScore(b)} ${host.name(b.b)}" } else emptyList(),
            matchScore = matchScore(m, board, game),
            intro = (if (board == 0 && game == 0) m.intro else emptyList()) + gameIntro(m, bd, g),
            outro = outro(m, board, game),
            summary = short, queue = s.channel?.let { ch -> BroadcastQueue(s.at, ch.queue.size, null, null) }, pace = host.watchPace(),
        )
    }

    /**
     * 요약 중계 shows game [game] of board [board] short (its last [SUMMARY_HALF_TURNS] half-turns):
     * every game but the one that decides the match, a team match's last game of its last board.
     */
    private fun short(s: Session, board: Int, game: Int): Boolean {
        if (!s.summary) return false
        val m = s.match
        val last = game == m.boards[board].games.lastIndex
        return !(last && (!m.team || board == m.boards.lastIndex))
    }

    /** [b]'s games won so far as "2:1". */
    private fun boardScore(b: WBoard): String {
        val a = b.games.sumOf { if (it.scoreA > it.scoreB) 1.0 else if (it.scoreA == it.scoreB) 0.5 else 0.0 }
        return "${num(a)}:${num(b.games.size - a)}"
    }

    /** Games won by side A of [games] (a level game half). */
    private fun wonA(games: List<WGame>): Double = games.sumOf { if (it.scoreA > it.scoreB) 1.0 else if (it.scoreA == it.scoreB) 0.5 else 0.0 }

    /** Boards won by side A of [boards] (a level board half). */
    private fun boardsA(m: WMatch, boards: List<WBoard>): Double = boards.sumOf { b -> m.winner(b).let { if (it > 0) 1.0 else if (it == 0) 0.5 else 0.0 } }

    /**
     * The match before game [game] of board [board], with the sides: "김민준 1 : 0 이서연", "시리즈 김민준 2 : 1
     * 이서연", "보드 대한민국 2 : 1 일본"; null before anything is decided.
     */
    private fun matchScore(m: WMatch, board: Int, game: Int): String? {
        if (m.team) {
            if (board == 0) return null
            val a = boardsA(m, m.boards.take(board))
            return "보드 ${m.teamA ?: ""} ${num(a)} : ${num(board - a)} ${m.teamB ?: ""}".replace("  ", " ")
        }
        if (game == 0) return null
        val bd = m.boards[board]
        val a = wonA(bd.games.take(game))
        return (if (m.firstTo > 0) "시리즈 " else "") + "${host.name(bd.a)} ${num(a)} : ${num(game - a)} ${host.name(bd.b)}"
    }

    /** The line before a game: who moves first, and that both get the same dice (a mirrored pair). */
    private fun gameIntro(m: WMatch, bd: WBoard, g: WGame): List<String> {
        val first = host.name(g.first)
        val out = ArrayList<String>()
        if (m.team && g.index == 0) out += "${bd.board + 1}번 보드 · ${host.name(bd.a)} 대 ${host.name(bd.b)}"
        out += when {
            g.decider -> "${Ko.ieyo(g.label)}."
            g.mirrored && g.index % 2 == 1 -> "${g.index + 1}판은 자리를 바꿔 ${Ko.ieyo("$first 선공")}."
            g.mirrored -> "${g.index + 1}판 · $first 선공. 두 선수 모두 같은 주사위를 받아요."
            else -> "${Ko.ieyo(g.label)}."
        }
        return out
    }

    /** The lines after game [game] of board [board]: who took it, the match so far, and at the end the result and what it meant. */
    private fun outro(m: WMatch, board: Int, game: Int): List<String> {
        val bd = m.boards[board]
        val g = bd.games[game]
        val out = ArrayList<String>()
        val (wName, ws, ls) = when {
            g.scoreA > g.scoreB -> Triple(host.name(bd.a), g.scoreA, g.scoreB)
            g.scoreB > g.scoreA -> Triple(host.name(bd.b), g.scoreB, g.scoreA)
            else -> Triple(null, g.scoreA, g.scoreB)
        }
        if (m.kind == WatchKind.HIGHLIGHT || m.kind == WatchKind.FRIENDLY) {
            out += if (wName == null) "무승부 $ws : $ls" else "🏆 $wName 승리! $ws : $ls"
            out += if (m.kind == WatchKind.HIGHLIGHT) "하이라이트 다시보기는 기록에 들어가지 않아요." else "이 친선 경기는 레이팅에 들어갔어요."
            return out
        }
        val n = if (g.decider) g.label.substringBefore(" ·") else "${g.index + 1}판"
        out += if (wName == null) "${Ko.eun(n)} $ws:$ls 무승부예요." else "${Ko.eun(n)} ${Ko.i(wName)} ${Ko.ro("$ws:$ls")} 가져갔어요."
        val lastGame = game == bd.games.lastIndex
        if (m.firstTo > 0 && !lastGame) {
            val a = wonA(bd.games.take(game + 1))
            out += "시리즈 ${host.name(bd.a)} ${num(a)} : ${num(game + 1 - a)} ${host.name(bd.b)}"
        }
        if (!lastGame) return out
        if (m.team && board < m.boards.lastIndex) {
            out += "${bd.board + 1}번 보드는 " + when (m.winner(bd)) {
                1 -> "${Ko.i(host.name(bd.a))} 이겼어요."
                -1 -> "${Ko.i(host.name(bd.b))} 이겼어요."
                else -> "비겼어요."
            }
            return out
        }
        // The match is over: the winner's score first.
        val aName = if (m.team) m.teamA ?: host.name(bd.a) else host.name(bd.a)
        val bName = if (m.team) m.teamB ?: host.name(bd.b) else host.name(bd.b)
        val winner: Int
        val (x, y) = if (m.team) {
            val a = boardsA(m, m.boards)
            winner = a.compareTo(m.boards.size - a)
            a to m.boards.size - a
        } else {
            winner = m.winner(bd)
            wonA(bd.games).let { it to bd.games.size - it }
        }
        val (tx, ty) = bd.games.sumOf { it.scoreA } to bd.games.sumOf { it.scoreB }
        fun score(swap: Boolean): String {
            val (p, q) = if (swap) y to x else x to y
            val (tp, tq) = if (swap) ty to tx else tx to ty
            return (if (m.team) "보드 " else "") + "${num(p)} : ${num(q)}" + if (!m.team && bd.games.size > 1) " (합계 $tp : $tq)" else ""
        }
        out += when {
            winner > 0 -> "🏆 $aName 승리! ${score(false)}"
            winner < 0 -> "🏆 $bName 승리! ${score(true)}"
            else -> "무승부 ${score(false)}"
        }
        if (!m.team && winner != 0) {
            val w = if (winner > 0) bd.a else bd.b
            val l = if (winner > 0) bd.b else bd.a
            if (m.bonus && bd.games.size == 2 && bd.games.all { if (winner > 0) it.scoreA > it.scoreB else it.scoreB > it.scoreA }) out += "2:0 완승으로 승점 ${WIN_POINTS + 1}점을 챙겨요."
            val gap = (m.ratings[l] ?: Double.NaN) - (m.ratings[w] ?: Double.NaN)
            if (!gap.isNaN() && gap >= UPSET_GAP) out += "레이팅 ${gap.roundToInt()}점 차를 뒤집은 이변이에요!"
            // The winner's lowest chance in the deciding game it won.
            val dec = bd.games.lastOrNull { if (winner > 0) it.scoreA > it.scoreB else it.scoreB > it.scoreA }
            val minP = dec?.facts?.winnerMinP
            if (minP != null && minP <= COMEBACK_P) out += "승리 확률이 ${(100 * minP).roundToInt()}%까지 떨어졌다가 뒤집었어요!"
        }
        out += "실제 경기를 그대로 다시 둔 거예요. 기록에는 다시 들어가지 않아요."
        return out
    }

    /** [s]'s steps of a game ([short]: its last [SUMMARY_HALF_TURNS] only), worked out once off the main thread. */
    private fun steps(s: Session, board: Int, game: Int, short: Boolean): Steps {
        val k = Triple(board, game, short)
        s.steps[k]?.let { return it }
        val st = Steps()
        s.steps[k] = st
        val g = s.match.boards[board].games[game]
        st.job = host.scope.launch(Dispatchers.Default) {
            val from = if (short) maxOf(0, g.turns.size - SUMMARY_HALF_TURNS) else 0
            runCatching { stepsOf(g, from) { list, done -> publishSteps(s, k, st, list, done) } }
                .onFailure { if (it !is CancellationException) publishSteps(s, k, st, st.list, true) }
        }
        return st
    }

    private fun publishSteps(s: Session, k: Triple<Int, Int, Boolean>, st: Steps, list: List<WatchStep>, done: Boolean) {
        synchronized(watchLock) {
            st.list = list
            st.done = done
            if (session !== s || s.board != k.first || s.game != k.second) return
            val w = watch ?: return
            if (w.summary != k.third) return
            watch = w.copy(steps = list, complete = done)
        }
    }

    /**
     * [g]'s steps from half-turn [from]: each throw with the mover's plan (depth 1), its commentary
     * and the first player's P(win) after it, as the watch screen always showed them; [out] gets
     * the steps so far after each turn.
     */
    private suspend fun stepsOf(g: WGame, from: Int, out: (List<WatchStep>, Boolean) -> Unit) {
        val t = host.tables() ?: return out(emptyList(), true)
        val advisor = Advisor(t.withoutRisk())
        val outlook = OutlookAnalysis(t)
        val talk = Commentary(t)
        val firstName = host.name(g.first)
        val secondName = host.name(g.second)
        val turns = g.turns
        var x = turns.getOrNull(from - 1)?.first ?: Board(t.rules)
        var y = turns.getOrNull(from - 1)?.second ?: Board(t.rules)
        val steps = ArrayList<WatchStep>()
        for (i in from until turns.size) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return
            val turn = turns[i]
            val (mover, other) = if (turn.side == 0) x to y else y to x
            val moverName = if (turn.side == 0) firstName else secondName
            val waitingName = if (turn.side == 0) secondName else firstName
            val made = runCatching {
                val plan = advisor.planTurn(mover, other, Mode.WIN, depth = 1)
                val otherWin = (1 - plan.startValue).coerceIn(0.0, 1.0)
                val after = outlook.winAfter(other, mover, otherWin)
                talk.aiSteps(
                    plan, mover, turn.throws, turn.held, turn.category, after?.let { w -> { c: Board -> w(c) } }, otherWin,
                    judge = true, who = moverName, viewer = waitingName,
                )
            }.getOrNull() ?: turn.throws.mapIndexed { k, d ->
                val end = k == turn.throws.size - 1
                AiStep(k + 1, d.sorted(), turn.held.getOrNull(k), if (end) turn.category else null, if (end) turn.points else 0, null, emptyList())
            }
            for (st in made) {
                // myWin is the waiting side's P(win): as the first player's.
                val winFirst = st.myWin?.let { if (turn.side == 0) 1 - it else it }
                val end = st.category != null
                steps += WatchStep(
                    i / 2 + 1, turn.side, st.throwNo, st.dice, st.held, st.category, st.points, winFirst,
                    st.lines.filter { !it.tag.contains("승률") }, if (end) turn.first else x, if (end) turn.second else y,
                )
            }
            x = turn.first
            y = turn.second
            out(steps.toList(), i == turns.size - 1)
        }
    }

    private fun startLoop() {
        loop?.cancel()
        loop = host.scope.launch {
            while (isActive) {
                if (!tick()) break
                delay(maxOf(MIN_STEP_MS, host.watchPace().ms))
            }
        }
    }

    /** One step of the screen: the next throw, or at a game's end (held for a moment) the next game, board or pick. */
    private fun tick(): Boolean = synchronized(watchLock) {
        val s = session ?: return false
        val w = watch ?: return false
        if (!w.playing) return false
        val st = s.steps[Triple(s.board, s.game, w.summary)] ?: return true
        val steps = st.list
        when {
            // The lines before the game stay up a step for each.
            w.shown == 0 && s.introHold < w.intro.size -> s.introHold++
            w.shown < steps.size -> watch = w.copy(steps = steps, complete = st.done, shown = w.shown + 1)
            !st.done -> watch = w.copy(steps = steps)
            s.hold < HOLD_TICKS -> {
                s.hold++
                watch = w.copy(steps = steps, complete = true)
            }
            else -> return advance(s)
        }
        true
    }

    /** After a game: the board's next game, the next board, or the match's end (on the channel: the next pick after a countdown). */
    private fun advance(s: Session): Boolean {
        val m = s.match
        val bd = m.boards[s.board]
        if (m.kind == WatchKind.REPLAY || m.kind == WatchKind.BROADCAST) {
            if (s.game < bd.games.lastIndex) {
                show(s, s.board, s.game + 1, playing = true)
                return true
            }
            if (m.team && s.board < m.boards.lastIndex) {
                show(s, s.board + 1, 0, playing = true)
                return true
            }
        }
        val ch = s.channel
        if (ch != null && ch.queue.indices.any(ch::open)) {
            countdown(s, ch)
            return false
        }
        val w = watch ?: return false
        watch = w.copy(playing = false, ended = true, outro = w.outro + if (ch != null) listOf(channelEnd()) else emptyList())
        return false
    }

    /** "이번 주 중계가 끝났어요. 다음 주 빅매치는 3경기예요." (next week's count once some are picked). */
    private fun channelEnd(): String {
        val coming = broadcast?.coming?.size ?: 0
        return "이번 주 중계가 끝났어요." + if (coming > 0) " 다음 주 빅매치는 ${coming}경기예요." else ""
    }

    /** The 4-second card before the channel's next pick ("지금 보기 ⏭⏭" skips it, "멈추기" holds it). */
    private fun countdown(s: Session, ch: Channel) {
        loop?.cancel()
        loop = host.scope.launch {
            val next = withContext(Dispatchers.Default) { synchronized(host.lock) { runCatching { nextOf(ch, s.at)?.let { it to nextTitle(ch.queue[it]) } }.getOrNull() } }
            for (sec in COUNTDOWN downTo 1) {
                synchronized(watchLock) {
                    if (session !== s) return@launch
                    watch = watch?.copy(playing = true, queue = BroadcastQueue(s.at, ch.queue.size, next?.second, sec))
                }
                delay(1_000)
            }
            synchronized(watchLock) {
                if (session !== s) return@launch
                val i = next?.first ?: return@launch run {
                    watch = watch?.let { w -> w.copy(playing = false, ended = true, queue = BroadcastQueue(s.at, ch.queue.size, null, null), outro = w.outro + channelEnd()) }
                }
                loading = host.scope.launch(Dispatchers.Default) { playPick(ch, i) }
            }
        }
    }

    /** "요트컵 8강, 김민준 대 이서연이에요": the countdown card's next match. */
    private fun nextTitle(key: String): String? {
        val ref = MatchRef.decode(key) ?: return null
        val p = host.world()?.plan ?: return null
        val f = SeasonMatches.of(p, ref)?.fixture ?: return null
        val team = f.kind == JobKind.TEAM || f.comp.byTeams
        fun side(k: String) = if (team) teamName(k) else host.name(k)
        return "${titleOf(p, f, ref.season)}, ${side(f.a)} 대 ${Ko.ieyo(side(f.b))}"
    }

    /** Pauses the game (the steps go on being worked out). */
    fun pause() {
        synchronized(watchLock) {
            loop?.cancel()
            // A countdown to the channel's next pick stops too (▶ 재생 starts it again).
            watch = watch?.let { w -> w.copy(playing = false, queue = w.queue?.copy(countdown = null)) }
        }
    }

    /** Plays on from the step shown. */
    fun resume() {
        synchronized(watchLock) {
            val w = watch ?: return
            if (w.playing || w.ended || session == null) return
            watch = w.copy(playing = true)
            startLoop()
        }
    }

    /** Jumps to the end of the turn shown (or of the next one). */
    fun skipTurn() {
        synchronized(watchLock) {
            val w = watch ?: return
            var i = w.shown
            while (i < w.steps.size && w.steps[i].category == null) i++
            watch = w.copy(shown = minOf(w.steps.size, i + 1))
        }
    }

    /** The board's next game now (the end of the match after the last). */
    fun nextGame() {
        synchronized(watchLock) {
            val s = session ?: return
            val bd = s.match.boards[s.board]
            when {
                s.game < bd.games.lastIndex -> show(s, s.board, s.game + 1, watch?.playing ?: true)
                s.match.team && s.board < s.match.boards.lastIndex -> show(s, s.board + 1, 0, watch?.playing ?: true)
                else -> {
                    val st = s.steps[Triple(s.board, s.game, watch?.summary == true)]
                    watch = watch?.copy(shown = st?.list?.size ?: 0, complete = st?.done ?: false)
                    s.hold = HOLD_TICKS
                    return
                }
            }
            if (watch?.playing == true) startLoop()
        }
    }

    /** The channel's next pick now (⏭⏭). */
    fun nextMatch() {
        synchronized(watchLock) {
            val ch = channel ?: return
            if (watch?.kind != WatchKind.BROADCAST) return
            val s = session
            val at = s?.at ?: pending
            // The last pick plays on (its end says the channel is over).
            if (s != null && ch.queue.indices.none(ch::open)) return
            loop?.cancel()
            // A pick waited for (or being worked out) is passed over.
            if (s == null) {
                loading?.cancel()
                ch.done += at
                if (ch.queue.indices.none(ch::open)) return fail(channelEnd())
            }
            loading = host.scope.launch(Dispatchers.Default) {
                val i = synchronized(host.lock) { nextOf(ch, at) } ?: return@launch
                playPick(ch, i)
            }
        }
    }

    /** Game [index] of the board shown, from its start. */
    fun pickGame(index: Int) {
        synchronized(watchLock) {
            val s = session ?: return
            val i = s.match.boards[s.board].games.indexOfFirst { it.index == index }.takeIf { it >= 0 } ?: return
            show(s, s.board, i, watch?.playing ?: true)
            if (watch?.playing == true) startLoop()
        }
    }

    /** Board [board] of a team match, from its first game. */
    fun pickBoard(board: Int) {
        synchronized(watchLock) {
            val s = session ?: return
            if (board !in s.match.boards.indices) return
            show(s, board, 0, watch?.playing ?: true)
            if (watch?.playing == true) startLoop()
        }
    }

    /** The watch screen's pace (saved in ui.txt). */
    fun setPace(p: WatchPace) {
        host.saveWatchPace(p)
        synchronized(watchLock) { watch = watch?.copy(pace = p) }
    }

    /** 요약 중계 (true) or every game in full: from the next game on. */
    fun setSummary(on: Boolean) {
        synchronized(watchLock) {
            channel?.summary = on
            session?.summary = on
        }
    }

    /** Whether the channel plays its games in 요약 (the current run's setting). */
    val summaryOn: Boolean get() = synchronized(watchLock) { channel?.summary ?: true }

    /** Leaves the watch screen: nothing goes on being played or worked out. */
    fun stop() {
        synchronized(watchLock) {
            loop?.cancel()
            loading?.cancel()
            session?.steps?.values?.forEach { it.job?.cancel() }
            session = null
            channel = null
            watch = null
        }
    }

    // ---- the broadcast channel -------------------------------------------------------------------------

    /** '📺 이번 주 중계' as last worked out ([refreshPicks]). */
    var broadcast by mutableStateOf<BroadcastView?>(null)
        private set

    /** Makes this week's picks when they are not made yet, and works out the channel's view ([broadcast]) off the main thread. */
    fun refreshPicks() {
        host.scope.launch(Dispatchers.Default) {
            val v = synchronized(host.lock) { runCatching { broadcastView() }.getOrNull() }
            host.scope.launch { broadcast = v }
        }
    }

    /**
     * The picks of the week in play and the next (under the lock; UX 3.7): made once each time the
     * week moves, from the season's matches of those weeks and the ratings then; a week's picks
     * only grow, so next week's picks made now are that week's first.
     */
    private fun ensurePicks(w: LeagueWorld, p: SeasonPlan) {
        val at = p.number to p.week
        if (pickedAt == at) return
        pickedAt = at
        if (candidatesSeason != p.number) {
            candidates.clear()
            candidatesSeason = p.number
        }
        val saved = book.picks.toMap()
        val out = ArrayList<Pair<Int, List<MatchRef>>>()
        for (week in listOf(p.week, p.week + 1)) {
            val cands = runCatching { candidatesOf(w, p, week) }.getOrDefault(emptyList())
            candidates[week] = cands
            val before = saved[week].orEmpty()
            val already = before.map { ref -> cands.firstOrNull { it.ref == ref } ?: BroadcastPicks.placeholder(ref, week) }
            out += week to (before + BroadcastPicks.choose(cands, already).map { it.ref })
        }
        book.picks = out
    }

    /** The channel's candidates of [week]: the season's matches of that week, with what their score reads (under the lock). */
    private fun candidatesOf(w: LeagueWorld, p: SeasonPlan, week: Int): List<BroadcastPicks.Candidate> {
        val s = p.league
        val favs = host.favourites()
        val players = favs.filter { ':' !in it }.toSet()
        val followedClubs = favs.filter { it.startsWith(LeagueSelector.CLUB) }.map { it.removePrefix(LeagueSelector.CLUB) }.toSet()
        val clubs = w.clubs
        val ranked = w.members.map { it to w.rating(it) }.filter { !it.second.isNaN() }.sortedByDescending { it.second }
        val rankOf = HashMap<String, Int>(ranked.size * 2).also { m -> ranked.forEachIndexed { i, (k, _) -> m[k] = i } }
        val n = maxOf(1, ranked.size)
        fun pct(k: String) = (rankOf[k] ?: n).toDouble() / n
        // The top-3 clubs of each league and the members they count (a national season's club table).
        val counted = HashMap<String, Set<String>>()
        if (s.leagues.national && clubs != null) {
            runCatching {
                val honours = w.honours.all.filter { it.season == p.number - 1 }
                for (lg in s.leagues.list) for (row in yacht.engine.league.ClubTable.of(s, clubs, lg.id, honours).take(CLUB_TOP)) counted[row.club] = row.counted.toSet()
            }
        }
        val race = RaceTable(s)
        return SeasonMatches.ofWeek(p, week).mapNotNull { m ->
            val f = m.fixture
            if (f.comp == Comp.FRIENDLY || f.comp == Comp.LAB) return@mapNotNull null
            val team = f.kind == JobKind.TEAM
            val keys = if (team) f.lineupA + f.lineupB else listOf(f.a, f.b)
            // Played before it could be picked, with a side that adapts and no log kept: it could not be shown.
            if (m.played && keys.any { host.adaptive(it) } && book.boards(m.ref).isEmpty()) return@mapNotNull null
            val lg = if (team) null else s.leagueOf(f.a)
            val rank = if (!s.leagues.national) 0 else lg?.let { host.leagueRank(it.id) } ?: Int.MAX_VALUE
            val tier = if (f.comp == Comp.LEAGUE) s.divisionOf(f.a)?.let { s.leagues.tier(it) } else null
            val star = keys.any { it in players } ||
                (f.comp == Comp.LEAGUE && tier == 0 && keys.any { k -> clubs?.clubOf(k)?.let { it in followedClubs } == true }) ||
                (f.comp.byTeams && (nationKey(f.a) in favs || nationKey(f.b) in favs))
            val club = if (team) null else clubs?.clubOf(f.a)
            val mates = f.comp == Comp.LEAGUE && club != null && club == clubs?.clubOf(f.b) && counted[club]?.let { f.a in it && f.b in it } == true
            val ra = w.rating(f.a)
            val rb = w.rating(f.b)
            // One pick a league (a legacy season's one league: one a division).
            val league = when {
                f.comp != Comp.LEAGUE -> null
                s.leagues.national -> lg?.id
                else -> s.divisionOf(f.a)?.let { "d$it" }
            }
            BroadcastPicks.Candidate(
                MatchRef(p.number, f.comp, f.id), week, f.comp, titleOf(p, f, p.number), f.a, f.b, keys,
                league, BroadcastPicks.base(f, rank, tier, upperTier(s, f)),
                f.comp == Comp.LEAGUE && tier == 0 && race.both(f.a, f.b), star, mates,
                keys.map(::pct).average(), !team && !ra.isNaN() && !rb.isNaN() && abs(ra - rb) <= BroadcastPicks.CLOSE_GAP,
            )
        }
    }

    /** The leaders' race of each division ([Drama.RACE_POINTS] from the top, once [Drama.RACE_FROM] of the league is played). */
    private class RaceTable(private val s: yacht.engine.league.Season) {
        private val near = HashMap<Int, Set<String>>()

        fun both(a: String, b: String): Boolean {
            if (s.progress < Drama.RACE_FROM) return false
            val d = s.divisionOf(a) ?: return false
            if (s.divisionOf(b) != d) return false
            val set = near.getOrPut(d) {
                val table = s.table(d)
                val top = table.firstOrNull()?.points ?: return@getOrPut emptySet()
                val race = Drama.RACE_POINTS + if (s.bonus) 1 else 0
                table.takeWhile { top - it.points <= race }.map { it.key }.toSet()
            }
            return a in set && b in set
        }
    }

    /** A promotion playoff tie's upper tier (its upper side's: 0 for the 1·2부 tie); null for any other match. */
    private fun upperTier(s: yacht.engine.league.Season, f: Fixture): Int? =
        if (f.comp != Comp.PROMOTION_PLAYOFF) null else s.divisionOf(f.a)?.let { s.leagues.tier(it) }

    /** The channel's view (under the lock): this week's picks with their results, followed players' other matches, next week's picks so far. */
    private fun broadcastView(): BroadcastView? {
        val w = host.world() ?: return null
        val p = w.plan ?: return null
        ensure(w, p)
        ensurePicks(w, p)
        // Picks read back from the book (the app started again in the same week) were chosen from
        // candidates no longer in memory: worked out again for the rows' reasons and tags.
        if (candidatesSeason != p.number) {
            candidates.clear()
            candidatesSeason = p.number
        }
        for (week in listOf(p.week, p.week + 1)) if (week !in candidates) candidates[week] = runCatching { candidatesOf(w, p, week) }.getOrDefault(emptyList())
        val picks = book.picks.toMap()
        fun pick(week: Int, ref: MatchRef): BroadcastPick? {
            val m = SeasonMatches.of(p, ref) ?: return null
            val f = m.fixture
            val c = candidates[week]?.firstOrNull { it.ref == ref }
            val team = f.kind == JobKind.TEAM || f.comp.byTeams
            fun side(k: String) = if (team) teamName(k) else host.shown(k)
            val result = m.score?.let { s ->
                "${num(s.scoreA)} : ${num(s.scoreB)}" + if (f.kind == JobKind.TEAM) " (보드)" else ""
            }
            return BroadcastPick(
                ref.encode(), week, f.comp, titleOf(p, f, p.number), side(f.a), side(f.b),
                c?.let { BroadcastPicks.reason(it) } ?: "", c?.let { BroadcastPicks.tags(it) }.orEmpty(), c?.score ?: 0,
                m.played, result, ref.encode() in watched,
            )
        }
        val now = picks[p.week].orEmpty().mapNotNull { pick(p.week, it) }
        val next = picks[p.week + 1].orEmpty().mapNotNull { pick(p.week + 1, it) }
        val chosen = picks[p.week].orEmpty().toSet()
        val more = candidates[p.week].orEmpty().count { it.star && it.ref !in chosen }
        return BroadcastView(p.number, p.week, now, more, next)
    }

    // ---- shared ---------------------------------------------------------------------------------------

    /** A team's name: a club's (legacy club league, club cup), else a nation's or a family's ([Competitions.teamLabel] through the host). */
    private fun teamName(id: String): String = host.name(id)

    /** "요트컵 8강 · 19주차", "1부 12라운드 · 12주차", "한국 1부 3라운드 · 5주차": where [f] is played. */
    private fun titleOf(p: SeasonPlan?, f: Fixture, season: Int): String {
        val current = p?.takeIf { it.number == season }
        val where = if (f.comp == Comp.LEAGUE) {
            val d = current?.league?.let { s -> s.divisionOf(f.a) }
            listOfNotNull(d?.let { current.league.leagues.label(it) }, f.label).joinToString(" ")
        } else {
            listOf(f.comp.label, f.label).filter { it.isNotBlank() }.distinct().joinToString(" ")
        }
        val week = when {
            f.week == SeasonCalendar.NONE || f.week < 0 -> null
            f.week == SeasonCalendar.PRESEASON -> "개막 전"
            current != null && f.week > current.calendar.leagueWeeks -> "포스트시즌 ${f.week - current.calendar.leagueWeeks}주차"
            current == null && (f.comp == Comp.TITLE_PLAYOFF || f.comp == Comp.PROMOTION_PLAYOFF) -> "포스트시즌"
            else -> "${f.week}주차"
        }
        return listOfNotNull(where.ifBlank { null }, week).joinToString(" · ")
    }

    companion object {
        /** The match log book's file (league/matchlogs.bin); deleted with the league. */
        const val LOGS = "matchlogs.bin"

        /** Re-simulated fixtures kept in memory, and the longest a re-simulation may take. */
        const val RESIMS = 8
        const val RESIM_MS = 10_000L

        /** Team matches whose boards' sides the ▶ rule keeps at hand (more than the results feed shows). */
        private const val LINEUPS = 64

        /** Followed players whose matches are logged in memory, and of followed clubs the best members of the first ones. */
        const val FOLLOWED_PLAYERS = 8
        const val FOLLOWED_CLUBS = 2
        const val CLUB_BEST = 3

        /** Title playoffs logged as late knockouts: of the best-ranked leagues. */
        const val KO_LEAGUES = 8

        /** Highlights archived first at a close (by drama). */
        private const val ARCHIVED_HIGHLIGHTS = 3

        /** The clubs of a league whose members' derby earns the "같은 클럽 맞대결" tag. */
        private const val CLUB_TOP = 3

        /** Articles under a report. */
        private const val NEWS = 3

        /** League points of a won match (a 2-0 sweep earns one more under 판 승점제). */
        private const val WIN_POINTS = 3

        /** A rating gap the loser had over the winner that makes an upset, and a winner's lowest chance that makes a comeback. */
        private const val UPSET_GAP = 100.0
        private const val COMEBACK_P = 0.2

        /** 요약 중계: the half-turns shown of a game that does not decide the match. */
        const val SUMMARY_HALF_TURNS = 4

        /** Steps a game's last throw stays up before the next game, the countdown before the next pick (s), the quickest step. */
        private const val HOLD_TICKS = 2
        private const val COUNTDOWN = 4
        private const val MIN_STEP_MS = 300L

        /** Stack of the replay worker (bytes), as the league's workers. */
        private const val WORKER_STACK = 16L shl 20

        /** Late knockout rounds by their label. */
        private val KO_LABEL = Regex("(^| )(8강|4강|결승)")

        // Why a match cannot be played again (UX 3.3), and the report's other states.
        const val CHANGED = "선수가 그 뒤로 바뀌어서(성장·은퇴·수정) 이 경기를 똑같이 다시 둘 수 없어요. 점수와 결과만 보여드려요."
        const val ADAPTIVE_TEXT = "상대에 맞춰 바뀌는 AI가 뛴 경기라 다시 둘 수 없어요. 점수와 결과만 보여드려요."
        const val LEFT = "이 경기의 선수가 리그를 떠나서 다시 볼 수 없어요."
        const val PAST = "지난 시즌 경기는 결승과 하이라이트처럼 따로 기록해 둔 경기만 다시 볼 수 있어요."
        const val FRIENDLY_TEXT = "친선 경기와 연구소 경기는 다시 볼 수 없어요."
        const val CORRUPT = "경기 기록이 손상돼서 다시 볼 수 없어요."
        const val SLOW = "이 경기는 다시 계산하는 데 너무 오래 걸려요. 점수와 결과만 보여드려요."
        const val LIVE_TEXT = "경기 중이에요. 끝나면 바로 리포트가 보여요."
        const val NOT_PLAYED = "아직 치르지 않은 경기예요."
        const val NOT_FOUND = "이 경기의 기록을 지금은 불러올 수 없어요."
        const val NO_TABLES = "계산표를 불러오는 중이에요. 잠시 뒤에 다시 해 주세요."

        /** The watch screen while a match is worked out, and while a pick of the channel waits for the league (stopped). */
        const val COMPUTING = "경기를 다시 계산하는 중이에요…"
        const val UNPLAYED_PICK = "아직 치르지 않은 경기예요. 이번 주를 진행하면 바로 볼 수 있어요."

        /** A nation's team is followed as "nation:{code}". */
        private fun nationKey(team: String) = LeagueSelector.NATION + team

        /** Games before a tie's deciders: legs times games per match. */
        private fun matchGames(f: Fixture, perMatch: Int): Int = (if (f.kind == JobKind.TIE) maxOf(1, f.legs) else 1) * perMatch

        /** "1판 · 김민준 선공", "결정전 1판 · 이서연 선공". */
        private fun gameLabel(f: Fixture, index: Int, perMatch: Int, first: String): String {
            val n = matchGames(f, perMatch)
            return if (f.firstTo == 0 && index >= n) "결정전 ${index - n + 1}판 · $first 선공" else "${index + 1}판 · $first 선공"
        }

        /** A card's 13 values: the 12 boxes (0 when empty) and the bonus. */
        private fun boxes(b: Board): IntArray = IntArray(Category.ALL.size + 1) { i -> if (i < Category.ALL.size) b[Category.ALL[i]] ?: 0 else b.bonus }

        private fun num(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)
    }
}

/**
 * The broadcast channel's choice (UX 3.7): a score per match of the week (what is at stake, a
 * title race, a followed player, club mates, the level of play) and the picks in score order
 * under the diversity rules. Pure: the candidates carry every fact their score reads.
 */
internal object BroadcastPicks {
    /** Picks a week, and the diversity caps: per competition, league fixtures, star picks. */
    const val PER_WEEK = 6
    const val PER_COMP = 2
    const val LEAGUE_MAX = 3
    const val STARS = 2

    const val RACE = 25
    const val STAR = 80
    const val MATES = 10
    const val LEVEL = 20
    const val CLOSE = 10

    /** The best-ranked leagues (0-based rank) whose title and 1·2부 promotion playoffs count as big. */
    const val TOP_RANK = 3

    /** Ratings this close make a match "level". */
    const val CLOSE_GAP = 40.0

    /**
     * One match the channel can pick: [base] (what is at stake), a title [race] in a 1부, a
     * followed side ([star]), two counted members of a top club ([mates]), the sides' average
     * rating rank among the members ([percentile], 0 the best) and whether they are [close] in
     * rating. [keys] are the players (each picked at most once a week), [league] a league fixture's league.
     */
    data class Candidate(
        val ref: MatchRef,
        val week: Int,
        val comp: Comp,
        val title: String,
        val a: String,
        val b: String,
        val keys: List<String>,
        val league: String?,
        val base: Int,
        val race: Boolean,
        val star: Boolean,
        val mates: Boolean,
        val percentile: Double,
        val close: Boolean,
    ) {
        val level: Int get() = (LEVEL * (1 - percentile.coerceIn(0.0, 1.0))).roundToInt() + if (close) CLOSE else 0
        val score: Int get() = base + (if (race) RACE else 0) + (if (star) STAR else 0) + (if (mates) MATES else 0) + level
    }

    /** A pick made before whose match is no candidate now (counted by its competition only). */
    fun placeholder(ref: MatchRef, week: Int) = Candidate(ref, week, ref.comp, "", "", "", emptyList(), null, 0, false, false, false, 1.0, false)

    /**
     * What is at stake in [f] (UX 3.7's base): finals 100; a title playoff of a league ranked
     * [rank] ≤ [TOP_RANK] (0-based) 70; CL and nation cup knockouts 60; CCL knockouts and the 요트컵
     * from the 8강 50; all-star, legend and club-cup knockouts 40; a 1·2부 promotion playoff of
     * such a league 35; CL and nation cup groups 30; the 요트컵's 16강-32강 25; CCL and club-cup groups
     * 20; a 1부 league fixture max(0, 30 − 3 × rank). [tier] is a league fixture's, [upper] a
     * promotion tie's upper tier.
     */
    fun base(f: Fixture, rank: Int, tier: Int?, upper: Int? = null): Int {
        val group = f.label.endsWith("차전")
        return when {
            Drama.isFinal(f) || f.comp == Comp.SUPER -> 100
            f.comp == Comp.TITLE_PLAYOFF -> if (rank <= TOP_RANK) 70 else 0
            f.comp == Comp.CHAMPIONS || f.comp == Comp.WORLD_CUP || f.comp == Comp.CONTINENTAL -> if (group) 30 else 60
            f.comp == Comp.CONT_CHAMPIONS -> if (group) 20 else 50
            f.comp == Comp.CUP -> cupRound(f.label)
            f.comp == Comp.ALL_STAR || f.comp == Comp.LEGEND -> 40
            f.comp == Comp.CLUB_CUP -> if (group) 20 else 40
            f.comp == Comp.PROMOTION_PLAYOFF -> if (upper == 0 && rank <= TOP_RANK) 35 else 0
            f.comp == Comp.LEAGUE -> if (tier == 0) maxOf(0, 30 - 3 * minOf(rank, 10)) else 0
            else -> 0
        }
    }

    /** The 요트컵's round by its label: from the 8강 (and the losers' final) 50, the 16강-32강 25, earlier 0. */
    private fun cupRound(label: String): Int {
        if ("결승" in label) return 50
        val n = Regex("(\\d+)강").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return when {
            n <= 8 -> 50
            n <= 32 -> 25
            else -> 0
        }
    }

    /**
     * The picks to add to [already] (this week's made before, which count for every cap) from
     * [candidates], in score order (ties: base, then competition order, then id) under the caps:
     * [PER_WEEK] in all, [PER_COMP] a competition, [LEAGUE_MAX] league fixtures and one a league,
     * every player once, [STARS] star picks.
     */
    fun choose(candidates: List<Candidate>, already: List<Candidate>, total: Int = PER_WEEK): List<Candidate> {
        val perComp = HashMap<Comp, Int>()
        val perLeague = HashMap<String, Int>()
        val used = HashSet<String>()
        var leagues = 0
        var stars = 0
        val taken = HashSet<MatchRef>()
        fun take(c: Candidate) {
            perComp.merge(c.comp, 1, Int::plus)
            if (c.comp == Comp.LEAGUE) {
                leagues++
                c.league?.let { perLeague.merge(it, 1, Int::plus) }
            }
            used += c.keys
            if (c.star) stars++
            taken += c.ref
        }
        for (c in already) take(c)
        val out = ArrayList<Candidate>()
        val order = candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.base }.thenBy { it.comp.ordinal }.thenBy { it.ref.id })
        for (c in order) {
            if (already.size + out.size >= total) break
            if (c.ref in taken) continue
            // The league has its own caps (three fixtures, one a league).
            if (c.comp != Comp.LEAGUE && (perComp[c.comp] ?: 0) >= PER_COMP) continue
            if (c.comp == Comp.LEAGUE && (leagues >= LEAGUE_MAX || (c.league != null && (perLeague[c.league] ?: 0) >= 1))) continue
            if (c.keys.any { it in used }) continue
            if (c.star && stars >= STARS) continue
            take(c)
            out += c
        }
        return out
    }

    /**
     * The channel's next pick after pick [after] of [n] (-1: from the first): the first one still
     * to be shown ([open]) that is [played], in pick order from [after] round to it; else the first
     * one still to be played; null when none is left.
     */
    fun next(n: Int, after: Int, open: (Int) -> Boolean, played: (Int) -> Boolean): Int? {
        val order = (1..n).map { (after + it).mod(n) }.filter(open)
        return order.firstOrNull(played) ?: order.firstOrNull()
    }

    /** The pick's headline reason. */
    fun reason(c: Candidate): String = when {
        c.base >= 100 -> "결승 무대"
        c.star -> "★ 응원하는 선수"
        c.race -> "우승 경쟁"
        c.base >= 60 -> "토너먼트 빅매치"
        c.base >= 35 -> "걸린 게 큰 경기"
        c.mates -> "같은 클럽 맞대결"
        c.close -> "팽팽한 맞대결"
        c.percentile <= 0.1 -> "상위권 맞대결"
        else -> "이번 주 볼 만한 경기"
    }

    /** Short tags on the pick's row. */
    fun tags(c: Candidate): List<String> = buildList {
        if (c.star) add("★")
        if (c.race) add("우승 경쟁")
        if (c.mates) add("🤝 같은 클럽 맞대결")
        if (c.close) add("접전 예상")
    }
}
