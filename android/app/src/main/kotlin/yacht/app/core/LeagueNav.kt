package yacht.app.core

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import yacht.engine.league.Comp
import yacht.engine.league.Confed
import yacht.engine.league.Family
import yacht.engine.league.Origin

/** The league screen's four tabs (the bottom bar), in order. */
enum class LeagueTab(val label: String, val icon: String) { HOME("홈", "🏠"), MATCHES("경기", "🎲"), TABLES("순위", "📊"), RECORDS("기록", "📚") }

/** The tab a link from outside the app opens (the league notification's [LeagueTab] name); null for anything else. */
fun deepLinkTab(name: String?): LeagueTab? = LeagueTab.entries.firstOrNull { it.name == name }

/** 경기's sub-views (its chip row). */
enum class MatchesView(val label: String) { WEEK("이번 주"), RESULTS("결과"), BROADCAST("중계"), HIGHLIGHTS("하이라이트") }

/** 순위's sub-views. */
enum class TablesView(val label: String) { LEAGUE("리그"), COMPS("대회"), CLUBS("클럽"), RANKING("랭킹"), LADDER("레이팅") }

/** How 순위 › 리그 shows a division (the 보기 ▾ sheet). */
enum class TableMode(val label: String) { TABLE("순위표"), FORM("최근 폼"), LUCK("운 보정"), ROUND("라운드"), CHART("순위 변동"), H2H("상대 전적"), ODDS("확률") }

/** 기록's sub-views. */
enum class RecordsView(val label: String) { LEADERS("리더"), ROOM("기록실"), HALL("전당"), SEASONS("결산"), MORE("더 보기") }

/** Which results 경기 › 결과 lists. */
enum class ResultsFilter(val label: String) { ALL("전체"), FOLLOWED("★ 응원"), LEAGUE("리그"), COMPS("대회"), FRIENDLY("친선") }

/** Statistics of this season or of a whole career. */
enum class StatScope(val label: String) { SEASON("이번 시즌"), CAREER("통산") }

/** The player page's tabs. */
enum class PlayerTab(val label: String) { OVERVIEW("개요"), STATS("기록"), MATCHES("경기"), HONOURS("수상") }

/** The news page's filters. */
enum class NewsFilter(val label: String) { ALL("전체"), TOP("주요"), LEAGUE("리그"), WORLD("국제"), MARKET("이적·협회") }

/** The rating ladder's orders. */
enum class LadderSort(val label: String) { RATING("레이팅"), WIN_RATE("승률"), SCORE("평균 점수"), GAMES("경기 수"), STREAK("연승") }

/** 국가대표 랭킹's orders: titles, the top five's strength, players. */
enum class NationSort(val label: String) { CUPS("대회 성적"), STRENGTH("전력 (상위 5명)"), PLAYERS("선수 수") }

/**
 * The leaderboards: the hub's eight first ([LeagueAnalytics.HUB]), each named by
 * [LeagueAnalytics.title] (the engine's [yacht.engine.league.LeaderStat] labels).
 */
enum class LeaderKind {
    SCORER, YACHTS, MVP, CLUTCH, COMEBACK, LUCKY, BONUS, SWEEPS, UNLUCKY, GIANT, STEADY, OVER300,
}

/** The part of the rules page a link opens at. */
enum class RulesPart { TOP, LEAGUE, CUPS, CLUBS, FEDERATION }

/** The club page's tabs. */
enum class ClubTab(val label: String) { OVERVIEW("개요"), SQUAD("선수단"), BUSINESS("구단"), HISTORY("역사") }

/** 이적 센터's tabs. */
enum class MarketTab(val label: String) { LIVE("이적설"), MOVES("이적 내역"), RECORDS("최고 이적료"), COACHES("감독") }

/** 시대와 메타's tabs. */
enum class MetaTab(val label: String) { ERAS("시대·스타일"), BOXES("칸별 메타") }

/** 클럽 랭킹's lists. */
enum class ClubRankKind(val label: String) { VALUE("가치"), RESULTS("성적"), RICH("수입"), FANS("인기"), HEALTH("재정") }

/** Whose style shares the chart draws (a page argument of [Page.Meta]). */
enum class ShareScope(val label: String) { ELITE("상위 리그 1부"), ALL("전체 선수") }

/** What the ⓘ sheet explains ([Sheet.INFO]; the topic is [LeagueNav.info]). */
enum class InfoTopic { FINANCE, BOARD, FACILITIES, POWER, PREDICT, VOTES, ERAS, ARCS }

