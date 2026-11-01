package yacht.app.core

import yacht.engine.league.Accounts
import yacht.engine.league.BoardOutlook
import yacht.engine.league.ClubDigest
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubYear
import yacht.engine.league.DerbyRecord
import yacht.engine.league.Gender
import yacht.engine.league.Owner
import yacht.engine.league.Philosophy
import yacht.engine.league.Sponsor
import yacht.engine.league.StadiumProject
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily

// The club world's views (UX 13.2): the club page's 개요 · 선수단 · 구단 · 역사, the player's 💼 line,
// the coach page, 클럽 랭킹 and 이적 센터. Their shapes are fixed here; a later stage only appends
// fields with defaults for its own feature (P8 the club and coach pages and the ranking, P16 the
// transfer centre and the rival line). Money is in the engine's units (`Money.text` writes it).

/**
 * How hard the board presses a coach (UX 5), with its word and its dots (good colour up to 2,
 * tertiary at 3, error from 4).
 */
enum class PressureLevel(val word: String, val dots: Int) {
    SAFE("안정적이에요", 1), OK("괜찮아요", 2), SHAKY("흔들려요", 3), DANGER("위험해요", 4), EDGE("경질 직전이에요", 5);

    companion object {
        /**
         * The level of `ratio` = the pressure the next checkpoint would leave over the sacking
         * threshold (else the pressure now over it): below 0.25, 0.5, 0.75 and 1.0, then 1.0 and up.
         * A ratio that is not a number (no threshold known) reads as SAFE.
         */
        fun of(ratio: Double): PressureLevel = when {
            ratio.isNaN() || ratio < 0.25 -> SAFE
            ratio < 0.5 -> OK
            ratio < 0.75 -> SHAKY
            ratio < 1.0 -> DANGER
            else -> EDGE
        }
    }
}

/** A coach in a line (a club's 👔 block, the free pool, the hot seat): its contract [since]..[until] and [pressure] (null without a club). */
data class CoachLine(val id: String, val shown: String, val philosophy: Philosophy, val since: Int, val until: Int, val reputation: Int, val pressure: PressureLevel?)

/** A club's home ground: [attendance] a match on average, [fill] its share of the seats (0..1), the [project] under way, the naming-rights name. */
data class StadiumView(val name: String, val capacity: Int, val attendance: Int, val fill: Double, val project: StadiumProject?, val naming: String?)

/**
 * A rival of a club: its [kind] (N national classic, R regional, H title race, T transfers), the
 * rivalry's [name] ("서울-부산 클래식"), since when, the all-time [record] and this [season]'s
 * wins, draws and losses (the club's view).
 */
data class RivalView(val badge: ClubBadge, val kind: Char, val name: String, val since: Int, val record: DerbyRecord?, val season: List<Int>)

/**
 * A transfer story (이적설, UX 6.2): [buyer] wants [key] of [seller] for [fee], a rumour since week
 * [rumourWeek] and talks since [talksWeek] (0: none); how it ended ([reason] in words, [by] the club
 * that took the player instead), a winter window's, a move between rivals, a followed one's.
 */
data class StoryView(
    val id: Int, val stage: StoryStage, val key: String, val shown: String, val buyer: ClubBadge?, val seller: ClubBadge?,
    val fee: Int, val rumourWeek: Int, val talksWeek: Int, val reason: String?, val by: ClubBadge?, val winter: Boolean, val rival: Boolean, val followed: Boolean,
)

/**
 * A member's contract (the 선수단 rows and the 💼 details): shirt [number], captain, the season it
 * runs [until], wage, value and release [clause] (0: none), an academy graduate of [gradSeason],
 * on loan from [loanFrom], at the club [since].
 */
data class TermsView(val number: Int, val captain: Boolean, val until: Int, val wage: Int, val value: Int, val clause: Int, val academy: Boolean, val gradSeason: Int, val loanFrom: ClubBadge?, val since: Int)

