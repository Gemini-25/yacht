package yacht.app.ui

import yacht.app.core.InfoTopic
import yacht.engine.league.EncKind
import yacht.engine.league.StoryStage

// The club world's and the story pages' words (UX 14.1) and the ⓘ sheet's explanations (14.4), in
// 해요체. Texts with a name in them are functions of their names, so StoryTextsTest reads each with
// names ending with and without a final consonant (each stage adds its own texts here and their
// cases there).

/** A club's financial health in words, by its grade A..E (UX 14.1). */
internal fun healthWord(grade: Char): String = when (grade) {
    'A' -> "아주 튼튼해요"
    'B' -> "튼튼해요"
    'C' -> "보통이에요"
    'D' -> "불안해요"
    else -> "위기예요"
}

/** A club's trouble in a word: 1 적자, 2 재정 위기, 3 법정관리; null for none. */
internal fun troubleWord(trouble: Int): String? = when (trouble) {
    1 -> "적자"
    2 -> "재정 위기"
    3 -> "법정관리"
    else -> null
}

/** A training ground's or an academy's level 1..5 in a word. */
internal fun facilityWord(level: Int): String = when {
    level <= 1 -> "기초"
    level == 2 -> "보통"
    level == 3 -> "좋음"
    level == 4 -> "최신"
    else -> "세계 최고"
}

/** The fans' mood 0..100 in words and its face. */
internal fun moodWord(mood: Int): Pair<String, String> = when {
    mood >= 80 -> "열광하고 있어요" to "🤩"
    mood >= 60 -> "만족해요" to "😊"
    mood >= 40 -> "그럭저럭이에요" to "😐"
    mood >= 25 -> "불만이 커요" to "😠"
    else -> "화가 났어요" to "😡"
}

/** How a coach's spell ended (its `end` code): S 경질, P 다른 클럽으로, L 계약 만료, R 은퇴, '-' 지금. */
internal fun tenureEnd(end: Char): String = when (end) {
    'S' -> "경질"
    'P' -> "다른 클럽으로"
    'L' -> "계약 만료"
    'R' -> "은퇴"
    else -> "지금"
}

/** A transfer story's stage with its icon ("📝 협상 중"). */
internal fun storyStage(stage: StoryStage): String = when (stage) {
    StoryStage.RUMOUR -> "💬"
    StoryStage.TALKS -> "📝"
    StoryStage.DONE -> "✅"
    StoryStage.COLLAPSED -> "❌"
    StoryStage.HIJACKED -> "🦅"
} + " " + stage.label

/** A rank's move since the last list: "▲2", "▼1", "–" for none, "NEW" for a newcomer ([move] null). */
internal fun rankMove(move: Int?): String = when {
    move == null -> "NEW"
    move > 0 -> "▲$move"
    move < 0 -> "▼${-move}"
    else -> "–"
}

/** An encyclopedia page's live link, by the kind of what it is about ("클럽 페이지 ›"). */
internal fun liveLink(kind: EncKind): String = when (kind) {
    EncKind.PLAYER -> "선수 페이지 ›"
    EncKind.CLUB -> "클럽 페이지 ›"
    EncKind.COACH -> "감독 페이지 ›"
    EncKind.LEAGUE -> "리그 페이지 ›"
    EncKind.NATION -> "나라 페이지 ›"
    EncKind.COMPETITION -> "대회 페이지 ›"
}

/** The ⓘ sheet's title for [t] (UX 14.4). */
internal fun infoTitle(t: InfoTopic): String = when (t) {
    InfoTopic.FINANCE -> "클럽 재정은 이렇게 움직여요"
    InfoTopic.BOARD -> "구단의 기대와 감독 압박"
    InfoTopic.FACILITIES -> "시설과 육성"
    InfoTopic.POWER -> "파워 랭킹"
    InfoTopic.PREDICT -> "시즌 전망"
    InfoTopic.VOTES -> "기자단 투표"
    InfoTopic.ERAS -> "시대"
    InfoTopic.ARCS -> "세계 정세"
}