/**
 * A page's chip row whose scroll state is hoisted like the tabs' ([LeagueNav.chipState]), so the
 * harness can check that it scrolls instead of clipping (UX 2.1): the club page's tabs, 이적 센터's,
 * 시대와 메타's and 클럽 랭킹's lists.
 */
enum class PageChips { CLUB, MARKET, META, CLUB_RANK }

/** The bottom sheet shown over the league screen (one at a time). */
enum class Sheet {
    NONE, SETTINGS, LEAGUES, WEEKS, TABLE_MODE, RESULTS_FILTER, HIGHLIGHT_FILTER, LADDER_FILTER, LEADER_FILTER,
    CONFED, STAGE, PLAYER_ACTIONS, RATING_INFO,

    /** A static text explaining [LeagueNav.info] (ⓘ buttons set the topic, then the sheet). */
    INFO,

    /** The league sheet in a mode that only changes the top page's league (기자단, 클럽 랭킹): never [LeagueNav.selectLeague]. */
    LEAGUE_PICK,

    /** A list of links (an arc's subject clubs, a player's contract details). */
    LIST,
}

/** 순위 › 레이팅's filters (none set: everyone). */
data class LadderFilter(val followed: Boolean = false, val origin: Origin? = null, val nation: String? = null, val family: Family? = null)

/** The leaderboards' scope: this season or careers, one league or all, the first tier only. */
data class LeaderFilter(
    val scope: StatScope = StatScope.SEASON,
    val league: String? = null,
    val tierOnly: Boolean = false,
    /** Each player's last [LeaderFilter.RECENT] league matches instead of [scope]. */
    val recent: Boolean = false,
    /** Whose boards: [league]'s members (null: the shown league), everyone, a nation's players ([nation]) or a club's ([club]); null picks one followed. */
    val area: LeaderArea = LeaderArea.ALL,
    val nation: String? = null,
    val club: String? = null,
) {
    companion object {
        /** League matches the "최근 10경기" boards count. */
        const val RECENT = 10
    }
}

/** Whose leaderboards: my league (the shown one), everyone, a nation's players, a club's members. */
enum class LeaderArea(val label: String) { LEAGUE("내 리그"), ALL("전체"), NATION("국가"), CLUB("클럽") }

/** 경기 › 하이라이트's filters. */
data class HighlightFilter(val allTime: Boolean = false, val followed: Boolean = false)

/** The matches a [Page.Matches] lists. */
sealed interface MatchQuery {
    data class Group(val comp: Comp, val part: Int, val group: String) : MatchQuery
    data class OfPlayer(val key: String) : MatchQuery
    data class OfClub(val id: String, val week: Int? = null) : MatchQuery
    data class OfWeek(val week: Int, val comp: Comp? = null) : MatchQuery
    data class Round(val league: String, val tier: Int, val round: Int) : MatchQuery
}

/**
 * A full-screen page over the tabs; each carries its own state (changed with [LeagueNav.replaceTop]).
 * A page's tab, stepper or scope that does not change what loads stays out of [loadKey], so
 * opening a link to the page on top replaces it instead of stacking a copy ([LeagueNav.open]).
 */
sealed interface Page {
    /** The key loaders cache by and views compare with (`view.key == page.loadKey`). */
    val loadKey: String

    data class Player(val key: String, val tab: PlayerTab = PlayerTab.OVERVIEW, val scope: StatScope = StatScope.SEASON) : Page { override val loadKey get() = "p:$key:$scope" }

    /** A club; [tab] is page state (the tab row of a club with a club world, UX 4). */
    data class Club(val id: String, val tab: ClubTab = ClubTab.OVERVIEW) : Page { override val loadKey get() = "c:$id" }
    data class League(val id: String) : Page { override val loadKey get() = "l:$id" }
    data class Nation(val code: String) : Page { override val loadKey get() = "n:$code" }
    data object Nations : Page { override val loadKey get() = "nations" }

    /** [stage] indexes the competition's flattened stage list (part × side × round); null = the stage in play. */
    data class Competition(val comp: Comp, val stage: Int? = null) : Page { override val loadKey get() = "k:${comp.code}" }

    /** [key] is a MatchRef in text form, "season:compCode:id[:board[:game]]". */
    data class Match(val key: String, val board: Int = 0, val game: Int = 0) : Page { override val loadKey get() = "m:$key" }
    data class Matches(val query: MatchQuery, val title: String) : Page { override val loadKey get() = "q:$query" }
    data class Leaderboard(val kind: LeaderKind) : Page { override val loadKey get() = "b:$kind" }
    data class Review(val season: Int) : Page { override val loadKey get() = "r:$season" }
    data class Compare(val a: String, val b: String? = null) : Page { override val loadKey get() = "v:$a:$b" }
    data class Rules(val part: RulesPart = RulesPart.TOP) : Page { override val loadKey get() = "rules" }
    data object Follows : Page { override val loadKey get() = "follows" }
    data object News : Page { override val loadKey get() = "news" }
    data object Federation : Page { override val loadKey get() = "fed" }
    data object Lab : Page { override val loadKey get() = "lab" }

