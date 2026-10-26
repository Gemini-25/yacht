package yacht.engine.league

/**
 * A nation's school of play (국가별 성향, docs/LEAGUE_SPEC.md 1.4): the styles its players take
 * up more often ([favoured], drawn [FAVOUR] times as often as elsewhere), an overlay it likes
 * ([overlay], [FAVOUR] times its usual share), and points added to its players' abilities
 * ([bias]: 판단력, 예측력, 승부 감각, 멘탈). Every style and every level of ability still comes
 * from every nation; a school only tilts the odds. [motto] says it in one line (해요체).
 */
class School(val favoured: List<String>, val overlay: String?, val bias: IntArray, val motto: String)

object Schools {
    /** How much more often a school's styles and overlay are drawn. */
    const val FAVOUR = 2.5

    private fun s(favoured: String, overlay: String?, j: Int, f: Int, c: Int, m: Int, motto: String) =
        School(favoured.split(' '), overlay, intArrayOf(j, f, c, m), motto)

    private val BY_CODE: Map<String, School> = mapOf(
        "KR" to s("ev purist", null, 3, 1, 0, 1, "계산이 정확하고 보너스를 꼬박꼬박 챙기는 정석의 나라예요."),
        "JP" to s("purist noscratch", null, 4, 0, -2, 1, "위쪽 칸을 차근차근 채우는 꼼꼼한 장인이 많아요."),
        "CN" to s("fourkind collector", null, 0, 3, 0, 1, "같은 눈을 모아 큰 족보를 노리는 선수가 많아요."),
        "US" to s("hunter reroller", "gambler", 0, 0, 4, 0, "요트를 사랑하는 화끈한 승부사들의 나라예요."),
        "GB" to s("hoarder timid", null, 1, 1, 0, 4, "침착하게 초이스를 아껴 두는 신사적인 운영이 특징이에요."),
        "FR" to s("straight contrarian", null, 0, 3, 1, 0, "스트레이트를 즐기는 예술파가 많아요."),
        "DE" to s("ev noscratch", "locker", 4, 1, 0, 2, "실수 없이 단단하게, 기계처럼 정확하게 둬요."),
        "ES" to s("fullhouse straight", null, 0, 1, 3, 0, "풀하우스와 스트레이트로 화려하게 점수를 쌓아요."),
        "BR" to s("hunter reroller", "streaky", 0, 0, 3, -2, "흥이 오르면 아무도 못 막는 공격형이 많아요."),
        "IT" to s("noscratch purist", "locker", 1, 0, 1, 3, "앞서면 철저히 잠그는 수비의 나라예요."),
        "VN" to s("small collector", null, 1, 2, 0, 0, "작은 눈도 버리지 않는 알뜰한 선수가 많아요."),
        "AR" to s("hunter fourkind", "gambler", 0, 0, 4, -2, "지고 있어도 끝까지 요트를 노리는 승부사 기질이 있어요."),
        "MX" to s("fullhouse reroller", "streaky", 0, 0, 2, 0, "끝까지 굴리며 풀하우스를 노리는 열정파가 많아요."),
        "CA" to s("ev hoarder", null, 2, 1, 0, 2, "무리하지 않고 꾸준하게 점수를 쌓아요."),
        "AU" to s("reroller hunter", "gambler", 0, 0, 3, 1, "과감하게 굴리고 요트에 승부를 거는 편이에요."),
        "IN" to s("ev lower", null, 1, 4, 0, 0, "남은 판을 멀리 내다보는 계산파가 많아요."),
        "TR" to s("hunter scratcher", "gambler", 0, 0, 3, -3, "뜨겁고 과감해요. 어려운 칸은 일찍 지우고 요트를 노려요."),
        "NL" to s("straight lower", null, 0, 2, 2, 0, "족보 칸을 넓게 쓰는 토털 요트를 해요."),
        "SE" to s("ev timid", null, 1, 1, 0, 4, "흔들림 없이 침착한 선수가 많아요."),
        "TH" to s("small fullhouse", null, 0, 1, 1, 1, "작은 눈과 풀하우스를 알뜰하게 챙겨요."),
        "NG" to s("reroller fourkind", null, 0, 0, 3, 1, "끝까지 포기하지 않고 굴려 큰 족보를 만들어요."),
        "PL" to s("noscratch ev", null, 2, 1, 0, 1, "0점을 싫어하는 끈질긴 선수가 많아요."),
        "CO" to s("fullhouse hunter", "streaky", 0, 0, 2, 0, "리듬을 타면 무섭게 몰아쳐요."),
        "EG" to s("collector fourkind", null, 0, 2, 1, 0, "같은 눈을 모으는 끈기가 대단해요."),
        "MA" to s("straight scratcher", null, 0, 1, 2, 0, "스트레이트를 즐기고 어려운 칸은 미련 없이 지워요."),
        "SA" to s("hunter reroller", "gambler", 0, 0, 3, -1, "요트 한 방에 승부를 거는 선수가 많아요."),
        "SN" to s("fourkind reroller", null, 0, 0, 2, 2, "끝까지 침착하게 굴려 포카드를 만들어요."),
        "ZA" to s("ev straight", null, 1, 1, 1, 1, "정석에 스트레이트를 곁들이는 균형 잡힌 스타일이에요."),
    )

    private val NONE = School(emptyList(), null, IntArray(4), "")

    fun of(code: String): School = BY_CODE[code] ?: NONE
}