/** The ⓘ sheet's explanation of [t] (UX 14.4). */
internal fun infoText(t: InfoTopic): String = when (t) {
    InfoTopic.FINANCE ->
        "클럽은 시즌이 끝날 때 한 번 결산해요. 수입은 중계권과 상금(클럽 순위), 국제 대회 상금, 스폰서, 입장 수입, 구단주 지원에서 나오고, " +
            "강한 리그일수록 커요. 지출은 선수 연봉, 감독 연봉, 시설 운영비, 공사비, 영입 이적료예요. 빚이 한 시즌 수입의 절반을 넘으면 " +
            "재정 위기예요. 그동안은 선수를 살 수 없어요. 빚이 한 시즌 수입만큼 쌓이면 법정관리에 들어가요. 그러면 다음 시즌 클럽 순위 " +
            "승점이 10% 깎여요. 돈은 선수의 능력을 바꾸지 않아요. 누가 어느 클럽에서 뛰는지만 바꿔요."
    InfoTopic.BOARD ->
        "구단은 시즌을 시작할 때 선수들의 레이팅으로 클럽 순위 목표를 정해요. 야망이 큰 구단주는 목표를 더 높게 잡아요. 겨울 점검(시즌 " +
            "중간)과 시즌이 끝날 때 목표보다 성적이 나쁘면 압박이 쌓여요. 압박이 구단주의 인내심을 넘으면 감독이 경질돼요. 팬 만족도가 " +
            "낮아도 압박이 커지고, 우승하면 압박이 사라져요."
    InfoTopic.FACILITIES ->
        "훈련 시설, 감독의 육성 능력, 유스 아카데미는 선수가 성장하는 속도를 아주 조금(최대 ±12%) 바꿔요. 나라마다 합이 0이 되게 맞춰서 " +
            "리그 전체의 실력은 그대로예요. 선수가 오를 수 있는 한계는 바뀌지 않아요. 유스 아카데미가 좋을수록 좋은 유망주를 먼저 " +
            "데려올 가능성도 커요."
    InfoTopic.POWER ->
        "파워 랭킹은 기자단이 매주 매기는 선수 순위예요. 시즌을 시작할 때의 레이팅에, 기대보다 더 딴 승점과 최근 5경기 폼을 더하고, " +
            "상위 리그 1부면 점수를 조금 더 줘요. 리그 경기를 4번 이상 치른 1부 선수만 들어가요."
    InfoTopic.PREDICT ->
        "시즌이 열리면 기자단이 리그마다 남은 경기를 400번 모의로 치러 우승 후보, 다크호스, 강등 후보를 골라요. 경기 화면의 우승 확률과 " +
            "같은 계산이에요. 시즌이 끝나면 얼마나 맞혔는지 성적표가 나와요."
    InfoTopic.VOTES ->
        "리그 랭킹 상위 리그의 기자들이 뽑아요. 기자마다 1위에 5점, 2위에 3점, 3위에 1점을 줘요. 기자마다 레이팅, 우승, 기록을 보는 " +
            "눈이 달라서 결과가 리그 MVP와 다를 수 있어요."
    InfoTopic.ERAS ->
        "한 유형이 큰 대회(상위 리그 1부와 챔피언스 리그) 우승을 선수 수에 비해 눈에 띄게 많이 가져가면, 그 유형의 시대가 열려요. " +
            "잘나가는 유형은 신인 사이에서 조금씩 유행하지만, 너무 많아지면 식고 카운터 유형이 나타나요. 흔해지면 시대도 저물어요. " +
            "주사위 점수 규칙은 그대로예요."
    InfoTopic.ARCS ->
        "세계 정세는 몇 시즌에 한 번꼴로 일어나는 큰 사건을 다뤄요. 사건은 여러 시즌에 걸쳐 단계별로 이어지고, 결말에 따라 리그 " +
            "구성이나 클럽 재정이 실제로 바뀌어요."
}
