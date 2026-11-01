package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.CoachPage
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.pressureOf
import yacht.engine.league.Money
import yacht.engine.league.StyleFamily

// 감독 (UX 5): a coach's philosophy, its board and their pressure, its career, honours, signings by
// style and the player it was. The texts with names and numbers in them are CoachTexts' functions
// (StoryTextsTest reads them).

/** [Page.Coach]: loads the coach's page (again each closed week) and draws it. */
@Composable
internal fun CoachPageBody(lg: LeagueController, v: LeagueView, page: Page.Coach) {
    LaunchedEffect(page.loadKey, v.clock?.season, v.clock?.week) { lg.worldPages.loadCoach(page.id) }
    val c = lg.worldPages.coach(page)
    if (c == null) {
        CenterText("감독 기록을 불러오는 중…")
        return
    }
    CoachContent(lg, c)
}

/**
 * The coach page (UX 5): the header (its club, the free pool's rank or its retirement), 🧠 철학, 📋
 * 구단의 기대 (employed), 📜 경력, 🏆 수상, 🎯 영입 성향 and 🎲 선수 시절 (an ex-player).
 */
@Composable
private fun CoachContent(lg: LeagueController, c: CoachPage) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(CoachTexts.header(c.age, c.philosophy.label), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            EncPill(nav, "k:${c.id}")
        }
        val club = c.club
        when {
            club != null -> Row(Modifier.clickable { nav.open(Page.Club(club.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ClubEmblem(club.emblem, 18.dp)
                Text(" " + CoachTexts.club(club.name, c.since) + " ›", style = MaterialTheme.typography.labelMedium, color = cs.secondary, fontWeight = FontWeight.Bold)
            }
            c.retired != null -> Text(CoachTexts.retired(c.retired), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            else -> Text(CoachTexts.free(c.freeRank), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        CoachHeading("🧠 철학")
        Text(c.philosophy.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        CoachSmall(c.philosophy.desc)
        if (c.dev > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoachSmall("육성 능력 ${stars(c.dev)}")
                InfoMark(nav, InfoTopic.FACILITIES)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachSmall("평판 ${c.reputation}/100  ")
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(c.reputation.coerceIn(0, 100) / 100f).fillMaxHeight().background(gameColors.me))
            }
        }
        val board = c.board
        if (club != null && board != null) {
            CoachHeading("📋 구단의 기대")
            Text(CoachTexts.board(c.league, board.clubs, board.target, board.place), style = MaterialTheme.typography.bodySmall)
            BoardLine(nav, board.target, board.place, c.pressure ?: pressureOf(board))
            if (board.threshold > 0 && board.projected >= board.threshold) {
                Text(ClubTexts.warning(c.winterNext), style = MaterialTheme.typography.labelSmall, color = cs.error, fontWeight = FontWeight.Bold)
            }
        }
        if (club != null) CoachSmall("계약 시즌 ${c.until}까지 · 연봉 ${Money.text(c.wage)}")
        if (c.tenures.isNotEmpty() || c.olderClubs > 0) {
            CoachHeading("📜 경력")
            for (t in c.tenures) {
                val line = ClubTexts.tenure(t.from, t.to, t.club?.name ?: "–", t.best, t.titles, tenureEnd(t.end))
                val id = t.club?.id
                if (id != null && id in (lg.view?.badges ?: emptyMap())) {
                    Row(Modifier.fillMaxWidth().clickable { nav.open(Page.Club(id)) }.padding(vertical = 3.dp)) {
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = WRAP, overflow = TextOverflow.Ellipsis)
                        Text("›", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                } else {
                    CoachSmall(line)
                }
            }
            if (c.olderClubs > 0) CoachSmall(if (c.tenures.isEmpty()) CoachTexts.career(c.olderClubs, c.olderSeasons) else CoachTexts.older(c.olderClubs, c.olderSeasons))
        }
        CoachHeading("🏆 수상")
        CoachSmall(CoachTexts.honours(c.clubTitles, c.leagueTitles, c.awards, c.sacked))
        if (c.signings.isNotEmpty()) {
            CoachHeading("🎯 영입 성향")
            val top = c.signings.maxOf { it.second }.toDouble()
            val dark = cs.background.luminance() < 0.5f
            for ((f, n) in c.signings) {
                val fits = f in c.philosophy.families
                BarRow((if (fits) "★ " else "") + f.label, n.toDouble(), top, "${n}명", familyColor(f, dark))
            }
            if (c.philosophy.families.isNotEmpty()) CoachSmall(CoachTexts.fits(c.philosophy.families))
        }
        val player = c.player
        if (player != null) {
            CoachHeading("🎲 선수 시절")
            Row(Modifier.fillMaxWidth().clickable { nav.open(Page.Player(player)) }.padding(vertical = 3.dp)) {
                Text(CoachTexts.playerDays(c.playerSeasons, c.playerTitles), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CoachHeading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun CoachSmall(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The coach page's texts with names and numbers in them (UX 5, 해요체), for StoryTextsTest. */
internal object CoachTexts {
    fun header(age: Int, philosophy: String): String = "${age}세 · $philosophy 감독"

    fun club(club: String, since: Int): String = "$club 감독 · 시즌 ${since}부터"

    fun free(rank: Int?): String = "지금은 쉬고 있어요" + (rank?.let { " · 쉬는 감독 가운데 평판 ${it}위" } ?: "")

    fun retired(season: Int): String = "시즌 ${season}에 은퇴했어요"

    /** 📋 구단의 기대's lead: "구단은 {league} 클럽 {clubs}곳 가운데 {target}위를 바라고 있어요. 지금은 {place}위예요." */
    fun board(league: String, clubs: Int, target: Int, place: Int): String =
        "구단은 ${league.ifEmpty { "리그" }} 클럽 ${clubs}곳 가운데 ${target}위를 바라고 있어요. 지금은 ${place}위예요."

    fun older(clubs: Int, seasons: Int): String = "그 전 ${clubs}개 클럽" + (if (seasons > 0) " ${seasons}시즌" else "")

    /** A retired coach's whole career (no spells kept one by one). */
    fun career(clubs: Int, seasons: Int): String = "${clubs}개 클럽에서" + (if (seasons > 0) " ${seasons}시즌 동안" else "") + " 지휘했어요."

    fun honours(clubTitles: Int, leagueTitles: Int, awards: Int, sacked: Int): String =
        "클럽 순위 우승 ${clubTitles}회 · 소속 선수 리그 우승 ${leagueTitles}회 · 올해의 감독 ${awards}회 · 경질 ${sacked}회"

    /** Under the signings: the families its philosophy looks for (★ in the bars). */
    fun fits(families: Set<StyleFamily>): String = "★ ${families.joinToString("·") { it.label }} 선수를 좋아해요."

    fun playerDays(seasons: Int, titles: Int): String = "${seasons}시즌을 뛰었고 주요 우승 ${titles}회를 했어요. 선수 페이지 ›"
}
