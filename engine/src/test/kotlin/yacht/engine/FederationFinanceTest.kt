package yacht.engine

import yacht.engine.league.ClubEconomy
import yacht.engine.league.Era
import yacht.engine.league.FamilyStats
import yacht.engine.league.Federation
import yacht.engine.league.FederationStats
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueIds
import yacht.engine.league.Lever
import yacht.engine.league.MetaBook
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Offseason
import yacht.engine.league.Proposal
import yacht.engine.league.ProposalKind
import yacht.engine.league.StyleFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 요트협회's club-world levers (design ENG 6.8): the nine levers, the six new kinds at their floors and
 * thresholds with today's pressure formula and their guards, their titles and evidence, the `fs` line's
 * new fields and `lv2=` (read by this build and by the 3ce4b93 decoder; an old line reads the defaults),
 * [Federation.applyTo] on the new keys, the statistics read from a club world's accounts and a season's
 * `fm` line, and the 긴급 안건 in federation.txt.
 */
class FederationFinanceTest {
    private val config = LeagueConfig.DEFAULT

    private fun levers(c: LeagueConfig) = Lever.entries.map { c.valueOf(it.key)!! }

    /** Statistics of season [season] that make none of the six competition rules' proposals. */
    private fun stats(
        season: Int = 5, clubs: Int = 0, red: Int = 0, topIncome: Int = 0, restIncome: Int = 0,
        elite: Int = 0, eliteRatio: Int = 0, eliteFamily: Char = '-', eraNth: Int = 0, eraSeasons: Int = 0, levers: List<String> = levers(config),
    ) = FederationStats(
        season, true, 0, 0, null, 0, 0, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0, 0, 0, 0, levers,
        clubs = clubs, red = red, topIncome = topIncome, restIncome = restIncome,
        elite = elite, eliteRatio = eliteRatio, eliteFamily = eliteFamily, eraNth = eraNth, eraSeasons = eraSeasons,
    )

    private class Case(
        val kind: ProposalKind, val config: LeagueConfig, val to: String, val at: (Int) -> FederationStats,
        val floor: Int, val threshold: Int, val short: Int, val off: List<Pair<LeagueConfig, FederationStats>>,
    )

    private val cases: List<Case> by lazy {
        listOf(
            Case(
                ProposalKind.FFP_UP, config, "2", { stats(clubs = 100, red = it) }, 8, 15, 7,
                listOf(config.copy(ffp = 2) to stats(clubs = 100, red = 15), config to stats(clubs = 39, red = 39)),
            ),
            Case(
                ProposalKind.FFP_DOWN, config, "0", { stats(clubs = 1000, red = it) }, 20, 5, 21,
                listOf(config.copy(ffp = 0) to stats(clubs = 1000, red = 5), config to stats(clubs = 39, red = 0)),
            ),
            Case(
                ProposalKind.SOLIDARITY_UP, config, "10", { stats(clubs = 100, red = 5, topIncome = it, restIncome = 100) }, 260, 320, 259,
                listOf(
                    config.copy(solidarity = 20) to stats(clubs = 100, topIncome = 320, restIncome = 100),
                    config to stats(clubs = 100, topIncome = 320, restIncome = 0), config to stats(clubs = 39, topIncome = 320, restIncome = 100),
                ),
            ),
            Case(
                ProposalKind.SOLIDARITY_DOWN, config, "0", { stats(clubs = 100, red = 5, topIncome = it, restIncome = 100) }, 190, 160, 191,
                listOf(config.copy(solidarity = 0) to stats(clubs = 100, topIncome = 160, restIncome = 100), config to stats(clubs = 39, topIncome = 160, restIncome = 100)),
            ),
            Case(
                ProposalKind.STYLE_CAP_DOWN, config, "13", { stats(elite = 200, eliteRatio = it, eliteFamily = 'Y') }, 160, 200, 159,
                listOf(
                    config.copy(styleCap = 13) to stats(elite = 200, eliteRatio = 200, eliteFamily = 'Y'),
                    config to stats(elite = 79, eliteRatio = 200, eliteFamily = 'Y'), config to stats(elite = 200, eliteRatio = 200, eliteFamily = '-'),
                ),
            ),
            Case(
                ProposalKind.STYLE_CAP_UP, config, "17", { stats(elite = 200, eliteRatio = it, eliteFamily = 'C') }, 125, 110, 126,
                listOf(config.copy(styleCap = 17) to stats(elite = 200, eliteRatio = 110, eliteFamily = 'C'), config to stats(elite = 79, eliteRatio = 110, eliteFamily = 'C')),
            ),
        )
    }

