package yacht.engine.league

import java.util.Random

/** A player's gender (성별): shown as 남/여; it changes nothing in play. */
enum class Gender(val code: Char, val label: String) {
    MALE('M', "남"),
    FEMALE('F', "여"),
    ;

    companion object {
        fun of(code: Char?): Gender? = entries.firstOrNull { it.code == code }
    }
}

/** The four confederations (대륙): each holds its own continental cup (대륙컵) and hosts the world cup in turn. */
enum class Confed(val label: String, val cup: String, val icon: String) {
    ASIA("아시아", "아시아컵", "🌏"),
    EUROPE("유럽", "유럽컵", "🌍"),
    AMERICAS("아메리카", "아메리카컵", "🌎"),
    AFRICA("아프리카·중동", "아프리카·중동컵", "🌍"),
}

/** How a nation writes a full name in Korean. */
enum class NameOrder {
    /** Family name then given name, no space: 김민준, 왕웨이, 응우옌꽝하이. */
    FAMILY_GIVEN,

    /** Family name, a space, the given name: 사토 하루토. */
    FAMILY_SPACE_GIVEN,

    /** Given name, a space, the family name: 리암 존슨, 루카스 시우바. */
    GIVEN_SPACE_FAMILY,
}

/**
 * A pool of names with weights, written as space-separated tokens "name" or "name:weight"
 * (weight 1 when left out). Draws are by weight; the order is fixed (generated players draw
 * from it, so a pool may only ever be appended to).
 */
class NamePool(spec: String) {
    val names: List<String>
    private val cumulative: IntArray

    init {
        val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        names = tokens.map { it.substringBefore(':') }
        val weights = tokens.map { t -> t.substringAfter(':', "1").toInt().also { require(it > 0) { "weight of $t" } } }
        cumulative = IntArray(weights.size)
        var sum = 0
        for (i in weights.indices) {
            sum += weights[i]
            cumulative[i] = sum
        }
    }

    val size: Int get() = names.size
    val isEmpty: Boolean get() = names.isEmpty()
    val total: Int get() = cumulative.lastOrNull() ?: 0

    fun draw(rng: Random): String {
        val x = rng.nextInt(total)
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] > x) hi = mid else lo = mid + 1
        }
        return names[lo]
    }

    fun weight(name: String): Int {
        val i = names.indexOf(name)
        return if (i < 0) 0 else cumulative[i] - (if (i == 0) 0 else cumulative[i - 1])
    }
}

/**
 * A nation of the league (국적): its Korean name, a short form for running text ("한국 선수"),
 * the flag, its share of generated players ([weight], out of [Nations.TOTAL]), how its names are
 * written and drawn, and its cities (the players' hometowns, by weight).
 *
 * Given names come in two eras where a nation has them ([maleClassic], [femaleClassic]: names of
 * players born in the 1980s-90s; the plain lists are 2000s names): older debutants draw the
 * classic list more often. Vietnamese given names are a middle name and a name ([maleMiddle],
 * [femaleMiddle]) written together ("반또안").
 */
class Nation(
    /** ISO 3166-1 alpha-2, the save code and the national team's id. */
    val code: String,
    val name: String,
    val short: String,
    val flag: String,
    val weight: Int,
    val order: NameOrder,
    families: String,
    male: String,
    female: String,
    cities: String,
    maleClassic: String = "",
    femaleClassic: String = "",
    maleMiddle: String = "",
    femaleMiddle: String = "",
) {
    val families = NamePool(families)
    val male = NamePool(male)
    val female = NamePool(female)
    val maleClassic = NamePool(maleClassic)
    val femaleClassic = NamePool(femaleClassic)
    val maleMiddle = NamePool(maleMiddle)
    val femaleMiddle = NamePool(femaleMiddle)
    val cities = NamePool(cities)

    fun given(g: Gender, classic: Boolean): NamePool = when (g) {
        Gender.MALE -> if (classic && !maleClassic.isEmpty) maleClassic else male
        Gender.FEMALE -> if (classic && !femaleClassic.isEmpty) femaleClassic else female
    }

    fun middle(g: Gender): NamePool = if (g == Gender.MALE) maleMiddle else femaleMiddle

    /** "리암" and "존슨" as this nation writes the full name. */
    fun compose(family: String, given: String): String = when (order) {
        NameOrder.FAMILY_GIVEN -> family + given
        NameOrder.FAMILY_SPACE_GIVEN -> "$family $given"
        NameOrder.GIVEN_SPACE_FAMILY -> "$given $family"
    }

    /**
     * The family name in [full] (a name this nation's pools made), null when it cannot tell:
     * the part after the space, before it (Japanese), or the longest family name of the pool
     * that starts the name ("남궁민수" is 남궁, "응우옌꽝하이" is 응우옌).
     */
    fun familyOf(full: String): String? = when (order) {
        NameOrder.GIVEN_SPACE_FAMILY -> full.substringAfterLast(' ', "").ifEmpty { null }
        NameOrder.FAMILY_SPACE_GIVEN -> full.substringBefore(' ', "").ifEmpty { null }
        NameOrder.FAMILY_GIVEN -> families.names.filter { full.startsWith(it) && full.length > it.length }.maxByOrNull { it.length }
    }

    /** Its confederation (대륙). */
    val confed: Confed get() = Nations.confedOf(code)

    /** "🇯🇵 일본". */
    val flagged: String get() = "$flag $name"

    override fun toString() = flagged
}

/**
 * The 28 nations. Weights follow the real populations, held between [MIN_WEIGHT] and
 * [MAX_WEIGHT] millions ([population]): China, India, the US, Brazil and Nigeria at the top
 * (about 7% each), Korea about 2.4%, the smallest about 1.4% (14 players in a league of 1000,
 * enough for a national team of 5). No nation is favoured beyond its people. The order and the
 * codes are fixed (saves keep the code; generated players draw by the list): only append.
 */
object Nations {
    /** A nation's draw weight: its population in millions, held between these (no nation more than 5 times another). */
    const val MIN_WEIGHT = 30
    const val MAX_WEIGHT = 150

    /**
     * The weight of a nation of [millions] people: as many players as its people, within
     * [MIN_WEIGHT]..[MAX_WEIGHT] so the biggest do not swamp the league nor the smallest
     * vanish from it (1000 players: 14 to 69 of each nation).
     */
    private fun population(millions: Int): Int = millions.coerceIn(MIN_WEIGHT, MAX_WEIGHT)

