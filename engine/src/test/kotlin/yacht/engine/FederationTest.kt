package yacht.engine

import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Cup
import yacht.engine.league.Federation
import yacht.engine.league.FederationStats
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Lever
import yacht.engine.league.MatchFormat
import yacht.engine.league.MemoryCoefficients
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Odds
import yacht.engine.league.Offseason
import yacht.engine.league.Proposal
import yacht.engine.league.ProposalKind
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 요트협회 (ENG 6): each kind at its floor and threshold, the assembly's picks, the hard rule on
 * levers, the user's choice and the committee's seeded vote, applying adopted changes (void ones,
 * replays after a kill in both orders), the statistics of played seasons, and federation.txt.
 */
class FederationTest {
    private val config = LeagueConfig.DEFAULT
    private val committee = (1..9).map { "m$it" }

    private fun levers(c: LeagueConfig) = Lever.entries.map { c.valueOf(it.key)!! }

    /** Statistics of season [season] that make no proposal under DEFAULT; each test moves some of them. */
    private fun stats(
        season: Int = 5, promoted: Int = 0, bounced: Int = 0, champPpm: Double? = null,
        playoffLeagues: Int = 0, leaderLost: Int = 0, leagues: Int = 0, runaway: Int = 0,
        decided: Int = 0, sweeps: Int = 0, rounds: Int = 40, projected: Int = 0, legacy: Int = 0,
        koTies: Int = 0, koUpsets: Int = 0, champPoints: Int = 0, champMatches: Int = 0, cclPoints: Int = 0, cclMatches: Int = 0,
        levers: List<String> = levers(config), up: List<String> = emptyList(), national: Boolean = true,
        bonusRanked: Int = 0, bonusMoved: Int = 0, spread: Double? = null,
    ) = FederationStats(
        season, national, promoted, bounced, champPpm, playoffLeagues, leaderLost, leagues, runaway, decided, sweeps, rounds, projected, legacy,
        koTies, koUpsets, champPoints, champMatches, cclPoints, cclMatches, levers, up, bonusRanked, bonusMoved, spread,
    )

    /**
     * A kind under [config] with its statistic set by [at] (the count over a fixed sample), the
     * counts at its [floor] and [threshold] (where it presses [peak]: 1.0, or less where the
     * threshold lies beyond what a count can reach) and one [short] of the floor; [off] are rules
     * and statistics past the threshold that its guard refuses.
     */
    private class Case(
        val kind: ProposalKind, val config: LeagueConfig, val to: String, val at: (Int) -> FederationStats,
        val floor: Int, val threshold: Int, val short: Int, val off: List<Pair<LeagueConfig, FederationStats>>, val peak: Double = 1.0,
    )

    private val cases: List<Case> by lazy {
        val noPo = config.copy(titlePlayoff = false)
        val noSweep = config.copy(sweepBonus = false)
        val shortKo = config.copy(longKnockouts = false)
        listOf(
            Case(
                ProposalKind.SWAP_DOWN, config, "1", { stats(promoted = 100, bounced = it) }, 38, 50, 37,
                listOf(config.copy(autoSwap = 1) to stats(promoted = 100, bounced = 50), config to stats(promoted = 9, bounced = 9)),
            ),
            Case(
                ProposalKind.SWAP_UP, config, "3", { stats(promoted = 100, bounced = it, champPpm = 0.1) }, 20, 12, 21,
                listOf(
                    config.copy(autoSwap = 4) to stats(promoted = 100, bounced = 12, champPpm = 0.1),
                    config to stats(promoted = 9, bounced = 0, champPpm = 0.1),
                    config to stats(promoted = 100, bounced = 12, champPpm = -0.01),
                    config to stats(promoted = 100, bounced = 12, champPpm = null),
                ),
            ),
            Case(
                ProposalKind.TITLE_PO_OFF, config, "0", { stats(playoffLeagues = 50, leaderLost = it) }, 39, 45, 38,
                listOf(noPo to stats(playoffLeagues = 50, leaderLost = 45), config to stats(playoffLeagues = 3, leaderLost = 3)),
            ),
            Case(
                ProposalKind.TITLE_PO_ON, noPo, "1", { stats(leagues = 100, runaway = it) }, 33, 50, 32,
                listOf(config to stats(leagues = 100, runaway = 50), noPo to stats(leagues = 3, runaway = 3)),
            ),
            Case(
                ProposalKind.SWEEP_OFF, config, "0", { stats(bonusRanked = 100, bonusMoved = it) }, 29, 26, 30,
                listOf(
                    noSweep to stats(bonusRanked = 100, bonusMoved = 26), config.copy(matchFormat = MatchFormat.MIRRORED) to stats(bonusRanked = 100, bonusMoved = 26),
                    config to stats(bonusRanked = 99, bonusMoved = 0),
                ),
            ),
            Case(
                ProposalKind.SWEEP_ON, noSweep, "1", { stats(bonusRanked = 100, bonusMoved = it) }, 33, 36, 32,
                listOf(config to stats(bonusRanked = 100, bonusMoved = 36), noSweep.copy(matchFormat = MatchFormat.SINGLE) to stats(bonusRanked = 100, bonusMoved = 36)),
            ),
            Case(
                ProposalKind.SIZE_DOWN, config, "18", { stats(spread = it / 100.0) }, 115, 120, 114,
                listOf(config.copy(divisionSize = 16) to stats(spread = 1.20), config to stats(spread = null)),
            ),
            Case(
                ProposalKind.SIZE_UP, config, "22", { stats(spread = it / 100.0, projected = 100, legacy = 100) }, 110, 106, 111,
                listOf(config.copy(divisionSize = 24) to stats(spread = 1.06, projected = 100, legacy = 100), config to stats(spread = 1.06, projected = 101, legacy = 100)),
            ),
            Case(
                ProposalKind.LONG_KO_ON, shortKo, "1", { stats(koTies = 100, koUpsets = it) }, 9, 25, 8,
                listOf(config to stats(koTies = 100, koUpsets = 25), shortKo to stats(koTies = 9, koUpsets = 9)),
            ),
            Case(
                // Its floor is no upset at all, and its threshold lies below zero: it never presses more than 0.3.
                ProposalKind.LONG_KO_OFF, config, "0", { stats(koTies = 25, koUpsets = it) }, 0, 0, 1,
                listOf(shortKo to stats(koTies = 25, koUpsets = 0), config to stats(koTies = 9, koUpsets = 0)), peak = 0.3,
            ),
            Case(
                ProposalKind.CL_DIRECT_UP, config, "8", { stats(champPoints = it, champMatches = 20, cclMatches = 20) }, 6, 13, 5,
                listOf(
                    config.copy(clDirect = 12) to stats(champPoints = 13, champMatches = 20, cclMatches = 20),
                    config to stats(champPoints = 33, champMatches = 11, cclMatches = 20), config to stats(champPoints = 20, champMatches = 20, cclMatches = 11),
                ),
            ),
            Case(
                ProposalKind.CL_DIRECT_DOWN, config, "6", { stats(champMatches = 20, cclPoints = it, cclMatches = 20) }, 6, 13, 5,
                listOf(config.copy(clDirect = 0) to stats(champMatches = 20, cclPoints = 13, cclMatches = 20), config to stats(champMatches = 11, cclPoints = 20, cclMatches = 20)),
            ),
        )
    }