    /**
     * 이적·드래프트, the 이적 센터 to be (UX 6). [tab] null: chosen when the page opens (이적 내역 before the
     * rumour weeks, 이적설 from them); [replay]: ☀ 이적시장 다시 보기's moves shown (-1: off).
     */
    data class Market(val tab: MarketTab? = null, val replay: Int = -1) : Page { override val loadKey get() = "market" }
    data object Prospects : Page { override val loadKey get() = "prospects" }
    data object Legends : Page { override val loadKey get() = "legends" }
    data object Families : Page { override val loadKey get() = "families" }

    /** 메타 리포트, 시대와 메타 to be (UX 8): its [tab] and whose style shares its chart draws ([scope]). */
    data class Meta(val tab: MetaTab = MetaTab.ERAS, val scope: ShareScope = ShareScope.ELITE) : Page { override val loadKey get() = "meta" }

    /** 탐구: fun facts of the season and the league comparison ([leagues]: the comparison first). */
    data class Facts(val leagues: Boolean = false) : Page { override val loadKey get() = "facts" }

    /** 연대기: the seasons as a timeline (champions, dynasties, the era's best, the ranking's no. 1). */
    data object Chronicle : Page { override val loadKey get() = "chronicle" }

    /** A coach (감독, UX 5); "co:" because "k:" is [Competition]'s. */
    data class Coach(val id: String) : Page { override val loadKey get() = "co:$id" }

    /** 클럽 랭킹 (UX 4.8): its list; [scope] a national league id (its nations' clubs) or null (the world), changed by the bar's scope action with replaceTop. */
    data class ClubRanking(val kind: ClubRankKind = ClubRankKind.VALUE, val scope: String? = null) : Page { override val loadKey get() = "cr:${scope ?: "*"}" }

    /**
     * 기자단 (UX 7): [league] the league whose predictions and top 5 the page shows (null: the shown
     * league at open), page state that never changes [LeagueNav.league]; [reveal]: "▶ 5위부터 발표"'s rows shown (-1: all).
     */
    data class Press(val league: String? = null, val reveal: Int = -1) : Page { override val loadKey get() = "press" }

    /** 세계 정세 (UX 9.1). */
    data object Events : Page { override val loadKey get() = "events" }

    /** One world event's arc by its [id] (an article's "KIND@season" resolved to the id when the link is built, so an arc has one key). */
    data class Event(val id: Int) : Page { override val loadKey get() = "ev:$id" }

    /** A season's World Cup story (UX 9.5): [revealed] balls of the draw shown (-1: all), [round] vote rounds shown (-1: all), [playing] steps by itself. */
    data class WcStory(val season: Int, val revealed: Int = -1, val round: Int = -1, val playing: Boolean = false) : Page { override val loadKey get() = "wc:$season" }

    /**
     * An encyclopedia page (UX 10.1): [ref] an encyclopedia reference in its encoded form ("p:g001",
     * "c:c05", "c:c05@2031", "k:k12", "l:KR", "n:KR", "x:E"); [trail] the refs read before it in this
     * reading chain, oldest first, at most [LeagueNav.TRAIL] ([LeagueNav.read], [LeagueNav.back]).
     */
    data class Encyclopedia(val ref: String, val trail: List<String> = emptyList()) : Page { override val loadKey get() = "enc:$ref" }

    /** 백과사전 검색 (UX 10.2); the query is [LeagueNav.searchQuery]. */
    data object Search : Page { override val loadKey get() = "search" }
}

/**
 * The page a link names (UX 2.4): one string form for every tappable reference that crosses a
 * view boundary (home lines, news pills, encyclopedia values, event subjects, vote rows); null for
 * anything else. Entity prefixes are the encyclopedia's: `p:<key>` a player, `c:<id>` a club
 * (`c:<id>@<founded>`, a gone or reused club's id: its encyclopedia page), `k:<id>` a coach, `l:<id>` a
 * league, `n:<code>` a nation, `x:<comp>` a competition (its [Comp.code] or [Comp] name, or an
 * honour's competition: CL, CCL, CUP, SUPER, WC; a `.<section>` after it is read past) when [has]
 * says the season holds it, else its encyclopedia page; then `ev:<id>` a world event, `wc:<season>` a
 * World Cup story, `mk[:<tab>]` 이적 센터, `pr[:<league>]` 기자단, `meta[:<tab>]` 시대와 메타,
 * `cr[:<kind>]` 클럽 랭킹 (tabs and kinds by their enum names), `m:<matchref>` a match report and
 * `enc:<ref>` an encyclopedia page.
 */
