package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import yacht.engine.explain.Ko
import yacht.engine.league.AiOdds
import yacht.engine.league.ArcKind
import yacht.engine.league.Era
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Media
import yacht.engine.league.Meta
import yacht.engine.league.MetaBook
import yacht.engine.league.PowerRow
import yacht.engine.league.PressAwards
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StyleFamily
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 기자단 and 시대와 메타 on request (`lg.storyPages`, UX 7-8), read under the lock off the main thread
 * ([PageLoads]), never in the 300 ms snapshot: the press (the power ranking as the desk ranked it,
 * the season's predictions, the last scorecard and press vote), the meta (eras, style shares,
 * matchups, newcomer weights) and what both have to show ([status], for the places that link to
 * them). A season or a save without the press or the meta gives empty parts, never an error.
 */
class StoryPages internal constructor(private val host: LeagueHost) {
    private var pressView by mutableStateOf<PressView?>(null)
    private var metaView by mutableStateOf<MetaView?>(null)
    private var statusView by mutableStateOf<StoryStatus?>(null)
    private val loads = PageLoads(host) {
        pressView = null
        metaView = null
        statusView = null
    }

    /** 기자단 as loaded last ([PressView.league] says whose predictions it holds); null before, or once the league is replaced. */
    val press: PressView? get() = loads.current(pressView)

    /** 시대와 메타's 시대·스타일 as loaded last. */
    val meta: MetaView? get() = loads.current(metaView)

    /** What 기자단 and 시대와 메타 have to show, as loaded last. */
    val status: StoryStatus? get() = loads.current(statusView)

    /** Loads 기자단 with [league]'s predictions and top 5 into [press] (≈ 4 ms: the predictions' 400 runs). */
    fun loadPress(league: String) = loads.load("press", { w -> pressOf(w, league) }) { pressView = it }

    /** Loads the eras, the style shares and the meta into [meta]. */
    fun loadMeta() = loads.load("meta", ::metaOf) { metaView = it }

    /** Loads [status] (O(1): flags and the era's name). */
    fun loadStatus() = loads.load("status", ::statusOf) { statusView = it }

    // ---- 기자단 (UX 7) ----------------------------------------------------------------------------