    val KR = Nation(
        "KR", "대한민국", "한국", "🇰🇷", population(52), NameOrder.FAMILY_GIVEN,
        families = "김:215 이:147 박:84 최:47 정:43 강:24 조:21 윤:21 장:20 임:17 한:15 오:15 서:15 신:14 권:14 황:14 " +
            "안:13 송:13 유:12 전:11 홍:11 고:9 문:9 양:9 손:9 배:8 백:7 허:6 남:6 심:5 노:5 하:5 곽:4 성:4 차:4 주:4 " +
            "우:4 구:4 나:3 민:3 진:3 지:3 엄:3 채:3 원:3 천:2 방:2 공:2 현:2 함:2 변:2 염:2 여:2 추:1 도:1 소:1 석:1 " +
            "선:1 설:1 마:1 길:1 연:1 위:1 표:1 명:1 기:1 반:1 왕:1 금:1 옥:1 육:1 인:1 맹:1 제:1 모:1 탁:1 국:1 " +
            "남궁:1 황보:1 제갈:1 선우:1",
        male = "민준 서준 도윤 예준 시우 하준 주원 지호 지후 준우 준서 도현 건우 현우 우진 선우 서진 민재 연우 정우 " +
            "유준 승우 승현 시윤 준혁 은우 지환 승민 지우 유찬 윤우 민성 준영 시후 진우 지원 수호 재윤 시현 동현 " +
            "태윤 민우 이준 재원 한결 지훈 은찬 태현 준호 지안 로운 하율 이안 서우 윤호 도하 태민 민규 재민 시온 " +
            "하람 은호 지성 준희 예성 승준 현서 태양 다온 도겸 우빈 건희 지한 재현 성빈 규민 태오 율찬 시혁 민찬 " +
            "현수 도진 주안 정훈 영우 해준 세윤 찬영 서후 준수 은성 동윤 강민 우혁 지석",
        female = "서연 서윤 지우 서현 민서 하은 하윤 윤서 지유 지민 채원 수아 지아 지윤 은서 다은 예은 수빈 소율 예린 " +
            "예원 지원 소윤 시은 하린 유나 채은 가은 윤아 서아 아린 연우 하율 다인 서영 예서 수민 지안 나윤 시아 " +
            "유진 아윤 채윤 다연 소은 서은 예나 가윤 하연 은채 지현 나은 유주 서하 다윤 연서 수현 예지 주아 리아 " +
            "이서 은유 서율 채아 하영 시연 유하 소연 민지 도연 라희 아인 이나 지수 윤지 다현 채린 세아 하엘 소민 " +
            "예빈 나연 아영 은지 효주 다솔 수연 가연 한별 새봄 여름 단비 시우 해인 보민 혜인",
        maleClassic = "지훈 현우 성민 동현 민수 준호 민혁 승현 성현 현준 상현 재현 영민 정훈 성진 준영 민석 진우 재훈 동욱 " +
            "상훈 성훈 태현 승민 우현 경민 민호 현석 재우 정민 성호 준혁 영준 상민 동하 종현 형준 창민 태훈 진혁 " +
            "용준 건호 인호 동원 상우 재석 준기 대호 경환 종민 석진 주환 기현 원준 한솔 정우 영훈 세훈 광민 명수 " +
            "재형 민기 태준 성우 진호 영재 동건 형석 지성 수현 병철 영수 정호 성준 동민 기훈 상철 용호 재영 승훈",
        femaleClassic = "지은 수진 지현 민지 유진 혜진 은지 수빈 지혜 지영 은영 현정 미영 혜원 수연 소영 지연 은정 민정 수정 " +
            "선영 윤정 은비 보람 아름 미진 정은 혜림 다혜 슬기 하나 예진 소희 나연 가영 지원 세영 혜인 수현 은혜 " +
            "유리 한나 다솜 지희 경희 은주 미나 보영 주희 혜정 연주 수지 희정 나래 서영 정아 현주 은미 소연 진아 " +
            "유정 지선 수민 다은 예슬 효진 민영 혜수 선희 은경 미경 소라 재희 정화 유경 윤희 나영 주연 혜민 지아",
        cities = "서울:20 부산:7 인천:6 대구:5 대전:3 광주:3 수원:3 울산:2 세종:1 춘천:1 청주:2 전주:2 포항:1 창원:2 제주:1 강릉:1 " +
            "성남:2 고양:2 용인:2 안양:1 천안:1 김해:1 목포:1 여수:1 순천:1 안동:1 경주:1 원주:1 군산:1 진주:1 구미:1 속초:1",
    )

    val JP = Nation(
        "JP", "일본", "일본", "🇯🇵", population(124), NameOrder.FAMILY_SPACE_GIVEN,
        families = "사토:18 스즈키:17 다카하시:14 다나카:13 와타나베:12 이토:11 야마모토:11 나카무라:10 고바야시:10 가토:9 " +
            "요시다:8 야마다:8 사사키:7 야마구치:6 마쓰모토:6 이노우에:6 기무라:6 하야시:5 사이토:5 시미즈:5 야마자키:4 " +
            "모리:4 이케다:4 하시모토:4 아베:4 이시카와:4 야마시타:3 나카지마:3 이시이:3 오가와:3 마에다:3 오카다:3 " +
            "하세가와:3 후지타:3 고토:3 곤도:3 무라카미:3 엔도:3 아오키:3 사카모토:3 후쿠다:2 오타:2 니시무라:2 후지이:2 " +
            "오카모토:2 미우라:2 후지와라:2 마쓰다:2 나카가와:2 나카노:2 하라다:2 오노:2 다무라:2 다케우치:2 가네코:2 " +
            "와다:2 나카야마:2 이시다:2 우에다:2 모리타:2 하라:2 시바타:2 사카이:2 구도:2 요코야마:2 미야자키:2 " +
            "미야모토:2 우치다:2 다카기:2 안도:2 다니구치 마루야마 이마이 다카다 후지모토 다케다 무라타 우에노 스기야마 " +
            "마스다 히라노 오쓰카 지바 구보 마쓰이 이와사키 사쿠라이 노구치 마쓰오 기쿠치 노무라 아라이 미즈노 오니시",
        male = "하루토 소타 유토 하루키 렌 미나토 유마 소스케 이쓰키 다이키 쇼타 고타 류세이 가이토 하야토 다쿠미 겐타 " +
            "히로토 리쿠 소마 하루마 아오이 유이토 아사히 요타 다이치 슌 가이 쓰바사 고키 유키 나오키 가즈키 다이고 " +
            "료 쇼 히나타 아라타 하루 유세이 고다이 다이가 쇼마 레오 유시 겐신 소라 이오리 다케루",
        female = "유이 아오이 히나 메이 유나 리코 사쿠라 미유 하나 아카리 미오 린 사키 나나 에마 고하루 히마리 쓰무기 " +
            "유즈키 히나타 미사키 모모카 이로하 아야네 리오 유아 시오리 미즈키 가논 노아 마오 유키나 사나 미쿠 " +
            "고코나 하루나 와카나 아이리 니코 리사 마히로 스즈 유메 가호 호노카 미아",
        maleClassic = "쇼헤이 이치로 겐지 다카시 히로시 사토시 마코토 료타 다이스케 유스케 겐타로 고헤이 신지 유이치 고지 " +
            "데쓰야 다쿠야 가즈야 마사토 요시키 히로키 아키라 신야 도모야 다쓰야 겐이치 마사키 슈헤이 고스케 준 " +
            "게이스케 요헤이 나오토 쇼고 다케시 유지 가즈마 히데키 노부유키 마사히로",
        femaleClassic = "아야카 마이 나쓰키 하루카 마나미 지히로 아스카 가나 나오미 유코 미호 사야카 에리 아이 가오리 노조미 " +
            "레이나 아즈사 마리 리나 미카 유카 도모미 아키코 게이코 나쓰미 유미 사오리 마키 에미 미키 가스미 " +
            "아유미 지하루 마유 메구미 나오코 레이코 가요코 준코",
        cities = "도쿄:10 오사카:5 요코하마:4 나고야:3 삿포로:2 후쿠오카:2 고베:2 교토:2 가와사키:1 사이타마:1 히로시마:1 " +
            "센다이:1 지바:1 니가타:1 하마마쓰:1 구마모토:1 오카야마:1 시즈오카:1 가고시마:1 나가사키:1 가나자와:1 나하:1",
    )

    val CN = Nation(
        "CN", "중국", "중국", "🇨🇳", population(1410), NameOrder.FAMILY_GIVEN,
        families = "왕:72 리:70 장:75 류:54 천:46 양:31 황:24 자오:23 우:20 저우:19 쉬:14 쑨:14 마:13 주:12 후:11 궈:11 " +
            "허:11 가오:10 린:10 뤄:9 정:9 량:8 셰:7 쑹:7 탕:6 한:6 펑:6 덩:5 차오:5 쩡:5 샤오:5 톈:4 둥:4 판:4 위안:4 " +
            "차이:4 위:4 두:4 예:4 청:3 웨이:3 쑤:3 뤼:3 딩:3 런:3 루:3 야오:3 선:3 중:3 추이:3 탄:3 랴오:3 진:2 스:2 " +
            "자:2 샤:2 푸:2 팡:2 쩌우:2 슝:2 바이:2 멍:2 친:2 허우:1 인:1 쉐:1 옌:1 돤:1 레이:1 룽:1 구:1 마오:1 " +
            "하오:1 궁:1 사오:1 완:1 첸:1",
        male = "웨이 하오 쥔 레이 강 융 제 타오 밍 차오 량 빈 후이 신 펑 위 카이 진 루이 보 원 둥 화 젠 핑 " +
            "쯔하오 하오란 위쉬안 위항 하오위 쯔쉬안 무천 이천 젠궈 즈창 즈웨이 젠화 원보 톈위 이밍 쯔제 진룽 샤오밍 " +
            "펑페이 원하오 쥔제 자하오 위천 하오천 쯔양 보원 이판 다웨이 궈창 젠민 즈하오 리쥔 융창 하이타오 춘레이 " +
            "원제 톈하오 위쩌 밍하오 자러 하오쉬안 이난",
        female = "팡 나 민 징 옌 링 팅 쉐 칭 멍 닝 핑 쥐안 란 메이 잉 샤 훙 친 루 " +
            "쯔한 위퉁 신이 이눠 위시 쯔이 이디 잉사 스원 친원 아이링 샤오옌 메이링 리리 징이 자이 위한 슈잉 슈란 " +
            "위란 샤오훙 리훙 춘메이 신위 원징 하이옌 샤오리 자신 단단 위루 쓰치 멍야오 자치 위페이 시위 안치 " +
            "징원 루이산 칭칭",
        cities = "베이징:6 상하이:6 광저우:4 선전:4 청두:3 충칭:3 톈진:2 우한:2 항저우:2 난징:2 시안:2 쑤저우:1 칭다오:1 " +
            "다롄:1 선양:1 창사:1 정저우:1 하얼빈:1 쿤밍:1 샤먼:1 지난:1 푸저우:1",
    )