fun pageOf(link: String, has: (Comp) -> Boolean = { true }): Page? {
    val colon = link.indexOf(':')
    val head = if (colon < 0) link else link.substring(0, colon)
    val rest = if (colon < 0) null else link.substring(colon + 1)
    // Every form but the four bare ones needs something after its colon.
    val id = rest?.takeIf { it.isNotEmpty() }
    return when (head) {
        "p" -> id?.let { Page.Player(it) }
        "c" -> id?.let { if ('@' in it) Page.Encyclopedia(link) else Page.Club(it) }
        "k" -> id?.let { Page.Coach(it) }
        "l" -> id?.let { Page.League(it) }
        "n" -> id?.let { Page.Nation(it) }
        "x" -> id?.let { compOfLink(it.substringBefore('.'))?.let { c -> if (has(c)) Page.Competition(c) else Page.Encyclopedia(link) } }
        "ev" -> id?.toIntOrNull()?.let { Page.Event(it) }
        "wc" -> id?.toIntOrNull()?.let { Page.WcStory(it) }
        "mk" -> if (rest == null) Page.Market() else enumOf<MarketTab>(rest)?.let { Page.Market(it) }
        "pr" -> if (rest == null) Page.Press() else id?.let { Page.Press(it) }
        "meta" -> if (rest == null) Page.Meta() else enumOf<MetaTab>(rest)?.let { Page.Meta(it) }
        "cr" -> if (rest == null) Page.ClubRanking() else enumOf<ClubRankKind>(rest)?.let { Page.ClubRanking(it) }
        "m" -> id?.let { Page.Match(it) }
        "enc" -> id?.let { Page.Encyclopedia(it) }
        else -> null
    }
}

/** An enum entry by its name, any case; null for another word. */
private inline fun <reified E : Enum<E>> enumOf(name: String): E? = enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }

/** The competitions an `x:` link names by an honour's base (HonourKind: "CL" the 챔피언스 리그, "CCL.ASIA" a 대륙 챔피언스). */
private val HONOUR_COMPS = mapOf("CL" to Comp.CHAMPIONS, "CCL" to Comp.CONT_CHAMPIONS, "CUP" to Comp.CUP, "SUPER" to Comp.SUPER, "WC" to Comp.WORLD_CUP)

/** The competition of an `x:` link's id (before its section): a [Comp.code], an honour's base, or a [Comp] name. */
private fun compOfLink(id: String): Comp? =
    (if (id.length == 1) Comp.of(id[0]) else null) ?: HONOUR_COMPS[id] ?: enumOf<Comp>(id)

/**
 * Everything the league screens show and the screenshot harness sets: no screen state lives in
 * `remember` (only a widget's own, such as a text field's focus or a card's 펼치기). Main thread
 * only; [onLeague] is told when the shown league changes.
 */
class LeagueNav(private val onLeague: () -> Unit = {}) {
    var tab by mutableStateOf(LeagueTab.HOME)
    var matches by mutableStateOf(MatchesView.WEEK)
    var tables by mutableStateOf(TablesView.LEAGUE)
    var tableMode by mutableStateOf(TableMode.TABLE)
    var records by mutableStateOf(RecordsView.LEADERS)
    var results by mutableStateOf(ResultsFilter.ALL)
    var newsFilter by mutableStateOf(NewsFilter.ALL)
    var newsFollowed by mutableStateOf(false)

    /** The league shown (null: the controller's default) and its tier; a legacy pyramid's group within the tier. */
    var league by mutableStateOf<String?>(null)
        private set
    var tier by mutableStateOf(0)
    var group by mutableStateOf(0)

    /** 경기 › 이번 주's week; null = the week in play. */
    var week by mutableStateOf<Int?>(null)

    /** 순위 › 리그 › 라운드's round; null = the current round. */
    var round by mutableStateOf<Int?>(null)

    /** The players of 순위 변동 (at most 8); empty = the default rows. */
    var chartKeys by mutableStateOf<List<String>>(emptyList())
    var highlights by mutableStateOf(HighlightFilter())
    var leaders by mutableStateOf(LeaderFilter())
    var ladderQuery by mutableStateOf("")
    var ladderSort by mutableStateOf(LadderSort.RATING)
    var ladderFilter by mutableStateOf(LadderFilter())

