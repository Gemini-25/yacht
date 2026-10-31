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

/** The leaderboards, [first] the six shown before "다른 부문 6개 ›". */
enum class LeaderKind(val label: String, val first: Boolean) {
    SCORER("득점왕", true), YACHTS("요트왕", true), MVP("MVP 레이스", true),
    CLUTCH("클러치", true), COMEBACK("역전의 명수", true), LUCKY("행운아", true), BONUS("보너스왕", false), SWEEPS("완승왕", false),
    UNLUCKY("불운", false), GIANT("거인 사냥꾼", false), STEADY("꾸준함", false), OVER300("300점 클럽", false),
}

/** The part of the rules page a link opens at. */
enum class RulesPart { TOP, LEAGUE, CUPS, CLUBS, FEDERATION }

/** The bottom sheet shown over the league screen (one at a time). */
enum class Sheet {
    NONE, SETTINGS, LEAGUES, WEEKS, TABLE_MODE, RESULTS_FILTER, HIGHLIGHT_FILTER, LADDER_FILTER, LEADER_FILTER,
    CONFED, STAGE, PLAYER_ACTIONS, RATING_INFO,
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

/** A full-screen page over the tabs; each carries its own state (changed with [LeagueNav.replaceTop]). */
sealed interface Page {
    /** The key loaders cache by and views compare with (`view.key == page.loadKey`). */
    val loadKey: String

    data class Player(val key: String, val tab: PlayerTab = PlayerTab.OVERVIEW, val scope: StatScope = StatScope.SEASON) : Page { override val loadKey get() = "p:$key:$scope" }
    data class Club(val id: String) : Page { override val loadKey get() = "c:$id" }
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
    data object Market : Page { override val loadKey get() = "market" }
    data object Prospects : Page { override val loadKey get() = "prospects" }
    data object Legends : Page { override val loadKey get() = "legends" }
    data object Families : Page { override val loadKey get() = "families" }
    data object Meta : Page { override val loadKey get() = "meta" }

    /** 탐구: fun facts of the season and the league comparison ([leagues]: the comparison first). */
    data class Facts(val leagues: Boolean = false) : Page { override val loadKey get() = "facts" }

    /** 연대기: the seasons as a timeline (champions, dynasties, the era's best, the ranking's no. 1). */
    data object Chronicle : Page { override val loadKey get() = "chronicle" }
}

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
    var sheet by mutableStateOf(Sheet.NONE)

    /** The pages over the tabs, the top one last (at most [MAX_PAGES]). */
    val pages = mutableStateListOf<Page>()
    val page: Page? get() = pages.lastOrNull()

    private val chipStates = HashMap<LeagueTab, LazyListState>()

    /** The chip row's scroll state, hoisted so the harness can check that no row overflows. */
    fun chipState(t: LeagueTab): LazyListState = chipStates.getOrPut(t) { LazyListState() }

    /** Opens [p] on top (nothing when it is already the top page); a ninth page drops the oldest. */
    fun open(p: Page) {
        if (page == p) return
        pages += p
        while (pages.size > MAX_PAGES) pages.removeAt(0)
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

    /** System back inside the league: the sheet, then the top page, then to 홈; false when back should leave the league. */
    fun back(): Boolean = when {
        sheet != Sheet.NONE -> {
            sheet = Sheet.NONE
            true
        }
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
    }
}