/** One item of a club's accounts ("중계권" and its amount). */
data class AccountItem(val label: String, val amount: Int)

/**
 * The club page's club-world parts (UX 4.2-4.4); null in a legacy season, before the league's
 * club world exists (a save not yet migrated) and for a gone club. [health] is the grade A..E,
 * [trouble] 0 none, 1 적자, 2 재정 위기, 3 법정관리; [last] the last accounts with their three
 * biggest items a side ([incomeTop], [expenseTop]), [years] the kept seasons (≤ 5).
 */
data class ClubLifeView(
    val nickname: String, val colours: String, val foundedYear: Int,
    val health: Char, val trouble: Int,
    val balance: Int, val value: Int, val valueRank: Int?, val budget: Int, val wages: Int, val income: Int?,
    val last: Accounts?, val incomeTop: List<AccountItem>, val expenseTop: List<AccountItem>, val years: List<ClubYear>,
    val owner: Owner, val sponsor: Sponsor?, val stadium: StadiumView, val training: Int, val academy: Int,
    val fans: Int, val fansRank: Int, val fansNationRank: Int, val mood: Int,
    val coach: CoachLine?, val board: BoardOutlook?, val captain: String?,
    val rivals: List<RivalView>, val nextRivalMatch: FollowMatch?, val stories: List<StoryView>,
    val graduates: Int,
)

/** A legend of a club's 명예의 전당 (≤ 5): its retired [number] when it has one, seasons and titles at the club, a one-club player. */
data class LegendView(val key: String, val shown: String, val number: Int?, val seasons: Int, val titles: Int, val oneClub: Boolean, val retired: Boolean)

/** One of a club's four records: its holder ([key] null when it is no player), the value in words, the season it was set. */
data class ClubRecordView(val label: String, val key: String?, val holder: String, val value: String, val season: Int)

/** A coach's spell at [club] from season [from] to [to] (0: now), its best place, titles and how it ended ([end]: S, P, L, R, '-'). */
data class TenureView(val coach: String, val shown: String, val club: ClubBadge?, val from: Int, val to: Int, val best: Int, val titles: Int, val end: Char)

/** A notable transfer of [season] (a `tx` line): the player, the clubs, the fee and its flags (W L C V K D F). */
data class FeeLine(val season: Int, val key: String, val shown: String, val from: ClubBadge?, val to: ClubBadge?, val fee: Int, val flags: String)

/**
 * The club page's 역사 (UX 4.5): the seasons of its club-table [titles], its 클럽 대항전 titles, its
 * members' major titles; its legends, records, coaches (newest first) and biggest transfers.
 */
data class ClubHistoryView(
    val titles: List<Int>, val cupTitles: Int, val memberTitles: Int,
    val legends: List<LegendView>, val records: List<ClubRecordView>, val tenures: List<TenureView>, val notable: List<FeeLine>,
)

/**
 * The player page's 💼 line (UX 4.7): the club, the contract ([terms] null for a retired player or
 * one without a club: the coach line still shows), an open [story] about the player, its club's
 * coach ([clubCoach], an id) and the coach it became ([coach], an id).
 */
data class PlayerClubCard(val key: String, val club: ClubBadge?, val terms: TermsView?, val story: StoryView?, val clubCoach: String?, val coach: String?)

/**
 * A coach's page (UX 5); [key] equals its page's load key. [club] null while free ([freeRank] its
 * reputation's place among the free coaches) or retired (in season [retired]); [player] the player
 * it was; [tenures] the newest spells, the older summed into [olderClubs] and [olderSeasons];
 * [signings] its clubs' signings while in charge, by family.
 */