    /** The kinds of the six competition rules (the club world's three levers' are FederationFinanceTest's). */
    private val ruleKinds = ProposalKind.entries.filter { it.lever.ordinal < Lever.FIRST }

    @Test
    fun everyKindFiresFromItsFloorWithThePressureFormula() {
        assertEquals(ruleKinds.toSet(), cases.map { it.kind }.toSet())
        for (c in cases) {
            fun of(s: FederationStats) = Federation.candidates(s, c.config).filter { it.kind == c.kind }
            val floor = of(c.at(c.floor)).single()
            assertEquals(0.3, floor.pressure, "${c.kind} at its floor")
            assertEquals(c.config.valueOf(c.kind.lever.key), floor.from, "${c.kind}")
            assertEquals(c.to, floor.to, "${c.kind}")
            assertEquals(c.peak, of(c.at(c.threshold)).single().pressure, "${c.kind} at its threshold")
            assertEquals(emptyList(), of(c.at(c.short)), "${c.kind} short of its floor")
            // Only this kind fires on these numbers, and its proposal reads with its real numbers.
            assertEquals(listOf(c.kind), Federation.candidates(c.at(c.threshold), c.config).map { it.kind })
            for (p in listOf(floor, of(c.at(c.threshold)).single())) {
                assertTrue(p.title.isNotBlank() && p.evidence.isNotBlank())
                assertFalse("?" in p.title + p.evidence || "{" in p.title + p.evidence || "null" in p.evidence, "${c.kind}: ${p.title} / ${p.evidence}")
            }
            for ((i, off) in c.off.withIndex()) assertEquals(emptyList(), Federation.candidates(off.second, off.first).filter { it.kind == c.kind }, "${c.kind} guard $i")
        }
        // Far past the threshold the pressure stops at 3.
        assertEquals(3.0, Federation.candidates(stats(spread = 2.0), config).single().pressure)
        assertEquals(0.65, Federation.pressure(ProposalKind.SIZE_DOWN, 1.175), 1e-9)
        // A neutral season proposes nothing.
        assertEquals(emptyList(), Federation.candidates(stats(), config))
    }

    /**
     * The ten seasons FederationCalibration played under the default rules (1000 generated
     * players, league seed 5, real games, the federation off so the rules stay), as its CSV has
     * them: promoted, bounced, champPpm, leaderLost (of 28), runaway (of 28), decided, sweeps,
     * 요트컵 upsets (of 22 ties), the league champions' and the 대륙 챔피언스 finalists' 챔피언스
     * 리그 points and matches; then (the same seasons played again) the members of the 1000 whose
     * final place the 2-0 bonus moved, and the divisions' spread.
     */
    private val realSeasons: List<FederationStats> = listOf(
        listOf(0, 0, 0.797, 19, 7, 7954, 7680, 2, 0, 0, 0, 0, 286, 1.184),
        listOf(67, 13, 0.906, 22, 9, 7639, 7352, 0, 43, 45, 89, 61, 287, 1.135),
        listOf(64, 19, 0.951, 17, 7, 7781, 7512, 5, 55, 49, 81, 65, 309, 1.157),
        listOf(66, 17, 0.893, 18, 13, 7767, 7475, 1, 69, 56, 65, 51, 345, 1.129),
        listOf(67, 10, 0.919, 19, 12, 7749, 7500, 0, 72, 55, 64, 54, 316, 1.144),
        listOf(63, 13, 0.813, 20, 8, 7655, 7372, 0, 74, 55, 65, 59, 296, 1.075),
        listOf(71, 14, 0.928, 23, 4, 7719, 7394, 1, 59, 53, 79, 56, 334, 1.083),
        listOf(71, 14, 0.900, 20, 8, 7584, 7319, 1, 57, 51, 71, 57, 275, 1.122),
        listOf(68, 15, 0.919, 19, 9, 7635, 7361, 1, 89, 62, 55, 52, 305, 1.085),
        listOf(67, 15, 0.977, 17, 12, 7807, 7508, 0, 62, 51, 76, 58, 298, 1.142),
    ).mapIndexed { i, r ->
        fun n(j: Int) = (r[j] as Number).toInt()
        stats(
            season = i + 1, promoted = n(0), bounced = n(1), champPpm = (r[2] as Number).toDouble(), playoffLeagues = 28, leaderLost = n(3),
            leagues = 28, runaway = n(4), decided = n(5), sweeps = n(6), rounds = 42, projected = 17000, legacy = 24500, koTies = 22, koUpsets = n(7),
            champPoints = n(8), champMatches = n(9), cclPoints = n(10), cclMatches = n(11),
            bonusRanked = 1000, bonusMoved = n(12), spread = (r[13] as Number).toDouble(),
        )
    }