    /** 기자단 of [league] (UX 7): every part from the books and the plan, the predictions computed again (pure). */
    private fun pressOf(w: LeagueWorld, league: String): PressView? {
        val p = w.plan ?: return null
        val s = p.number
        val week = runCatching { p.week }.getOrDefault(1)
        if (!p.league.leagues.national) {
            return PressView(
                s, week, phaseOf(week, false), league, 0, 0, emptyList(), null, emptyList(), emptyList(), emptyList(), null, null, emptyList(),
                0, 0, emptyList(), emptyList(), national = false,
            )
        }
        val drawn = drawnRatings(w, p)
        val gone = { k: String -> !w.present(k) }
        val desk = w.desk
        val ours = desk.season == s
        val media = w.world.media
        // The press vote and the scorecard of the last close (the review weeks put them first).
        val voteSeason = w.stats.pressVotes.keys.filter { it < s || (it == s && w.seasonOver) }.maxOrNull() ?: 0
        val tallies = if (voteSeason > 0) w.stats.pressVotes(voteSeason) else emptyList()
        val sc = media.allScorecards.lastOrNull { it.season < s || (it.season == s && w.seasonOver) }
        val fresh = (voteSeason == s - 1 && tallies.isNotEmpty()) || sc?.season == s - 1
        val names = HashMap<String, String>()
        fun shown(k: String) = names.getOrPut(k) { host.shown(k) }
        val column = w.news.all.firstOrNull { it.kind == POWER_KIND && it.season <= s }
        // This season's ranking as the desk last ranked it; in its first weeks, the last column of the season before.
        var powerSeason = s
        var powerWeek = if (ours) desk.powerWeek else 0
        val power = if (powerWeek > 0) {
            Media.power(p, desk, drawn, null, Media.WORLD_TOP, gone).map { powerLine(p, it, shown(it.key)) }
        } else {
            column?.takeIf { it.season == s - 1 }?.let { a ->
                powerSeason = a.season
                powerWeek = a.week.takeIf { it > 0 } ?: 0
                a.keys.take(Media.WORLD_TOP).mapIndexed { i, k -> PowerLine(k, shown(k), host.badge(k), i + 1, null, "", null, "") }
            }.orEmpty()
        }
        val id = p.league.leagues.byId(league)?.takeIf { it.national }?.id ?: Media.leagues(p, drawn).firstOrNull() ?: league
        val leagueTop = if (ours && desk.powerWeek > 0) Media.power(p, desk, drawn, id, Media.LEAGUE_TOP, gone).map { powerLine(p, it, shown(it.key)) } else emptyList()
        fun top(m: Map<String, Int>) = m.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(TOP_WEEKS).map { it.key to it.value }
        val weeksTop = if (ours) top(desk.weeksTop) else emptyList()
        // Career weeks at no. 1: the closes' (`pr1`) and this season's so far.
        val career = HashMap(media.careerTop)
        if (ours) for ((k, n) in desk.weeksTop) career.merge(k, n, Int::plus)
        val careerTop = top(career)
        for ((k, _) in weeksTop + careerTop) shown(k)
        val votes = PressAwards.AWARDS.mapNotNull { (code, label) ->
            val t = tallies.firstOrNull { it.award == code } ?: return@mapNotNull null
            val coach = code == PressAwards.COACH
            val coaches = if (coach) w.clubs?.let(::coachNames).orEmpty() else emptyMap()
            val rows = t.rows.take(if (code == PressAwards.GOLDEN_DICE) VOTE_ROWS else AWARD_ROWS).map { r ->
                if (coach) VoteLine("k:${r.key}", coaches[r.key] ?: r.key, r.points, r.firsts) else VoteLine("p:${r.key}", shown(r.key), r.points, r.firsts)
            }
            AwardVote(code, label, rows)
        }
        val goldenDice = w.honours.all.filter { it.kind == PressAwards.GOLDEN_DICE }.sortedByDescending { it.season }.take(GOLDEN_KEPT).map { it.season to shown(it.key) }
        val scorecard = sc?.let { c ->
            ScorecardView(
                c.season, c.hits, c.leagues, c.top3, c.relHits, c.relPicks, c.rho,
                c.over?.let { Swing(it.key, shown(it.key), it.predicted, it.final) }, c.flop?.let { Swing(it.key, shown(it.key), it.predicted, it.final) },
                c.perLeague.map { LeagueScore(it.league, it.hit, it.top3, it.relHits, it.rho) },
            )
        }
        val history = media.allScorecards.filter { it.season <= (sc?.season ?: 0) }.sortedByDescending { it.season }.take(HISTORY)
            .map { Triple(it.season, "${it.hits}/${it.leagues}", it.rho) }
        return PressView(
            s, week, phaseOf(week, fresh), id, powerWeek, Media.firstWeek(p), power, column?.takeIf { it.season == powerSeason }, leagueTop,
            weeksTop, careerTop, predictionOf(p, drawn, id, ::shown), scorecard, history,
            voteSeason, tallies.firstOrNull()?.jury ?: 0, votes, goldenDice,
            powerSeason = powerSeason, names = weeksTop.plus(careerTop).associate { (k, _) -> k to shown(k) },
            firstRanking = ours && desk.powerWeek > 0 && desk.powerPrevWeek == 0,
        )
    }

    /** A row of the power ranking: its league's 1부 ("한국 1부"), its points above expectation and its last five's wins. */
    private fun powerLine(p: SeasonPlan, r: PowerRow, shown: String): PowerLine {
        val where = p.league.leagueOf(r.key)?.let { LeagueIds.division(it.id, 0) }.orEmpty()
        return PowerLine(r.key, shown, host.badge(r.key), r.rank, r.prev?.let { it - r.rank }, where, r.score.roundToInt(), StoryLines.powerSub(r.points - r.expected, r.wins5), r.weeksTop)
    }