    // English given names: the US and Canada share one set, Britain and Australia another.
    private const val EN_US_MALE = "리암 노아 올리버 일라이자 제임스 윌리엄 벤저민 루카스 헨리 시어도어 잭 리바이 알렉산더 잭슨 메이슨 " +
        "마이클 이선 대니얼 매튜 에이든 조지프 데이비드 존 로버트 크리스토퍼 조슈아 앤드루 라이언 타일러 브랜던 " +
        "저스틴 케빈 브라이언 에릭 조너선 네이선 카일 오스틴 제이컵 딜런 개빈 케일럽 마커스 트레버 그레이슨 " +
        "카터 로건 오언 와이엇 헌터 에번 콜린 숀 데릭 제이든"
    private const val EN_US_FEMALE = "올리비아 엠마 샬럿 아멜리아 소피아 미아 이사벨라 에블린 하퍼 애비게일 에밀리 엘리자베스 매디슨 " +
        "엘라 그레이스 클로이 빅토리아 라일리 오브리 해나 제시카 애슐리 사라 사만다 제니퍼 니콜 스테파니 로런 " +
        "레이철 메건 케이틀린 브리트니 앰버 대니엘 알렉시스 테일러 시드니 모건 헤일리 줄리아 노라 스칼렛 " +
        "페넬로피 레일라 조이 앨리슨 브룩 케일리 매켄지 아만다 캐서린 에이버리"
    private const val EN_UK_MALE = "올리버 조지 해리 노아 잭 레오 아서 무함마드 오스카 찰리 제이컵 토머스 윌리엄 제임스 조슈아 " +
        "알피 프레디 아치 헨리 시어도어 에드워드 대니얼 매튜 루이스 라이언 리스 조 해리슨 벤 샘 톰 크레이그 " +
        "개러스 스튜어트 이언 닐 칼럼 코너 키런 앨빈 핀리 로리 제이미 애덤 리암 조던 마일스 엘리엇 휴고 " +
        "패트릭 데클런 로스 에번 핀레이 해미시"
    private const val EN_UK_FEMALE = "올리비아 아멜리아 아일라 에이바 아이비 프레야 릴리 플로렌스 미아 이사벨라 로지 엘시 그레이스 소피 " +
        "에밀리 루비 포피 엘리 클로이 루시 케이티 해나 로런 제이드 레베카 샬럿 엠마 조지아 몰리 홀리 베서니 " +
        "피오나 에이미 앨리스 메이지 매틸다 이모젠 페이지 섀넌 커스틴 제마 엘리너"

    val US = Nation(
        "US", "미국", "미국", "🇺🇸", population(335), NameOrder.GIVEN_SPACE_FAMILY,
        families = "스미스:10 존슨:8 윌리엄스:7 브라운:6 존스:6 가르시아:5 밀러:5 데이비스:5 로드리게스:4 마르티네스:4 " +
            "에르난데스:3 로페스:3 윌슨:3 앤더슨:3 토머스:3 테일러:3 무어:3 잭슨:3 마틴:3 리:3 페레스:2 톰프슨:2 " +
            "화이트:2 해리스:2 클라크:2 루이스:2 로빈슨:2 워커:2 영:2 앨런:2 킹:2 라이트:2 스콧:2 힐:2 그린:2 애덤스:2 " +
            "넬슨:2 베이커:2 홀:2 캠벨:2 미첼:2 카터:2 로버츠:2 토레스 응우옌 플로레스 리베라 김 박 첸 파텔 필립스 " +
            "에번스 터너 파커 콜린스 에드워즈 스튜어트 모리스 머피 쿡 로저스 모건 피터슨 쿠퍼 리드 베일리 벨 켈리 " +
            "하워드 워드 콕스 리처드슨 우드 브룩스 베넷 그레이 제임스 헤이스",
        male = EN_US_MALE, female = EN_US_FEMALE,
        cities = "뉴욕:6 로스앤젤레스:5 시카고:3 휴스턴:3 피닉스:2 필라델피아:2 샌안토니오:1 샌디에이고:1 댈러스:2 시애틀:2 " +
            "보스턴:2 마이애미:2 애틀랜타:2 덴버:1 라스베이거스:1 디트로이트:1 포틀랜드:1 내슈빌:1 샌프란시스코:2 " +
            "오스틴:1 미니애폴리스:1 호놀룰루:1",
    )

    val GB = Nation(
        "GB", "영국", "영국", "🇬🇧", population(68), NameOrder.GIVEN_SPACE_FAMILY,
        families = "스미스:8 존스:6 윌리엄스:5 테일러:5 브라운:4 데이비스:4 에번스:3 윌슨:3 토머스:3 존슨:3 로버츠:3 " +
            "로빈슨:2 톰프슨:2 라이트:2 워커:2 화이트:2 에드워즈:2 휴스:2 그린:2 홀:2 루이스:2 해리스:2 클라크:2 " +
            "잭슨:2 우드:2 터너:2 마틴:2 파텔 쿠퍼 힐 워드 모리스 무어 리 킹 베이커 해리슨 모건 앨런 제임스 스콧 " +
            "필립스 왓슨 파커 프라이스 베넷 영 그리피스 미첼 켈리 쿡 카터 리처드슨 베일리 콜린스 벨 쇼 머피 " +
            "밀러 콕스 리처즈 칸 맥도널드 캠벨 스튜어트 오코너 매케이",
        male = EN_UK_MALE, female = EN_UK_FEMALE,
        cities = "런던:10 맨체스터:3 리버풀:2 버밍엄:2 리즈:2 글래스고:2 에든버러:2 카디프:1 브리스틀:1 셰필드:1 뉴캐슬:1 " +
            "노팅엄:1 레스터:1 사우샘프턴:1 벨파스트:1 옥스퍼드:1 케임브리지:1 브라이턴:1",
    )

    val FR = Nation(
        "FR", "프랑스", "프랑스", "🇫🇷", population(68), NameOrder.GIVEN_SPACE_FAMILY,
        families = "마르탱:6 베르나르:4 토마:3 프티:3 로베르:3 리샤르:3 뒤랑:3 뒤부아:3 모로:3 로랑:3 시몽:2 미셸:2 " +
            "르페브르:2 르루아:2 루:2 다비드:2 베르트랑:2 모렐:2 푸르니에:2 지라르:2 보네:2 뒤퐁:2 랑베르:2 퐁텐:2 " +
            "루소:2 뱅상:2 페랭 모랭 마티외 클레망 고티에 뒤몽 슈발리에 블랑 게랭 부아예 가르니에 프랑수아 메르시에 " +
            "앙드레 바르비에 마르샹 뒤퓌 르메르 디알로 트라오레 카마라 시세 코나테 벤살렘",
        male = "레오 가브리엘 라파엘 아르튀르 루이 쥘 아당 마엘 뤼카 위고 노아 사샤 앙투안 토마 니콜라 쥘리앵 피에르 " +
            "알렉상드르 막심 바티스트 테오 클레망 로맹 마티외 기욤 세바스티앵 올리비에 에티엔 장 폴 앙리 필리프 " +
            "킬리앙 에두아르 오렐리앵 다미앵 뱅자맹 이브 파스칼 티보 우스만 무사 야니스 나탕 에탕 티모테",
        female = "자드 루이즈 엠마 앙브르 알리스 로즈 안나 리나 미아 레아 클로에 마농 카미유 쥘리 마리 소피 이자벨 " +
            "나탈리 오드레 엘로디 셀린 마틸드 쥘리에트 클레르 엘리즈 마고 아델 이네스 조에 폴린 상드린 비르지니 " +
            "카롤린 마리옹 아멜리 클라라 에바 쥐스틴 로르 엘레오노르 가랑스 롤라 아이샤 파투",
        cities = "파리:8 마르세유:3 리옹:3 툴루즈:2 니스:2 낭트:2 스트라스부르:1 몽펠리에:1 보르도:2 릴:2 렌:1 랭스:1 " +
            "그르노블:1 디종:1 앙제:1 툴롱:1 생테티엔:1 르아브르:1",
    )

