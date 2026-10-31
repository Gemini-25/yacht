package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yacht.engine.Category
import yacht.engine.league.AiStats
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Origin
import yacht.engine.league.Preview
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.TieRule
import yacht.engine.league.TieView
import yacht.engine.league.byTeams
import yacht.engine.league.diff
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The league's analytics on request (`lg.analytics`): player pages, and later leaderboards,
 * season reviews, match lists and aggregates, each loader keeping its last results by key.
 */
class LeagueAnalytics internal constructor(private val host: LeagueHost) {
    /**
     * The player pages loaded last (at most [WorldPages.KEEP]), by [Page.Player.loadKey], the newest
     * last; all of one league ([source]). Compared by identity, like the competition pages.
     */
    var players by mutableStateOf<Map<String, PlayerPage>>(emptyMap(), referentialEqualityPolicy())
        private set

    /** The league [players] were read from: a page of a league replaced since (a reset, a seed, a restore) is not shown. */
    private var source: LeagueWorld? = null
    private val requests = AtomicInteger()

    /** The newest request per load key: an older one finishing later is dropped. */
    private val latest = ConcurrentHashMap<String, Int>()

    /** [page]'s data, when it was loaded from the league in play (else null: still to load). */
    fun player(page: Page.Player): PlayerPage? = players[page.loadKey]?.takeIf { source === host.world() }

    /**
     * Loads [key]'s page with the numbers of [scope] into [players]: off the main thread, under the
     * league's lock, in O(the season's fixtures + the league's members). The page shown meanwhile
     * is the one loaded before, so a page opened again (back from a rival's) draws at once.
     */
    fun loadPlayer(key: String, scope: StatScope) {
        val loadKey = Page.Player(key, scope = scope).loadKey
        val n = requests.incrementAndGet()
        latest[loadKey] = n
        host.scope.launch(Dispatchers.Default) {
            val view = host.view()
            val built = synchronized(host.lock) {
                val w = host.world() ?: return@synchronized null
                // A part the league does not have in that shape (a new kind of competition view) leaves the page unloaded, not the app down.
                runCatching { w to playerPage(w, view, key, scope, loadKey) }.getOrNull()
            } ?: run {
                latest.remove(loadKey, n)
                return@launch
            }
            host.scope.launch {
                val (w, page) = built
                if (latest[loadKey] != n) return@launch
                latest.remove(loadKey, n)
                if (w !== host.world()) return@launch
                if (w !== source) {
                    source = w
                    players = emptyMap()
                }
                players = WorldPages.keep(players, loadKey, page)
            }
        }
    }

    /** [key]'s page (under the lock); [view] is the screen's snapshot (its ladder line and competitions). */
    private fun playerPage(w: LeagueWorld, view: LeagueView?, key: String, scope: StatScope, loadKey: String): PlayerPage {
        val season = w.plan?.league
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

        val matches = ArrayList<PlayerMatch>()
        var next: FollowMatch? = null
        if (season != null && d != null) {
            var nextDay = Int.MAX_VALUE
            for (id in 0 until season.fixturesTotal) {
                val f = season.fixture(id)
                if (f.a != key && f.b != key) continue
                val first = f.a == key
                val opp = if (first) f.b else f.a
                val ref = "${season.number}:${Comp.LEAGUE.code}:$id"
                val where = "리그 ${f.round + 1}라운드"
                val result = season.result(id)
                if (result != null) {
                    val r = TieRule.match(result, season.format).let { if (first) it else -it }
                    val score = result.joinToString(" · ") { g -> if (first) "${g.a}:${g.b}" else "${g.b}:${g.a}" }
                    matches += PlayerMatch(ref, f.matchday + 1, where, opp, host.shown(opp), host.badge(opp), r, score, false, Comp.LEAGUE)
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
        if (view != null && view.clock?.season == season?.number) matches += knockoutTies(view, key)

        return PlayerPage(
            loadKey, profile, view?.rows?.firstOrNull { it.key == key }, host.badge(key), host.leagueLabel(key),
            d?.let { season.table(it).indexOfFirst { s -> s.key == key } + 1 }?.takeIf { it > 0 },
            if (own == null) emptyList() else statCells(own, others, key, scope),
            box,
            own?.takeIf { box.isNotEmpty() }?.let { s -> upper.sumOf { s.boxAverage(it) } to upper.sumOf { leagueBox(it) } } ?: (Double.NaN to Double.NaN),
            (own?.let { it.bonuses.toDouble() / it.games } ?: Double.NaN) to (if (games > 0) others.sumOf { it.second.bonuses }.toDouble() / games else Double.NaN),
            own?.histogram?.copyOf(),
            matches,
            emptyList(),
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
        )
    }

    /** [key]'s knockout ties this season in the snapshot's competitions (no fixture ids there yet: not opened). */
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
        val score = if (first) "${half(t.scoreA)}:${half(t.scoreB)}" else "${half(t.scoreB)}:${half(t.scoreA)}"
        return PlayerMatch(null, SeasonCalendar.NONE, where, opp, host.shown(opp), host.badge(opp), result, score, false, comp)
    }

    companion object {
        /** Highlights and articles a page keeps. */
        const val HIGHLIGHTS = 6
        const val NEWS = 10

        /** Fewest games to be ranked over a career (averages, 꾸준함), for the bonus rate, for 꾸준함 in a season; fewest decider or close games for 클러치. */
        const val CAREER_GAMES = 200
        const val SEASON_BONUS = 20
        const val CAREER_BONUS = 100
        const val SEASON_CLUTCH = 8
        const val CAREER_CLUTCH = 30
        const val SEASON_STEADY = 40

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

        private fun half(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)
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