    /** League [id]'s predictions ([Media.prediction], the open's own numbers) with each pick's place in its table now. */
    private fun predictionOf(p: SeasonPlan, drawn: (String) -> Double, id: String, shown: (String) -> String): PredictionView? {
        val pr = runCatching { Media.prediction(p, drawn, id) }.getOrNull() ?: return null
        val top = p.league.leagues.byId(id)?.top
        val table = top?.let { runCatching { p.league.table(it) }.getOrNull() }.orEmpty()
        val played = table.any { it.played > 0 }
        val place = table.withIndex().associate { (i, st) -> st.key to i + 1 }
        fun pick(o: AiOdds, pct: Double?) = Pick(o.key, shown(o.key), pct?.let { (it * 100).roundToInt() }, pr.predicted(o.key), if (played) place[o.key] else null)
        return PredictionView(
            pr.league, pr.season, pr.favourites.map { pick(it, it.title) }, pr.dark?.let { pick(it, it.top4) },
            pr.relegation.map { pick(it, it.relegation) }, pr.order.map { pick(it, null) },
        )
    }

    // ---- 시대와 메타 (UX 8) -------------------------------------------------------------------------

    /** 시대·스타일 (UX 8.1): the meta book, the newest family measure and the eras' stars from the honours. */
    private fun metaOf(w: LeagueWorld): MetaView {
        val p = w.plan
        val s = p?.number ?: 0
        val book = w.world.meta
        val families = w.stats.families
        val last = families.keys.maxOrNull() ?: 0
        val fs = families[last]
        val eras = book.eras.map { eraView(w, book, it, s) }.asReversed()
        val going = book.era
        val era = going?.let { e -> eras.firstOrNull { it.n == e.n } }
        val titles = going?.family?.let { f -> Meta.scores(families, last).firstOrNull { it.family == f } }?.let { it.titles to it.total }
        val candidate = book.candidate?.takeIf { c -> c.family != null && c.held >= 1 && c.family != going?.family }?.let { c ->
            val f = c.family!!
            // The name its era would take: one more of the family's ("두 번째 …" after one).
            CandidateView(f, c.held, book.name(Era(Int.MAX_VALUE, f, s, 0, 0)))
        }
        val shares = book.shares.map { ShareSeason(it.season, it.elite, it.all) }
        val newest = book.shares.lastOrNull()
        val before = book.shares.getOrNull(book.shares.size - 2)
        val now = newest?.let { ss ->
            StyleFamily.entries.map { f ->
                val i = f.ordinal
                FamilyShare(f, (ss.elite[i] / 10.0).roundToInt(), (ss.all[i] / 10.0).roundToInt(), before?.let { ((ss.elite[i] - it.elite[i]) / 10.0).roundToInt() })
            }.sortedWith(compareByDescending<FamilyShare> { ss.elite[it.family.ordinal] }.thenBy { it.family.ordinal })
        }.orEmpty()
        val matchups = fs?.matchups?.takeIf { it.isNotEmpty() }?.let { ms ->
            val n = StyleFamily.entries.size
            val score = Array(n) { DoubleArray(n) { Double.NaN } }
            val games = Array(n) { IntArray(n) }
            for (m in ms) {
                games[m.f.ordinal][m.g.ordinal] = m.matches
                if (m.matches >= MATCHUP_MIN) score[m.f.ordinal][m.g.ordinal] = m.excess / m.matches
            }
            H2hMatrix(MATCHUP_TITLE, StyleFamily.entries.map { it.label }, score, games)
        }
        val counter = book.counters.lastOrNull { it.season >= last - (Meta.COUNTER_EVERY - 1) && it.season <= last }
            ?.let { StoryLines.counter(it.counter, it.dominant, it.excess) }
        val ranked = if (p != null && p.league.leagues.national) Media.leagues(p, drawnRatings(w, p)) else emptyList()
        val leagues = fs?.leagues.orEmpty().let { lf ->
            val order = ranked.filter { it in lf } + lf.keys.filter { it !in ranked }
            order.take(TOP_LEAGUES).map { it to lf.getValue(it) }
        }
        return MetaView(
            s, era, titles, going != null && going.family == null, candidate, eras, shares, now, matchups, counter,
            book.allWeights.map { it.key to it.value }, w.config.styleCap, leagues, measured = last,
        )
    }

