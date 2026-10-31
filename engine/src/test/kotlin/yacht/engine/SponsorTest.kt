package yacht.engine

import yacht.engine.league.ArcEffects
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubYear
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LifeReport
import yacht.engine.league.Nations
import yacht.engine.league.Sponsor
import yacht.engine.league.Sponsors
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Main sponsors (design ENG 1b.4): coined names, the deal formula, renewals, walk-outs, record deals and the crisis factor. */
class SponsorTest {
    /** Real companies, banks, airlines, carmakers, brands and funds (and revision 1's real-sounding pairs): no coined name may contain one. */
    private val deny = listOf(
        "삼성", "현대", "엘지", "LG", "에스케이", "SK", "롯데", "한화", "포스코", "기아", "대우", "두산", "신세계", "씨제이", "CJ", "한진", "금호",
        "효성", "코오롱", "대한항공", "아시아나", "셀트리온", "카카오", "네이버", "쿠팡", "넥슨", "엔씨", "넷마블", "하이브", "농심", "오뚜기",
        "동원", "오리온", "빙그레", "한국전력", "대양해운", "금강건설", "누리텔레콤", "퀀텀", "썬라이즈", "레드락", "캐피털", "캐피탈", "증권",
        "신한", "국민은행", "우리은행", "하나은행", "기업은행", "미래에셋", "교보", "동양", "대림", "삼양", "한솔", "태영", "부영", "호반",
        "아모레", "유한", "녹십자", "종근당", "한미", "대웅", "광동", "보령", "일동", "해태", "크라운", "진로", "하이트", "매일", "남양",
        "풀무원", "대상", "샘표", "이마트", "홈플러스", "다이소", "무신사", "토스", "야놀자", "컬리", "팬택", "한글과컴퓨터", "안랩", "웹젠",
        "펄어비스", "크래프톤", "스마일게이트", "위메이드", "컴투스", "게임빌", "그라비티", "데브시스터즈", "네오위즈", "시프트업", "팬오션",
        "고려해운", "에이치엠엠", "장금상선", "대한통운", "한라", "만도", "넥센", "한국타이어", "에쓰오일", "지에스", "GS", "케이티", "KT",
        "유플러스", "에스원", "한국가스", "제주항공", "진에어", "티웨이", "에어부산", "이스타",
        "보다폰", "텔레포니카", "오렌지", "버라이즌", "에미레이트", "에티하드", "카타르", "루프트한자", "알리안츠", "악사", "아비바", "푸르덴셜",
        "제네랄리", "산탄데르", "바클레이스", "지멘스", "보쉬", "필립스", "소니", "파나소닉", "토요타", "도요타", "혼다", "닛산", "마쓰다",
        "폭스바겐", "아우디", "벤츠", "피아트", "르노", "푸조", "포드", "쉐보레", "테슬라", "네슬레", "다논", "유니레버", "하이네켄", "칼스버그",
        "펩시", "코카", "아디다스", "나이키", "푸마", "라쿠텐", "요코하마", "브리지스톤", "피렐리", "미쉐린", "가즈프롬", "아람코", "페트로나스",
        "마스터카드", "아마존", "구글", "애플", "마이크로소프트", "인텔", "오라클", "시스코", "레노버", "화웨이", "샤오미", "알리바바", "텐센트",
        "바이두", "노키아", "에릭슨", "스포티파이", "우버", "지프", "오펠", "볼보", "이케아", "레고", "유니클로", "캐논", "니콘", "엡손", "후지쯔",
        "히타치", "도시바", "미쓰비시", "미쓰이", "스미토모", "노무라", "다이와", "미즈호", "아사히", "산토리", "삿포로", "야마하", "가와사키",
        "스즈키", "스바루", "렉서스", "블랙록", "뱅가드", "블랙스톤", "칼라일", "아폴로", "엘리엇", "소프트뱅크", "골드만", "모건", "도이체",
        "노바티스", "로슈", "화이자", "모더나", "바이엘", "바스프", "에어버스", "보잉", "롤스로이스", "페라리", "람보르기니", "포르쉐", "마세라티",
        "벤틀리", "재규어", "랜드로버", "티모바일", "도이치", "에어프랑스", "싱가포르항공", "캐세이", "콴타스", "에어캐나다", "터키항공",
        "아에로플로트", "라탐", "아비앙카", "에어아시아", "라이언에어", "이지젯", "구찌", "프라다", "루이비통", "샤넬", "에르메스", "디올",
        "버버리", "아르마니", "베르사체", "로레알", "랑콤", "시세이도", "콜게이트", "킴벌리",
    )