    val DE = Nation(
        "DE", "독일", "독일", "🇩🇪", population(84), NameOrder.GIVEN_SPACE_FAMILY,
        families = "뮐러:8 슈미트:7 슈나이더:5 피셔:4 베버:4 마이어:4 바그너:3 베커:3 슐츠:3 호프만:3 셰퍼:3 코흐:2 " +
            "바우어:2 리히터:2 클라인:2 볼프:2 슈뢰더:2 노이만:2 슈바르츠:2 치머만:2 브라운:2 크뤼거:2 하르트만:2 랑게:2 " +
            "베르너:2 크라우제:2 레만 쾰러 헤르만 쾨니히 발터 후버 카이저 푹스 페터스 랑 숄츠 묄러 바이스 융 한 " +
            "슈베르트 포겔 프리드리히 켈러 귄터 프랑크 베르거 빙클러 로트 베크 로렌츠 바우만 프랑케 알브레히트 슈만 지몬 " +
            "외즈칸 일마즈",
        male = "루카스 레온 핀 요나스 노아 엘리아스 파울 벤 루이스 펠릭스 막시밀리안 에밀 야코프 다비트 니클라스 팀 얀 " +
            "토마스 미하엘 슈테판 안드레아스 크리스티안 마르쿠스 볼프강 위르겐 클라우스 디터 마티아스 세바스티안 " +
            "플로리안 토비아스 율리안 마누엘 카이 마츠 토니 한스 카를 프란츠 올리버 요하네스 모리츠 레니 마르셀 " +
            "옌스 라르스 하노 프리츠",
        female = "미아 엠마 한나 조피아 레나 레아 마리 클라라 안나 라우라 율리아 리나 엘라 밀라 에밀리아 하이디 자비네 " +
            "클라우디아 안드레아 페트라 니콜 슈테파니 카트린 안케 그레타 프리다 마틸다 샤를로테 요하나 레오니 " +
            "알리나 마야 이다 파울라 헬레네 리자 프란치스카 잉에 비르기트 우르줄라 테레자",
        cities = "베를린:6 함부르크:3 뮌헨:3 쾰른:2 프랑크푸르트:2 슈투트가르트:2 뒤셀도르프:2 도르트문트:2 에센:1 라이프치히:1 " +
            "브레멘:1 드레스덴:1 하노버:1 뉘른베르크:1 뒤스부르크:1 보훔:1 본:1",
    )

    // Spanish given names: Spain has its own; Mexico and Argentina share a Latin American set.
    private const val LATAM_MALE = "산티아고 마테오 세바스티안 레오나르도 마티아스 에밀리아노 디에고 호세 루이스 후안 카를로스 헤수스 " +
        "알레한드로 페르난도 리카르도 하비에르 라파엘 에두아르도 로베르토 헤라르도 오마르 이반 호르헤 마누엘 다니엘 " +
        "안드레스 라울 엑토르 기예르모 엔리케 파블로 알폰소 가브리엘 아르투로 페드로 우고 마르코 바우티스타 펠리페 " +
        "토마스 니콜라스 호아킨 벤하민 라우타로 티아고 레안드로 마르틴 곤살로 페데리코 파쿤도 아구스틴 이그나시오 " +
        "에세키엘 앙헬 세르히오 훌리안 엔소 알레호 프란코 로드리고 크리스티안 에르난 다미안"
    private const val LATAM_FEMALE = "소피아 레히나 발레리아 히메나 카밀라 발렌티나 이사벨라 루시아 레나타 과달루페 마리아 아나 다니엘라 " +
        "페르난다 안드레아 가브리엘라 알레한드라 파트리시아 베로니카 아드리아나 모니카 클라우디아 마리아나 파울리나 " +
        "로사 엘레나 카롤리나 나탈리아 산드라 프리다 에스메랄다 마르티나 카탈리나 올리비아 훌리에타 밀라그로스 " +
        "아구스티나 파울라 플로렌시아 로시오 칸델라 미카엘라 안토넬라 로레나 실비나 루시아나 셀레스테 비비아나"

    val ES = Nation(
        "ES", "스페인", "스페인", "🇪🇸", population(48), NameOrder.GIVEN_SPACE_FAMILY,
        families = "가르시아:10 로드리게스:7 곤살레스:7 페르난데스:7 로페스:7 마르티네스:7 산체스:6 페레스:6 고메스:4 마르틴:4 " +
            "히메네스:3 루이스:3 에르난데스:3 디아스:3 모레노:3 무뇨스:3 알바레스:3 로메로:2 알론소:2 구티에레스:2 나바로:2 " +
            "토레스:2 도밍게스:2 바스케스:2 라모스:2 힐:2 라미레스:2 세라노:2 블랑코:2 몰리나:2 모랄레스:2 수아레스:2 " +
            "오르테가:2 델가도:2 카스트로:2 오르티스:2 루비오:2 마린 산스 누녜스 이글레시아스 메디나 가리도 코르테스 " +
            "카스티요 산토스 로사노 게레로 카노 프리에토 멘데스 크루스 칼보 갈레고 비달 레온 마르케스 에레라 페냐 " +
            "플로레스 카브레라 캄포스 베가",
        male = "우고 마르틴 루카스 마테오 레오 다니엘 알레한드로 파블로 마누엘 알바로 아드리안 다비드 마리오 엔소 디에고 " +
            "마르코스 하비에르 세르히오 카를로스 호세 안토니오 후안 미겔 라파엘 페르난도 호르헤 루이스 이케르 사비 " +
            "안드레스 다니 알베르토 라울 이반 루벤 오스카르 곤살로 이그나시오 페드로 페란 마르크 호안 비센테 " +
            "에리크 우나이 아이토르 제라르 이스마엘 니코",
        female = "루시아 소피아 마르티나 마리아 훌리아 파울라 발레리아 엠마 다니엘라 카를라 알바 노아 사라 카르멘 아나 " +
            "라우라 엘레나 크리스티나 마르타 이레네 알리시아 클라우디아 안드레아 누리아 로시오 필라르 몬세라트 이네스 " +
            "블랑카 아이타나 알렉시아 베아트리스 파트리시아 에바 실비아 로사 메르세데스 테레사 올가 아이노아 네레아 " +
            "레이레 마이테 롤라",
        cities = "마드리드:7 바르셀로나:6 발렌시아:3 세비야:3 사라고사:1 말라가:1 무르시아:1 팔마:1 라스팔마스:1 빌바오:2 " +
            "알리칸테:1 코르도바:1 바야돌리드:1 비고:1 히혼:1 그라나다:1 산세바스티안:1",
    )