    @Test
    fun theNineLeversAreTheSixRulesAndTheClubWorldsThree() {
        assertEquals(
            listOf("autoSwap", "titlePlayoff", "sweepBonus", "divisionSize", "longKnockouts", "clDirect", "ffp", "solidarity", "styleCap"),
            Lever.entries.map { it.key },
        )
        assertEquals(6, Lever.FIRST)
        assertEquals(listOf("재정 규정", "연대 기금", "신인 스타일 상한"), Lever.entries.drop(Lever.FIRST).map { it.label })
        for (l in Lever.entries) assertNotNull(config.valueOf(l.key), l.key)
        assertEquals(listOf("끄기", "기본", "엄격"), (0..2).map { Lever.text(Lever.FFP, "$it") })
        assertEquals("10%", Lever.text(Lever.SOLIDARITY, "10"))
        assertEquals(listOf("1.3배", "1.5배", "1.7배"), LeagueConfig.STYLE_CAPS.map { Lever.text(Lever.STYLE_CAP, "$it") })
        assertEquals("7", Lever.text(Lever.CL_DIRECT, "7"))
        // Each new kind on its own lever, two directions a lever, none structure-free (a legacy season has no club world).
        val newer = ProposalKind.entries.filter { it.lever.ordinal >= Lever.FIRST }
        assertEquals(cases.map { it.kind }.toSet(), newer.toSet())
        for (l in Lever.entries.drop(Lever.FIRST)) assertEquals(2, newer.count { it.lever == l })
        assertTrue(newer.none { it.structureFree })
    }

    @Test
    fun theFloorsAndThresholdsOfTheSixNewKinds() {
        assertEquals(
            mapOf(
                ProposalKind.FFP_UP to (0.08 to 0.15), ProposalKind.FFP_DOWN to (0.02 to 0.005),
                ProposalKind.SOLIDARITY_UP to (2.6 to 3.2), ProposalKind.SOLIDARITY_DOWN to (1.9 to 1.6),
                ProposalKind.STYLE_CAP_DOWN to (1.6 to 2.0), ProposalKind.STYLE_CAP_UP to (1.25 to 1.1),
            ),
            ProposalKind.entries.filter { it.lever.ordinal >= Lever.FIRST }.associateWith { it.floor to it.threshold },
        )
    }

    @Test
    fun everyNewKindFiresFromItsFloorWithThePressureFormulaAndItsGuards() {
        for (c in cases) {
            fun of(s: FederationStats) = Federation.candidates(s, c.config).filter { it.kind == c.kind }
            val floor = of(c.at(c.floor)).single()
            assertEquals(0.3, floor.pressure, "${c.kind} at its floor")
            assertEquals(c.config.valueOf(c.kind.lever.key), floor.from)
            assertEquals(c.to, floor.to, "${c.kind}")
            assertEquals(1.0, of(c.at(c.threshold)).single().pressure, "${c.kind} at its threshold")
            assertEquals(emptyList(), of(c.at(c.short)), "${c.kind} short of its floor")
            assertEquals(listOf(c.kind), Federation.candidates(c.at(c.threshold), c.config).map { it.kind }, "only ${c.kind}")
            // Short of the floor but leaning its way: a 검토 안건.
            val review = Federation.candidates(c.at(c.short), c.config, floorFree = true).single { it.kind == c.kind }
            assertTrue(review.review && review.pressure > 0 && review.pressure < Federation.FLOOR_PRESSURE)
            for (p in listOf(floor, of(c.at(c.threshold)).single(), review)) {
                assertTrue(p.title.isNotBlank() && p.evidence.isNotBlank())
                assertFalse("?" in p.title + p.evidence || "{" in p.title + p.evidence || "null" in p.evidence, "${c.kind}: ${p.title} / ${p.evidence}")
                assertNotNull(c.config.with(p.lever.key, p.to), "${c.kind}: the rules take ${p.to}")
            }
            for ((i, off) in c.off.withIndex()) assertEquals(emptyList(), Federation.candidates(off.second, off.first).filter { it.kind == c.kind }, "${c.kind} guard $i")
        }
        // A season without a club world (and without an `fm` line) proposes none of them.
        assertEquals(emptyList(), Federation.candidates(stats(), config))
        assertEquals(emptyList(), Federation.candidates(stats(), config, floorFree = true))
        // Under strict FFP the 3rd step down; at 엄격 1.3배 the cap can only loosen.
        assertEquals("1", Federation.candidates(stats(clubs = 1000, red = 5), config.copy(ffp = 2)).single().to)
        assertEquals("15", Federation.candidates(stats(elite = 200, eliteRatio = 110, eliteFamily = 'C'), config.copy(styleCap = 13)).single().to)
    }

