package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.league.ChaseLine
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubTable
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Drama
import yacht.engine.league.HonourKind
import yacht.engine.league.JobKind
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueWorld
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Nations
import yacht.engine.league.Preview
import yacht.engine.league.Proposal
import yacht.engine.league.RecordChase
import yacht.engine.league.RecordKind
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatch
import yacht.engine.league.SeasonMatches
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import yacht.engine.league.byTeams
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * The league's home dashboard (`lg.home`, UX design 2): [view] is built on request ([refresh])
 * off the main thread under the league's lock, never in the screen's snapshot. The controller
 * refreshes it when the 홈 tab appears, the week in play moves, a followed player's or club
 * member's result lands, a favourite changes or a banner is dismissed; there is no timer.
 *
 * The followed players' and clubs' best members' league fixtures are found through an index of
 * their fixture ids, built for those keys only (one scan of the season's fixtures) and dropped
 * when the season changes, so a refresh costs a few of their fixtures, not the whole season.
 */
class HomeFeed internal constructor(private val host: LeagueHost) {
    /** The home as last built; null before the first build (and for a league replaced since, until the next). */
    var view by mutableStateOf<HomeView?>(null, referentialEqualityPolicy())
        private set

    // ---- refresh (any thread) --------------------------------------------------------------------

    private val building = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val builds = AtomicLong()

    @Volatile
    private var shown = 0L

    /** Builds [view] again off the main thread; a request while a build runs is folded into one more build after it. */
    fun refresh() {
        if (!building.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        host.scope.launch(Dispatchers.Default) {
            val n = builds.incrementAndGet()
            val (w, v) = try {
                synchronized(host.lock) {
                    val w = host.world()
                    w to w?.let { runCatching { build(it) }.getOrNull() }
                }
            } finally {
                building.set(false)
                if (again.getAndSet(false)) refresh()
            }
            host.scope.launch {
                // Two builds can land out of order: keep the newer; nothing of a league replaced since; a build
                // that failed keeps the home as it was.
                if (n < shown || w !== host.world() || pinned || (w != null && v == null)) return@launch
                shown = n
                view = v
            }
        }
    }

    /** Forgets what was built from a league being replaced (a reset, a seed, a restore); the next [refresh] builds anew. Main thread. */
    fun clear() {
        view = null
    }

    /** A home shown in place of the built one ([show]), which no build replaces meanwhile. */
    @Volatile
    private var pinned = false

    /** Shows [v] (the screenshot harness's hand-made home) until called with null, which builds the home again. */
    internal fun show(v: HomeView?) {
        pinned = v != null
        if (v != null) view = v else refresh()
    }

    // ---- the followed fixture index (under the lock) ---------------------------------------------

    /** The plan [index] is of; its fixture ids per followed key. */
    private var indexPlan: SeasonPlan? = null
    private val index = HashMap<String, IntArray>()

    /** Scans of a season's league fixtures for the index (once a season, and again only for keys followed later). */
    internal var indexScans = 0
        private set

    /** League fixture ids of [keys] in [p] (under the lock), from the index; keys not in it yet are added in one scan. */
    private fun indexed(p: SeasonPlan, keys: Collection<String>): Map<String, IntArray> {
        if (p !== indexPlan) {
            index.clear()
            indexPlan = p
        }
        val missing = keys.filter { it !in index }.toSet()
        if (missing.isNotEmpty()) {
            index.putAll(SeasonMatches.fixtureIndex(p, missing))
            indexScans++
        }
        return keys.associateWith { index.getValue(it) }
    }

    /**
     * [keys]' matches this season (under the lock), each in week order (league fixtures first in a
     * week): their league fixtures from the index, and every other competition's matches in one
     * pass (a team match as the key's own board, [yacht.engine.league.MatchRef.board]).
     */
    private fun matchesOf(p: SeasonPlan, keys: Collection<String>): Map<String, List<SeasonMatch>> {
        val ids = indexed(p, keys)
        val out = keys.associateWith { ArrayList<SeasonMatch>() }
        for ((k, list) in out) for (id in ids.getValue(k)) {
            SeasonMatches.of(p, Comp.LEAGUE, id)?.takeIf { it.fixture.a == k || it.fixture.b == k }?.let { list += it }
        }
        val wanted = keys.toSet()
        for (c in p.comps + p.post) {
            if (c.comp == Comp.LEAGUE) continue
            for (m in SeasonMatches.ofComp(p, c.comp)) {
                val f = m.fixture
                if (f.kind == JobKind.TEAM) {
                    for ((i, k) in f.lineupA.withIndex()) if (k in wanted) out.getValue(k) += m.copy(ref = m.ref.copy(board = i))
                    for ((i, k) in f.lineupB.withIndex()) if (k in wanted) out.getValue(k) += m.copy(ref = m.ref.copy(board = i))
                } else {
                    if (f.a in wanted) out.getValue(f.a) += m
                    if (f.b in wanted && f.b != f.a) out.getValue(f.b) += m
                }
            }
        }
        for (list in out.values) list.sortBy { if (it.week == SeasonCalendar.NONE) Int.MAX_VALUE else it.week }
        return out
    }

    // ---- the build (under the lock) --------------------------------------------------------------

    /** The home of [w] as it stands (under the lock). */
    internal fun build(w: LeagueWorld): HomeView {
        val p = w.plan
        val favs = host.favourites()
        val rows = host.view()?.rows.orEmpty().associateBy { it.key }
        // Each part on its own: one that fails leaves the others up.
        val (cards, more) = runCatching { cards(w, p, favs, rows) }.getOrElse { emptyList<FollowCard>() to 0 }
        val pending = w.federation.pending().takeIf { w.config.federation && p?.league?.leagues?.national == true }.orEmpty()
        return HomeView(
            p?.number ?: 0, p?.week ?: 0,
            runCatching { banner(w, p, host.uiPrefs(), favs) }.getOrNull(),
            cards, more,
            p?.let { runCatching { world(it) }.getOrNull() },
            pending.size, pending.count { it.choice != Proposal.NONE },
            p?.let { runCatching { chase(w, it, favs) }.getOrNull() }.orEmpty(),
        )
    }

    /**
     * The one banner (UX 2), the first that applies: a season just closed (until dismissed, its
     * review still unread or the next season's first [SEASON_END_WEEKS] weeks) > the first national
     * season (until dismissed) > a legacy season with national rules next (until dismissed) > an
     * empty league.
     */
    internal fun banner(w: LeagueWorld, p: SeasonPlan?, prefs: UiPrefs, favs: Set<String>): HomeBanner? {
        if (p != null) {
            val closed = p.number - 1
            val unread = w.archive.pendingReview?.season == closed
            if (closed >= 1 && !prefs.dismissed(UiPrefs.SEASON_END, closed) && (unread || p.week <= SEASON_END_WEEKS)) {
                seasonEnd(w, closed)?.let { return it }
            }
            val national = p.league.leagues.national
            if (national && !prefs.dismissed(UiPrefs.REFORM, p.number) && firstNational(w, p)) return HomeBanner.Reform(p.number)
            if (!national && w.config.national && !prefs.dismissed(UiPrefs.COMING, p.number)) return HomeBanner.Coming(p.number, coming(w, favs))
        }
        return if (w.ladder.matches == 0L) HomeBanner.Empty else null
    }

    /**
     * Season [n]'s banner: the shown league's champion (a national season's: of the league the
     * screen shows, else of the best-ranked league), the 챔피언스 리그, the 요트컵 and the MVP; then the
     * scorer, the Yacht king and (a legacy season) the promotions. Null when it has no honours kept.
     */
    private fun seasonEnd(w: LeagueWorld, n: Int): HomeBanner.SeasonEnd? {
        val h = w.honours.of(n)
        if (h.isEmpty()) return null
        val byKind = h.associateBy { it.kind }
        val tops = h.filter { HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null }
        val champion = if (tops.isEmpty()) {
            byKind["L0"]?.let { "1부 ${host.name(it.key)}" }
        } else {
            val shown = host.view()?.season?.league
            val rank = w.plan?.ranking
            val pick = tops.firstOrNull { HonourKind.leagueOf(it.kind) == shown }
                ?: tops.minByOrNull { t -> HonourKind.leagueOf(t.kind)!!.let { id -> (rank?.rank(id) ?: Int.MAX_VALUE).toLong() * 1000 + LeagueIds.order(id) } }
            pick?.let { "${LeagueIds.label(HonourKind.leagueOf(it.kind)!!)} ${host.name(it.key)}" }
        }
        val first = listOfNotNull(
            champion,
            byKind["CL"]?.let { "챔스 ${host.name(it.key)}" },
            byKind["CUP"]?.let { "요트컵 ${host.name(it.key)}" },
            byKind["MVP"]?.let { "MVP ${host.name(it.key)}" },
        )
        val promoted = if (tops.isEmpty()) h.count { it.kind.startsWith("UP") } else 0
        val second = listOfNotNull(
            byKind["SCORER"]?.let { "득점왕 ${host.name(it.key)}" },
            byKind["YACHT"]?.let { "요트왕 ${host.name(it.key)}" },
            promoted.takeIf { it > 0 }?.let { "승격 ${it}명" },
        )
        return HomeBanner.SeasonEnd(n, listOf(first, second).filter { it.isNotEmpty() }.map { it.joinToString(" · ") })
    }

    /** True for the first national season after legacy ones: the season before crowned a legacy champion, or this season's reform article is in. */
    private fun firstNational(w: LeagueWorld, p: SeasonPlan): Boolean =
        w.honours.of(p.number - 1).any { it.kind == "L0" } || w.news.all.any { it.kind == REFORM_NEWS && it.season == p.number }

    /**
     * Where the followed players (the best-rated [COMING_PLAYERS]) would play next season under
     * national leagues: the leagues [NationalLeagues.plan] forms from every member's nation (a
     * migration founds clubs by nationality), as (name, "🇯🇵 일본 리그").
     */
    private fun coming(w: LeagueWorld, favs: Set<String>): List<Pair<String, String>> {
        val players = favs.filter { ':' !in it && it in w.members }.sortedByDescending { w.rating(it) }.take(COMING_PLAYERS)
        if (players.isEmpty()) return emptyList()
        fun nation(k: String) = w.players[k]?.identity?.nation?.takeIf { Nations.of(it) != null } ?: Nations.KR.code
        val plan = NationalLeagues.plan(w.members.groupingBy(::nation).eachCount(), w.config, emptySet())
        return players.mapNotNull { k -> plan[nation(k)]?.let { id -> host.shown(k) to "${LeagueIds.icon(id)} ${LeagueIds.label(id)}" } }
    }

    // ---- followed cards ---------------------------------------------------------------------------

    /**
     * The followed entities' cards, the first [HomeView.MAX_CARDS] (players by rating, then clubs,
     * nations and leagues), and how many more are followed.
     */
    private fun cards(w: LeagueWorld, p: SeasonPlan?, favs: Set<String>, rows: Map<String, LadderRow>): Pair<List<FollowCard>, Int> {
        fun rating(k: String) = w.ladder.rating(k).takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY
        val players = favs.filter { ':' !in it && (it in rows || w.ladder.entry(it) != null) }.sortedWith(compareByDescending<String> { rating(it) }.thenBy { it })
        val clubs = favs.filter { it.startsWith(LeagueSelector.CLUB) }.sorted()
        val nations = favs.filter { it.startsWith(LeagueSelector.NATION) }.sortedBy { f -> Nations.ALL.indexOfFirst { it.code == f.removePrefix(LeagueSelector.NATION) } }
        val leagues = favs.filter { it.startsWith(LeagueSelector.LEAGUE) }.sortedBy { LeagueIds.order(it.removePrefix(LeagueSelector.LEAGUE)) }
        val all = players + clubs + nations + leagues
        val shown = all.take(HomeView.MAX_CARDS).toSet()
        val book = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION }
        // The clubs' best members by rating (their week's results), with the players: one pass for all.
        val best = clubs.filter { it in shown }.take(FOLLOWED_CLUBS)
            .associateWith { f -> book?.members(f.removePrefix(LeagueSelector.CLUB)).orEmpty().sortedByDescending(::rating).take(CLUB_BEST) }
        val matches = if (p == null) emptyMap() else matchesOf(p, (players.filter { it in shown } + best.values.flatten()).distinct())
        val out = ArrayList<FollowCard>()
        for (k in players) if (k in shown) out += playerCard(w, p, k, rows[k], matches[k])
        for (f in clubs) if (f in shown) out += clubCard(w, p, f, book, best[f].orEmpty(), matches)
        for (f in nations) if (f in shown) out += nationCard(w, p, f)
        for (f in leagues) if (f in shown) out += leagueCard(w, p, f)
        return out to (all.size - out.size)
    }

    private fun playerCard(w: LeagueWorld, p: SeasonPlan?, key: String, row: LadderRow?, matches: List<SeasonMatch>?): FollowCard {
        val s = p?.league
        val d = s?.divisionOf(key)
        val place = d?.let { s.table(it).indexOfFirst { r -> r.key == key }.takeIf { i -> i >= 0 }?.let { i -> "${s.leagues.label(d)} ${i + 1}위" } }
        val detail = row?.rating?.let { r -> "레이팅 ${"%,d".format(r)}" + (row.lastDelta.takeIf { it != 0 }?.let { " (${if (it > 0) "+" else ""}$it)" } ?: "") }
        val list = matches.orEmpty()
        val last = list.lastOrNull { it.played }?.let { followMatch(w, p!!, it, key) }
        val listed = list.firstOrNull { !it.played }?.let { followMatch(w, p!!, it, key) }
        // A knockout tie won whose next stage is not drawn yet (and comes before the next match listed).
        val pending = if (p == null) null else undrawn(p, list, key)?.takeIf { (pw, _) -> listed == null || pw < listed.week }
        val next = pending?.let { (week, where) -> FollowMatch("", week, where, "", "", null, null, false, null) } ?: listed
        val note = when {
            p == null || d == null && list.isEmpty() -> if (p == null) null else "이번 시즌엔 리그에 나서지 않아요."
            next == null -> "이번 시즌 경기가 모두 끝났어요."
            next.opponent.isEmpty() -> "다음 경기 상대가 아직 정해지지 않았어요."
            else -> null
        }
        return FollowCard(
            FollowKind.PLAYER, key, key, host.shown(key), host.badge(key), place ?: row?.divisionLabel ?: "", detail,
            last, next, row?.recent?.takeLast(FORM).orEmpty(), note,
        )
    }

    /**
     * [key]'s next knockout stage not drawn yet (its week and name): a stage of a competition still
     * going whose last tie [key] played it won (not a final).
     */
    private fun undrawn(p: SeasonPlan, list: List<SeasonMatch>, key: String): Pair<Int, String>? {
        val byComp = list.filter { it.ref.comp != Comp.LEAGUE && it.fixture.kind == JobKind.TIE }.groupBy { it.ref.comp }
        return byComp.mapNotNull { (comp, ms) ->
            if (p.competition(comp)?.finished != false || ms.any { !it.played }) return@mapNotNull null
            val last = ms.last()
            if (Drama.isFinal(last.fixture) || side(last, key) != 1) return@mapNotNull null
            val week = p.calendar.week(comp, last.stage + 1)
            if (week == SeasonCalendar.NONE) null else week to comp.label
        }.minByOrNull { it.first }
    }

    /** +1 when [key] won [m], -1 lost, 0 drawn; null before it is played. */
    private fun side(m: SeasonMatch, key: String): Int? {
        val f = m.fixture
        val first = if (f.kind == JobKind.TEAM) key in f.lineupA else f.a == key
        val r = m.score?.winner ?: m.games?.let { TieRule.match(it) } ?: return null
        return if (first) r else -r
    }

    /** [m] as [key]'s card shows it: where, the opponent (a team match: its board's), the result and score, ▶, the chance before it. */
    private fun followMatch(w: LeagueWorld, p: SeasonPlan, m: SeasonMatch, key: String): FollowMatch {
        val f = m.fixture
        val team = f.kind == JobKind.TEAM
        val first = if (team) key in f.lineupA else f.a == key
        val opp = if (team) (if (first) f.lineupB else f.lineupA).getOrNull(m.ref.board).orEmpty() else if (first) f.b else f.a
        val other = if (team || f.comp.byTeams) Competitions.teamLabel(if (first) f.b else f.a) else null
        val where = when (f.comp) {
            Comp.LEAGUE -> p.league.divisionOf(key)?.let { p.league.leagues.label(it) } ?: Comp.LEAGUE.label
            else -> listOf(f.comp.label, f.label).filter { it.isNotBlank() }.distinct().joinToString(" ")
        }
        val name = when {
            opp.isEmpty() -> other.orEmpty()
            other != null -> "${host.shown(opp)} ($other)"
            else -> host.shown(opp)
        }
        val score = m.score?.let { s -> if (first) "${num(s.scoreA)}:${num(s.scoreB)}" else "${num(s.scoreB)}:${num(s.scoreA)}" }
        val (a, b) = if (team) (f.lineupA.getOrNull(m.ref.board) to f.lineupB.getOrNull(m.ref.board)) else (f.a to f.b)
        val replay = m.played && a != null && b != null && runCatching { host.canReplay(m.ref, a, b) }.getOrDefault(false)
        val chance = if (m.played || team || opp.isEmpty()) null else {
            val ra = w.ladder.rating(key)
            val rb = w.ladder.rating(opp)
            if (ra.isNaN() || rb.isNaN()) null else (100 * Preview.expected(ra, rb)).roundToInt()
        }
        return FollowMatch(m.ref.encode(), m.week, where, opp, name, if (m.played) side(m, key) else null, score, replay, chance)
    }

    /**
     * A followed club's card: its place in its league's club table and the gap to the top (or to
     * the next), its members' league record of the week, and the best member's result of the week
     * (▶ when it can be played again).
     */
    private fun clubCard(w: LeagueWorld, p: SeasonPlan?, fav: String, book: ClubBook?, best: List<String>, matches: Map<String, List<SeasonMatch>>): FollowCard {
        val id = fav.removePrefix(LeagueSelector.CLUB)
        val badge = host.view()?.badges?.get(id)
        val title = badge?.name ?: book?.nameOf(id) ?: id
        val s = p?.league
        val members = book?.members(id).orEmpty()
        val lg = s?.let { members.firstNotNullOfOrNull { k -> it.leagueOf(k) } }?.takeIf { it.national }
        if (p == null || s == null || book == null || lg == null) {
            return FollowCard(FollowKind.CLUB, fav, id, title, badge, if (book?.club(id) == null) "해체된 클럽이에요." else "", null, null, null, "", null)
        }
        val table = ClubTable.of(s, book, lg.id, w.honours.of(p.number))
        val at = table.indexOfFirst { it.club == id }
        val line = if (at < 0) LeagueIds.label(lg.id) else "${LeagueIds.label(lg.id)} 클럽 순위 ${at + 1}위 · ${pts(table[at].points)}점"
        val gap = when {
            at > 0 -> "1위와 ${pts(table[0].points - table[at].points)}점 차"
            at == 0 && table.size > 1 -> "2위와 ${pts(table[0].points - table[1].points)}점 차"
            else -> null
        }
        // The week's league results of every member: the week in play's round, else the last one played.
        val set = members.toSet()
        var label = "이번 주"
        var wdl = weekRecord(p, set, p.week - 1)
        if (wdl.sum() == 0 && p.week >= 2) {
            wdl = weekRecord(p, set, p.week - 2)
            label = "지난주"
        }
        val record = if (wdl.sum() == 0) null else "$label 선수단 ${wdl[0]}승 ${wdl[1]}무 ${wdl[2]}패"
        val last = best.mapNotNull { k -> matches[k]?.lastOrNull { it.played && it.week >= p.week - 1 }?.let { k to it } }
            .firstOrNull()?.let { (k, m) -> followMatch(w, p, m, k).let { fm -> fm.copy(where = "${fm.where} · ${host.name(k)}") } }
        return FollowCard(FollowKind.CLUB, fav, id, title, badge, line, listOfNotNull(gap, record).joinToString(" · ").ifEmpty { null }, last, null, "", null)
    }

    /** Wins, draws and losses of [keys] in the league's round [day] (0-based), from their own side. */
    private fun weekRecord(p: SeasonPlan, keys: Set<String>, day: Int): IntArray {
        val out = IntArray(3)
        if (day < 0) return out
        val s = p.league
        for (f in s.matchday(day)) {
            val g = s.result(f.id) ?: continue
            val r = TieRule.match(g, s.format)
            if (f.a in keys) out[if (r > 0) 0 else if (r == 0) 1 else 2]++
            if (f.b in keys) out[if (r < 0) 0 else if (r == 0) 1 else 2]++
        }
        return out
    }

    /**
     * A followed nation's card: in a season with a nation cup its team's last and next ties (▶ /
     * 🔮); otherwise that there is none this season, with its league's leader.
     */
    private fun nationCard(w: LeagueWorld, p: SeasonPlan?, fav: String): FollowCard {
        val code = fav.removePrefix(LeagueSelector.NATION)
        val n = Nations.of(code)
        val title = n?.let { "${it.flag} ${it.name} 대표팀" } ?: code
        val cups = p?.let { pl -> (pl.comps + pl.post).filter { it.comp == Comp.WORLD_CUP || it.comp == Comp.CONTINENTAL } }.orEmpty()
        if (p == null || cups.isEmpty()) {
            val lg = p?.let { leagueOfNation(it, code) }
            val leader = lg?.let { p.league.table(it.top).firstOrNull() }?.let { host.shown(it.key) }
            val note = "이번 시즌엔 국가대항전이 없어요" + (if (lg != null && leader != null) " · ${LeagueIds.label(lg.id)} 1위 $leader" else "")
            return FollowCard(FollowKind.NATION, fav, code, title, null, "", null, null, null, "", note.takeIf { p != null })
        }
        val ties = cups.flatMap { SeasonMatches.ofComp(p, it.comp) }.filter { it.fixture.a == code || it.fixture.b == code }
            .sortedBy { if (it.week == SeasonCalendar.NONE) Int.MAX_VALUE else it.week }
        fun of(m: SeasonMatch): FollowMatch {
            val f = m.fixture
            val first = f.a == code
            val opp = if (first) f.b else f.a
            val r = m.score?.winner?.let { if (first) it else -it }
            val score = m.score?.let { s -> if (first) "${num(s.scoreA)}:${num(s.scoreB)}" else "${num(s.scoreB)}:${num(s.scoreA)}" }
            val where = listOf(f.comp.label, f.label).filter { it.isNotBlank() }.distinct().joinToString(" ")
            return FollowMatch(m.ref.encode(), m.week, where, opp, Competitions.teamLabel(opp), r, score, false, null)
        }
        val last = ties.lastOrNull { it.played }?.let(::of)
        val next = ties.firstOrNull { !it.played }?.let(::of)
        val note = when {
            ties.isEmpty() -> "이번 시즌 국가대항전에 나서지 않아요."
            next == null -> "이번 시즌 국가대항전 경기가 모두 끝났어요."
            else -> null
        }
        return FollowCard(FollowKind.NATION, fav, code, title, null, cups.joinToString(" · ") { it.comp.label }, null, last, next, "", note)
    }

    /** A followed league's card: its 1부 leader and the gap to the second. */
    private fun leagueCard(w: LeagueWorld, p: SeasonPlan?, fav: String): FollowCard {
        val id = fav.removePrefix(LeagueSelector.LEAGUE)
        val title = "${LeagueIds.icon(id)} ${LeagueIds.label(id)}"
        val lg = p?.league?.leagues?.byId(id)?.takeIf { it.national }
        if (p == null || lg == null) return FollowCard(FollowKind.LEAGUE, fav, id, title, null, "", null, null, null, "", if (p == null) null else "이번 시즌엔 열리지 않는 리그예요.")
        val table = p.league.table(lg.top)
        val lead = table.firstOrNull()
        val line = lead?.let { "1부 선두 ${host.shown(it.key)} (${it.points}점)" + (table.getOrNull(1)?.let { s -> " · 2위와 ${it.points - s.points}점 차" } ?: "") } ?: ""
        val rank = p.ranking.of(id)?.let { "리그 랭킹 ${it.rank + 1}위" }
        return FollowCard(FollowKind.LEAGUE, fav, id, title, null, line, rank, null, null, "", null)
    }

    /** The league nation [code]'s players play in this season: its own, its confederation's 연합 리그, the 국제 연합 리그, else Korea's. */
    private fun leagueOfNation(p: SeasonPlan, code: String): yacht.engine.league.LeagueDef? {
        val leagues = p.league.leagues
        if (!leagues.national) return leagues.list.firstOrNull()
        return leagues.byId(code) ?: Nations.of(code)?.confed?.let { leagues.byId(LeagueIds.region(it)) } ?: leagues.byId(LeagueIds.WORLD) ?: leagues.byId(Nations.KR.code)
    }

    // ---- the international stage and the record chase --------------------------------------------

    /** 국제 무대 of a national season: the 챔피언스 리그's and 대륙 챔피언스' stages, the ranking's top 3 and this season's race; null otherwise. */
    private fun world(p: SeasonPlan): WorldSnippet? {
        if (!p.league.leagues.national) return null
        val comps = host.view()?.comps.orEmpty()
        val stages = listOf(Comp.CHAMPIONS, Comp.CONT_CHAMPIONS).mapNotNull { c -> comps.firstOrNull { it.first == c }?.let { (_, v) -> stageOf(v)?.let { c to it } } }
        val live = Coefficients.season(p).filterValues { it[1] > 0 }.mapValues { Coefficients.value(it.value) }
        val ranking = p.ranking.rows.take(WORLD_TOP).map { r -> RankRow(r, LeagueIds.label(r.id), LeagueIds.short(r.id), LeagueIds.icon(r.id), live[r.id]) }
        val race = live.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { LeagueIds.order(it.key) }).take(WORLD_TOP).map { it.key to it.value }
        if (stages.isEmpty() && ranking.isEmpty() && race.isEmpty()) return null
        return WorldSnippet(stages, ranking, race)
    }

    /** The stage [v] is at: the bracket round in play, the groups until the knockout is drawn (a section's, of its first). */
    private fun stageOf(v: CompetitionView): String? {
        fun groups(g: CompetitionView.Groups): String? {
            val k = g.knockout?.rounds.orEmpty()
            val drawn = k.any { r -> r.ties.any { it.a != null || it.b != null } }
            return if ((drawn || g.groups.isEmpty()) && k.isNotEmpty()) roundInPlay(k)?.let { k[it].label } else if (g.groups.isNotEmpty()) "조별 리그" else null
        }
        return when (v) {
            is CompetitionView.Bracket -> roundInPlay(v.rounds)?.let { v.rounds[it].label }
            is CompetitionView.Groups -> groups(v)
            is CompetitionView.Sections -> v.parts.firstOrNull()?.let(::groups)
            else -> null
        }
    }

    /**
     * 기록 추격: the nearest chases ([RecordChase]), at most [CHASES]: the followed players' first,
     * then everyone's, those [RecordChase.near] before the others; one line a kind and a player.
     * Only records of an earlier season are chased (as the news desk's "기록에 다가선다"): one set
     * this season is no history yet (a new league's first weeks hold records of 2연승).
     */
    private fun chase(w: LeagueWorld, p: SeasonPlan, favs: Set<String>): List<ChaseLine> {
        val followed = favs.filter { ':' !in it && it in w.members }
        fun old(l: ChaseLine) = w.stats.records[l.kind]?.let { it.season < p.number } == true
        val mine = if (followed.isEmpty()) emptyList() else RecordChase.of(w.stats, p, followed, top = 1).flatMap { it.second }.filter(::old)
        val all = RecordChase.of(w.stats, p, w.members, top = 1).flatMap { it.second }.filter(::old)
        val out = ArrayList<ChaseLine>()
        // The most games played is a matter of time, not of play: told only when nothing else is near.
        val order = (mine.filter(RecordChase::near) + all.filter(RecordChase::near) + mine + all).sortedBy { it.kind == RecordKind.CAREER_GAMES }
        for (l in order) {
            if (out.size >= CHASES) break
            if (out.none { it.kind == l.kind || it.key == l.key }) out += l
        }
        return out
    }

    companion object {
        /** A season's end is on the home for the next season's first weeks. */
        const val SEASON_END_WEEKS = 2

        /** Followed players whose next league the coming-reform banner names. */
        const val COMING_PLAYERS = 3

        /** Followed clubs whose best members' matches are looked up (the players' are, on every card), and how many members. */
        const val FOLLOWED_CLUBS = 2
        const val CLUB_BEST = 3

        /** Results in a player's form line. */
        const val FORM = 5

        /** Leagues in the 국제 무대's ranking and race. */
        const val WORLD_TOP = 3

        /** Record chases on the home. */
        const val CHASES = 3

        private const val REFORM_NEWS = "national_reform"

        /** "2", "1.5". */
        private fun num(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else String.format(Locale.ROOT, "%.1f", x)

        /** Club points to one decimal ("41.5"). */
        private fun pts(x: Double): String = String.format(Locale.ROOT, "%.1f", x)
    }
}