    val BR = Nation(
        "BR", "브라질", "브라질", "🇧🇷", population(216), NameOrder.GIVEN_SPACE_FAMILY,
        families = "시우바:12 산투스:8 올리베이라:6 소자:5 호드리게스:4 페헤이라:4 아우베스:4 페레이라:4 리마:3 고메스:3 " +
            "히베이루:3 카르발류:3 아우메이다:3 로페스:2 소아레스:2 비에이라:2 바르보자:2 호샤:2 지아스:2 나시멘투:2 " +
            "아라우주:2 코스타:2 멘데스:2 카스트루 마르칭스 핀투 모레이라 프레이타스 몬테이루 카르도주 테이셰이라 " +
            "코헤아 멜루 브리투 모라이스 피게이레두 마샤두 파리아스 하무스 쿠냐",
        male = "루카스 가브리에우 스 페드루 주앙 하파에우 브루누 펠리피 길례르미 구스타부 다니에우 치아구 히카르두 " +
            "호드리구 비니시우스 레안드루 마르쿠스 파울루 카이우 이고르 다비 아르투르 미게우 베르나르두 에두아르두 " +
            "안드레 조제 안토니우 프란시스쿠 카를루스 루이스 지에구 빅토르 엔주 에리크 웨슬리 헤낭 알리송 다닐루 " +
            "조나탕 에베르통 사무에우 이자키 마테우",
        female = "마리아 아나 줄리아 베아트리스 라리사 가브리엘라 마리아나 페르난다 아만다 브루나 카밀라 레치시아 이자벨라 " +
            "라우라 소피아 알리시 마누엘라 루이자 발렌치나 엘로이자 조반나 하파엘라 비토리아 헤베카 클라라 세실리아 " +
            "이자도라 마르타 타이스 파트리시아 아드리아나 루시아나 카롤리나 데보라 탈리타 레아 니나 " +
            "제시카 알라나 마이라 에스테르",
        cities = "상파울루:8 리우데자네이루:6 브라질리아:2 사우바도르:2 포르탈레자:2 벨루오리존치:2 마나우스:1 쿠리치바:2 " +
            "헤시피:2 포르투알레그리:2 벨렝:1 고이아니아:1 산투스:1 캄피나스:1 플로리아노폴리스:1",
    )

    val IT = Nation(
        "IT", "이탈리아", "이탈리아", "🇮🇹", population(59), NameOrder.GIVEN_SPACE_FAMILY,
        families = "로시:8 루소:6 페라리:6 에스포지토:5 비안키:5 로마노:5 콜롬보:4 리치:4 마리노:4 그레코:4 브루노:4 갈로:4 " +
            "콘티:3 데루카:3 만치니:3 코스타:3 조르다노:3 리초:3 롬바르디:3 모레티:3 바르비에리:2 폰타나:2 산토로:2 " +
            "마리아니:2 리날디:2 카루소:2 페라라:2 갈리:2 마르티니:2 레오네:2 롱고:2 젠틸레:2 마르티넬리:2 비탈레:2 " +
            "세라:2 코폴라:2 데안젤리스:2 파리시 빌라 펠레그리니 팔미에리 베르나르디 피오레 사르토리 페리 파가니",
        male = "레오나르도 프란체스코 알레산드로 로렌초 마티아 안드레아 가브리엘레 리카르도 토마소 에도아르도 마테오 " +
            "주세페 안토니오 조반니 마르코 루카 파올로 잔루카 로베르토 스테파노 다니엘레 페데리코 니콜로 시모네 " +
            "알베르토 카를로 파비오 조르조 다비데 에마누엘레 피에트로 클라우디오 마우로 산드로 브루노 엔리코 " +
            "자코모 필리포 미켈레 살바토레 빈첸초 체사레",
        female = "소피아 줄리아 아우로라 알리체 지네브라 엠마 조르자 그레타 베아트리체 안나 키아라 마르티나 사라 " +
            "프란체스카 엘레나 발렌티나 알레산드라 페데리카 실비아 카밀라 엘리사 일라리아 로베르타 파올라 시모나 " +
            "바르바라 크리스티나 다니엘라 비토리아 레베카 노에미 루도비카 비앙카 아다 체칠리아 마리아 조반나 " +
            "로셀라 안젤라 테레사",
        cities = "로마:6 밀라노:6 나폴리:3 토리노:3 팔레르모:2 제노바:2 볼로냐:2 피렌체:2 바리:1 카타니아:1 베네치아:1 " +
            "베로나:1 파르마:1 칼리아리:1",
    )

    val VN = Nation(
        "VN", "베트남", "베트남", "🇻🇳", population(100), NameOrder.FAMILY_GIVEN,
        families = "응우옌:38 쩐:11 레:9 팜:7 호앙:4 후인:3 판:4 부:2 보:2 당:2 부이:2 도 호 응오 즈엉 리 딘 쯔엉 다오 " +
            "럼 마이 찐 르엉 하 까오 따",
        maleMiddle = "반:5 득:2 민:2 꽝:2 호앙:2 타인:2 쑤언 띠엔 꽁 흐우 주이 꾸옥 자 아인 응옥",
        male = "하이 또안 럼 중 허우 마인 득 후이 비엣 꾸옛 쯔엉 탕 훙 롱 남 선 뚱 카인 바오 푹 히에우 따이 띤 끼엔 빈 흥 " +
            "끄엉 떰 타인 코아 팟 닷 꿘 록 안 뚜언 퐁 꽝 하오 민 히엡 녓",
        femaleMiddle = "티:6 투 타인 응옥 프엉 바오 낌 투이 홍 미 카인 자 아인 민",
        female = "란 흐엉 마이 투 항 응옥 린 안 짱 하 프엉 뚜옛 투이 호아 응아 하인 후옌 옌 비 늉 쩜 꾸인 트 응언 쩌우 " +
            "타오 니 지엡 로안 띠엔 번 냐",
        cities = "호찌민:6 하노이:5 하이퐁:2 다낭:2 껀터:1 나트랑:1 후에:1 비엔호아:1 붕따우:1 꾸이년:1 하롱:1",
    )

    val AR = Nation(
        "AR", "아르헨티나", "아르헨티나", "🇦🇷", population(46), NameOrder.GIVEN_SPACE_FAMILY,
        families = "곤살레스:8 로드리게스:7 고메스:6 페르난데스:6 로페스:6 디아스:5 마르티네스:5 페레스:5 가르시아:4 산체스:4 " +
            "로메로:4 수아레스:3 알바레스:3 토레스:3 루이스:3 라미레스:3 플로레스:3 아코스타:3 베니테스:3 메디나:2 " +
            "에레라:2 아기레:2 페레이라:2 구티에레스:2 히메네스:2 몰리나:2 실바:2 카스트로:2 로하스:2 오헤다:2 모레노:2 " +
            "소사:2 로시:2 파레데스 페라리 비안키 롬바르디 코레아 카루소 피오레 갈반 레데스마 미란다 바렐라",
        male = LATAM_MALE, female = LATAM_FEMALE,
        cities = "부에노스아이레스:8 코르도바:3 로사리오:3 멘도사:2 라플라타:1 마르델플라타:1 투쿠만:1 살타:1 산타페:1",
    )

    val MX = Nation(
        "MX", "멕시코", "멕시코", "🇲🇽", population(129), NameOrder.GIVEN_SPACE_FAMILY,
        families = "에르난데스:10 가르시아:9 마르티네스:8 로페스:7 곤살레스:7 페레스:5 로드리게스:5 산체스:5 라미레스:5 " +
            "크루스:4 플로레스:4 고메스:4 모랄레스:3 바스케스:3 레예스:3 히메네스:3 토레스:3 디아스:3 구티에레스:3 " +
            "루이스:3 멘도사:3 아길라르:3 오르티스:3 모레노:2 카스티요:2 로메로:2 알바레스:2 멘데스:2 차베스:2 리베라:2 " +
            "후아레스:2 라모스:2 도밍게스:2 에레라:2 메디나:2 카스트로:2 바르가스:2 게레로:2 코르테스:2 살리나스 오로스코 " +
            "게바라 오초아 로사노 로하스 베가 시스네로스 몬테스",
        male = LATAM_MALE, female = LATAM_FEMALE,
        cities = "멕시코시티:8 과달라하라:3 몬테레이:3 푸에블라:2 티후아나:1 레온:1 칸쿤:1 메리다:1 톨루카:1 케레타로:1",
    )

    val CA = Nation(
        "CA", "캐나다", "캐나다", "🇨🇦", population(40), NameOrder.GIVEN_SPACE_FAMILY,
        families = "스미스:4 브라운:3 트랑블레:3 마틴:3 로이:2 리:2 윌슨:2 존슨:2 맥도널드:2 캠벨:2 앤더슨:2 테일러:2 " +
            "톰프슨:2 가뇽:2 화이트 왕 싱 르블랑 부샤르 펠티에 코테 라부아 포르탱 모리슨 스튜어트 로버트슨 영 클라크 " +
            "스콧 머리 매케이 웡 챈 고티에 벨랑제",
        male = EN_US_MALE, female = EN_US_FEMALE,
        cities = "토론토:6 몬트리올:4 밴쿠버:4 캘거리:2 에드먼턴:2 오타와:2 위니펙:1 퀘벡:1 핼리팩스:1 빅토리아:1",
    )