    @Test
    fun theNewProposalsReadAsTheirNumbersSay() {
        val ffp = Federation.candidates(stats(clubs = 127, red = 21), config).single()
        assertEquals("재정 규정 강화 (기본 → 엄격)", ffp.title)
        assertEquals("클럽 127곳 가운데 21곳(17%)이 적자로 시즌을 마쳤어요. 쓰는 돈을 버는 돈에 맞추도록 규정을 강화해 클럽을 지켜요.", ffp.evidence)
        assertEquals(listOf("127", "21", "17"), ffp.args)
        val none = Federation.candidates(stats(clubs = 127, red = 0), config).single()
        assertEquals("재정 규정 완화 (기본 → 끄기)", none.title)
        assertEquals("클럽 127곳 가운데 적자로 시즌을 마친 클럽이 한 곳도 없었어요. 규정을 풀어 클럽이 더 과감하게 투자하게 해요.", none.evidence)
        val few = Federation.candidates(stats(clubs = 1000, red = 10), config).single()
        assertEquals("클럽 1000곳 가운데 적자로 시즌을 마친 클럽이 10곳(1%)뿐이에요. 규정을 풀어 클럽이 더 과감하게 투자하게 해요.", few.evidence)
        val gap = Federation.candidates(stats(clubs = 127, red = 5, topIncome = 3300, restIncome = 1000), config).single()
        assertEquals("연대 기금 5% → 10%", gap.title)
        assertEquals("상위 8개 리그 클럽의 평균 수입이 나머지 리그의 3.3배였어요. 중계권 수입을 더 나눠 격차를 줄여요.", gap.evidence)
        val close = Federation.candidates(stats(clubs = 127, red = 5, topIncome = 1500, restIncome = 1000), config).single()
        assertEquals("연대 기금 5% → 0%", close.title)
        assertEquals("상위 8개 리그 클럽의 평균 수입이 나머지 리그의 1.5배로, 격차가 줄었어요. 기금을 줄여 상위 리그의 힘을 지켜요.", close.evidence)
        val era = Federation.candidates(stats(elite = 310, eliteRatio = 212, eliteFamily = 'Y', eraNth = 2, eraSeasons = 4), config).single()
        assertEquals("신인 스타일 상한 1.5배 → 1.3배", era.title)
        assertEquals(
            "두 번째 요트파의 시대가 4시즌째예요. 상위 리그 1부 선수의 42%가 요트파로, 보통 비율의 2.1배예요. 신인 스타일 상한을 낮춰 다양성을 지켜요.",
            era.evidence,
        )
        val noEra = Federation.candidates(stats(elite = 310, eliteRatio = 212, eliteFamily = 'Y'), config).single()
        assertEquals("상위 리그 1부 선수의 42%가 요트파로, 보통 비율의 2.1배예요. 신인 스타일 상한을 낮춰 다양성을 지켜요.", noEra.evidence)
        val even = Federation.candidates(stats(elite = 310, eliteRatio = 104, eliteFamily = 'C'), config).single()
        assertEquals("신인 스타일 상한 1.5배 → 1.7배", even.title)
        assertEquals("가장 많은 족보파도 상위 리그 1부에서 보통 비율의 1.0배뿐이에요. 상한을 풀어 신인들이 유행을 따르게 해요.", even.evidence)
        // A 긴급 안건 says who decided it.
        val e = Proposal(4, Proposal.EMERGENCY_NUMBER, ProposalKind.CL_DIRECT_UP, "7", "8", 1.0, listOf("3"), result = Proposal.YES, by = Proposal.EMERGENCY)
        assertTrue(e.emergency && e.adopted && !e.review)
        assertEquals("슈퍼리그 사태로 긴급 결정했어요.", e.evidence)
        assertEquals("챔피언스 리그 직행 7개 리그 → 8개", e.title)
        assertFalse(e.copy(number = 1, result = Proposal.VOID).emergency)
    }

