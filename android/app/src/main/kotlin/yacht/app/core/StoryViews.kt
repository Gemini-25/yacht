package yacht.app.core

import yacht.engine.league.ArcKind
import yacht.engine.league.Article
import yacht.engine.league.EncBrowse
import yacht.engine.league.EncHit
import yacht.engine.league.StyleFamily

// The story pages' views (UX 13.3): 기자단, 시대와 메타, 세계 정세 and the World Cup story, the
// encyclopedia's search, and the review's big stories. Their shapes are fixed here; a later stage
// only appends fields with defaults for its own feature (P12 the press and meta part, P18 the
// events, P22 the search).

// ---- 기자단 (UX 7) --------------------------------------------------------------------------------

/**
 * A player of the power ranking: [rank], its [move] against last week (null: new), its club mark,
 * "{league short} {tier}부" ([where]), the [score], line 2 ([sub]: 기대보다 승점, 최근 5경기) and its
 * weeks at no. 1 this season.
 */
data class PowerLine(val key: String, val shown: String, val badge: ClubBadge?, val rank: Int, val move: Int?, val where: String, val score: Int?, val sub: String, val weeksTop: Int = 0)

/** A pick of a prediction: its chance in percent, the place predicted and the place now (null before a match). */
data class Pick(val key: String, val shown: String, val pct: Int?, val predicted: Int?, val now: Int?)

/** 🔮 시즌 전망 of [league] in [season]: 우승 후보, 다크호스, 강등 후보 and the predicted order (as `Odds.preseason` gives them). */
data class PredictionView(val league: String, val season: Int, val favourites: List<Pick>, val dark: Pick?, val relegation: List<Pick>, val order: List<Pick>)

/** A player whose final place was furthest from the prediction. */
data class Swing(val key: String, val shown: String, val predicted: Int, val final: Int)

/** One league's line of a scorecard: the favourite won, the champion among the 3 favourites, relegation picks relegated, Spearman ρ × 100. */
data class LeagueScore(val league: String, val hit: Boolean, val top3: Boolean, val relHits: Int, val rho: Int)

/** 📋 예측 성적표 of [season]: [hits] favourites of [leagues] leagues won, the rest as UX 7.3 reads it. */
data class ScorecardView(val season: Int, val hits: Int, val leagues: Int, val top3: Int, val relHits: Int, val relPicks: Int, val rho: Int, val over: Swing?, val flop: Swing?, val perLeague: List<LeagueScore>)

/** A row of a press vote: what a tap opens ([link], a [pageOf] link), the name, the points and the first-place votes. */
data class VoteLine(val link: String, val shown: String, val points: Int, val firsts: Int)

/** One award of the press vote ([award] a `PressAwards` code, [label] its name) with its top rows. */
data class AwardVote(val award: String, val label: String, val rows: List<VoteLine>)

/** The part of the season 기자단 is in, which orders its sections (UX 7.1): weeks 1-5, the season, the review weeks. */
enum class PressPhase { PRESEASON, SEASON, REVIEW }

/**
 * 기자단 ([StoryPages.press], UX 7) of [league] at [week] of [season]: the power ranking of [powerWeek]
 * (none before [firstWeek]) with its column, the league's top 5, the weeks at no. 1 this season
 * and in all, the prediction and the last scorecard ([history]: season, "h/n", ρ), and the press
 * vote of [voteSeason] by [jury] voters with every 골든 다이스 (season, name). [powerSeason] is the
 * season [power] is of (the season before in the review weeks, when only its last column is left:
 * its rows then have no score); [names] the shown names of [weeksTop]'s and [careerTop]'s keys;
 * [national] false for a legacy season, which keeps no press; [firstRanking] for the season's first
 * ranked week, whose rows have no move to show.
 */
data class PressView(
    val season: Int, val week: Int, val phase: PressPhase, val league: String,
    val powerWeek: Int, val firstWeek: Int, val power: List<PowerLine>, val column: Article?, val leagueTop: List<PowerLine>,
    val weeksTop: List<Pair<String, Int>>, val careerTop: List<Pair<String, Int>>,
    val prediction: PredictionView?, val scorecard: ScorecardView?, val history: List<Triple<Int, String, Int>>,
    val voteSeason: Int, val jury: Int, val votes: List<AwardVote>, val goldenDice: List<Pair<Int, String>>,
    val powerSeason: Int = season, val names: Map<String, String> = emptyMap(), val national: Boolean = true,
    val firstRanking: Boolean = false,
)

// ---- 시대와 메타 (UX 8) -----------------------------------------------------------------------------

/** The [n]-th era: its family (null: 춘추전국 시대), its [name], seasons [from]..[to] (null: going on), its star (key, name). */
data class EraView(val n: Int, val family: StyleFamily?, val name: String, val from: Int, val to: Int?, val star: Pair<String, String>?)

/** The next era's candidate: its family, the closes it has led, and the era it would open. */
data class CandidateView(val family: StyleFamily, val held: Int, val era: String)

/** Each family's share of a season (per mille, family order): of the best leagues' 1부 ([elite]) and of every player ([all]). */
data class ShareSeason(val season: Int, val elite: List<Int>, val all: List<Int>)