    val AU = Nation(
        "AU", "호주", "호주", "🇦🇺", population(27), NameOrder.GIVEN_SPACE_FAMILY,
        families = "스미스:5 존스:3 윌리엄스:3 브라운:3 윌슨:3 테일러:3 존슨:2 화이트:2 마틴:2 앤더슨:2 톰프슨:2 토머스:2 " +
            "워커:2 해리스:2 라이언:2 켈리:2 응우옌 리 로빈슨 킹 데이비스 라이트 에번스 로버츠 그린 홀 우드 잭슨 " +
            "클라크 미첼 쿠퍼 맥도널드 머피 오브라이언 해밀턴 그레이엄 첸",
        male = EN_UK_MALE, female = EN_UK_FEMALE,
        cities = "시드니:6 멜버른:6 브리즈번:3 퍼스:2 애들레이드:2 골드코스트:1 캔버라:1 호바트:1 다윈:1",
    )

    val IN = Nation(
        "IN", "인도", "인도", "🇮🇳", population(1440), NameOrder.GIVEN_SPACE_FAMILY,
        families = "싱:5 쿠마르:5 샤르마:4 파텔:4 굽타:3 칸:3 레디:2 다스:2 라오:2 메타:2 조시:2 샤:2 베르마:2 미슈라:2 " +
            "야다브:2 아이어 나이르 초프라 카푸르 말호트라 아가르왈 보스 바네르지 차터지 무커지 필라이 메논 데사이 " +
            "자인 바트 판데이 티와리 삭세나 타쿠르 차우한 나이두 쿨카르니 신하 두타 라나 세티",
        male = "라훌 아르준 로한 비크람 아미트 라지 아디티아 라비 산자이 아닐 카란 니킬 아르나브 비베크 수레시 디팍 " +
            "아자이 마니시 라제시 사친 비라트 리샤브 쿠날 바룬 싯다르트 하르시 야시 이샨 아리안 카비르 데브 프라나브 " +
            "가우라브 아비셰크 모히트 아카시 타룬 니라즈 안키트 수닐",
        female = "프리야 아나냐 아디티 푸자 네하 카비아 디비야 스네하 리야 슈레야 이샤 안잘리 미라 니샤 사냐 아이슈와리아 " +
            "디피카 락슈미 수니타 키란 탄비 아르티 스와티 난디니 라디카 심란 크리티 팔라비 샬리니 가우리 조티 레카 " +
            "비디아 삭시 아브니 디야 산비 이라 타라",
        cities = "뭄바이:5 델리:5 벵갈루루:3 하이데라바드:2 첸나이:2 콜카타:2 아메다바드:1 푸네:1 자이푸르:1 러크나우:1 " +
            "찬디가르:1 수라트:1",
    )

    val TR = Nation(
        "TR", "튀르키예", "튀르키예", "🇹🇷", population(85), NameOrder.GIVEN_SPACE_FAMILY,
        families = "일마즈:6 카야:4 데미르:4 샤힌:3 첼리크:3 일디즈:3 일드름:3 외즈튀르크:2 아이든:2 외즈데미르:2 아르슬란:2 " +
            "도안:2 클르치:2 체틴:2 카라:2 야부즈:2 쿠르트:2 외즈칸 심셰크 폴라트 에르도안 귀네시 악타시 바이락타르 " +
            "퀴취크 타시 알튼 카플란 귈 아크소이 외젠 코르크마즈 차크르 아이한 투나",
        male = "메흐메트 무스타파 아흐메트 알리 휘세인 하산 이브라힘 이스마일 오스만 유수프 무라트 에민 엠레 부라크 " +
            "오우즈 케렘 아르다 하칸 제키 오잔 젠기즈 메르트 바르시 에네스 외메르 세미흐 볼칸 세르칸 타네르 우무트 " +
            "일케르 바투한 에페 알프 카안 야으즈 케난",
        female = "아이셰 파트마 에미네 하티제 제이네프 엘리프 메르베 에스라 데니즈 에브루 셀린 이렘 에다 아슬르 투바 " +
            "세다 데프네 아즈라 에일륄 니사 멜리스 잔수 괴크체 에제 셰이마 휠야 슐레 벨리즈 일라이다 외즐렘",
        cities = "이스탄불:8 앙카라:3 이즈미르:3 부르사:1 안탈리아:1 아다나:1 코냐:1 가지안테프:1 트라브존:1 에스키셰히르:1",
    )

    val NL = Nation(
        "NL", "네덜란드", "네덜란드", "🇳🇱", population(18), NameOrder.GIVEN_SPACE_FAMILY,
        families = "더용:5 얀선:4 더프리스:4 판덴베르흐:3 판데이크:3 바커르:3 피서르:3 스미트:3 메이어르:2 더부르:2 뮐더르:2 " +
            "더흐로트:2 보스:2 페르묄런:2 페이터르스:2 헨드릭스:2 데커르:2 브라우어르:2 더빗:2 데이크스트라:2 스미츠 더흐라프 " +
            "코크 야코프스 더한 페르메이르 하위스만 판레이우언 포스트마",
        male = "단 셈 뤼카스 핀 레비 밀란 바우터르 산더르 톰 뤼크 브람 테이스 스테인 요리스 쿤 피터르 얀 헤이스 마테이스 " +
            "니크 데니스 마르크 로빈 바스 뤼트 빔 케이스 디르크 미힐 예스퍼르",
        female = "엠마 율리아 밀라 소피 테스 사라 안나 니나 에바 로터 산너 리사 아누크 이리스 린 엘리너 리커 다니엘러 " +
            "비비아너 펨케 노르 플로르 리스베트 마르티나 요커",
        cities = "암스테르담:5 로테르담:4 헤이그:2 위트레흐트:2 에인트호번:2 흐로닝언:1 틸뷔르흐:1 알크마르:1 네이메헌:1 아른험:1",
    )

    val SE = Nation(
        "SE", "스웨덴", "스웨덴", "🇸🇪", population(11), NameOrder.GIVEN_SPACE_FAMILY,
        families = "요한손:5 안데르손:5 칼손:4 닐손:4 에릭손:4 라르손:3 올손:3 페르손:3 스벤손:3 구스타브손:2 페테르손:2 " +
            "욘손:2 얀손:2 한손:2 벵트손 옌손 린드베리 린드스트룀 린드크비스트 린드그렌 악셀손 베리 베리스트룀 " +
            "룬드크비스트 룬드베리 룬드그렌 망누손 마트손 홀름 산드베리 포르스베리 셰베리 엥스트룀 에클룬드 달",
        male = "에리크 라르스 칼 안데르스 요한 페르 닐스 미카엘 얀 올로프 구스타브 오스카르 빌헬름 루카스 알렉산데르 " +
            "엘리아스 후고 악셀 레오 노아 아르비드 에밀 빅토르 필리프 세바스티안 헨리크 프레드리크 망누스 비에른 " +
            "스벤 마츠 이사크 루드비그",
        female = "안나 마리아 엘사 아스트리드 잉리드 카린 크리스티나 엠마 마야 엘린 이다 린네아 엘라 프레야 에바 소피아 " +
            "클라라 빌마 사가 스티나 리사 시리 테레세 프리다 헬레나 요한나 사라 알리세 엘리노르",
        cities = "스톡홀름:6 예테보리:3 말뫼:2 웁살라:1 베스테로스:1 외레브로:1 린셰핑:1 헬싱보리:1 노르셰핑:1",
    )

