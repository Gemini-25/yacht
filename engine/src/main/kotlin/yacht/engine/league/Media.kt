package yacht.engine.league

import java.util.TreeMap

/**
 * A row of the weekly power ranking (파워 랭킹, design ENG 5.1), built on request for the screens:
 * member [key] at [rank] (last week's [prev], null when new), its [score] `R0 + 6 (P − E) + 12
 * (form5 − e5) + S` from the rating it was [drawn] with, league [points] and [expected] points
 * through the week, its last 5 matches' mean points and expected points, the stage bonus [stage]
 * (20 / 10 / 0), league matches [played] and weeks at #1 this season ([weeksTop]).
 */
data class PowerRow(
    val key: String, val rank: Int, val prev: Int?, val score: Double, val drawn: Double, val points: Int,
    val expected: Double, val form5: Double, val expected5: Double, val stage: Int, val played: Int, val weeksTop: Int,
)

/** A candidate's votes in a press award: [points] (5-3-1 a ballot) and first-place votes. */
data class PressVoteRow(val key: String, val points: Int, val firsts: Int)

/**
 * A press award's tally of [season] (`pv` in stats.txt, design ENG 5.4): award [award] (a
 * [PressAwards.AWARDS] code) voted by [jury] journalists, every candidate's votes, winner first.
 */
data class PressTally(val season: Int, val award: String, val jury: Int, val rows: List<PressVoteRow>) {
    /** The `pv` line (with its '\n'). */
    fun encode(): String = "pv $season $award $jury ${LineParts.list(rows) { "${Field.esc(it.key)}:${it.points}:${it.firsts}" }}\n"

    companion object {
        /** Reads a `pv` line split on spaces; null when it cannot. */
        fun decode(f: List<String>): PressTally? = runCatching {
            val rows = LineParts.items(f[4]).map { e ->
                val p = e.split(':')
                PressVoteRow(Field.unesc(p[0]), p[1].toInt(), p[2].toInt())
            }
            PressTally(f[1].toInt(), f[2], f[3].toInt(), rows)
        }.getOrNull()
    }
}

/**
 * The press-vote awards (기자단 투표, design ENG 5.4). [KINDS] are the honours they give (골든 다이스,
 * 올해의 영입), apart from [Awards.KINDS] (which drives the season's own awards and their news): 올해의
 * 감독 is a tally only (the coach's count is raised in the next club off-season).
 */
object PressAwards {
    const val GOLDEN_DICE = "GD"
    const val COACH = "COY"
    const val SIGNING = "SIGN"

    /** Honour kinds given by the press votes. */
    val KINDS: Set<String> = setOf(GOLDEN_DICE, SIGNING)

    /** Every award, with its label, in the order the screens show them. */
    val AWARDS: List<Pair<String, String>> = listOf(GOLDEN_DICE to "골든 다이스", COACH to "올해의 감독", SIGNING to "올해의 영입")
}

/** A member's predicted and final place (예측 성적표's over- and underachievers). */
data class ScoreSwing(val key: String, val predicted: Int, val final: Int)

/** One league's line of a scorecard: the favourite won, the champion was among the 3 favourites, relegation picks relegated, Spearman ρ × 100. */
data class LeagueScorecard(val league: String, val hit: Boolean, val top3: Boolean, val relHits: Int, val rho: Int)

/**
 * The preseason predictions' scorecard of [season] (`psc`, design ENG 5.2): over [leagues] leagues
 * the favourites who won, champions among the 3 favourites, relegation picks relegated of
 * [relPicks], the mean Spearman ρ × 100, the biggest overachiever and flop, and (for the newest
 * season) each league's line.
 */
data class PredictionScorecard(
    val season: Int, val hits: Int, val leagues: Int, val top3: Int, val relHits: Int, val relPicks: Int, val rho: Int,
    val over: ScoreSwing?, val flop: ScoreSwing?, val perLeague: List<LeagueScorecard> = emptyList(),
)

/** The World Cup host vote for the WC of [season] (`wcv`, design ENG 5.5): the [host] and each round's votes per nation. */
data class WcVote(val season: Int, val host: String, val rounds: List<List<Pair<String, Int>>>)

/**
 * A World Cup's draw ceremony (`wcc`): its presenter (a legend's key, null for none), the group of
 * death ([death], a group's letter), the groups as drawn (letter → nations) and the pots.
 */
data class WcCeremony(val season: Int, val presenter: String?, val death: String, val groups: List<Pair<String, List<String>>>, val pots: List<List<String>>)

/**
 * The media's part of world.txt (design ENG 5): career weeks at #1 of the power ranking (`pr1`),
 * the predictions' scorecards (`psc`, 30 seasons), the World Cup host votes (`wcv`) and draw
 * ceremonies (`wcc`). The logic is P10's, in this file.
 */
