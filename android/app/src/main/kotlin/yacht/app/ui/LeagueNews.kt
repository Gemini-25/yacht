package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.ClockView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueView
import yacht.app.core.NewsFilter
import yacht.app.core.Page
import yacht.app.core.Screen
import yacht.app.core.pageOf
import yacht.engine.league.ArcKind
import yacht.engine.league.Article
import yacht.engine.league.Comp
import yacht.engine.league.Confed
import yacht.engine.league.LeagueIds
import yacht.engine.league.MatchRef
import yacht.engine.league.Nations
import yacht.engine.league.SeasonCalendar

// 뉴스 (UX design 5.4, 11): the league's articles by week, filtered (전체 · 주요 · 리그 · 국제 · 이적·클럽)
// and, with the bar's ★, only the followed ones; the week's big story on top; each article with
// its kind, where it happened, the people in it (players, clubs, coaches, nations), the page of
// its story (기자단, 이적 센터, …) and its match (📋 리포트, ▶) when it has one.

/**
 * The kinds of [NewsFilter.WORLD] (with the CL, CCL and nation cups' news by their competition):
 * the international stage and the league ranking, the 요트협회, the world events, the World Cup's
 * host vote and draw, the eras, and the media's power ranking, predictions and votes (UX 11).
 */
private fun worldKind(kind: String): Boolean = kind in WORLD_KINDS || WORLD_PREFIXES.any { kind.startsWith(it) }

private val WORLD_KINDS = setOf(
    "intl_week", "league_ranking", "draw_ccl", "draw_cl", "draw_wc", "draw_cc", "wc_open", "cc_open", "nation_squad", "nation_awards",
    "merger_proposed", "league_merged", "league_split", "counter_style", "imitation", "press_award", "press_awards", "predictions",
    "prediction_scorecard", "power_ranking",
)
private val WORLD_PREFIXES = listOf("federation_", "superleague_", "investor_", "crisis_", "boom_", "wc_", "era_")

/**
 * The kinds of [NewsFilter.MARKET] (이적·클럽, UX 11): transfers and the market, drafts and the
 * youth, the coaches, and the clubs' own news (money, owners, sponsors, stadiums, fans, academies,
 * legends, captains, rivals).
 */
private fun marketKind(kind: String): Boolean = kind in MARKET_KINDS || MARKET_PREFIXES.any { kind.startsWith(it) }

private val MARKET_KINDS = setOf(
    "market_week", "deal_collapsed", "record_fee", "rival_move", "release_clause", "free_agent", "contract_renewal", "winter_window",
    "deadline_day", "loan_digest", "return_home", "market_digest", "draft", "youth", "player_to_coach", "forced_sale", "administration",
    "rescue", "takeover", "owner_exit", "wonderkid", "academy_graduates", "one_club_man", "captain", "derby_result", "rivalry_born",
)
private val MARKET_PREFIXES = listOf("transfer", "coach_", "club_", "sponsor_", "stadium_", "fan_")

/** A filter's chip label: [NewsFilter.MARKET] holds the clubs' news too since UX 11, so it reads 이적·클럽 (its enum label is older). */
internal fun filterLabel(f: NewsFilter): String = if (f == NewsFilter.MARKET) "이적·클럽" else f.label

/** Digest articles: their keys are the people of a list ("+{n}명" opens it). */
private val DIGESTS = setOf("cup_digest", "group_digest", "milestones_week", "milestones", "league_titles_digest", "market_digest", "club_digest", "retirements", "rookies")

/** True when [a] passes [filter]. */
internal fun newsShown(a: Article, filter: NewsFilter): Boolean = when (filter) {
    NewsFilter.ALL -> true
    NewsFilter.TOP -> a.importance >= 4
    // A legacy season's articles have no league scope: its league's own news by its competition.
    NewsFilter.LEAGUE -> if (a.scope.isNotEmpty()) Confed.entries.none { it.name == a.scope } else a.comp == Comp.LEAGUE
    NewsFilter.WORLD -> a.comp == Comp.CHAMPIONS || a.comp == Comp.CONT_CHAMPIONS || a.comp == Comp.WORLD_CUP || a.comp == Comp.CONTINENTAL ||
        worldKind(a.kind) || Confed.entries.any { it.name == a.scope }
    NewsFilter.MARKET -> marketKind(a.kind)
}

/**
 * True when [a] is about something followed: a followed player, club or nation among its keys, or
 * its scope a followed league (or the league of a followed nation).
 */