data class CoachPage(
    val key: String, val id: String, val shown: String, val age: Int, val gender: Gender,
    val philosophy: Philosophy, val dev: Int, val reputation: Int,
    val club: ClubBadge?, val since: Int, val until: Int, val wage: Int, val board: BoardOutlook?, val pressure: PressureLevel?,
    val player: String?, val playerSeasons: Int, val playerTitles: Int,
    val tenures: List<TenureView>, val olderClubs: Int, val olderSeasons: Int,
    val clubTitles: Int, val leagueTitles: Int, val awards: Int, val sacked: Int,
    val signings: List<Pair<StyleFamily, Int>>, val retired: Int?, val freeRank: Int?,
)

/**
 * A row of 클럽 랭킹: its rank, the list's value in words, and [move], the places gained since the
 * last close's top 10 (the `rk` line). Null both for a club new to the top 10 ("NEW") and for every
 * row below the top 10, which shows no move: read it with the rank.
 */
data class ClubRankLine(val rank: Int, val badge: ClubBadge, val value: String, val move: Int?, val followed: Boolean)

/**
 * 클럽 랭킹 (UX 4.8); [key] equals its page's load key (the scope). [lists] by kind; [grades] the
 * clubs of each health grade, A first.
 */
data class ClubRankingView(val key: String, val season: Int, val lists: Map<ClubRankKind, List<ClubRankLine>>, val grades: List<Int>)

/** A coach on the hot seat (⚠ 경질 위기): the club's [target] and [place], the board's [ratio] ([PressureLevel.of]). */
data class HotSeatLine(val club: ClubBadge, val coach: CoachLine, val target: Int, val place: Int, val ratio: Double)

/**
 * A coach's move of [season] (이적 센터 › 감독): at the winter checkpoint or after the season; [kind] S
 * 경질, H 선임, P 영입, R 은퇴, L 계약 만료, C 선수에서 감독으로; [from] the club it left when it moved.
 */
data class CoachMoveLine(val season: Int, val winter: Boolean, val kind: Char, val coach: String, val shown: String, val club: ClubBadge?, val from: ClubBadge?)

/** One of the coaches' records (최장 재임, 최다 우승, …): who holds it and the value in words. */
data class CoachRecordLine(val label: String, val coach: String, val shown: String, val value: String)

/** 이적 센터 › 감독 (UX 6.5): the hot seat, this season's moves, the free pool's best and the records. */
data class CoachMarketView(val hotSeat: List<HotSeatLine>, val moves: List<CoachMoveLine>, val free: List<CoachLine>, val records: List<CoachRecordLine>)

/**
 * 이적 센터 › 최고 이적료 (UX 6.4): the world record, its progression (newest first), the kept seasons'
 * top 20, each of the best-ranked leagues' record (league id, fee) and last summer's top 10.
 */
data class FeeRecordsView(val world: FeeLine?, val progression: List<FeeLine>, val top: List<FeeLine>, val leagues: List<Pair<String, FeeLine>>, val lastSummer: List<FeeLine>)

/**
 * 이적 센터 ([MarketPages.view], UX 6): the national clubs' kept moves (newest first) and the last
 * window's numbers, as 이적·드래프트 shows them; then the week it was read at, the open stories
 * ([live]) and those that ended in the last window ([ended]), the indexes of [moves] made in a
 * winter window, ☀ 다시 보기's rounds (name, index of its first move), the fee records and the coaches.
 */
data class MarketView(
    val season: Int, val moves: List<ClubMove>, val digest: ClubDigest?,
    val week: Int = 0, val live: List<StoryView> = emptyList(), val ended: List<StoryView> = emptyList(),
    val winterMoves: Set<Int> = emptySet(),
    val rounds: List<Pair<String, Int>> = emptyList(),
    val records: FeeRecordsView? = null, val coaches: CoachMarketView? = null,
)

/** A match report's rival line (`MatchReport.rival`, UX 6.6): the rivalry's name and [a]'s all-time record against [b] before the match. */
data class RivalLine(val name: String, val a: ClubBadge, val b: ClubBadge, val w: Int, val d: Int, val l: Int)