    @Test
    fun theFsLineCarriesTheNewFieldsAndTheNewerLeversOnlyWhenTheyHaveThem() {
        // Without a club world and with the newer levers at their defaults: the line as 3ce4b93 wrote it.
        val plain = stats()
        assertEquals(
            "national=1,elevator=0/0,champPpm=-,leaderLost=0/0,runaway=0/0,sweep=0/0,rounds=40,projected=0,legacy=0,koUpsets=0/0," +
                "clChamp=0/0,clCcl=0/0,lv=2/1/1/20/1/7,bonusMoved=0/0,spread=-",
            plain.encode(),
        )
        assertEquals(plain, FederationStats.decode(5, plain.encode()))
        // With them: red, income, elite, era and lv2 after the old fields.
        val full = stats(
            clubs = 127, red = 21, topIncome = 3300, restIncome = 1000, elite = 310, eliteRatio = 212, eliteFamily = 'Y', eraNth = 2, eraSeasons = 4,
            levers = levers(config.copy(ffp = 2, solidarity = 10)),
        )
        val text = full.encode()
        assertTrue(text.endsWith(",spread=-,red=21/127,income=3300/1000,elite=Y:212/310,era=2/4,lv2=2/10/15"), text)
        assertEquals(full, FederationStats.decode(5, text))
        assertEquals(0.21 / 1.27, full.redShare, 1e-9)
        assertEquals(3.3, full.incomeGap!!, 1e-9)
        assertEquals(2.12, full.styleRatio, 1e-9)
        // A line from before them reads the newer levers at their defaults and no club world.
        val old = FederationStats.decode(5, plain.encode().replace(",bonusMoved=0/0,spread=-", ""))!!
        assertEquals(listOf("2", "1", "1", "20", "1", "7", "1", "5", "15"), old.levers)
        assertEquals(0 to null, old.clubs to old.incomeGap)
        // lv= keeps exactly six values: a line with nine there is unusable.
        assertNull(FederationStats.decode(5, plain.encode().replace("lv=2/1/1/20/1/7", "lv=2/1/1/20/1/7/1/5/15")))
        // The 3ce4b93 decoder reads the new line and keeps every old field.
        val older = OldDecoder.decode(text)
        assertNotNull(older)
        assertEquals(listOf("2", "1", "1", "20", "1", "7"), older.levers)
        assertEquals(40, older.rounds)
        // In federation.txt, through a reload.
        val f = Federation()
        f.assemble(5, full, config.copy(ffp = 2, solidarity = 10), false)
        val back = Federation.decode(f.encode())
        assertEquals(full.copy(up = emptyList()), back.stats(5))
        assertEquals(f.encode(), back.encode())
    }

    /** FederationStats.decode as 3ce4b93 had it (six levers required), returning the fields it kept. */
    private object OldDecoder {
        class Old(val rounds: Int, val levers: List<String>)

        fun decode(text: String): Old? = runCatching {
            val f = text.split(',').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun pair(k: String): Pair<Int, Int> = f.getValue(k).split('/').let { it[0].toInt() to it[1].toInt() }
            for (k in listOf("elevator", "leaderLost", "runaway", "sweep", "koUpsets", "clChamp", "clCcl")) pair(k)
            val levers = f.getValue("lv").split('/')
            require(levers.size == 6)
            f["bonusMoved"]?.let { pair("bonusMoved") }
            f.getValue("champPpm").takeIf { it != "-" }?.toDouble()
            f["spread"]?.takeIf { it != "-" }?.toDouble()
            f.getValue("projected").toInt()
            f.getValue("legacy").toInt()
            Old(f.getValue("rounds").toInt(), levers)
        }.getOrNull()
    }