internal fun followedNews(a: Article, favourites: Set<String>): Boolean {
    if (a.keys.any { it in favourites || nationFavourite(it) in favourites || LeagueSelector.CLUB + it in favourites }) return true
    if (a.scope.isEmpty()) return false
    if (LeagueSelector.LEAGUE + a.scope in favourites) return true
    return favourites.any { f -> f.startsWith(NATION_PREFIX) && nationLeague(f.removePrefix(NATION_PREFIX)) == a.scope }
}

/** The league a nation's players mostly play in, by id: its own, else its confederation's 연합 리그. */
private fun nationLeague(code: String): String? = Nations.of(code)?.let { n -> if (LeagueIds.known(code)) code else LeagueIds.region(n.confed) }

/** [Page.News]: the filter chips, the week's big story, then the articles by week (all of them, [LeagueController.loadNews]). */
@Composable
internal fun NewsPage(c: AppController, v: LeagueView) {
    val lg = c.league
    val nav = lg.nav
    // Everything the league keeps, read again when the newest article changes.
    LaunchedEffect(v.news.firstOrNull()) { lg.loadNews() }
    val list = lg.newsList
    val all = list?.articles ?: v.news
    // The world events' ids, for their articles' pills (only while there is such an article).
    val arcs = remember(all) { all.any { arcOf(it.kind) != null } }
    LaunchedEffect(arcs, v.news.firstOrNull()) { if (arcs) lg.eventPages.loadEvents() }
    val replay = list?.replayable.orEmpty()
    val favs = lg.favourites
    val filter = nav.newsFilter
    val onlyFollowed = nav.newsFollowed
    val shown = remember(all, filter, onlyFollowed, favs) {
        // The international week's table after every matchday: only the newest of each competition and season.
        val told = HashSet<Pair<Int, Comp?>>()
        all.filter { newsShown(it, filter) && (!onlyFollowed || followedNews(it, favs)) && (it.kind != INTL_WEEK || told.add(it.season to it.comp)) }
    }
    val clock = v.clock
    // The newest importance-5 article of the last two weeks, on top in large.
    val lead = remember(shown, clock) { shown.firstOrNull { a -> a.importance >= 5 && clock != null && a.season == clock.season && a.week >= clock.week - 1 } }
    Column {
        NewsChips(filter) { nav.newsFilter = it }
        if (shown.isEmpty()) {
            CenterText(
                when {
                    onlyFollowed -> "응원하는 선수나 클럽, 국가, 리그의 소식이 아직 없어요. 선수를 눌러 ‘☆ 응원하기’를, 순위 › 랭킹 › 국가대표 랭킹에서 나라를 골라 ‘☆ 응원’을 눌러 보세요."
                    filter != NewsFilter.ALL -> "‘${filterLabel(filter)}’ 소식이 아직 없어요."
                    else -> "리그가 진행되면 우승 경쟁, 이변, 기록, 이적 같은 소식이 기사로 올라와요."
                },
            )
            return@Column
        }
        Articles(c, v, shown.filter { it !== lead }, lead, replay)
    }
}

/** The filters in one row that fits a phone (13 + 4 × 55 + 85 + 42 = 360 dp). */
@Composable
private fun NewsChips(selected: NewsFilter, onSelect: (NewsFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (f in NewsFilter.entries) FilterChip(selected = f == selected, onClick = { onSelect(f) }, label = { Text(filterLabel(f), fontSize = 12.sp, maxLines = 1) })
    }
}

@Composable
private fun Articles(c: AppController, v: LeagueView, all: List<Article>, lead: Article?, replay: Set<String>) {
    val clock = v.clock
    // By the week they happened in (newest season and week first, newest first within a week),
    // under a heading per week; news from before weeks were kept ends each season.
    val news = remember(all) {
        all.withIndex().sortedWith(compareByDescending<IndexedValue<Article>> { it.value.season }.thenByDescending { weekOrder(it.value.week) }.thenBy { it.index }).map { it.value }
    }
    // Two articles can read the same (the same lead change twice): number the repeats.
    val keys = remember(news) {
        val seen = HashMap<String, Int>()
        news.map { a ->
            val base = "${a.season}-${a.week}-${a.kind}-${a.headline}-${a.keys}"
            val n = seen.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base-$n"
        }
    }
    // Another season's post-season starts at its first week with playoff news (that season's
    // length is not kept): its weeks from there are named as the season named them.
    val firstPost = remember(news) {
        news.filter { (it.comp == Comp.TITLE_PLAYOFF || it.comp == Comp.PROMOTION_PLAYOFF) && it.week > 0 && it.week < SeasonCalendar.CLOSING }
            .groupBy { it.season }.mapValues { (_, l) -> l.minOf { it.week } }
    }
    val headings = remember(news, clock) { news.map { headingText(it, clock, firstPost[it.season]) } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lead != null) item(key = "lead") { ArticleCard(c, v, lead, replayable = lead.ref in replay, lead = true) }
        itemsIndexed(news, key = { i, _ -> keys[i] }) { i, a ->
            // A heading where the season or its text changes (weeks that read the same share one).
            val prev = news.getOrNull(i - 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prev == null || prev.season != a.season || headings[i - 1] != headings[i]) WeekHeading(a, headings[i])
                ArticleCard(c, v, a, replayable = a.ref in replay)
            }
        }
    }
}