    /** Every name a club world can coin: both forms, the 지역 기업 and naming companies (the same forms) and the funds. */
    private val coined: List<String> =
        Sponsors.forms(true).map { it.first } + Sponsors.forms(false).map { it.first } +
            Sponsors.FOREIGN_PREFIXES.indices.flatMap { listOf(Sponsors.fund(it), Sponsors.state(it)) }

    @Test
    fun everyNameIsCoined() {
        assertEquals(300, Sponsors.forms(true).size)
        assertEquals(450, Sponsors.forms(false).size)
        for (n in coined) for (d in deny) assertFalse(d in n, "$n contains $d")
        // The forms: "휘담전자" for Korea, "벨로렌 텔레콤" elsewhere, each name once.
        assertEquals("휘담전자", Sponsors.name(0, 0))
        assertEquals("벨로렌 텔레콤", Sponsors.name(0, Sponsors.KR_SECTORS.size))
        assertEquals(coined.size, coined.toSet().size)
        assertTrue(Sponsors.forms(true).all { ' ' !in it.first })
        assertTrue(Sponsors.forms(false).all { ' ' in it.first })
        assertEquals("벨로렌 파트너스", Sponsors.fund(0))
        assertEquals("이스벤 인베스트먼트", Sponsors.state(29))
    }

    @Test
    fun namesAreUniqueInABookOf377Clubs() {
        val others = Nations.ALL.filter { it != Nations.KR }
        val specs = ArrayList<Eco.Spec>()
        for (k in 0 until 16) specs += Eco.Spec(ClubBook.idOf(specs.size + 1), Nations.KR.code, listOf("kr$k"), city = k % ClubNames.cities(Nations.KR).size)
        var i = 0
        while (specs.size < 377) {
            val nation = others[i % others.size]
            val k = i / others.size
            specs += Eco.Spec(ClubBook.idOf(specs.size + 1), nation.code, listOf("p$i"), city = k % ClubNames.cities(nation).size)
            i++
        }
        val book = Eco.book(specs)
        ClubEconomy.found(book, 0, { 20 })
        val deals = book.clubs.associate { it.id to book.life(it.id)!!.sponsor!! }
        assertEquals(377, deals.size)
        assertEquals(377, deals.values.map { it.name }.toSet().size, "unique")
        for (c in book.clubs) {
            val name = deals.getValue(c.id).name
            val korean = c.nation == Nations.KR.code
            assertEquals(!korean, ' ' in name, "${c.id} ${c.nation} $name")
            assertTrue(name in coined)
            assertEquals(Sponsors.forms(korean).single { it.first == name }.second, deals.getValue(c.id).sector, "the sector of the name")
        }
        // Deterministic: the same book founds the same names.
        val again = Eco.book(specs)
        ClubEconomy.found(again, 0, { 20 })
        assertEquals(book.encode(), again.encode())
        // A draw never gives a taken name: with every Korean name taken, the next comes from the other forms.
        val all = Sponsors.forms(true).map { it.first }.toSet()
        assertFalse(Sponsors.draw("KR", 1L, 1, 1, all).first in all)
        assertTrue(Sponsors.namingName("KR", 1L, 1, 3, setOf(Sponsors.draw("KR", 1L, 1, 3, emptySet()).first)) in coined)
    }