    val TH = Nation(
        "TH", "태국", "태국", "🇹🇭", population(72), NameOrder.GIVEN_SPACE_FAMILY,
        families = "새땅 새림 시숙 텅디 깨우마니 숙짜이 웡사왓 랏따나꾼 분미 짜이디 짜른쑥 시웡 프롬마 콩깨우 분마탄 " +
            "사라찻 송끄라신 당다 펫람 부리랏 수완나랏 시리완 분낙 깨우사이 퐁사완 숙사왓 참쁘라섯",
        male = "솜차이 솜삭 솜밧 아피찻 나타폰 위차이 티라왓 차나팁 티라실 수파차이 수파촉 아누차 타나왓 폰차이 낏띠삭 " +
            "나롱 쁘라섯 사란 짜뚜폰 티라톤 수파난 꾼라웃 아난 차이왓 탄와 니띠퐁 끄릿 위라쳇",
        female = "랏차녹 부사난 아리야 모리야 아타야 폰아농 수다랏 니라몬 낫차 깐야 수니사 피야폰 와라폰 차니다 쁘림 " +
            "와니다 파니다 아차라 까녹완 시리낫 낫타야 폰티파 탄야 수파차라",
        cities = "방콕:8 치앙마이:2 푸껫:1 파타야:1 콘깬:1 나콘랏차시마:1 우돈타니:1 핫야이:1 부리람:1 촌부리:1",
    )

    val NG = Nation(
        "NG", "나이지리아", "나이지리아", "🇳🇬", population(224), NameOrder.GIVEN_SPACE_FAMILY,
        families = "오카포르 오케케 아데예미 오콩코 에제 은워수 아부바카르 벨로 이브라힘 무함마드 아데바요 오군레예 발로군 " +
            "아폴라비 은완코 오비 오코예 추쿠 온예카 오그본나 아킨왈레 올라왈레 라왈 무사 유수프 알리유 단주마 우체 " +
            "에메니케 올라툰지 아델레케 아자이",
        male = "치네두 에메카 툰데 올루와세운 아요델레 이케추쿠 페미 세군 켈레치 오빈나 우체나 빅터 새뮤얼 대니얼 아메드 " +
            "이브라힘 무사 압둘 사니 토비 쿤레 왈레 바요 은남디 치디 이페아니 카요데 다요",
        female = "응고지 치오마 아다에제 푼밀라요 폴라케 아마카 이페오마 은네카 예툰데 부콜라 테미토페 아이샤 파티마 자이납 " +
            "블레싱 그레이스 에스더 치아마카 올루치 케미 톨루 시미솔라 론케 할리마 하우와 에베레 오노메 프레셔스",
        cities = "라고스:6 아부자:3 카노:2 이바단:2 포트하커트:2 베닌시티:1 에누구:1 카두나:1",
    )

    val PL = Nation(
        "PL", "폴란드", "폴란드", "🇵🇱", population(37), NameOrder.GIVEN_SPACE_FAMILY,
        families = "노바크:4 코발스키:3 비시니에프스키:2 부이치크:2 코발치크:2 카민스키:2 지엘린스키:2 시만스키:2 레반도프스키 " +
            "보즈니아크 동브로프스키 코즈워프스키 야브원스키 마예프스키 올셰프스키 야보르스키 바실레프스키 그라보프스키 " +
            "파블라크 미할스키 크룰 비에초레크 노바코프스키 피오트로프스키 마주르 크라프치크 카치마레크",
        male = "야쿠프 카츠페르 미하우 피오트르 토마시 크시슈토프 파베우 마테우시 아담 얀 마르친 우카시 그제고시 보이치에흐 " +
            "안제이 시몬 필리프 안토니 프란치셰크 다비트 카롤 바르토시 다니엘 라파우 로베르트 마치에이 카밀",
        female = "안나 마리아 카타지나 마우고자타 아그니에슈카 바르바라 에바 마그달레나 요안나 알렉산드라 조피아 율리아 " +
            "한나 마야 레나 주잔나 나탈리아 올리비아 빅토리아 아멜리아 이가 마르타 모니카 유스티나 도로타",
        cities = "바르샤바:5 크라쿠프:3 우치:2 브로츠와프:2 포즈난:2 그단스크:2 슈체친:1 카토비체:1 루블린:1 비아위스토크:1",
    )

    // The Arabic given names Egypt, Morocco and Saudi Arabia share.
    private const val ARABIC_MALE = "무함마드:4 아흐메드:3 마흐무드:2 오마르:2 알리:2 유세프:2 하산 칼리드 이브라힘 카림 타리크 아므르 함자 " +
        "압둘라 사미르 나빌 아이만 와엘 지아드 야신 아나스 바실 파이살 술탄 나와프 투르키 마지드 사이프 아딜 함단"
    private const val ARABIC_FEMALE = "파티마:3 마리암:3 누르:2 라일라 야스민 아미라 하나 살마 사라 아야 자이나브 라니아 다나 리나 " +
        "하디자 누르한 말락 히바 사나 아스마 라나 샤이마 와파 가다 루브나 아비르 하난 마나르"

    val CO = Nation(
        "CO", "콜롬비아", "콜롬비아", "🇨🇴", population(52), NameOrder.GIVEN_SPACE_FAMILY,
        families = "로드리게스:5 고메스:5 곤살레스:5 마르티네스:4 가르시아:4 로페스:4 에르난데스:3 산체스:3 라미레스:3 페레스:3 " +
            "디아스:3 토레스:2 로하스:2 바르가스:2 모레노:2 구티에레스:2 히메네스:2 무뇨스:2 카스트로:2 오르티스:2 " +
            "레스트레포 오소리오 산타마리아 아리스티사발 사파타 쿠아드라도 레온 바예호 사라비아 몬토야",
        male = LATAM_MALE, female = LATAM_FEMALE,
        cities = "보고타:6 메데인:3 칼리:3 바랑키야:2 카르타헤나:1 부카라망가:1 페레이라:1 마니살레스:1",
    )

    val EG = Nation(
        "EG", "이집트", "이집트", "🇪🇬", population(113), NameOrder.GIVEN_SPACE_FAMILY,
        families = "하산 압델라흐만 엘사예드 모스타파 파루크 가말 나세르 엘마스리 사이드 아델 함디 라가브 샤반 마흐무드 " +
            "엘바드리 조흐디 압델아지즈 엘샤페이 오스만 수브히 엘가브리 파흐미 타우피크 아슈르",
        male = ARABIC_MALE, female = ARABIC_FEMALE,
        cities = "카이로:6 알렉산드리아:3 탄타:2 포트사이드:1 수에즈:1 룩소르:1 아스완:1 만수라:1",
    )

    val MA = Nation(
        "MA", "모로코", "모로코", "🇲🇦", population(37), NameOrder.GIVEN_SPACE_FAMILY,
        families = "벤나니 알라위 엘파시 탄지 베라다 엘울라디 벤살라 부자이드 엘아미리 사이디 엘이드리시 벤잘룬 라흐마니 아마르 " +
            "엘카티비 지하니 벤자키 엘마그리비 부스타니 타하리 엘알라미 하다드 엘구자",
        male = ARABIC_MALE, female = ARABIC_FEMALE,
        cities = "카사블랑카:5 라바트:3 마라케시:2 페스:2 탕헤르:2 아가디르:1 메크네스:1 우지다:1",
    )

    val SA = Nation(
        "SA", "사우디아라비아", "사우디", "🇸🇦", population(33), NameOrder.GIVEN_SPACE_FAMILY,
        families = "알도사리 알카흐타니 알오타이비 알샴리 알하르비 알마리 알가므디 알자흐라니 알무타이리 알시흐리 알말키 " +
            "알아나지 알하젬 알부카리 알루와일리 알수바이이 알자미 알라시디 알오마리 알바카르",
        male = ARABIC_MALE, female = ARABIC_FEMALE,
        cities = "리야드:5 제다:4 담맘:2 메카:1 메디나:1 타이프:1 아브하:1 타부크:1",
    )

    val SN = Nation(
        "SN", "세네갈", "세네갈", "🇸🇳", population(18), NameOrder.GIVEN_SPACE_FAMILY,
        families = "디오프:3 은디아예:3 디알로:2 파이:2 세크 시세 사르 니앙 바 팔 투레 카마라 은다오 시 음바이 은도예 소우 " +
            "디아뉴 티암 공디암 사네 바지 디아타",
        male = "마마두 이브라히마 셰이크 우스만 무사 바카리 우마르 압둘라예 이드리사 라민 바바카르 엘하지 말릭 세리뉴 모마르 " +
            "아마두 시디 샤리프 알리운 파페 은디아가 사무엘 칼릴루 앙투안",
        female = "파투 아미나타 아이사투 마리에메 쿰바 아와 다바 소피에투 코디아 은고네 아스투 야시네 빈타 라마 마티 니마 " +
            "펜다 아디자 마리아마 셀린 카디 로키아",
        cities = "다카르:6 티에스:2 생루이:1 지갱쇼르:1 카올라크:1 투바:1 음부르:1",
    )

