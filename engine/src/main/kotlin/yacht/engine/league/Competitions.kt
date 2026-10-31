package yacht.engine.league

/**
 * What the 대회 screen draws of a competition: an immutable snapshot (build it under the
 * league's lock, show it anywhere). Keys are AI keys, or team ids for team competitions
 * ([Competitions.teamLabel] names them); [squads] lists each team's AIs, board 1 first.
 */
sealed class CompetitionView {
    abstract val title: String
    abstract val squads: Map<String, List<String>>

    /** A knockout bracket, first round first. */
    data class Bracket(
        override val title: String,
        val rounds: List<BracketRound>,
        override val squads: Map<String, List<String>> = emptyMap(),
    ) : CompetitionView()

    /** Group tables, then the knockout stage ([knockout] has empty sides until it is drawn). */
    data class Groups(
        override val title: String,
        val groups: List<GroupTable>,
        val knockout: Bracket?,
        override val squads: Map<String, List<String>> = emptyMap(),
        val notes: CompetitionNotes? = null,
    ) : CompetitionView()

    /** Several tournaments played side by side (the continental cups), each with its own groups and bracket. */
    data class Sections(
        override val title: String,
        val parts: List<Groups>,
        override val squads: Map<String, List<String>> = emptyMap(),
        val notes: CompetitionNotes? = null,
    ) : CompetitionView()
}

/** One round of a bracket ("16강", "결승"; "패자조 2R", "최종 결승" with double elimination). */
data class BracketRound(
    val label: String,
    val ties: List<TieView>,
    val side: BracketSide = BracketSide.WINNERS,
    /** Its stage in the competition's bracket stages (the calendar's), -1 when unknown. */
    val stage: Int = -1,
)

/**
 * One tie on screen. [a] is the better seed; a null side is not known yet, or a bye when the
 * tie has a [winner] without being [played]. Scores are games won for one-on-one ties and
 * boards won for team matches, a level game or board counting half (so x.5 can show).
 */
data class TieView(
    val a: String?,
    val b: String?,
    val scoreA: Double,
    val scoreB: Double,
    val winner: String?,
    val played: Boolean,
    /** Matches in the tie (two legs = 2); for a series, the most pairs of games. */
    val legs: Int,
    /** A series tie: first to this many games won (0: legs of matches). */
    val firstTo: Int = 0,
    /**
     * The side that came from the winners' bracket ([a] and [b] go by seed): in a losers'
     * round that takes the winners' losers, the one that dropped; in the grand final, the
     * winners' champion. Null elsewhere or while unknown.
     */
    val fromWinners: String? = null,
    /**
     * Decided without a match: a bye with its side as [a] and [winner], or (with double
     * elimination) an empty losers' tie both of whose feeders were byes, with no side at all.
     */
    val bye: Boolean = false,
    /** The tie's fixture id (its [MatchRef.id]) once both sides are known; -1 for a bye and before. */
    val id: Int = -1,
)

/** A group table, best first. */
data class GroupTable(
    val label: String,
    val rows: List<GroupRow>,
    /** A 2-0 sweep earns a bonus point here (판 승점제's 완승 보너스): [GroupRow.sweeps] are in the points. */
    val bonus: Boolean = false,
)

/** A group table line; [diff] is the score difference (boards for team matches). */
data class GroupRow(
    val key: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val points: Int,
    val diff: Int,
    /** 2-0 sweeps that earned the bonus point (with [GroupTable.bonus]). */
    val sweeps: Int = 0,
)

/** A competition the 대회 screen can draw. */
interface ViewableCompetition : Competition {
    fun view(): CompetitionView
}

/**
 * The competitions of docs/LEAGUE_SPEC.md 2.1 (playoffs) and 2.3, for the app's hook lists:
 * [factories] for the season start, [postFactories] once the league stage is over, and
 * [codecs] to read them back from season.txt.
 */
object Competitions {
    /**
     * Builders of a season's competitions, champions league first (it gets the cup slots first),
     * then the 대륙 챔피언스 (national seasons only). The 클럽 대항전 needs the clubs: the world adds
     * [ClubCup.factory].
     */
    fun factories(): List<(SeasonStart) -> Competition?> =
        listOf(ChampionsLeague::create, ContinentalChampions::create, Cup::create, SuperCup::create, AllStar::create, FamilyCup::create, NationCup::world, NationCup::continental)

    /** Builders of the post-season: the title playoff, then the promotion playoffs. */
    fun postFactories(): List<(SeasonPlan) -> Competition?> = listOf(TitlePlayoff::create, PromotionPlayoff::create)

    val codecs: Map<Comp, CompetitionCodec> = mapOf(
        Comp.CUP to Cup.codec,
        Comp.CHAMPIONS to ChampionsLeague.codec,
        Comp.CONT_CHAMPIONS to ContinentalChampions.codec,
        Comp.SUPER to SuperCup.codec,
        Comp.ALL_STAR to AllStar.codec,
        Comp.FAMILY to FamilyCup.codec,
        Comp.WORLD_CUP to NationCup.worldCodec,
        Comp.CONTINENTAL to NationCup.continentalCodec,
        Comp.CLUB_CUP to ClubCup.codec,
        Comp.TITLE_PLAYOFF to TitlePlayoff.codec,
        Comp.PROMOTION_PLAYOFF to PromotionPlayoff.codec,
    )

    /** The view of [c], or null when it has none. */
    fun view(c: Competition): CompetitionView? = (c as? ViewableCompetition)?.view()

    /** Views of every competition of [plan] that has one (post-season last), in plan order. */
    fun views(plan: SeasonPlan): List<Pair<Comp, CompetitionView>> =
        (plan.comps + plan.post).mapNotNull { c -> view(c)?.let { c.comp to it } }

    /** The Korean name of a team id (all-star sides and family teams); other ids as they are. */
    fun teamLabel(id: String): String = when (id) {
        AllStar.TOP -> AllStar.TOP_LABEL
        AllStar.CHALLENGER -> AllStar.CHALLENGER_LABEL
        else -> Family.entries.firstOrNull { it.name == id }?.label ?: Nations.flagged(id) ?: id
    }
}