    @Test
    fun theDealFollowsTheLeagueTheFansAndRecentTitles() {
        val lambda = LeagueEconomy.lambda(2, 28)
        // λ × (1.2 + 0.7 × ln(1 + F / 5,000)) × (1 + 0.08 × min(5, titles)) × sector factor: the worked example's 4.6억.
        assertEquals(46, Sponsors.perSeason(lambda, 25_000, 3, 0))
        assertEquals(37, Sponsors.perSeason(lambda, 25_000, 0, 0))
        assertEquals(Sponsors.perSeason(lambda, 25_000, 5, 0), Sponsors.perSeason(lambda, 25_000, 9, 0))
        assertEquals(12, Sponsors.perSeason(1.0, 0, 0, 0))
        // Sectors: banks 1.10, telecoms 1.05, games 0.95, the rest 1.0, in both forms.
        val factor = Sponsors.SECTORS.associateWith { Sponsors.sectorFactor(Sponsors.SECTORS.indexOf(it)) }
        assertEquals(1.10, factor.getValue("은행"))
        assertEquals(1.10, factor.getValue("뱅크"))
        assertEquals(1.05, factor.getValue("통신"))
        assertEquals(1.05, factor.getValue("텔레콤"))
        assertEquals(0.95, factor.getValue("게임즈"))
        assertEquals(0.95, factor.getValue("게이밍"))
        assertEquals(24, Sponsors.SECTORS.indices.count { Sponsors.sectorFactor(it) == 1.0 })
        assertEquals(41, Sponsors.perSeason(lambda, 25_000, 0, Sponsors.SECTORS.indexOf("은행")), "3.745 × 1.10")
        // A crisis's factor and a replacement's 0.85.
        assertEquals(22, Sponsors.perSeason(lambda, 25_000, 0, 0, 0.7 * Sponsors.REPLACEMENT), "3.745 × 0.7 × 0.85")
        // 2 to 4 seasons, all three lengths.
        val lengths = (1..60).flatMap { c -> (1..10).map { s -> Sponsors.length(5L, c, s) } }
        assertEquals(setOf(2, 3, 4), lengths.toSet())
        // Naming rights: 0.5 × λ × capacity / 20,000 억.
        assertEquals(15, Sponsors.naming(1.5, 40_000))
    }

    /** A Korean league of three clubs with the deals given (null: none), the club-life step after season [season] (plan seed [seed]). */
    private fun close(deals: List<Sponsor?>, season: Int = 3, seed: Long = 7L, arcs: ArcEffects = ArcEffects.NONE, places: List<Int> = listOf(1, 2, 3), fans: Int = 20_000): Pair<ClubBook, LifeReport> {
        val book = Eco.world(Eco.book(deals.indices.map { i -> Eco.Spec("c0${i + 1}", "KR", listOf("a$i", "b$i"), city = i) }), fans = fans)
        val e = book.world!!.economy
        for ((i, d) in deals.withIndex()) {
            val id = "c0${i + 1}"
            if (d != null) e.sponsors[id] = d
            Eco.closed(book, id, season, 100)
            // The places of the seasons before (the deal's record).
            e.years[id] = listOf(ClubYear(season, 0, 100, fans, places[i])) + (1 until season).reversed().map { ClubYear(it, 0, 100, fans, places[i]) }
        }
        val ids = deals.indices.map { "c0${it + 1}" }
        val order = ids.sortedBy { places[ids.indexOf(it)] }
        val plan = Eco.plan(season, listOf("KR" to book.clubs.flatMap { it.squad }), seed)
        return book to ClubEconomy.life(book, plan, Eco.input(book, arcs = arcs), mapOf("KR" to Eco.table("KR", order)))
    }