    /** Era [e] for the screen, its star the era's most decorated league champion of its family (any family's in 춘추전국). */
    private fun eraView(w: LeagueWorld, book: MetaBook, e: Era, now: Int): EraView {
        val seasons = e.from..(if (e.to > 0) e.to else now)
        val titles = w.honours.all.filter { it.season in seasons && HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null }
            .filter { h -> e.family == null || familyOf(w, h.key) == e.family }
        val star = titles.groupBy { it.key }.entries
            .maxWithOrNull(compareBy<Map.Entry<String, List<yacht.engine.league.Honour>>> { it.value.size }.thenBy { it.value.maxOf { h -> h.season } }.thenByDescending { it.key })
            ?.key?.let { it to host.shown(it) }
        return EraView(e.n, e.family, book.name(e), e.from, e.to.takeIf { it > 0 }, star)
    }

    /** [key]'s style family by its primary recipe: a member's, else a legend's kept genes. */
    private fun familyOf(w: LeagueWorld, key: String): StyleFamily? =
        StyleFamily.of(w.players[key]?.spec?.play ?: w.players.legends.firstOrNull { it.record.key == key }?.spec?.play)

    // ---- what links to them --------------------------------------------------------------------

    private fun statusOf(w: LeagueWorld): StoryStatus {
        val p = w.plan
        val s = p?.number ?: 0
        if (p == null || !p.league.leagues.national) return StoryStatus(s, press = false, meta = false, era = null)
        val media = w.world.media
        val press = (w.desk.media && w.desk.season == s) || w.stats.pressVotes.isNotEmpty() || media.allScorecards.isNotEmpty() || media.careerTop.isNotEmpty()
        val book = w.world.meta
        // As [MetaView.any] has it (the page shows its eras tab exactly then): an era, a season's
        // shares, or the newest family measure with its matchups or leagues.
        val fm = w.stats.families.entries.maxByOrNull { it.key }?.value
        val meta = book.eras.isNotEmpty() || book.shares.isNotEmpty() || fm != null && (fm.matchups.isNotEmpty() || fm.leagues.isNotEmpty())
        val era = book.era?.let { e -> StoryLines.eraLine(book.name(e), s - e.from + 1) }
        return StoryStatus(s, press, meta, era)
    }

    companion object {
        /** The weeks 기자단 puts the last close's vote first in (the SeasonEnd banner's weeks) and the season's predictions first in. */
        const val REVIEW_WEEKS = HomeFeed.SEASON_END_WEEKS
        const val PRESEASON_WEEKS = 5

        /** Rows of 골든 다이스's vote, of 올해의 감독's and 올해의 영입's, 골든 다이스 winners kept, weeks-at-no.-1 rows, scorecard seasons. */
        const val VOTE_ROWS = 5
        const val AWARD_ROWS = 3
        const val GOLDEN_KEPT = 100
        const val TOP_WEEKS = 5
        const val HISTORY = 10

        /** Matches a family pair needs for its cell of the matchups (as the counter rule's). */
        const val MATCHUP_MIN = Meta.COUNTER_MATCHES

        /** The best-ranked leagues whose strongest family 시대·스타일 lists. */
        const val TOP_LEAGUES = 8

        const val MATCHUP_TITLE = "스타일 상성"

        /** The power ranking's column. */
        private const val POWER_KIND = "power_ranking"

        /**
         * 기자단's part of the season (UX 7.1) at [week]: the review weeks (the last close's vote or
         * scorecard is [fresh]) put the vote first, the rest of the first five weeks the predictions,
         * the season after them the power ranking.
         */
        fun phaseOf(week: Int, fresh: Boolean): PressPhase = when {
            fresh && week <= REVIEW_WEEKS -> PressPhase.REVIEW
            week <= PRESEASON_WEEKS -> PressPhase.PRESEASON
            else -> PressPhase.SEASON
        }
    }
}

/** The lines the story loaders write into their views (해요체; the screens' own texts are in their files). */
internal object StoryLines {
    /** A power ranking row's line 2 after its league: "기대보다 승점 +3.2 · 최근 5경기 4승". */
    fun powerSub(above: Double, wins5: Int): String = "기대보다 승점 ${signed(above)} · 최근 5경기 ${wins5}승"