class MediaBook {
    internal val weeksTop = TreeMap<String, Int>()
    internal val scorecards = TreeMap<Int, PredictionScorecard>()
    internal val votes = TreeMap<Int, WcVote>()
    internal val ceremonies = TreeMap<Int, WcCeremony>()

    /** Career weeks at #1 of the power ranking, by key. */
    val careerTop: Map<String, Int> get() = weeksTop

    fun scorecard(season: Int): PredictionScorecard? = scorecards[season]

    val allScorecards: List<PredictionScorecard> get() = scorecards.values.toList()

    /** The host vote for the World Cup of [season]; null when none was held (today's rotation). */
    fun vote(season: Int): WcVote? = votes[season]

    fun ceremony(season: Int): WcCeremony? = ceremonies[season]

    companion object {
        private fun swing(s: ScoreSwing?) = s?.let { "${Field.esc(it.key)}:${it.predicted}:${it.final}" } ?: "-"

        private fun swingOf(t: String): ScoreSwing? = t.takeIf { it != "-" }?.split(':')?.let { ScoreSwing(Field.unesc(it[0]), it[1].toInt(), it[2].toInt()) }

        private fun ratio(t: String): Pair<Int, Int> = t.split('/').let { it[0].toInt() to it[1].toInt() }

        private val PR1 = LineParts.of<WorldBook>("pr1", { w, out ->
            if (w.media.weeksTop.isNotEmpty()) out.append("pr1 ").append(w.media.weeksTop.entries.joinToString(",") { "${Field.esc(it.key)}:${it.value}" }).append('\n')
        }) { w, f ->
            for (e in LineParts.items(f[1])) w.media.weeksTop[Field.unesc(e.substringBefore(':'))] = e.substringAfter(':').toInt()
            true
        }

        private val PSC = LineParts.of<WorldBook>("psc", { w, out ->
            for (s in w.media.scorecards.values) {
                out.append("psc ${s.season} ${s.hits}/${s.leagues} ${s.top3}/${s.leagues} ${s.relHits}/${s.relPicks} ${s.rho} ${swing(s.over)} ${swing(s.flop)} ")
                out.append(LineParts.list(s.perLeague, ';') { "${Field.esc(it.league)}:${LineParts.bit(it.hit)}:${LineParts.bit(it.top3)}:${it.relHits}:${it.rho}" }).append('\n')
            }
        }) { w, f ->
            val (hits, leagues) = ratio(f[2])
            val (top3, _) = ratio(f[3])
            val (relHits, relPicks) = ratio(f[4])
            val per = f.getOrNull(8)?.let { t ->
                LineParts.items(t, ';').map { e ->
                    val p = e.split(':')
                    LeagueScorecard(Field.unesc(p[0]), p[1] == "1", p[2] == "1", p[3].toInt(), p[4].toInt())
                }
            }.orEmpty()
            val season = f[1].toInt()
            w.media.scorecards[season] = PredictionScorecard(season, hits, leagues, top3, relHits, relPicks, f[5].toInt(), swingOf(f[6]), swingOf(f[7]), per)
            true
        }

        private val WCV = LineParts.of<WorldBook>("wcv", { w, out ->
            for (v in w.media.votes.values) {
                out.append("wcv ${v.season} ${v.host} ")
                out.append(LineParts.list(v.rounds.withIndex().toList(), ';') { (i, r) -> r.joinToString(",") { "${i + 1}:${it.first}:${it.second}" } }).append('\n')
            }
        }) { w, f ->
            val rounds = LineParts.items(f[3], ';').map { r -> r.split(',').filter { it.isNotEmpty() }.map { e -> e.split(':').let { it[1] to it[2].toInt() } } }
            val season = f[1].toInt()
            w.media.votes[season] = WcVote(season, f[2], rounds)
            true
        }

        private val WCC = LineParts.of<WorldBook>("wcc", { w, out ->
            for (c in w.media.ceremonies.values) {
                out.append("wcc ${c.season} ${LineParts.opt(c.presenter)} ${c.death} ")
                out.append(LineParts.list(c.groups, ';') { (g, ns) -> "$g=${ns.joinToString(",")}" }).append(' ')
                out.append(LineParts.list(c.pots, ';') { it.joinToString(",") }).append('\n')
            }
        }) { w, f ->
            val groups = LineParts.items(f[4], ';').map { e -> e.substringBefore('=') to LineParts.items(e.substringAfter('=')) }
            val pots = LineParts.items(f[5], ';').map { LineParts.items(it) }
            val season = f[1].toInt()
            w.media.ceremonies[season] = WcCeremony(season, Field.opt(f[2]), f[3], groups, pots)
            true
        }

        /** `pr1 psc wcv wcc`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(PR1, PSC, WCV, WCC)
    }
}