    @Test
    fun renewalsAndWalkOutsAreSeeded() {
        // The chances: renew with 0.8 for a decent record, 0.5 for a poor one; a crisis doubles the walk-outs.
        fun share(perf: Double, walkout: Double): Double {
            val rng = Random(3)
            return (1..4_000).count { Sponsors.renews(perf, walkout, rng) } / 4_000.0
        }
        assertEquals(0.8, share(0.0, 1.0), 0.03)
        assertEquals(0.8, share(-0.2, 1.0), 0.03)
        assertEquals(0.5, share(-0.3, 1.0), 0.03)
        assertEquals(0.6, share(0.0, 2.0), 0.03)
        assertEquals(0.0, share(-0.3, 2.0))
        // Through the step: a deal of seasons 1..3 ends; it is renewed (same sponsor, from 4) or replaced at 0.85; deterministic for a seed.
        var renewed = 0
        for (seed in 1L..200L) {
            val deal = Sponsor("솔채통신", 1, 40, 1, 3, false)
            val (book, r) = close(listOf(deal, null, null), seed = seed)
            assertEquals(r, close(listOf(deal, null, null), seed = seed).second)
            val now = book.life("c01")!!.sponsor!!
            assertEquals(4, now.from)
            assertTrue(now.until in 5..7)
            val change = r.signed.single { it.club == "c01" }
            if (change.renewed) {
                renewed++
                assertEquals("솔채통신" to 1, now.name to now.sector)
                assertTrue(r.left.none { it.club == "c01" })
            } else {
                assertEquals(deal, r.left.single { it.club == "c01" }.sponsor)
                assertTrue(now.name != "솔채통신")
                assertEquals(Sponsors.perSeason(1.0, book.life("c01")!!.fans, 1, now.sector, Sponsors.REPLACEMENT), now.perSeason)
            }
        }
        assertTrue(renewed in 140..180, "$renewed of 200")
        // A deal not at its end stays (no crisis, mood above 20).
        val (book, r) = close(listOf(Sponsor("솔채통신", 1, 40, 1, 6, false), null, null))
        assertEquals(Sponsor("솔채통신", 1, 40, 1, 6, false), book.life("c01")!!.sponsor)
        assertTrue(r.left.isEmpty())
        // Clubs without a sponsor sign one at 0.85 × the formula (club-table titles of the last 5 seasons count).
        assertEquals(setOf("c02", "c03"), r.signed.map { it.club }.toSet())
    }

    @Test
    fun aSponsorMayWalkMidDealInACrisis() {
        val crisis = ArcEffects(sponsorFactor = 0.7, tvFactor = 0.85)
        fun walked(arcs: ArcEffects): Int = (1L..400L).count { seed -> close(listOf(Sponsor("솔채통신", 1, 40, 1, 9, false), null, null), seed = seed, arcs = arcs).second.left.any { it.mid } }
        assertEquals(0, walked(ArcEffects.NONE))
        assertTrue(walked(crisis) in 40..80, "p 0.15")
        assertTrue(walked(crisis.copy(walkoutFactor = 2.0)) in 95..145, "p 0.30")
        val seed = (1L..400L).first { s -> close(listOf(Sponsor("솔채통신", 1, 40, 1, 9, false), null, null), seed = s, arcs = crisis).second.left.isNotEmpty() }
        val (book, r) = close(listOf(Sponsor("솔채통신", 1, 40, 1, 9, false), null, null), seed = seed, arcs = crisis)
        assertEquals(null, book.life("c01")!!.sponsor, "no sponsor until the next off-season")
        val ev = r.events({ 0 }, { 0 }, { null }).single { it.kind == "sponsor_exit" }
        assertEquals(listOf("c01"), ev.keys)
        assertEquals(listOf("솔채통신", "1"), ev.args)
        // New deals in a crisis: × 0.7.
        val signed = r.signed.single { it.club == "c02" }.sponsor
        assertEquals(Sponsors.perSeason(1.0, book.life("c02")!!.fans, 0, signed.sector, 0.7 * Sponsors.REPLACEMENT), signed.perSeason)
    }

    @Test
    fun aDealAboveTheWorldsBestIsARecord() {
        // c01 holds 3억 a season to season 9; c02 (a champion of 200,000 fans) signs more, c03 less.
        val (book, r) = close(listOf(Sponsor("솔채통신", 1, 30, 1, 9, false), null, null), fans = 20_000)
        val small = r.signed.single { it.club == "c03" }.sponsor
        assertFalse(small.record)
        assertTrue(book.life("c01")!!.sponsor!!.perSeason == 30)
        val (rich, r2) = close(listOf(Sponsor("솔채통신", 1, 30, 1, 9, false), null, null), fans = 400_000)
        val big = r2.signed.first { it.club == "c02" }.sponsor
        assertTrue(big.perSeason > 30)
        assertTrue(big.record)
        assertTrue(rich.life("c02")!!.sponsor!!.record)
        // Once one is signed above, the next must beat it.
        val third = r2.signed.single { it.club == "c03" }.sponsor
        assertEquals(third.perSeason > big.perSeason, third.record)
        val ev = r2.events({ 0 }, { 0 }, { null }).single { it.kind == "sponsor_deal" }
        assertEquals("1", ev.args[3])
    }
}