/** A family's share now (per cent) and its change since the last season in points ([change] null without one). */
data class FamilyShare(val family: StyleFamily, val elite: Int, val all: Int, val change: Int?)

/**
 * 시대와 메타 ([StoryPages.meta], UX 8.1) at [season]: the era now ([titles]: k of T big titles), 춘추전국
 * ([chaos]), the candidate and every era (newest first); the shares by season and now, the last
 * season's matchups and the counter sentence; the newcomer weights, the style cap (13, 15, 17:
 * tenths of the usual share) and each best-ranked league's strongest family. [measured] is the
 * newest close measured (`fm`), whose [matchups] hold each row family's points a match above
 * expectation against the column family in [H2hMatrix.score] (NaN below [StoryPages.MATCHUP_MIN]
 * matches; not a share) and the matches in [H2hMatrix.games].
 */
data class MetaView(
    val season: Int, val era: EraView?, val titles: Pair<Int, Int>?, val chaos: Boolean, val candidate: CandidateView?, val eras: List<EraView>,
    val shares: List<ShareSeason>, val now: List<FamilyShare>, val matchups: H2hMatrix?, val counter: String?,
    val weights: List<Pair<StyleFamily, Double>>, val styleCap: Int, val leagues: List<Pair<String, StyleFamily>>,
    val measured: Int = 0,
) {
    /**
     * Whether the meta has kept anything yet (an era, a season's style shares or a family measure):
     * without it 시대와 메타 is the boxes' report alone, as before the eras.
     */
    val any: Boolean get() = eras.isNotEmpty() || shares.isNotEmpty() || matchups != null || leagues.isNotEmpty()
}

/**
 * What 기자단 and 시대와 메타 have to show ([StoryPages.status]), for the places that link to them:
 * [press] when the press has started its work (the power ranking, a vote or a scorecard kept),
 * [meta] when the meta has kept anything, and the era going on in a line ("🧭 보너스파의 시대
 * 3시즌째"; null for none), as of [season]. A legacy season has none of them.
 */
data class StoryStatus(val season: Int, val press: Boolean, val meta: Boolean, val era: String?)

// ---- 세계 정세 and the World Cup (UX 9) --------------------------------------------------------------

/** A step of an arc's stepper: its label, [state] -1 done, 0 now, 1 to come, and its season when known. */
data class ArcStep(val label: String, val state: Int, val season: Int?)

/**
 * One world event ([EventPages.arc], UX 9.2); [key] equals its page's load key. [end] null while
 * it goes on; [now] the stage's sentence, [subjects] (label, link), this season's [effects] in
 * words, the [outcome] when done, its articles (newest first).
 */
data class ArcView(
    val key: String, val id: Int, val kind: ArcKind, val icon: String, val title: String, val start: Int, val end: Int?,
    val steps: List<ArcStep>, val now: String, val subjects: List<Pair<String, String>>,
    val effects: List<String>, val outcome: String?, val articles: List<Article>,
)

/** An arc that ended, as 지난 사건 lists it. */
data class ArcLine(val id: Int, val icon: String, val label: String, val start: Int, val end: Int, val outcome: String)

/**
 * 세계 정세 ([EventPages.events], UX 9.1): the arcs going on and those that ended (newest first), and
 * the arc each news "KIND@season" belongs to ([ids]: "SUPER_LEAGUE@7" → the arc's id), so an
 * article's link names one arc ([EventPages.arcId], UX 11).
 */
data class EventsView(val active: List<ArcView>, val ended: List<ArcLine>, val ids: Map<String, Int> = emptyMap())

/** A round of the World Cup host vote: (nation, votes) and the nation that dropped out (null in the last). */
data class WcRound(val votes: List<Pair<String, Int>>, val out: String?)

/** The draw ceremony: its presenter (a player) and titles, the pots, the drawing order (nation, group), the group of death and its mean rating. */
data class CeremonyView(val presenter: String?, val presenterTitles: Int, val pots: List<List<String>>, val order: List<Pair<String, String>>, val death: String, val deathRating: Int?)

/**
 * A World Cup's story ([EventPages.wc], UX 9.5); [key] equals its page's load key. [how] V voted,
 * U unopposed, R by the confederations' rotation; [ceremony] null when none was kept.
 */
data class WcStoryView(val key: String, val season: Int, val host: String?, val bids: List<String>, val rounds: List<WcRound>, val how: Char, val ceremony: CeremonyView?)

// ---- the encyclopedia (UX 10) -------------------------------------------------------------------

/** 백과사전 검색 ([EncPages.results]): the hits of [query], or 둘러보기 ([browse]) for an empty one. */
data class EncSearchView(val query: String, val hits: List<EncHit>, val browse: EncBrowse?)

// ---- the review (UX 3.5, 7.5) ---------------------------------------------------------------------

/**
 * A season review's big stories ([ReviewPage.stories], from the archive's story sub-lines):
 * "📰 이번 시즌의 큰 이야기" lines (text, link), 골든 다이스's top 3, the predictions in a line, the
 * season's record fee, the deals and sackings, the coach of the year and the era in words.
 */
data class ReviewStories(
    val big: List<Pair<String, String>>, val goldenDice: List<VoteLine>, val predict: String?, val record: FeeLine?,
    val deals: Int, val sackings: Int, val coachOfYear: String?, val era: String?,
)