    /** 리그 랭킹's confederation; null = 전체. */
    var rankingConfed by mutableStateOf<Confed?>(null)

    /** 국가대표 랭킹's order. */
    var nationSort by mutableStateOf(NationSort.CUPS)

    /** The player the lab opens with as its A (a player page's "⚗ 연구소에서 붙이기"); the lab takes it once. */
    var labPick by mutableStateOf<String?>(null)
    var sheet by mutableStateOf(Sheet.NONE)

    /** 백과사전 검색's query ([Page.Search]'s field and 기록 › 더 보기's first row). */
    var searchQuery by mutableStateOf("")

    /** What [Sheet.INFO] explains (an ⓘ sets it, then the sheet). */
    var info by mutableStateOf(InfoTopic.FINANCE)

    /** The pages over the tabs, the top one last (at most [MAX_PAGES]). */
    val pages = mutableStateListOf<Page>()
    val page: Page? get() = pages.lastOrNull()

    private val chipStates = HashMap<LeagueTab, LazyListState>()
    private val pageChipStates = HashMap<PageChips, LazyListState>()

    /** The chip row's scroll state, hoisted so the harness can check that no row overflows. */
    fun chipState(t: LeagueTab): LazyListState = chipStates.getOrPut(t) { LazyListState() }

    /** A page's chip row's scroll state, hoisted as the tabs' are. */
    fun chipState(row: PageChips): LazyListState = pageChipStates.getOrPut(row) { LazyListState() }

    /**
     * Opens [p] on top (nothing when it is already the top page); a ninth page drops the oldest.
     * A top page with [p]'s load key is replaced instead: a link to the page shown (another tab or
     * stepper of it) never stacks a copy.
     */
    fun open(p: Page) {
        val top = page
        if (top == p) return
        if (top != null && top.loadKey == p.loadKey) {
            replaceTop(p)
            return
        }
        pages += p
        while (pages.size > MAX_PAGES) pages.removeAt(0)
    }

    /**
     * A link inside an encyclopedia page: [ref]'s page takes the top page's place and the top one
     * joins its trail (the last [TRAIL] kept), so a reading chain costs one stack slot; from any
     * other page it opens on top.
     */
    fun read(ref: String) {
        val top = page as? Page.Encyclopedia ?: return open(Page.Encyclopedia(ref))
        if (top.ref == ref) return
        replaceTop(Page.Encyclopedia(ref, (top.trail + top.ref).takeLast(TRAIL)))
    }

    /** The encyclopedia page read before the top one (its trail's last), in its place; false when the top page has no trail. */
    private fun backInTrail(): Boolean {
        val top = page as? Page.Encyclopedia ?: return false
        if (top.trail.isEmpty()) return false
        replaceTop(Page.Encyclopedia(top.trail.last(), top.trail.dropLast(1)))
        return true
    }

    /** A page bar's ← on [p]: back along an encyclopedia page's trail, else [p] closes. */
    fun leave(p: Page) {
        if (p === page && backInTrail()) return
        close(p)
    }

    /** Changes the top page's own state (a player tab, a report board or game, a competition stage). */
    fun replaceTop(p: Page) {
        if (pages.isEmpty()) open(p) else pages[pages.lastIndex] = p
    }

    /** Closes [p] (normally the top page: its ← or 닫기); the sheet and the other pages stay. */
    fun close(p: Page) {
        val i = pages.lastIndexOf(p)
        if (i >= 0) pages.removeAt(i)
    }

    /** Goes to tab [t] at its root: pages and the sheet close. */
    fun go(t: LeagueTab) {
        pages.clear()
        sheet = Sheet.NONE
        tab = t
    }

    /** Shows league [id] (null: the default) at [tier]: its first group and the current round. */
    fun selectLeague(id: String?, tier: Int = 0) {
        league = id
        this.tier = tier
        group = 0
        round = null
        onLeague()
    }

    /**
     * System back inside the league: the sheet, then the encyclopedia trail, then the top page, then
     * to 홈; false when back should leave the league.
     */
    fun back(): Boolean = when {
        sheet != Sheet.NONE -> {
            sheet = Sheet.NONE
            true
        }
        backInTrail() -> true
        pages.isNotEmpty() -> {
            pages.removeAt(pages.lastIndex)
            true
        }
        tab != LeagueTab.HOME -> {
            tab = LeagueTab.HOME
            true
        }
        else -> false
    }

    companion object {
        const val MAX_PAGES = 8

        /** Encyclopedia pages a reading chain remembers ([Page.Encyclopedia.trail]). */
        const val TRAIL = 20
    }
}