    val ZA = Nation(
        "ZA", "남아프리카공화국", "남아공", "🇿🇦", population(63), NameOrder.GIVEN_SPACE_FAMILY,
        families = "은코시 들라미니 응쿠베 마흘랑구 몰레페 은들로부 판데르메르베 보타 판와이크 스미트 모요 모코에나 마세코 " +
            "쿠말로 은들렐라 몰로이 프레토리우스 모체과 뒤플레시 스테인 판니케르크 마부자 시톨레",
        male = "시부시소 반딜레 음툰지 타보 시포 레보 테보고 카기소 시야봉가 만들라 피터 요한 데온 브랜던 루안 니코 탄도 템바 " +
            "로완 키건 무지 라이언",
        female = "레라토 은톰비 자넬레 탄디웨 노지포 부시시웨 레필웨 리네 아넬리스 놈불라 진흘레 린디웨 미셸 엘마리 보이투멜로 " +
            "날레디 템비 카리나 디네오 팔레사 요란디",
        cities = "요하네스버그:5 케이프타운:4 더반:3 프리토리아:2 포트엘리자베스:1 블룸폰테인:1 이스트런던:1",
    )

    /** Every nation, in draw order (fixed: append only). */
    val ALL: List<Nation> = listOf(KR, JP, CN, US, GB, FR, DE, ES, BR, IT, VN, AR, MX, CA, AU, IN, TR, NL, SE, TH, NG, PL, CO, EG, MA, SA, SN, ZA)

    private val CONFEDS: Map<String, Confed> = buildMap {
        for (c in listOf("KR", "JP", "CN", "VN", "IN", "TH", "AU")) put(c, Confed.ASIA)
        for (c in listOf("GB", "FR", "DE", "ES", "IT", "TR", "NL", "SE", "PL")) put(c, Confed.EUROPE)
        for (c in listOf("US", "BR", "AR", "MX", "CA", "CO")) put(c, Confed.AMERICAS)
        for (c in listOf("NG", "EG", "MA", "SA", "SN", "ZA")) put(c, Confed.AFRICA)
    }

    fun confedOf(code: String): Confed = CONFEDS[code] ?: Confed.ASIA

    /** "🇯🇵 일본" for a nation code; null for anything else. */
    fun flagged(code: String?): String? = of(code)?.flagged

    /** Famous meetings (news call them by name). */
    val RIVALS: Map<Set<String>, String> = mapOf(
        setOf("KR", "JP") to "한일전",
        setOf("BR", "AR") to "남미 라이벌전",
        setOf("US", "MX") to "북중미 라이벌전",
        setOf("EG", "MA") to "북아프리카 더비",
    )

    fun rivalry(a: String, b: String): String? = RIVALS[setOf(a, b)]

    val TOTAL: Int = ALL.sumOf { it.weight }

    private val byCode = ALL.associateBy { it.code }

    /** The nation of a saved code; null for a code this version does not know. */
    fun of(code: String?): Nation? = code?.let { byCode[it] }

    /**
     * A nation for a new player by the weights, each multiplied by [boost] (e.g. to bring back a
     * nation the league has fewer of than its share; 1 = as listed).
     */
    fun draw(rng: Random, boost: (Nation) -> Double = { 1.0 }): Nation {
        val w = ALL.map { it.weight * boost(it).coerceAtLeast(0.0) }
        var x = rng.nextDouble() * w.sum()
        for (i in ALL.indices) {
            x -= w[i]
            if (x < 0) return ALL[i]
        }
        return ALL.last()
    }

    /**
     * Rookie intake boost that keeps the league's mix near the weights: a nation below its share
     * gets up to twice its weight, one above it down to half. [counts] are the alive players by
     * nation code, [total] all of them.
     */
    fun rebalance(counts: Map<String, Int>, total: Int): (Nation) -> Double = { n ->
        if (total <= 0) 1.0 else {
            val want = n.weight.toDouble() / TOTAL * total
            val have = (counts[n.code] ?: 0).toDouble()
            (want / maxOf(have, 0.5)).coerceIn(0.5, 2.0)
        }
    }
}

/**
 * Names of generated and evolved players: a family name by weight and a given name of the
 * player's gender (an older debutant draws the classic list more often), written as the nation
 * writes them, never the same twice ([unique]).
 */
object PlayerNames {
    /**
     * A full name that reads right: its given name is not its family name, a Korean one does not
     * start with "선수" ("선수민" reads as "player 민"), and a joined name does not start with
     * another family name of the pool (선+우빈 would read as 선우빈).
     */
    private fun acceptable(nation: Nation, family: String, given: String, full: String): Boolean {
        if (given == family) return false
        val plain = full.replace(" ", "")
        if (nation.order == NameOrder.FAMILY_GIVEN) {
            // No 이이준 or 웨이웨이: a given name never starts with the family name (KR, CN, VN).
            if (given.startsWith(family)) return false
            if (nation.code == "KR" && "선수" in plain) return false
            if (nation.families.names.any { it.length > family.length && it.startsWith(family) && full.startsWith(it) }) return false
        }
        return true
    }

    /** A family name as written for [gender]: Polish women take -ska for -ski (코발스카). */
    private fun written(nation: Nation, family: String, gender: Gender): String = when {
        nation.code == "PL" && gender == Gender.FEMALE && family.endsWith("스키") -> family.dropLast(1) + "카"
        nation.code == "PL" && gender == Gender.FEMALE && family.endsWith("츠키") -> family.dropLast(1) + "카"
        else -> family
    }

    /** Chance of a classic given name for a player debuting at [age]: 15% at 18, 70% at 32 and above. */
    fun classicChance(age: Int): Double = ((age - 16) / 22.0).coerceIn(0.15, 0.7)

    /** One name of [nation] for a player of [gender] debuting at [age] (may be taken already). */
    fun draw(nation: Nation, gender: Gender, age: Int, rng: Random, family: String? = null): String {
        var fam = family ?: nation.families.draw(rng)
        val classic = rng.nextDouble() < classicChance(age)
        repeat(60) { k ->
            // After a few misses another family name too (one that reads badly with every name).
            if (k == 20 && family == null) fam = nation.families.draw(rng)
            val given = givenName(nation, gender, if (k < 40) classic else !classic, rng)
            val full = nation.compose(written(nation, fam, gender), given)
            if (acceptable(nation, fam, given, full)) return full
        }
        return nation.compose(written(nation, fam, gender), givenName(nation, gender, !classic, rng))
    }

    private fun givenName(nation: Nation, gender: Gender, classic: Boolean, rng: Random): String {
        val pool = nation.given(gender, classic)
        val middle = nation.middle(gender)
        if (middle.isEmpty) return pool.draw(rng)
        repeat(10) {
            val m = middle.draw(rng)
            val n = pool.draw(rng)
            if (m != n) return m + n
        }
        return pool.draw(rng)
    }

    /**
     * A name not in [taken]: up to [TRIES] draws of a full name, then the family
     * name kept and a numbered given name ("리암 존슨 2") as a last resort (a test makes sure
     * the pools never get there in a league of [Capacity] players). With [family] (a child of
     * the same nation keeping a parent's family name) that family is tried first.
     */
    fun unique(nation: Nation, gender: Gender, age: Int, rng: Random, taken: Set<String>, family: String? = null): String {
        if (family != null) repeat(TRIES / 4) {
            val n = draw(nation, gender, age, rng, family)
            if (n !in taken) return n
        }
        repeat(TRIES) {
            val n = draw(nation, gender, age, rng)
            if (n !in taken) return n
        }
        val base = draw(nation, gender, age, rng)
        var i = 2
        while ("$base $i" in taken) i++
        return "$base $i"
    }

    const val TRIES = 200

    /** A hometown of [nation] by its cities' weights. */
    fun hometown(nation: Nation, rng: Random): String = nation.cities.draw(rng)
}