    @Test
    fun adoptedProposalsSetTheNewLevers() {
        fun adopt(kind: ProposalKind, from: String, to: String, n: Int) = Proposal(5, n, kind, from, to, 1.0, emptyList(), result = Proposal.YES, by = Proposal.COMMITTEE)
        val adopted = listOf(adopt(ProposalKind.FFP_UP, "1", "2", 1), adopt(ProposalKind.SOLIDARITY_UP, "5", "10", 2), adopt(ProposalKind.STYLE_CAP_DOWN, "15", "13", 3))
        val (next, void) = Federation.applyTo(config, adopted)
        assertEquals(emptyList(), void)
        assertEquals(config.copy(ffp = 2, solidarity = 10, styleCap = 13), next)
        val changed = config.encode(full = true).lines().zip(next.encode(full = true).lines()).filter { (a, b) -> a != b }.map { it.second }
        assertEquals(listOf("ffp 2", "solidarity 10", "styleCap 13"), changed)
        // Applied again (a replay): nothing; changed by hand since: void.
        assertEquals(next to emptyList(), Federation.applyTo(next, adopted))
        assertEquals(listOf(ProposalKind.SOLIDARITY_UP), Federation.applyTo(config.copy(solidarity = 15), adopted).second.map { it.kind })
    }

    @Test
    fun anEmergencyProposalIsSavedAndUpsertedOnce() {
        val f = Federation()
        val e = Proposal(4, Proposal.EMERGENCY_NUMBER, ProposalKind.CL_DIRECT_UP, "7", "8", 1.0, listOf("3"), result = Proposal.YES, by = Proposal.EMERGENCY)
        // Only an adopted 긴급 안건 goes in.
        assertFalse(f.upsert(listOf(e.copy(by = Proposal.COMMITTEE), e.copy(result = Proposal.NONE))))
        assertTrue(f.upsert(listOf(e)))
        assertFalse(f.upsert(listOf(e)))
        val text = f.encode()
        assertTrue("prop 4 9 CL_DIRECT_UP clDirect 7 8 1.00 - A E 0 0 3" in text, text)
        val back = Federation.decode(text)
        assertEquals(listOf(e), back.of(4))
        assertEquals(text, back.encode())
        // An older build's decoder skips the line (its deciders are -, U and C).
        assertFalse(Proposal.EMERGENCY in setOf(Proposal.NONE, Proposal.USER, Proposal.COMMITTEE))
        // Decided at its close: resolve returns it among the adopted ones, also when asked again.
        assertEquals(listOf(e), f.resolve(5, 1L, (1..9).map { "m$it" }))
        assertEquals(listOf(e), f.resolve(5, 2L, emptyList()))
        assertEquals(6, e.appliesFrom)
    }