/** Week order inside a season: the close last, news without a week first. */
private fun weekOrder(w: Int): Int = if (w == SeasonCalendar.NONE) -1 else w

/**
 * The heading of [a]'s week: this season's by the clock ("포스트시즌 2주차"), another season's
 * from [firstPost], its first week with playoff news, on as "포스트시즌 N주차".
 */
internal fun headingText(a: Article, clock: ClockView?, firstPost: Int?): String {
    val current = clock != null && a.season == clock.season
    val post = !current && firstPost != null && a.week >= firstPost && a.week < SeasonCalendar.CLOSING
    val week = if (post) "포스트시즌 ${a.week - firstPost!! + 1}주차" else weekLabel(a.week, a.season, clock)
    return when {
        a.week == SeasonCalendar.NONE -> "시즌 ${a.season} · 주차 기록 없음"
        current && a.week == clock!!.week && !clock.over -> "$week · 이번 주"
        current -> week
        a.week >= SeasonCalendar.CLOSING -> "시즌 ${a.season} 마감"
        else -> "시즌 ${a.season} · $week"
    }
}

@Composable
private fun WeekHeading(a: Article, text: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.secondary)
        if (a.week == SeasonCalendar.NONE) Text("업데이트 전에 나온 소식이라 몇 주차인지 몰라요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/**
 * An article's kind as an icon: 🏆 titles, ⚡ upsets, 🔄 comebacks, ✈️ transfers, 🏛 the 요트협회, 📈
 * rankings and races, 📝 records, 🎂 milestones; the club world's, the media's and the world
 * events' kinds one icon a meaning (UX 11: [arcIcon], [STORY_ICONS]); 📰 the rest.
 */
internal fun kindIcon(kind: String): String = when {
    kind == "title" || kind == "final" || kind == "award" || kind.endsWith("_title") || kind.endsWith("_champions") || kind == "league_titles_digest" || kind == "season_review" -> "🏆"
    kind == "upset" || kind == "league_upset" -> "⚡"
    kind == "comeback" -> "🔄"
    kind.startsWith("transfer") || kind == "return_home" || kind == "market_digest" || kind == "draft" -> "✈️"
    kind.startsWith("federation_") -> "🏛"
    kind == "league_ranking" || kind == "intl_week" || kind == "club_race" || kind == "title_race" -> "📈"
    kind == "record" || kind == "record_chase" || kind == "hall_of_fame" || kind == "dynasty" || kind == "dynasties" -> "📝"
    kind.startsWith("milestone") -> "🎂"
    else -> arcOf(kind)?.let(::arcIcon) ?: STORY_ICONS[kind] ?: STORY_PREFIX_ICONS.firstOrNull { kind.startsWith(it.first) }?.second ?: "📰"
}

/** The club world's, the market's and the media's kinds by icon (today's kinds keep theirs above). */
private val STORY_ICONS: Map<String, String> = buildMap {
    for (k in listOf("club_accounts", "club_trouble", "forced_sale", "administration", "club_folded", "club_world_digest")) put(k, "💰")
    for (k in listOf("takeover", "owner_exit", "rescue")) put(k, "🏢")
    put("player_to_coach", "👔")
    for (k in listOf("market_week", "deal_collapsed", "record_fee", "rival_move", "release_clause", "free_agent", "contract_renewal", "winter_window", "deadline_day", "loan_digest")) put(k, "✈️")
    for (k in listOf("wonderkid", "academy_graduates")) put(k, "🌱")
    for (k in listOf("club_legend", "one_club_man")) put(k, "🏛")
    put("captain", "©")
    for (k in listOf("derby_result", "rivalry_born")) put(k, "⚔")
    for (k in listOf("counter_style", "imitation")) put(k, "🧭")
    put("power_ranking", "📈")
    for (k in listOf("predictions", "prediction_scorecard")) put(k, "🔮")
    for (k in listOf("press_award", "press_awards")) put(k, "🎙")
    for (k in listOf("wc_bids", "wc_host_vote")) put(k, "🗳")
    put("wc_draw_ceremony", "🎟")
    put("club_ranking", "🏅")
}

/** Kinds of the phase by their prefix (no kind of today starts so). */
private val STORY_PREFIX_ICONS = listOf("sponsor_" to "🪧", "stadium_" to "🏟", "fan_" to "📣", "coach_" to "👔", "era_" to "🧭")

/** The world event an article is about, by its kind (the arcs' news, UX 9); null for another. */
private fun arcOf(kind: String): ArcKind? = when {
    kind.startsWith("superleague_") -> ArcKind.SUPER_LEAGUE
    kind.startsWith("investor_") -> ArcKind.INVESTOR
    kind.startsWith("crisis_") -> ArcKind.CRISIS
    kind == "merger_proposed" || kind == "league_merged" || kind == "league_split" -> ArcKind.MERGER
    kind.startsWith("boom_") -> ArcKind.BOOM
    else -> null
}

/**
 * An article's story pill (UX 11): its label and the [pageOf] link it opens, for the media's,
 * the eras', the world events', the World Cup's, the market's and the club ranking's news; null
 * for the others. A world event's article names its arc by [arcId] (the arc of its kind under
 * way in its season); no pill while that is not known.
 */
internal fun articleLink(a: Article, arcId: (ArcKind, Int) -> Int?): Pair<String, String>? {
    arcOf(a.kind)?.let { k -> return arcId(k, a.season)?.let { "🌐 자세히 ›" to "ev:$it" } }
    return when {
        a.kind == "power_ranking" -> "📈 파워 랭킹 ›" to "pr"
        a.kind == "predictions" || a.kind == "prediction_scorecard" -> "🔮 시즌 전망 ›" to (if (LeagueIds.known(a.scope)) "pr:${a.scope}" else "pr")
        a.kind == "press_award" || a.kind == "press_awards" -> "🎙 투표 결과 ›" to "pr"
        a.kind.startsWith("era_") || a.kind == "counter_style" || a.kind == "imitation" -> "🧭 시대 ›" to "meta"
        // The host vote is held in the season before its World Cup; the draw opens the World Cup's season.
        a.kind == "wc_bids" -> "🗳 유치전 ›" to "wc:${a.season + 1}"
        a.kind == "wc_host_vote" -> "🗳 투표 결과 ›" to "wc:${a.season + 1}"
        a.kind == "wc_draw_ceremony" -> "🎟 추첨식 ›" to "wc:${a.season}"
        a.kind in MARKET_STORIES -> "💸 이적 센터 ›" to "mk"
        a.kind == "club_ranking" -> "🏅 클럽 랭킹 ›" to "cr"
        else -> null
    }
}

/** The market's kinds whose pill opens 이적 센터. */
private val MARKET_STORIES = setOf("market_week", "deal_collapsed", "record_fee", "rival_move", "release_clause", "free_agent", "winter_window", "deadline_day", "loan_digest")

/** Where [a] happened, as its card's top line: its league ("🇰🇷 한국 리그"), a confederation's 대륙 챔피언스, or its competition. */
private fun scopeLine(a: Article, clock: ClockView?): String {
    val where = when {
        a.scope.isEmpty() -> null
        Confed.entries.any { it.name == a.scope } -> Confed.valueOf(a.scope).let { "${it.icon} ${it.label}" }
        LeagueIds.known(a.scope) -> "${LeagueIds.icon(a.scope)} ${LeagueIds.label(a.scope)}"
        else -> null
    }
    val comp = a.comp?.takeIf { it != Comp.LEAGUE || where == null }?.let { compIcon(it) + " " + it.label }
    return listOfNotNull(where, comp, "시즌 ${a.season}".takeIf { clock == null || a.season != clock.season }).joinToString(" · ").ifEmpty { "리그 소식" }
}

/** An article as other screens list it (the records room, the player page, the report): a tap opens its first key ([onOpen]). */
@Composable
internal fun ArticleCard(a: Article, clock: ClockView?, onOpen: (String) -> Unit) {
    ArticleFrame(a, clock, lead = false, compact = false, onClick = { a.keys.firstOrNull()?.let(onOpen) }) {}
}

/**
 * An article of the news page and the home: kind and scope, 🔥 by importance, the headline and
 * body, up to three of its people (players → their page, clubs → theirs; a digest's list behind
 * "+{n}명") and its match ("📋 리포트", "▶" when [replayable]). [compact]: the home's, without the
 * people; [lead]: the week's big story, in large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArticleCard(c: AppController, v: LeagueView, a: Article, compact: Boolean = false, replayable: Boolean = false, lead: Boolean = false) {
    val lg = c.league
    val nav = lg.nav
    val ref = a.ref?.let { MatchRef.decode(it) }
    // The home's card says its match by a 📋 at the top (no row of its own).
    val report = ref?.takeIf { compact }?.let { r -> { openReport(lg, r) } }
    ArticleFrame(a, v.clock, lead, compact, onClick = { a.keys.firstOrNull { lg.isAi(it) }?.let { nav.open(Page.Player(it)) } ?: ref?.let { openReport(lg, it) } }, report = report) {
        if (compact) return@ArticleFrame
        val people = peopleOf(lg, v, a)
        val story = articleLink(a, lg.eventPages::arcId)?.let { (label, link) -> pageOf(link)?.takeIf(::linkable)?.let { label to it } }
        var open by rememberSaveable(a.season, a.week, a.headline) { mutableStateOf(false) }
        val digest = a.kind in DIGESTS && people.size > PEOPLE
        if (people.isNotEmpty() || ref != null || story != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                for ((label, page) in if (open) people else people.take(PEOPLE)) Pill(label) { nav.open(page) }
                if (people.size > PEOPLE && !open) Pill(if (digest) "+${people.size - PEOPLE}명" else "+${people.size - PEOPLE}") { open = true }
                story?.let { (label, page) -> Pill(label, accent = true) { nav.open(page) } }
                if (ref != null) {
                    Pill("📋 리포트", accent = true) { openReport(lg, ref) }
                    if (replayable) Pill("▶", accent = true) { watchRef(c, ref) }
                }
            }
        }
    }
}

/** People named on an article's card: (label, page) of its players, clubs and coaches (and a nation cup's teams). */
private fun peopleOf(lg: LeagueController, v: LeagueView, a: Article): List<Pair<String, Page>> = a.keys.distinct().mapNotNull { k ->
    when {
        lg.isAi(k) -> lg.name(k) to Page.Player(k)
        v.badges.containsKey(k) -> v.badges.getValue(k).name to Page.Club(k)
        v.coaches.containsKey(k) -> Page.Coach(k).takeIf(::linkable)?.let { v.coaches.getValue(k) to it }
        (a.comp == Comp.WORLD_CUP || a.comp == Comp.CONTINENTAL) && Nations.of(k) != null -> Nations.of(k)!!.let { "${it.flag} ${it.name}" } to Page.Nation(k)
        else -> null
    }
}

/** People shown on a card before "+{n}". */
private const val PEOPLE = 3

/** The desk's article after a matchday of the 챔피언스 리그 or the 대륙 챔피언스 (the league coefficients it earned). */
private const val INTL_WEEK = "intl_week"

/** The report of the match [ref] names (at its board and game). */
private fun openReport(lg: LeagueController, ref: MatchRef) {
    lg.nav.open(Page.Match(ref.whole.encode(), maxOf(0, ref.board), maxOf(0, ref.game)))
}

/** Plays [ref]'s match again from its board and game. */
private fun watchRef(c: AppController, ref: MatchRef) {
    c.league.center.watch(ref.whole.copy(board = ref.board).encode(), game = maxOf(0, ref.game))
    c.screen = Screen.WATCH
}

@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        label, style = MaterialTheme.typography.labelSmall, color = if (accent) cs.primary else cs.onSurfaceVariant, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * An article's card: its kind, scope, 🔥 and its match's 📋 ([report], the home's), headline and
 * body, then [extra] (its people and match).
 */
@Composable
private fun ArticleFrame(a: Article, clock: ClockView?, lead: Boolean, compact: Boolean, onClick: () -> Unit, report: (() -> Unit)? = null, extra: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (lead || a.importance >= 5) cs.tertiaryContainer else cs.surface),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (lead) 12.dp else if (compact) 7.dp else 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindIcon(a.kind), fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                Text(scopeLine(a, clock), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.importance >= 4) Text("🔥".repeat(a.importance - 3), fontSize = 11.sp)
                if (report != null) Pill("📋 리포트", accent = true, onClick = report)
            }
            Text(
                a.headline, fontWeight = FontWeight.Bold,
                style = if (lead) MaterialTheme.typography.titleMedium else if (a.importance >= 4) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            Text(
                a.body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                maxLines = if (compact) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            extra()
        }
    }
}