    /** "🛡 카운터: 족보파가 요트파를 상대로 기대보다 경기당 승점을 0.30점 더 땄어요." (○○파 ends in a vowel). */
    fun counter(counter: StyleFamily, dominant: StyleFamily, excess: Double): String =
        "🛡 카운터: ${counter.label}가 ${dominant.label}를 상대로 기대보다 경기당 승점을 ${String.format(Locale.ROOT, "%.2f", excess)}점 더 땄어요."

    /** The era going on in a line: "🧭 보너스파의 시대 3시즌째". */
    fun eraLine(era: String, seasons: Int): String = "🧭 $era ${seasons}시즌째"

    /** A number of points with its sign, to one decimal ("+3.2", "-1.0", "0"). */
    fun signed(x: Double): String = if (abs(x) < 0.05) "0" else String.format(Locale.ROOT, "%+.1f", x)

    // ---- the review's big stories (UX 3.5, 7.5) -------------------------------------------------

    /** The predictions of a close in a line: "6/8 적중 · 최대 반전 🇨🇦 루카스 시우바". */
    fun predict(hits: Int, leagues: Int, over: String?): String = "$hits/$leagues 적중" + (over?.let { " · 최대 반전 $it" } ?: "")

    /** The season's era in words: "보너스파의 시대가 열렸어요" when it began at the close ('B'), "… 저물었어요" ('E'), else "…예요". */
    fun era(name: String, change: Char): String = when (change) {
        'B' -> "${Ko.i(name)} 열렸어요"
        'E' -> "${Ko.i(name)} 저물었어요"
        else -> Ko.ieyo(name)
    }

    fun eraBegins(name: String): String = "🧭 ${Ko.i(name)} 열렸어요"

    fun eraEnds(name: String, seasons: Int?): String = "🧭 ${Ko.i(name)} " + (seasons?.let { "${it}시즌 만에 " } ?: "") + "저물었어요"

    /** "🗳 시즌 8 요트 월드컵 개최지가 🇯🇵 일본으로 정해졌어요". */
    fun wcHost(season: Int, nation: String): String = "🗳 시즌 $season 요트 월드컵 개최지가 ${Ko.ro(nation)} 정해졌어요"

    /** "💰 세계 최고 이적료 185.0억 · 🇸🇳 앙투안 음바이"; a league's record names the league ([league]) in place of the world. */
    fun recordFee(fee: String, player: String, league: String? = null): String = "💰 ${league ?: "세계"} 최고 이적료 $fee · $player"

    fun goldenDice(player: String, points: Int?): String = "🎙 골든 다이스 $player" + (points?.let { " · ${it}점" } ?: "")

    /** A world event's icon, as the screens' (ui's `arcIcon`; StoryTextsTest keeps them one). */
    fun arcIcon(k: ArcKind): String = when (k) {
        ArcKind.SUPER_LEAGUE -> "🔱"
        ArcKind.INVESTOR -> "📊"
        ArcKind.CRISIS -> "📉"
        ArcKind.MERGER -> "🔗"
        ArcKind.BOOM -> "🆕"
    }

    /** The world event a news kind tells of ("superleague_outcome", "league_merged", "boom_ends", …); null for another kind. */
    fun arcOf(kind: String): ArcKind? = when {
        kind.startsWith("superleague") -> ArcKind.SUPER_LEAGUE
        kind.startsWith("investor") -> ArcKind.INVESTOR
        kind.startsWith("crisis") -> ArcKind.CRISIS
        kind.startsWith("merger") || kind == "league_merged" || kind == "league_split" -> ArcKind.MERGER
        kind.startsWith("boom") -> ArcKind.BOOM
        else -> null
    }

    /** An arc's stage as the review tells it: "📉 금융 위기가 시작됐어요" for a beginning, else "🔱 슈퍼리그 분리 위협이 결말을 맞았어요". */
    fun arcOutcome(k: ArcKind, kind: String): String =
        "${arcIcon(k)} ${Ko.i(k.label)} " + if (kind.endsWith("_begins")) "시작됐어요" else "결말을 맞았어요"
}