    @Test
    fun theStatisticsReadTheClubWorldsAccountsAndTheSeasonsStyles() {
        val (l, plan) = Cw.migrated(400)
        Cw.play(plan)
        l.close(plan)
        val next = l.next(plan.number)
        l.book.offseason(plan, l.input(next, plan))
        val honours = l.honours.toList()
        val bare = FederationStats.of(plan, honours, Federation(), config)
        assertEquals(0 to 0, bare.clubs to bare.red)
        val withClubs = FederationStats.of(plan, honours, Federation(), config, clubs = l.book)
        val report = ClubEconomy.report(l.book, plan.number)
        assertEquals(report.clubs.size, withClubs.clubs)
        assertTrue(withClubs.clubs >= 40, "${withClubs.clubs} clubs")
        assertEquals(report.clubs.count { it.second.balance < 0 }, withClubs.red)
        // Every old field as before; the money fields from the accounts.
        assertEquals(bare.encode(), withClubs.copy(clubs = 0, red = 0, topIncome = 0, restIncome = 0).encode())
        // No ranking yet: no best-ranked leagues, every club among the rest.
        assertTrue(NationalLeagues.bestRanked(plan, Federation.TOP).isEmpty())
        assertEquals(0, withClubs.topIncome)
        assertEquals(Math.round(report.incomes.values.sum().toDouble() / report.clubs.size).toInt(), withClubs.restIncome)
        // The season's best-ranked leagues from the federation's `top` line: their clubs' mean income against the others'.
        val first = plan.league.leagues.list.first().id
        val f = Federation().also { it.putTop(plan.number, listOf(first)) }
        val split = FederationStats.of(plan, honours, f, config, clubs = l.book)
        // A club's league is its nation's of the season (where most of its nation's clubs' members played), not
        // where its first member played: the off-season's transfers have brought some in from other leagues.
        val votes = HashMap<String, HashMap<String, Int>>()
        for (k in plan.league.standings.keys) {
            val nation = l.book.clubOf(k)?.let { l.book.nationOf(it) } ?: continue
            votes.getOrPut(nation) { HashMap() }.merge(plan.league.leagueOf(k)!!.id, 1, Int::plus)
        }
        fun leagueOf(id: String) = votes[l.book.nationOf(id)]?.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { LeagueIds.order(it.key) })?.key
        val (inTop, rest) = report.clubs.map { it.first }.partition { leagueOf(it) == first }
        assertTrue(inTop.isNotEmpty() && rest.isNotEmpty())
        assertTrue(inTop.none { id -> rest.any { l.book.nationOf(it) == l.book.nationOf(id) } }, "a nation's clubs are on one side")
        fun mean(ids: List<String>) = Math.round(ids.sumOf { report.incomes.getValue(it).toLong() }.toDouble() / ids.size).toInt()
        assertEquals(mean(inTop) to mean(rest), split.topIncome to split.restIncome)
        assertEquals(split.topIncome.toDouble() / split.restIncome, split.incomeGap!!, 1e-9)
        // A club of the rest whose first member is now a newcomer from the best-ranked league stays among the rest.
        val y = rest.first()
        val newcomer = plan.league.standings.keys.first { k -> plan.league.leagueOf(k)?.id == first && l.book.clubOf(k).let { it != null && it != y } }
        val squad = l.book.members(y)
        for (k in squad) l.book.place(k, null)
        l.book.place(newcomer, y)
        for (k in squad) l.book.place(k, y)
        assertEquals(first, plan.league.leagueOf(l.book.members(y).first())?.id)
        val moved = FederationStats.of(plan, honours, f, config, clubs = l.book)
        assertEquals(split.topIncome to split.restIncome, moved.topIncome to moved.restIncome)
        // The elite's style: the family most above its neutral share, and the era of that family going on.
        val elite = listOf(10, 60, 20, 10, 10, 5, 3, 2)
        val fs = FamilyStats(plan.number, elite, elite, emptyList(), List(8) { 0.0 }, emptyList(), emptyMap())
        val meta = MetaBook()
        val styled = FederationStats.of(plan, honours, Federation(), config, families = fs)
        assertEquals(Triple(120, 'Y', Math.round(100.0 * 60 / 120 / StyleFamily.NEUTRAL.getValue(StyleFamily.YACHT)).toInt()), Triple(styled.elite, styled.eliteFamily, styled.eliteRatio))
        assertEquals(0 to 0, styled.eraNth to styled.eraSeasons)
        metaEra(meta, Era(1, StyleFamily.YACHT, plan.number - 1, 0, 250))
        val withEra = FederationStats.of(plan, honours, Federation(), config, families = fs, meta = meta)
        assertEquals(1 to 2, withEra.eraNth to withEra.eraSeasons)
        // Another family's era is not this one's; another season's line is not read.
        val other = MetaBook().also { metaEra(it, Era(1, StyleFamily.COMBO, plan.number - 1, 0, 250)) }
        assertEquals(0, FederationStats.of(plan, honours, Federation(), config, families = fs, meta = other).eraNth)
        assertEquals(0, FederationStats.of(plan, honours, Federation(), config, families = fs.copy(season = plan.number - 1)).elite)
        // A legacy season reads none of it.
        val legacy = Offseason.first(5L, Offseason.Context(l.members, Cw::rating, { null }, { "" }, LeagueConfig()), 9)
        assertEquals(0, FederationStats.of(legacy, emptyList(), Federation(), LeagueConfig(), clubs = l.book, families = fs.copy(season = 9)).elite)
    }

    private fun metaEra(meta: MetaBook, era: Era) {
        meta.eraList += era
    }
}