    @Test
    fun theFloorsAreTheOnesCalibratedOnRealSeasons() {
        // ENG 6.4's table as S20 calibrated it, the bonus and size kinds on their new statistics (docs/LEAGUE_SPEC.md 11.4).
        val pinned = mapOf(
            ProposalKind.SWAP_DOWN to (0.38 to 0.50), ProposalKind.SWAP_UP to (0.20 to 0.12),
            ProposalKind.TITLE_PO_OFF to (0.78 to 0.90), ProposalKind.TITLE_PO_ON to (0.33 to 0.50),
            ProposalKind.SWEEP_OFF to (0.29 to 0.26), ProposalKind.SWEEP_ON to (0.33 to 0.36),
            ProposalKind.SIZE_DOWN to (1.15 to 1.20), ProposalKind.SIZE_UP to (1.10 to 1.06),
            ProposalKind.LONG_KO_ON to (0.09 to 0.25), ProposalKind.LONG_KO_OFF to (0.0 to -0.10),
            ProposalKind.CL_DIRECT_UP to (0.30 to 0.65), ProposalKind.CL_DIRECT_DOWN to (-0.30 to -0.65),
        )
        assertEquals(pinned, ruleKinds.associateWith { it.floor to it.threshold })
        // On the ten real seasons: what passed its floor each season, and what an assembly tabled.
        val passed = realSeasons.map { s -> Federation.candidates(s, config).map { it.kind } }
        assertEquals(
            listOf(
                listOf(ProposalKind.SWEEP_OFF, ProposalKind.SIZE_DOWN),
                listOf(ProposalKind.SWAP_UP, ProposalKind.TITLE_PO_OFF, ProposalKind.SWEEP_OFF, ProposalKind.LONG_KO_OFF, ProposalKind.CL_DIRECT_DOWN),
                listOf(ProposalKind.SIZE_DOWN),
                emptyList(),
                listOf(ProposalKind.SWAP_UP, ProposalKind.LONG_KO_OFF),
                listOf(ProposalKind.SIZE_UP, ProposalKind.LONG_KO_OFF),
                listOf(ProposalKind.SWAP_UP, ProposalKind.TITLE_PO_OFF, ProposalKind.SIZE_UP),
                listOf(ProposalKind.SWAP_UP, ProposalKind.SWEEP_OFF),
                listOf(ProposalKind.SIZE_UP, ProposalKind.CL_DIRECT_UP),
                listOf(ProposalKind.LONG_KO_OFF),
            ),
            passed,
        )
        // No kind (nor lever) in more than half the seasons; the bonus and the division size are live
        // (the share of decided matches won 2-0 is not: 96-97% under 판 승점제, the rounds always 42).
        for (k in ProposalKind.entries) assertTrue(passed.count { k in it } * 2 <= passed.size, "$k past its floor in ${passed.count { k in it }} seasons")
        for (l in Lever.entries) assertTrue(passed.count { p -> p.any { it.lever == l } } * 2 <= passed.size, "$l")
        assertTrue(passed.count { p -> p.any { it.lever == Lever.SWEEP } } >= 2 && passed.count { p -> p.any { it.lever == Lever.DIVISION_SIZE } } >= 2)
        assertTrue(realSeasons.all { it.sweepShare in 0.95..0.98 })
        // Each season's assembly as it would go with the rules left as they are: two as a rule, 검토
        // 안건 filling up; one where no other lever leans its way (seasons 3 and 4).
        val f = Federation()
        val assemblies = realSeasons.map { s -> f.assemble(s.season, s, config, false).also { f.resolve(s.season + 1, SEED, committee) } }
        assertEquals(listOf(2, 2, 1, 1, 2, 2, 2, 2, 2, 2), assemblies.map { it.size })
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 1), assemblies.map { a -> a.count { it.review } })
        // A typical season's proposal presses below 1 (the committee passes few of them); the strongest here, the divisions' spread of season 1, 0.78.
        val pressures = realSeasons.flatMap { Federation.candidates(it, config) }.map { it.pressure }
        assertEquals(0.78, pressures.max())
    }

    @Test
    fun proposalsReadAsTheirNumbersSay() {
        val down = Federation.candidates(stats(promoted = 56, bounced = 31), config).single()
        assertEquals("자동 승강 인원 2명 → 1명", down.title)
        assertEquals("1부로 올라온 56명 가운데 31명(55%)이 한 시즌 만에 다시 내려갔어요. 승강 인원을 줄여 1부를 안정시켜요.", down.evidence)
        val ko = Federation.candidates(stats(koTies = 18, koUpsets = 7), config.copy(longKnockouts = false)).single()
        assertEquals("긴 토너먼트 켜기", ko.title)
        assertEquals("요트컵 8강부터 18경기 가운데 7경기(39%)에서 레이팅이 100점 이상 낮은 쪽이 이겼어요. 시리즈를 늘려 실력이 드러나게 해요.", ko.evidence)
        val cl = Federation.candidates(stats(champMatches = 40, cclPoints = 30, cclMatches = 40), config).single()
        assertEquals("챔피언스 리그 직행 7개 리그 → 6개", cl.title)
        assertTrue("경기당 승점을 0.75점 더 땄어요" in cl.evidence, cl.evidence)
        assertEquals(listOf("-0.75", "0.00", "0.75"), cl.args)
        val size = Federation.candidates(stats(spread = 1.183), config).single()
        assertEquals("부 인원 20명 → 18명", size.title)
        assertEquals("부마다 1위와 꼴찌의 경기당 승점 차가 평균 1.18점이나 됐어요. 부를 줄여 실력이 비슷한 선수끼리 겨루게 해요.", size.evidence)
        val bonus = Federation.candidates(stats(bonusRanked = 1000, bonusMoved = 268), config).single()
        assertEquals("2:0 완승 보너스 없애기", bonus.title)
        assertEquals("리그 순위표 1000명 가운데 2:0 완승 보너스로 순위가 바뀐 선수는 268명(27%)뿐이었어요. 보너스를 없애 승점 계산을 단순하게 해요.", bonus.evidence)
        val on = Federation.candidates(stats(bonusRanked = 1000, bonusMoved = 352), config.copy(sweepBonus = false)).single()
        assertEquals("2:0 완승 보너스가 있었다면 리그 순위표 1000명 가운데 352명(35%)의 순위가 바뀌었을 거예요. 완승에 승점 1을 더 줘서 확실한 승리를 가려요.", on.evidence)
        // No case to count: said so, not "0%뿐".
        val calm = Federation.candidates(stats(koTies = 22, koUpsets = 0), config).single()
        assertEquals("요트컵 8강부터 22경기 동안 이변이 한 번도 없었어요. 시리즈를 줄여 시즌을 가볍게 해요.", calm.evidence)
        val stay = Federation.candidates(stats(promoted = 40, bounced = 0, champPpm = 0.584), config).single()
        assertEquals("2부 우승자들의 경기당 승점이 1부 중간 순위보다 0.58점 높았고, 1부로 올라온 선수는 한 명도 다시 내려가지 않았어요. 실력 있는 선수가 더 빨리 올라오게 해요.", stay.evidence)
        // Level with the 1부's middle (the guard takes 0): not "0.00점 높았고".
        val level = Federation.candidates(stats(promoted = 40, bounced = 4, champPpm = 0.004), config).single()
        assertEquals("2부 우승자들의 경기당 승점이 1부 중간 순위와 같았고, 1부로 올라온 선수 가운데 10%만 다시 내려갔어요. 실력 있는 선수가 더 빨리 올라오게 해요.", level.evidence)
        assertEquals(7, down.appliesFrom)
    }

    @Test
    fun anAssemblyTablesTwoAndAThirdOnlyWhenItPressesHard() {
        // SWAP_DOWN 1.0; SWEEP_OFF and SIZE_DOWN 0.65 (kind order breaks the tie); LONG_KO_OFF 0.3.
        val strong = stats(promoted = 100, bounced = 50, spread = 1.175, bonusRanked = 1000, bonusMoved = 275, koTies = 25, koUpsets = 0)
        assertEquals(4, Federation.candidates(strong, config).size)
        val f = Federation()
        val got = f.assemble(5, strong, config, structureFreeOnly = false)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF, ProposalKind.SIZE_DOWN), got.map { it.kind })
        assertEquals(listOf(1, 2, 3), got.map { it.number })
        assertEquals(listOf(1.0, 0.65, 0.65), got.map { it.pressure })
        assertTrue(got.all { it.season == 5 && it.pending && it.choice == Proposal.NONE })
        assertEquals(got, f.pending())
        assertEquals(5, f.assembled)
        // A third below 0.6 is not tabled (SWEEP_OFF at 0.58).
        val weaker = stats(promoted = 100, bounced = 50, spread = 1.175, bonusRanked = 1000, bonusMoved = 278, koTies = 25, koUpsets = 0)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SIZE_DOWN), Federation().assemble(5, weaker, config, false).map { it.kind })
        // One candidate at the floor is tabled; none leaves the rules as they are.
        assertEquals(listOf(0.3), Federation().assemble(5, stats(koTies = 25, koUpsets = 0), config, false).map { it.pressure })
        assertEquals(emptyList(), Federation().assemble(5, stats(), config, false))
        // Idempotent: a replayed open gets the stored proposals and statistics, whatever it brings.
        assertEquals(got, f.assemble(5, stats(), config, false))
        assertEquals(strong.encode(), f.stats(5)!!.encode())
    }

    @Test
    fun anAssemblyOfFewerThanTwoFillsUpWithReviewProposals() {
        // SWAP_DOWN past its floor (1.0); SIZE_DOWN short of its floor but leaning its way (spread
        // 1.14: 0.16); SWEEP_OFF short and leaning less (30%: 0.07); SIZE_UP leaning the other way.
        val s = stats(promoted = 100, bounced = 50, spread = 1.14, bonusRanked = 100, bonusMoved = 30)
        assertEquals(listOf(ProposalKind.SWAP_DOWN), Federation.candidates(s, config).map { it.kind })
        val floorFree = Federation.candidates(s, config, floorFree = true)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF, ProposalKind.SIZE_DOWN), floorFree.map { it.kind })
        assertEquals(listOf(1.0, 0.07, 0.16), floorFree.map { it.pressure })
        val got = Federation().assemble(5, s, config, false)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SIZE_DOWN), got.map { it.kind })
        assertEquals(listOf(false, true), got.map { it.review })
        assertEquals(
            "급한 안건은 아니지만 한번 검토해 보자고 올렸어요. 부마다 1위와 꼴찌의 경기당 승점 차가 평균 1.14점이었어요. 부를 줄여 실력이 비슷한 선수끼리 겨루게 해요.",
            got[1].evidence,
        )
        // Two past their floors: no review.
        val two = s.copy(bonusMoved = 26)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF), Federation().assemble(5, two, config, false).map { it.kind })
        // Only reviews: the two that lean most; nothing leaning: nothing tabled.
        val mild = stats(spread = 1.14, bonusRanked = 100, bonusMoved = 30)
        assertEquals(listOf(ProposalKind.SIZE_DOWN, ProposalKind.SWEEP_OFF), Federation().assemble(5, mild, config, false).map { it.kind })
        assertEquals(emptyList(), Federation().assemble(5, stats(), config, false))
        // A review reads back as one (its pressure is below the floor's, so a seat votes yes at
        // less than 0.25 + 0.35 x 0.3: the committee seldom passes it).
        val f = Federation()
        f.assemble(5, mild, config, false)
        val back = Federation.decode(f.encode())
        assertTrue(back.of(5).all { it.review && it.pressure < Federation.FLOOR_PRESSURE })
    }

    @Test
    fun assembliesKeepToOnePerLeverAndTheRulesTheConfigCanTake() {
        val rng = Random(12)
        repeat(400) {
            val s = stats(
                promoted = rng.nextInt(40), bounced = rng.nextInt(20), champPpm = if (rng.nextBoolean()) rng.nextDouble() - 0.3 else null,
                playoffLeagues = rng.nextInt(12), leaderLost = rng.nextInt(8), leagues = rng.nextInt(30), runaway = rng.nextInt(12),
                decided = 200, sweeps = 80 + rng.nextInt(80), rounds = 30 + rng.nextInt(22), projected = rng.nextInt(200), legacy = 100,
                koTies = rng.nextInt(30), koUpsets = rng.nextInt(10), champPoints = rng.nextInt(60), champMatches = 12 + rng.nextInt(20),
                cclPoints = rng.nextInt(60), cclMatches = 12 + rng.nextInt(20),
                bonusRanked = 1000, bonusMoved = 200 + rng.nextInt(200), spread = if (rng.nextBoolean()) 0.95 + 0.4 * rng.nextDouble() else null,
            )
            val cfg = config.copy(
                autoSwap = rng.nextInt(6), titlePlayoff = rng.nextBoolean(), sweepBonus = rng.nextBoolean(), divisionSize = 16 + rng.nextInt(9),
                longKnockouts = rng.nextBoolean(), clDirect = rng.nextInt(13),
            )
            val got = Federation().assemble(5, s.copy(levers = levers(cfg)), cfg, rng.nextBoolean())
            assertTrue(got.size <= Federation.MOST)
            assertEquals(got.size, got.map { it.lever }.toSet().size, "one per lever: $got")
            assertEquals(got.sortedWith(compareByDescending<Proposal> { it.pressure }.thenBy { it.kind.ordinal }), got)
            // Past their floors first (a third only when it presses hard); 검토 안건 only to fill up to two, leaning their way.
            val (firm, review) = got.partition { !it.review }
            assertTrue(firm.all { it.pressure >= 0.3 } && got.drop(2).all { it.pressure >= Federation.THIRD })
            assertTrue(review.isEmpty() || (firm.size + review.size <= Federation.TABLED && review.all { it.pressure > 0 && it.pressure < 0.3 }), "$got")
            for (p in got) assertEquals(p.to, assertNotNull(cfg.with(p.lever.key, p.to)).valueOf(p.lever.key))
        }
        // A change the rules cannot take is never tabled: divisionSize 23 + 2.
        val odd = config.copy(divisionSize = 23)
        val s = stats(spread = 1.06, projected = 1, legacy = 1, levers = levers(odd))
        assertEquals("25", Federation.candidates(s, odd).single().to)
        assertEquals(emptyList(), Federation().assemble(5, s, odd, false))
        // A lever with a proposal still pending gets no second one.
        val f = Federation()
        f.assemble(4, stats(season = 4, promoted = 100, bounced = 50), config, false)
        val next = f.assemble(5, stats(promoted = 100, bounced = 50, bonusRanked = 100, bonusMoved = 20), config, false)
        assertEquals(listOf(ProposalKind.SWEEP_OFF), next.map { it.kind })
    }

    @Test
    fun noProposalOnALeverChangedInTheLastTwoSeasons() {
        // SWAP_DOWN and SWEEP_OFF both press; only the lever's history differs.
        fun pressing(season: Int, lv: List<String>) = stats(season, promoted = 100, bounced = 50, bonusRanked = 100, bonusMoved = 26, levers = lv)
        val two = levers(config)
        val three = levers(config.copy(autoSwap = 3))
        val one = levers(config.copy(autoSwap = 1))
        fun assembly(history: List<List<String>>, next: LeagueConfig): List<ProposalKind> {
            val f = Federation()
            for ((i, lv) in history.dropLast(1).withIndex()) f.assemble(i + 1, stats(i + 1, levers = lv), next, false)
            return f.assemble(history.size, pressing(history.size, history.last()), next, false).map { it.kind }
        }
        // Played with 3 then 2 (changed into the season just played): no autoSwap proposal.
        assertEquals(listOf(ProposalKind.SWEEP_OFF), assembly(listOf(three, two), config))
        // Either direction: 1 then 2.
        assertEquals(listOf(ProposalKind.SWEEP_OFF), assembly(listOf(one, two), config))
        // Two seasons at 2 but the next one at 3 (adopted, or edited by hand): none either.
        val handEdited = config.copy(autoSwap = 3)
        assertEquals(listOf(ProposalKind.SWEEP_OFF), assembly(listOf(two, two), handEdited))
        // Changed three seasons ago (3, then 2, 2, and 2 next): free again.
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF), assembly(listOf(three, two, two), config))
        // No history at all: free.
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF), assembly(listOf(two), config))
    }

    @Test
    fun theFirstAssemblyHasOnlyTheStructureFreeKinds() {
        val s = stats(promoted = 100, bounced = 50, spread = 1.20, bonusRanked = 100, bonusMoved = 26, koTies = 20, koUpsets = 0)
        val all = Federation().assemble(5, s, config, structureFreeOnly = false).map { it.kind }
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF, ProposalKind.SIZE_DOWN), all)
        val first = Federation().assemble(5, s, config, structureFreeOnly = true)
        assertEquals(listOf(ProposalKind.SWEEP_OFF, ProposalKind.LONG_KO_OFF), first.map { it.kind })
        assertTrue(first.all { it.kind.structureFree })
        // A legacy season's numbers never speak for the national rules, whatever the caller asks.
        val legacy = s.copy(national = false)
        assertEquals(listOf(ProposalKind.SWEEP_OFF, ProposalKind.LONG_KO_OFF), Federation().assemble(5, legacy, config, structureFreeOnly = false).map { it.kind })
        assertTrue(Federation.candidates(legacy, config).all { it.kind.structureFree })
        assertEquals(setOf(ProposalKind.SWEEP_OFF, ProposalKind.SWEEP_ON, ProposalKind.LONG_KO_ON, ProposalKind.LONG_KO_OFF), ProposalKind.entries.filter { it.structureFree }.toSet())
    }

    @Test
    fun withTheFederationOffNothingIsTabledButTheSeasonIsRecorded() {
        val f = Federation()
        val off = config.copy(federation = false)
        assertEquals(emptyList(), f.assemble(5, stats(promoted = 100, bounced = 50, up = listOf("a", "b"), levers = levers(off)), off, false))
        assertEquals(5, f.assembled)
        assertEquals(listOf("a", "b"), f.promoted(5))
        assertNull(f.promoted(4))
        assertEquals(100, f.stats(5)!!.promoted)
        // The season as the file keeps it: the promoted are the up line, not the fs line.
        assertEquals(f.stats(5), Federation.decode(f.encode()).stats(5))
        // Legacy rules to come (before national leagues are offered): the federation never proposes on them.
        val legacy = LeagueConfig()
        val g = Federation()
        val pressing = stats(promoted = 100, bounced = 50, bonusRanked = 100, bonusMoved = 20, koTies = 20, koUpsets = 0, levers = levers(legacy))
        assertEquals(3, Federation.candidates(pressing, legacy).size)
        assertEquals(emptyList(), g.assemble(5, pressing, legacy, false))
        assertEquals(5, g.assembled)
        assertNotNull(g.stats(5))
        assertEquals(emptyList(), Federation().assemble(5, pressing.copy(national = false), legacy, true))
    }

    @Test
    fun theLeversAreTheSixCompetitionRulesAndTheClubWorldsThree() {
        assertEquals(
            listOf("autoSwap", "titlePlayoff", "sweepBonus", "divisionSize", "longKnockouts", "clDirect", "ffp", "solidarity", "styleCap"),
            Lever.entries.map { it.key },
        )
        for (l in Lever.entries) assertNotNull(config.valueOf(l.key), l.key)
        for (k in ProposalKind.entries) assertTrue(k.lever in Lever.entries)
        // Not the format, the population, national leagues (nor anything outside LeagueConfig).
        val never = setOf("format", "generated", "aging", "childShare", "baseAis", "guests", "onlyFlawed", "onlyTop", "national")
        assertTrue(Lever.entries.none { it.key in never })
        // Applying any adopted proposal changes its own key and nothing else.
        for (c in cases) {
            val p = Federation.candidates(c.at(c.threshold), c.config).single().copy(result = Proposal.YES)
            val (after, void) = Federation.applyTo(c.config, listOf(p))
            assertEquals(emptyList(), void)
            val changed = c.config.encode().lines().zip(after.encode().lines()).filter { (a, b) -> a != b }
            assertEquals(listOf("${c.kind.lever.key} ${p.to}"), changed.map { it.second }, "${c.kind}")
        }
        // Only the federation, the rules and the world know the levers.
        val allowed = setOf("Federation.kt", "Config.kt", "LeagueWorld.kt")
        val sources = File("src/main/kotlin/yacht/engine").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 50, "found the engine sources")
        val word = Regex("\\bLever\\b")
        assertEquals(emptyList(), sources.filter { it.name !in allowed && word.containsMatchIn(it.readText()) }.map { it.name })
    }

    /** P(5) of three proposals (SWAP_DOWN 1.0, SWEEP_OFF 0.65, SIZE_DOWN 0.65), pending. */
    private fun threePending(): Federation = Federation().also {
        it.assemble(5, stats(promoted = 100, bounced = 50, spread = 1.175, bonusRanked = 1000, bonusMoved = 275), config, false)
    }

    @Test
    fun theUsersChoiceBeatsTheCommitteeWhoseVoteIsSeeded() {
        val f = threePending()
        assertTrue(f.choose(5, 1, false))
        assertTrue(f.choose(5, 2, true))
        assertTrue(f.choose(5, 3, true))
        assertTrue(f.choose(5, 3, null))
        assertFalse(f.choose(5, 4, true))
        assertFalse(f.choose(4, 1, true))
        assertEquals(listOf(Proposal.NO, Proposal.YES, Proposal.NONE), f.of(5).map { it.choice })
        val saved = f.encode()
        val adopted = f.resolve(6, SEED, committee)
        val (p1, p2, p3) = f.of(5)
        // The user's choice decides, also against a proposal pressing at 1.0.
        assertEquals(Triple(Proposal.NO, Proposal.USER, 0), Triple(p1.result, p1.by, p1.yes + p1.no))
        assertEquals(Triple(Proposal.YES, Proposal.USER, 0), Triple(p2.result, p2.by, p2.yes + p2.no))
        // The committee votes on the rest: seat i yes with chance 0.25 + 0.35 × pressure, from the season's seed.
        val chance = 0.25 + 0.35 * p3.pressure
        val yes = (0 until 9).count { i -> Random(DiceStream.mix(SEED, 0xFED0, 5 * 16L + 3, i.toLong())).nextDouble() < chance }
        assertEquals(Proposal.COMMITTEE, p3.by)
        assertEquals(yes to 9 - yes, p3.yes to p3.no)
        assertEquals(if (yes >= 5) Proposal.YES else Proposal.NO, p3.result)
        assertEquals(f.of(5).filter { it.adopted }, adopted)
        assertTrue(f.pending().isEmpty())
        assertFalse(f.choose(5, 3, true), "a decided proposal takes no vote")
        // The same seed votes the same from the saved file; the vote's chance is about 1 in 5.5 at 0.3.
        val again = Federation.decode(saved)
        assertEquals(adopted, again.resolve(6, SEED, committee))
        assertEquals(f.encode(), again.encode())
        val weak = Federation().also { it.assemble(5, stats(koTies = 25, koUpsets = 0), config, false) }.encode()
        val passed = (1..3000).count { seed -> Federation.decode(weak).resolve(6, seed.toLong(), committee).isNotEmpty() }
        assertTrue(passed in 450..630, "$passed of 3000 weak proposals passed (expected 18%)")
        val strong = (1..300).count { seed -> Federation.decode(saved).resolve(6, seed.toLong(), committee).any { it.number == 3 } }
        assertTrue(strong in 100..170, "$strong of 300 at 0.65 (expected 44%)")
    }

    @Test
    fun aResolvedCloseReturnsWhatItStoredAndStaleOrDisabledProposalsAreVoid() {
        val f = threePending()
        f.choose(5, 1, true)
        val adopted = f.resolve(6, SEED, committee)
        val text = f.encode()
        assertEquals(6, f.resolved)
        assertEquals(adopted, f.resolve(6, SEED + 1, emptyList()))
        assertEquals(text, f.encode())
        // The federation off at the close: the pending ones are void.
        val off = threePending()
        assertEquals(emptyList(), off.resolve(6, SEED, committee, enabled = false))
        assertTrue(off.of(5).all { it.result == Proposal.VOID && it.by == Proposal.NONE })
        // A close that never came (P(4) still pending at the close of 6): void; P(5) decided.
        val late = Federation()
        late.assemble(4, stats(season = 4, bonusRanked = 100, bonusMoved = 20), config, false)
        late.assemble(5, stats(promoted = 100, bounced = 50), config, false)
        late.choose(5, 1, true)
        assertEquals(listOf(ProposalKind.SWAP_DOWN), late.resolve(6, SEED, committee).map { it.kind })
        assertEquals(Proposal.VOID, late.of(4).single().result)
        // No pending at the first national close: nothing happens but the guard.
        val empty = Federation()
        assertEquals(emptyList(), empty.resolve(3, SEED, committee))
        assertEquals(3, empty.resolved)
    }

    @Test
    fun adoptedChangesApplyOnceAndAreVoidWhenTheRuleChangedByHand() {
        val f = threePending()
        for (n in 1..3) f.choose(5, n, n != 3)
        val adopted = f.resolve(6, SEED, committee)
        assertEquals(listOf(ProposalKind.SWAP_DOWN, ProposalKind.SWEEP_OFF), adopted.map { it.kind })
        val (next, void) = Federation.applyTo(config, adopted)
        assertEquals(emptyList(), void)
        assertEquals(config.copy(autoSwap = 1, sweepBonus = false), next)
        // Idempotent: applied again (a replay), nothing changes.
        assertEquals(next to emptyList(), Federation.applyTo(next, adopted))
        // Rejected or pending proposals are never applied.
        assertEquals(config to emptyList(), Federation.applyTo(config, f.of(5).filter { !it.adopted }))
        // The user set autoSwap to 3 by hand after the assembly (it said 2 → 1): void; the other applies.
        val edited = config.copy(autoSwap = 3)
        val (got, voided) = Federation.applyTo(edited, adopted)
        assertEquals(listOf(ProposalKind.SWAP_DOWN), voided.map { it.kind })
        assertEquals(edited.copy(sweepBonus = false), got)
        f.markVoid(voided)
        f.markVoid(voided)
        assertEquals(listOf(Proposal.VOID, Proposal.YES, Proposal.NO), f.of(5).map { it.result })
        // The stored adopted list no longer holds it, so a replayed close does not apply it either.
        assertEquals(listOf(ProposalKind.SWEEP_OFF), f.resolve(6, SEED, committee).map { it.kind })
        // Set by hand to the proposed value: already applied, not void.
        assertEquals(config.copy(autoSwap = 1, sweepBonus = false) to emptyList(), Federation.applyTo(config.copy(autoSwap = 1), adopted))
    }

    @Test
    fun aCloseReplayedAfterAKillEndsTheSameInBothOrders() {
        val f = threePending()
        f.choose(5, 1, true)
        f.choose(5, 2, true)
        val beforeClose = f.encode()
        // The close as it runs once: resolve, apply to the rules (config.txt), save the league files.
        val once = Federation.decode(beforeClose)
        val adopted = once.resolve(6, SEED, committee)
        val (rules, void) = Federation.applyTo(config, adopted)
        once.markVoid(void)
        val files = once.encode()
        assertTrue(adopted.size >= 2)
        assertTrue(rules != config)
        // 1. config.txt was written, the league files were not: the close runs again on the old file and the new rules.
        val first = Federation.decode(beforeClose)
        val a1 = first.resolve(6, SEED, committee)
        assertEquals(adopted, a1)
        val (rules1, void1) = Federation.applyTo(rules, a1)
        first.markVoid(void1)
        assertEquals(rules, rules1)
        assertEquals(files, first.encode())
        // 2. the league files were written, config.txt was not: the stored adopted list applies to the old rules.
        val second = Federation.decode(files)
        val a2 = second.resolve(6, SEED, committee)
        assertEquals(adopted, a2)
        val (rules2, void2) = Federation.applyTo(config, a2)
        second.markVoid(void2)
        assertEquals(rules, rules2)
        assertEquals(files, second.encode())
        // The open replayed after a kill: the assembly finds its proposals.
        val s6 = stats(season = 6, koTies = 25, koUpsets = 0, levers = levers(rules))
        val opened = Federation.decode(files).also { it.assemble(6, s6, rules, false) }.encode()
        assertEquals(opened, Federation.decode(opened).also { assertEquals(it.of(6), it.assemble(6, s6, rules, false)) }.encode())
    }

    @Test
    fun theFileRoundTripsAndSkipsWhatItCannotRead() {
        val f = threePending()
        f.choose(5, 2, true)
        f.resolve(6, SEED, committee)
        f.assemble(6, stats(season = 6, koTies = 25, koUpsets = 0, champPpm = 0.123, up = listOf("p1", "p2")), config, false)
        f.choose(6, 1, false)
        for (s in 1..12) f.putCoefficients(s, mapOf("KR" to intArrayOf(10 + s, 3), "JP" to intArrayOf(s, 0)))
        f.putTop(5, listOf("KR", "JP", "CN"))
        f.putTop(6, (1..10).map { "L$it" })
        val text = f.encode()
        assertTrue(text.startsWith("federation 1\nmeta 6 6\n"), text)
        val back = Federation.decode(text)
        assertEquals(text, back.encode())
        assertEquals(f.proposals, back.proposals)
        assertEquals(6 to 6, back.assembled to back.resolved)
        assertEquals((3..12).toList(), back.coefficientSeasons)
        assertEquals(22.0 / 3, back.coefficient("KR", 12))
        assertEquals(0.0, back.coefficient("JP", 12))
        assertNull(back.coefficient("CN", 12))
        assertNull(back.coefficient("KR", 2))
        assertEquals(listOf("p1", "p2"), back.promoted(6))
        assertEquals(0.123, back.stats(6)!!.champPpm)
        assertEquals(f.stats(5)!!.encode(), back.stats(5)!!.encode())
        assertEquals(8, back.top(6)!!.size)
        // A longer top line (a hand-edited file) reads as the best 8, as putTop keeps them.
        assertEquals((1..8).map { "L$it" }, Federation.decode("federation 1\ntop 9 " + (1..12).joinToString(",") { "L$it" }).top(9))
        // Escaped evidence, an unknown kind, a broken line, a line of a later version: read or skipped.
        val extra = "prop 7 1 SWAP_DOWN autoSwap 2 1 0.40 - - - 0 0 a%20b|c%7Cd\nprop 7 2 NO_SUCH_KIND autoSwap 2 1 0.40 - - - 0 0 -\n" +
            "prop 7 3 SWAP_UP clDirect 2 3 0.40 - - - 0 0 -\nprop 7 4 SWAP_UP autoSwap 2 3 0.40 - X - 0 0 -\nprop 7\ncoef x KR 1 1\nfuture 1 2 3\n"
        val more = Federation.decode(text + extra)
        assertEquals(listOf("a b", "c|d"), more.proposal(7, 1)!!.args)
        assertNull(more.proposal(7, 2))
        assertNull(more.proposal(7, 3), "a kind on another lever's key is refused")
        assertNull(more.proposal(7, 4), "no such result")
        assertTrue("a%20b|c%7Cd" in more.encode())
        assertFailsWith<IllegalArgumentException> { Federation.decode("clubs 2\nmeta 1 1") }
        assertEquals(Federation().encode(), Federation.decode("federation 1\n").encode())
        // A lost meta line: the open replayed on it finds P(6) stored and tables no second one.
        val noMeta = Federation.decode(text.lines().filter { !it.startsWith("meta ") }.joinToString("\n"))
        assertEquals(0, noMeta.assembled)
        assertEquals(f.of(6), noMeta.assemble(6, stats(season = 6, promoted = 100, bounced = 50), config, false))
        assertEquals(6, noMeta.assembled)
        assertEquals(f.proposals, noMeta.proposals)
    }

    @Test
    fun theFederationIsTheCoefficientStoreAndKnowsTheMajorLeagues() {
        val f = Federation()
        val mem = MemoryCoefficients()
        val rng = Random(3)
        for (s in 1..12) {
            val v = listOf("KR", "JP", "CN", "XAS").associateWith { intArrayOf(rng.nextInt(80), 1 + rng.nextInt(6)) }
            f.putCoefficients(s, v)
            mem.putCoefficients(s, v)
        }
        assertEquals(mem.stored, f.coefficientSeasons)
        val world = NationalStart(listOf("KR" to listOf(16, 16), "JP" to listOf(16, 16), "CN" to listOf(16), "VN" to listOf(16), "XAS" to listOf(16)))
        val members = world.leagues.list.associate { lg -> lg.id to lg.divisions.flatMap { world.divisions[it] } }
        fun rank(store: yacht.engine.league.CoefficientStore) =
            Coefficients.ranking(store, world.leagues, 12, { id -> Coefficients.strength(members.getValue(id), world::rating) }, config).rows
        assertEquals(rank(mem), rank(f))
        // A season without a top line counts every league title; one with it, its best-ranked only.
        f.putTop(7, listOf("JP", "KR"))
        assertTrue(f.isMajor(6, "VN"))
        assertTrue(f.isMajor(7, "KR") && f.isMajor(7, "JP"))
        assertFalse(f.isMajor(7, "CN"))
        assertEquals(listOf("JP", "KR"), f.top(7))
    }

    // ---- the statistics of played seasons --------------------------------------------------------

    /** Three Asian leagues, two European and two American ones, and Africa's 연합 리그, 16 a division. */
    private val world = NationalStart(
        listOf(
            "KR" to listOf(16, 16, 16), "JP" to listOf(16, 16), "CN" to listOf(16, 16), "GB" to listOf(16, 16), "FR" to listOf(16, 16),
            "US" to listOf(16, 16), "BR" to listOf(16, 16), "XAF" to listOf(16, 16),
        ),
    )
    private val print = CompetitionPrint(world::rating)

    private fun drive(plan: SeasonPlan) {
        var guard = 0
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(print.play(f))
            check(++guard < 1_000_000)
        }
        assertTrue(plan.finished)
    }

    /**
     * National season 6 of [world] played to the end with every competition, after a season 5
     * whose honours were: the 챔피언스 리그 to KR's best, the three 대륙 챔피언스 finals between the
     * best of JP and CN, GB and FR, US and BR, and each league's title to its second best.
     */
    private fun playedSeason(): Pair<SeasonPlan, List<Honour>> {
        val n = 6
        val last = ArrayList<Honour>()
        last += Honour(n - 1, "CL", "kr0x00")
        last += Honour(n - 1, "CUP", "jp0x02")
        last += Honour(n - 1, "CUP2", "cn0x02")
        for ((c, a, b) in listOf(Triple("ASIA", "jp0x00", "cn0x00"), Triple("EUROPE", "gb0x00", "fr0x00"), Triple("AMERICAS", "us0x00", "br0x00"))) {
            last += Honour(n - 1, "CCL.$c", a)
            last += Honour(n - 1, "CCL2.$c", b)
        }
        for (lg in world.leagues.list) last += Honour(n - 1, "L0.${lg.id}", "${lg.id.lowercase()}0x01")
        val start = world.start(n, config, lastTables = world.divisions, lastLeagues = world.leagues, lastHonours = last)
        val season = Season.start(n, start.seed, world.divisions, config.doubleRoundRobin, world.leagues)
        val plan = SeasonPlan(season, config, Competitions.factories().mapNotNull { it(start) }.toMutableList())
        drive(plan)
        return plan to last
    }

    @Test
    fun theStatisticsOfAPlayedNationalSeason() {
        val (plan, last) = playedSeason()
        val season = plan.league
        val leagues = season.leagues
        val moves = Offseason.moves(plan)
        val now = Offseason.honours(plan, moves)
        // Last season's promoted: the bottom three of every 1부 (in it now) and two of KR's 2부 (not: left out).
        val up = leagues.list.flatMap { lg -> world.divisions[lg.top].takeLast(3) } + world.divisions[1].take(2)
        val fed = Federation().apply { assemble(5, stats(up = up), config, false) }
        val s = FederationStats.of(plan, last + now, fed, config)
        assertEquals(6, s.season)
        assertTrue(s.national)
        // The elevator.
        val down = moves.filter { it.from == 0 }.map { it.key }.toSet()
        assertEquals(24, s.promoted)
        assertEquals(up.take(24).count { it in down }, s.bounced)
        assertTrue(s.bounced > 0)
        assertEquals(moves.filter { it.to == 0 }.map { it.key }.toSet(), s.up.toSet())
        // The 2부 champions against the 1부's middle, per league.
        val edges = leagues.list.filter { it.size >= 2 }.map { lg ->
            val top = season.table(lg.top).map { it.points.toDouble() / it.played }.sorted()
            season.table(lg.top + 1).first().let { it.points.toDouble() / it.played } - (top[7] + top[8]) / 2
        }
        assertEquals(Math.round(edges.average() * 1000) / 1000.0, s.champPpm)
        // Title playoffs (one a league) and runaway leaders.
        fun holder(kind: String) = now.single { it.kind == kind }.key
        assertEquals(8, s.playoffLeagues)
        assertEquals(leagues.list.count { holder("REG.${it.id}") != holder("L0.${it.id}") }, s.leaderLost)
        assertEquals(8, s.leagues)
        assertEquals(leagues.list.count { season.table(it.top).let { t -> t[0].points - t[1].points >= 8 } }, s.runaway)
        // Sweeps as the odds count them (without their prior).
        assertTrue(s.decided > 1000)
        assertEquals(Odds.sweepRate(season, 0.0), s.sweepShare, 1e-12)
        // Weeks and fixtures: the national ladders at 22 a division, and the old pyramid of as many members.
        assertEquals(season.matchdays, s.rounds)
        val at22 = leagues.list.sumOf { lg ->
            val n = lg.divisions.sumOf { season.divisions[it].size }
            val sizes = NationalLeagues.sizes(n, NationalLeagues.tierCount(n, 22))
            Season.start(1, 1L, sizes.mapIndexed { d, k -> List(k) { "${lg.id}$d.$it" } }, true).fixturesTotal
        }
        assertEquals(at22, s.projected)
        val keys = world.keys
        assertEquals(Offseason.first(1L, Offseason.Context(keys, world::rating, { null }, { "" }, LeagueConfig())).league.fixturesTotal, s.legacy)
        // The 요트컵 from the 8강 on.
        val cup = plan.competition(Comp.CUP) as Cup
        val played = cup.seasonMatches().filter { it.played }
        val from = played.filter { it.fixture.label == "승자조 8강" }.minOf { it.stage }
        val late = played.filter { it.stage >= from }
        val rating = cup.entries.associate { it.key to it.rating }
        assertEquals(late.size, s.koTies)
        assertTrue(s.koTies >= Federation.MIN_TIES)
        val upsets = late.count { m ->
            val (w, l) = if (m.score!!.winner > 0) m.fixture.a to m.fixture.b else m.fixture.b to m.fixture.a
            rating.getValue(w) <= rating.getValue(l) - 100
        }
        assertEquals(upsets, s.koUpsets)
        // The 챔피언스 리그: the 7 league champions who came straight in against the 6 대륙 챔피언스 finalists (the holder is neither).
        val cl = plan.competition(Comp.CHAMPIONS) as ChampionsLeague
        val entrants = cl.entrantKeys()
        val ccl = setOf("jp0x00", "cn0x00", "gb0x00", "fr0x00", "us0x00", "br0x00")
        val champions = world.leagues.list.map { "${it.id.lowercase()}0x01" }.filter { it in entrants }.toSet()
        assertTrue("kr0x00" in entrants && entrants.containsAll(ccl))
        assertEquals(7, champions.size)
        val view = cl.view() as CompetitionView.Groups
        fun tally(who: Set<String>): Pair<Int, Int> {
            var points = 0
            var matches = 0
            for (g in view.groups) for (r in g.rows) if (r.key in who) {
                points += 3 * r.wins + r.draws
                matches += r.played
            }
            for (round in view.knockout!!.rounds) for (t in round.ties) if (t.played) for (k in listOfNotNull(t.a, t.b)) if (k in who) {
                matches++
                if (t.winner == k) points += 3
            }
            return points to matches
        }
        assertEquals(tally(champions), s.champPoints to s.champMatches)
        assertEquals(tally(ccl), s.cclPoints to s.cclMatches)
        assertTrue(s.champMatches >= 42 && s.cclMatches >= 36)
        assertEquals(levers(config), s.levers)
        // The 2-0 bonus's effect on the final tables, and the divisions' spread (3/1/0 per match).
        val rows = season.divisions.map { d -> d.mapNotNull { season.standings[it] }.filter { it.played > 0 } }
        assertEquals(rows.filter { it.size >= FederationStats.PLAYOFF }.sumOf { it.size }, s.bonusRanked)
        assertTrue(s.bonusMoved in 0..s.bonusRanked)
        val gaps = rows.filter { it.size >= FederationStats.SPREAD_MIN }.map { r -> r.map { (3.0 * it.wins + it.draws) / it.played }.let { it.max() - it.min() } }
        assertEquals(Math.round(gaps.average() * 1000) / 1000.0, s.spread)
        // Round trip of the fs line (and a line from before these fields: none); nothing the assembly needs is missing.
        assertEquals(s.encode(), FederationStats.decode(6, s.encode())!!.encode())
        val old = FederationStats.decode(6, s.encode().substringBefore(",bonusMoved="))!!
        assertEquals(Triple(0, 0, null), Triple(old.bonusRanked, old.bonusMoved, old.spread))
        assertEquals(s.copy(up = emptyList(), bonusRanked = 0, bonusMoved = 0, spread = null), old)
        val f = Federation.decode(fed.encode())
        f.assemble(6, s, config, false)
        assertEquals(f.encode(), Federation.decode(f.encode()).encode())
        // The 선수 위원회: each league's best, the leagues by strength without a ranking, then the best of the rest.
        assertEquals(
            listOf("kr0x00", "jp0x00", "cn0x00", "gb0x00", "fr0x00", "us0x00", "br0x00", "xaf0x00", "kr0x01"),
            Federation.committee(plan, LeagueRanking.EMPTY, world::rating),
        )
        val ranked = LeagueRanking(6, listOf("US", "KR").mapIndexed { r, id -> LeagueRank(id, r, 0, 0.0, emptyList(), 0.0, 0, false, 0) })
        assertEquals(listOf("us0x00", "kr0x00", "jp0x00"), Federation.committee(plan, ranked, world::rating).take(3))
    }

    @Test
    fun aLegacySeasonHasTheStatisticsOfTheStructureFreeKinds() {
        val keys = (1..200).map { "p%03d".format(it) }
        fun rating(k: String) = 2000.0 - 4 * k.drop(1).toInt()
        val legacy = LeagueConfig()
        val ctx = Offseason.Context(keys, ::rating, { null }, { "" }, legacy, Competitions.factories())
        val plan = Offseason.first(5L, ctx, 3)
        val p = CompetitionPrint(::rating)
        var guard = 0
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(p.play(f))
            check(++guard < 1_000_000)
        }
        val honours = Offseason.honours(plan, Offseason.moves(plan))
        val s = FederationStats.of(plan, honours, Federation(), LeagueConfig.DEFAULT)
        assertFalse(s.national)
        // No national divisions: no spread.
        assertNull(s.spread)
        assertEquals(Odds.sweepRate(plan.league, 0.0), s.sweepShare, 1e-12)
        assertTrue(s.decided > 0 && s.koTies >= Federation.MIN_TIES)
        // No national champions, so no 챔피언스 리그 comparison; nobody was promoted the season before (no up line).
        assertEquals(0 to 0, s.champMatches to s.cclMatches)
        assertEquals(0, s.promoted)
        assertEquals(1, s.playoffLeagues)
        assertEquals(levers(legacy), s.levers)
        // Its top tier is no national 1부: those promoted into it are not kept for the next season's elevator.
        assertTrue(Offseason.moves(plan).any { it.to == 0 && it.from > 0 })
        assertEquals(emptyList(), s.up)
        // The migration's assembly reads only what such a season has.
        val fed = Federation()
        val first = fed.assemble(3, s, LeagueConfig.DEFAULT, structureFreeOnly = true)
        assertTrue(first.all { it.kind.structureFree })
        assertEquals(emptyList(), fed.promoted(3))
    }

    private companion object {
        const val SEED = 0x5EEDL
    }
}
