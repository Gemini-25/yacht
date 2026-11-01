package yacht.uicheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.EraView
import yacht.app.core.LeagueTab
import yacht.app.core.MetaTab
import yacht.app.core.Page
import yacht.app.core.ShareSeason
import yacht.app.core.drawnRatings
import yacht.app.ui.ERA_SECTION
import yacht.app.ui.EraStrip
import yacht.app.ui.MetaFolds
import yacht.app.ui.PRESS_SECTION
import yacht.app.ui.ShareChart
import yacht.app.ui.WrappedCard
import yacht.app.ui.WrappedCardView
import yacht.engine.explain.Ko
import yacht.engine.league.Article
import yacht.engine.league.BigStory
import yacht.engine.league.FamilyMatchup
import yacht.engine.league.FamilyStats
import yacht.engine.league.FeeRecord
import yacht.engine.league.Honour
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Media
import yacht.engine.league.Nations
import yacht.engine.league.NewsBook
import yacht.engine.league.PressAwards
import yacht.engine.league.PressTally
import yacht.engine.league.PressVoteRow
import yacht.engine.league.ReviewCoaches
import yacht.engine.league.ReviewEra
import yacht.engine.league.ReviewMarket
import yacht.engine.league.ReviewMedia
import yacht.engine.league.SeasonStories
import yacht.engine.league.StyleFamily
import yacht.engine.league.WorldBook
import java.io.File
import kotlin.math.roundToInt

/**
 * The press and the meta on the story world (P12's variants, until P17 makes the engine write
 * them): a club world's story files ([ClubScene] or [ClubStory]) with the lines a few closes of
 * the press and the meta would have left, written as their codecs read them: the last close's
 * press vote (`pv`) and 골든 다이스 of every season (honours), the predictions' scorecards (`psc`),
 * the career weeks at no. 1 (`pr1`), six seasons of style shares (`ss`), two eras and a candidate,
 * the newcomer weights with a counter close (`meta`), the family measures of the last three closes
 * (`fm`, the matchups on the newest), a world event that ended (`evh`), the world-record move
 * (`tx` W), the last review's story sub-lines; and, from week 6, this season's power ranking as the
 * desk keeps it (`pw`, `p1`) with its column. The numbers are made up but consistent: the votes
 * add up to 9 points a juror, the shares to 1000, and each line reads back (checked).
 */
internal object PressStory {
    /** A press-and-meta variant: its [files], decoded as [world]; [last] the close it tells of. */
    class Scene(val files: Map<String, ByteArray>, val world: LeagueWorld, val last: Int, val league: String, val millis: Long)

    /** The jury of the made-up votes. */
    const val JURY = 40

    /** [base] with the press and the meta of its closes; [ranked]: this season's power ranking too (the season after week 5). */
    fun of(base: Map<String, ByteArray>, ranked: Boolean): Scene {
        val t0 = System.currentTimeMillis()
        val w = ClubStory.decode(base)
        val p = w.plan ?: error("press story: no season in play")
        val s = p.number
        val last = s - 1
        check(last >= 3) { "press story: season $s is too early" }
        val drawn = drawnRatings(w, p)
        val book = w.clubs ?: error("press story: no club book")
        fun name(k: String) = w.players[k]?.name ?: k
        val rated = w.members.filter { !w.ladder.rating(it).isNaN() }.sortedWith(compareByDescending<String> { w.ladder.rating(it) }.thenBy { it })
        val leagues = Media.leagues(p, drawn)
        check(leagues.isNotEmpty()) { "press story: no national league" }
        // The press vote of the last close (points 5-3-1 a ballot: 9 × the jury an award).
        val gd = rated.take(6)
        val coaches = book.coaches.filter { it.club != null }.sortedByDescending { it.reputation }.take(4).map { it.id }
        val movers = book.notable.map { it.key }.distinct().filter { it in w.members }.take(4).ifEmpty { rated.drop(6).take(4) }
        fun tally(award: String, keys: List<String>, votes: List<Pair<Int, Int>>) = PressTally(last, award, JURY, keys.zip(votes).map { (k, v) -> PressVoteRow(k, v.first, v.second) })
        val tallies = listOfNotNull(
            tally(PressAwards.GOLDEN_DICE, gd, GD_VOTES),
            coaches.takeIf { it.size == AWARD_VOTES.size }?.let { tally(PressAwards.COACH, it, AWARD_VOTES) },
            movers.takeIf { it.size == SIGN_VOTES.size }?.let { tally(PressAwards.SIGNING, it, SIGN_VOTES) },
        )
        // 골든 다이스 of every closed season: the last close's vote winner, before it each season's MVP.
        for (x in 1..last) {
            val winner = if (x == last) gd.first() else w.honours.of(x).firstOrNull { it.kind == "MVP" }?.key ?: rated[x % rated.size]
            w.honours.add(w.honours.of(x).filter { it.kind != PressAwards.GOLDEN_DICE } + Honour(x, PressAwards.GOLDEN_DICE, winner))
        }
        // The style families' measures of the last three closes, the matchups on the newest.
        val first = maxOf(1, last - SHARES.size + 1)
        val shareSeasons = (first..last).toList()
        fun sharesOf(x: Int) = SHARES[SHARES.size - shareSeasons.size + shareSeasons.indexOf(x)]
        val fms = (maxOf(first, last - 2)..last).map { x -> familyStats(x, x == last, sharesOf(x), leagues) }
        // The world's own lines (world.txt): the meta's, the media's, an ended world event.
        val worldText = buildString {
            append("${WorldBook.HEADER}\nguards $last $last 0 0\n")
            append("meta ${WEIGHTS.joinToString(",")} $last:C:Y:31\n")
            for (x in shareSeasons) append("ss $x ${sharesOf(x).first.joinToString(",")} ${sharesOf(x).second.joinToString(",")}\n")
            append("era 1 B ${last - 3} ${last - 1} 231\nera 2 C $last 0 198\ncand Y 1\n")
            append("pr1 ${rated.take(4).zip(listOf(12, 7, 4, 2)).joinToString(",") { (k, n) -> "$k:$n" }}\n")
            for (x in 2..last) {
                val n = minOf(leagues.size, 8)
                val hits = listOf(2, 3, 1, 3, 2, 3)[x % 6].coerceAtMost(n)
                val per = if (x == last) leagues.take(n).withIndex().joinToString(";") { (i, id) -> "$id:${if (i < hits) 1 else 0}:${if (i <= hits) 1 else 0}:${(i + 1) % 3}:${55 - 7 * i}" } else "-"
                val over = rated.getOrNull(20 + x)?.let { "$it:9:2" } ?: "-"
                val flop = rated.getOrNull(2 + x)?.let { "$it:1:8" } ?: "-"
                append("psc $x $hits/$n ${minOf(n, hits + 1)}/$n ${n - 1}/${3 * n} ${30 + 3 * x} $over $flop $per\n")
            }
            leagues.firstOrNull()?.let { id -> append("evh 1 BOOM ${last - 2} ${last - 1} S $id\n") }
        }
        val decodedWorld = WorldBook.decode(worldText)
        check(decodedWorld.meta.eras.size == 2 && decodedWorld.meta.shares.size == shareSeasons.size && decodedWorld.media.scorecard(last) != null) { "press story: world.txt does not read back" }
        // The last review's story sub-lines (P17 fills them at the close).
        val record = book.notable.maxByOrNull { it.fee }
        val recKey = record?.key ?: rated[7]
        val toClub = record?.to ?: book.clubOf(recKey)
        val fromClub = record?.from ?: book.clubs.firstOrNull { it.id != toClub }?.id
        val fee = maxOf(record?.fee ?: 0, 1850)
        val host = leagues.firstOrNull { Nations.of(it) != null } ?: Nations.KR.code
        val runner = Nations.ALL.map { it.code }.firstOrNull { it != host } ?: "JP"
        val coy = coaches.firstOrNull()
        val stories = SeasonStories(
            big = listOf(
                BigStory("era_begins", emptyList(), listOf("C", "9", "18", "족보파의 시대")),
                BigStory("wc_host_vote", listOf(host, runner), listOf("2", "21", "19")),
                BigStory("record_fee", listOf(recKey), listOf("$fee")),
                BigStory("press_award", gd.take(3), listOf("142", "18", "$JURY", "96", "61")),
            ),
            media = ReviewMedia(3, minOf(leagues.size, 8), 41, gd.take(3).zip(GD_VOTES).map { (k, v) -> PressVoteRow(k, v.first, v.second) }),
            market = ReviewMarket(FeeRecord(recKey, fromClub, toClub, fee, last), 23),
            coaches = ReviewCoaches(2, coy, coy?.let { id -> book.coach(id)?.club }),
            era = ReviewEra(StyleFamily.COMBO, 1, 'B'),
        )
        check(w.archive.amendReview(last, stories = stories)) { "press story: season $last's review is not kept" }
        val out = StoryWorld.variant(base) {
            this[LeagueWorld.HONOURS] = w.honours.encode()
            this[LeagueWorld.ARCHIVE] = w.archive.encode()
            this.world = worldText
            this[LeagueWorld.STATS] = this[LeagueWorld.STATS]!! + fms.joinToString("") { it.encode() } + tallies.joinToString("") { it.encode() }
            // The world-record move of the last window (the chronicle's 💰 line).
            if (toClub != null && fromClub != null) clubs = clubs!! + "tx $last T $recKey $fromClub $toClub $fee W\n"
            this[LeagueWorld.NEWS] = news(this[LeagueWorld.NEWS]!!, w, ranked, rated, ::name)
        }
        val back = ClubStory.decode(out)
        check(back.stats.pressVotes(last).size == tallies.size && back.stats.families(last) != null && back.archive.stories(last) != null) { "press story: the files do not read back" }
        if (ranked) check(back.desk.powerWeek > 0) { "press story: the desk's ranking does not read back" }
        return Scene(out, back, last, leagues.first(), System.currentTimeMillis() - t0)
    }

    /**
     * news.txt with the power ranking's column: with [ranked] this season's (the desk's last two
     * weeks ranked and its weeks at no. 1, by [Media.ranking] on the season's results), else the
     * season before's last column (the review weeks show it).
     */
    private fun news(text: String, w: LeagueWorld, ranked: Boolean, rated: List<String>, name: (String) -> String): String {
        val p = w.plan!!
        val s = p.number
        val book = NewsBook.decode(text)
        val desk = text.lines().drop(1).filter { it.isNotEmpty() && !it.startsWith("a ") }
        val extra = StringBuilder()
        val column: Article
        if (ranked) {
            val drawn = drawnRatings(w, p)
            val week = p.week - 1
            val from = maxOf(Media.firstWeek(p), 1)
            check(week > from) { "press story: week ${p.week} is before the power ranking's second week" }
            val tops = (from..week).associateWith { wk -> Media.ranking(p, drawn, wk).take(Media.WORLD_TOP).map { it.key } }
            val ones = tops.values.mapNotNull { it.firstOrNull() }.groupingBy { it }.eachCount()
            extra.append("pw $s ${week - 1} ${tops.getValue(week - 1).joinToString(",")}\n")
            extra.append("pw $s $week ${tops.getValue(week).joinToString(",")}\n")
            extra.append("p1 ${ones.entries.joinToString(",") { "${it.key}:${it.value}" }}\n")
            column = columnOf(s, week, tops.getValue(week), name)
        } else {
            column = columnOf(s - 1, p.calendar.leagueWeeks, rated.take(Media.WORLD_TOP), name)
        }
        book.add(column)
        return book.encode() + desk.joinToString("") { "$it\n" } + extra
    }

    /** A power ranking column of [week] (P15 writes the real template): its top 10 as keys. */
    private fun columnOf(season: Int, week: Int, top: List<String>, name: (String) -> String): Article {
        val (a, b, c) = top.take(3).map(name)
        return Article(
            season, "power_ranking", "${week}주차 파워 랭킹: ${Ko.i(a)} 1위에 올랐어요",
            "기자단이 매긴 이번 주 파워 랭킹이에요. ${Ko.i(a)} 맨 위에 섰고, ${Ko.wa(b)} ${Ko.i(c)} 뒤를 이었어요.", top, null, 2, week,
        )
    }

    /** A made-up family measure of close [season] with its shares [row] (per mille: the best leagues' 1부, everyone); [matchups] on the newest only, as stats.txt keeps them. */
    private fun familyStats(season: Int, matchups: Boolean, row: Pair<List<Int>, List<Int>>, leagues: List<String>): FamilyStats {
        fun counts(perMille: List<Int>, total: Int): List<Int> {
            val xs = perMille.map { (it * total / 1000.0).roundToInt() }.toMutableList()
            xs[2] += total - xs.sum()
            return xs
        }
        val families = StyleFamily.entries
        val ms = if (!matchups) emptyList() else buildList {
            for (f in families) for (g in families) {
                if (f == g) continue
                val n = 22 + ((f.ordinal * 7 + g.ordinal * 3) % 9) * 12
                val x = when {
                    f == StyleFamily.COMBO && g == StyleFamily.YACHT -> 0.31
                    f == StyleFamily.YACHT && g == StyleFamily.COMBO -> -0.25
                    else -> (RESIDUALS[f.ordinal] - RESIDUALS[g.ordinal]) / 40.0
                }
                add(FamilyMatchup(f, g, (n * (1.5 + x)).roundToInt(), n, x * n))
            }
        }
        return FamilyStats(
            season, counts(row.first, 80), counts(row.second, 300), TITLES[season % TITLES.size], RESIDUALS, ms,
            leagues.withIndex().associate { (i, id) -> id to LEAGUE_FAMILIES[i % LEAGUE_FAMILIES.size] },
        )
    }

    /** The newcomer weights (× 1000, family order). */
    private val WEIGHTS = listOf(1080, 940, 1220, 1100, 930, 1000, 880, 960)

    /** Each family's shrunk rating residual. */
    private val RESIDUALS = listOf(4.0, -1.0, 6.0, 2.0, -3.0, -1.0, -2.0, -4.0)

    /** The families of a close's big titles (its best leagues' champions, then the 챔피언스 리그's). */
    private val TITLES = listOf(
        listOf(StyleFamily.COMBO, StyleFamily.COMBO, StyleFamily.YACHT, StyleFamily.COMBO, StyleFamily.BONUS, StyleFamily.COMBO),
        listOf(StyleFamily.BONUS, StyleFamily.BONUS, StyleFamily.COMBO, StyleFamily.EV, StyleFamily.BONUS, StyleFamily.COMBO),
        listOf(StyleFamily.COMBO, StyleFamily.BONUS, StyleFamily.COMBO, StyleFamily.COMBO, StyleFamily.YACHT, StyleFamily.BONUS),
    )

    private val LEAGUE_FAMILIES = listOf(StyleFamily.COMBO, StyleFamily.BONUS, StyleFamily.YACHT, StyleFamily.COMBO, StyleFamily.EV, StyleFamily.BONUS, StyleFamily.CAUTION, StyleFamily.COMBO)

    /** Six seasons of style shares (per mille, family order; the best leagues' 1부, then everyone): 보너스파 rises, then 족보파. */
    private val SHARES = listOf(
        listOf(70, 205, 270, 120, 160, 85, 40, 50) to listOf(59, 198, 285, 107, 172, 81, 42, 56),
        listOf(72, 190, 262, 150, 150, 86, 40, 50) to listOf(60, 195, 283, 115, 170, 80, 41, 56),
        listOf(70, 180, 255, 185, 140, 85, 38, 47) to listOf(60, 192, 281, 125, 167, 80, 40, 55),
        listOf(66, 175, 270, 205, 128, 82, 34, 40) to listOf(61, 189, 284, 133, 162, 79, 39, 53),
        listOf(64, 170, 300, 190, 122, 80, 34, 40) to listOf(61, 187, 292, 131, 159, 79, 38, 53),
        listOf(62, 185, 325, 160, 118, 78, 32, 40) to listOf(62, 190, 298, 124, 157, 78, 38, 53),
    )

    /** The made-up votes (points, first-place votes): 골든 다이스's six, 올해의 감독's four, 올해의 영입's four. */
    private val GD_VOTES = listOf(142 to 18, 96 to 9, 61 to 6, 35 to 4, 17 to 2, 9 to 1)
    private val AWARD_VOTES = listOf(120 to 16, 102 to 14, 78 to 8, 60 to 2)
    private val SIGN_VOTES = listOf(130 to 17, 110 to 13, 80 to 7, 40 to 3)
}

/**
 * P12's shots (UX 15.3: lg87-lg88b, lg93, lg98d, lg98e and the league page's style line) on
 * [PressStory]'s variants restored into the real screen: 기자단 in the season (the power ranking
 * first, the first-screen check of its first section), in its first weeks (the predictions first)
 * and in the review weeks (the vote first, "▶ 5위부터 발표" at its third row); 시대와 메타's eras and
 * its matchups unfolded; the review with its big-story card, Wrapped's two new cards, the chronicle
 * with the story lines; a 30-season share chart drawn alone. Restores the story world as it was
 * before it returns.
 */
internal object ShotsStories : LeagueShots {
    override fun story(c: AppController, out: File) {
        val t0 = System.currentTimeMillis()
        val lg = c.league
        // In the season: on the club scene's files (a club world for 올해의 감독, 올해의 영입 and the club boards).
        val scene = PressStory.of(ClubScene.default.files, ranked = true)
        StoryWorld.restore(c, scene.files)
        fun press(page: Page.Press, league: String = scene.league) {
            lg.nav.go(LeagueTab.HOME)
            lg.nav.open(page)
            lg.storyPages.loadPress(league)
            waitFor("기자단 of $league") { lg.storyPages.press?.league == league }
        }
        press(Page.Press())
        firstScreenCheck(c, out, "lg87_press", listOf(PRESS_SECTION), heightDp = 1500)
        shot(c, out, "lg87_press_360", heightDp = 1500, widthDp = 360)
        // 시대와 메타: the eras tab, then the matchups unfolded.
        lg.nav.go(LeagueTab.HOME)
        lg.nav.open(Page.Meta())
        lg.loadRecords()
        lg.storyPages.loadMeta()
        waitFor("시대와 메타") { lg.storyPages.meta?.any == true }
        firstScreenCheck(c, out, "lg88_meta_eras", listOf(ERA_SECTION), heightDp = 1600)
        MetaFolds.matchups = true
        shot(c, out, "lg88b_meta_styles", heightDp = 1600)
        shot(c, out, "lg88b_meta_styles_360", heightDp = 1600, widthDp = 360)
        shot(c, out, "lg88b_meta_styles_dark", dark = true, heightDp = 1600)
        MetaFolds.matchups = false
        // The review of the last close with its big stories, Wrapped's new cards, the chronicle.
        lg.nav.go(LeagueTab.HOME)
        val review = Page.Review(scene.last)
        lg.nav.open(review)
        lg.analytics.loadReview(scene.last, lg.center)
        waitFor("review of season ${scene.last}") { lg.analytics.review(review)?.stories != null }
        shot(c, out, "lg93_review_stories", heightDp = 1800)
        val r = lg.analytics.review(review)!!
        shotOf(out, "lg98e_wrapped_cards", heightDp = 540) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WrappedCardView(r, WrappedCard.GOLDEN)
                WrappedCardView(r, WrappedCard.PREDICT)
            }
        }
        lg.nav.go(LeagueTab.HOME)
        lg.nav.open(Page.Chronicle)
        lg.analytics.loadChronicle()
        waitFor("chronicle") { lg.analytics.chronicle() != null }
        shot(c, out, "lg98d_chronicle_eras", heightDp = 1400)
        // The league page's 🧭 line and its 기자단 links.
        lg.nav.go(LeagueTab.HOME)
        val league = Page.League(scene.league)
        lg.nav.open(league)
        lg.worldPages.loadLeague(scene.league)
        lg.storyPages.loadStatus()
        waitFor("league page") { lg.worldPages.league(league)?.style != null && lg.storyPages.status?.press == true }
        shot(c, out, "lg98g_league_style", heightDp = 1200)
        // In the first weeks: the predictions first (week 3), then the review weeks' vote first (week 1).
        val preseason = PressStory.of(ClubStory.build(StoryWorld.files(StoryWorld.build(untilWeek = 3))).files, ranked = false)
        StoryWorld.restore(c, preseason.files)
        press(Page.Press(), preseason.league)
        shot(c, out, "lg87b_press_preseason", heightDp = 1500)
        val reviewWeeks = PressStory.of(ClubStory.build(StoryWorld.files(StoryWorld.build(untilWeek = 1))).files, ranked = false)
        StoryWorld.restore(c, reviewWeeks.files)
        press(Page.Press(reveal = 3), reviewWeeks.league)
        shot(c, out, "lg87c_press_votes", heightDp = 1400)
        // Thirty seasons of shares and their eras, drawn alone (the story world has six).
        shotOf(out, "lg88c_share_chart_30", heightDp = 380) { ThirtySeasons() }
        lg.nav.go(LeagueTab.HOME)
        StoryWorld.restore(c, StoryWorld.files())
        println("story shots (press, meta): ${(System.currentTimeMillis() - t0) / 1000.0} s (variants built in ${(scene.millis + preseason.millis + reviewWeeks.millis) / 1000.0} s)")
    }
}

/** A share chart of 30 made-up seasons with five eras (two of one family, 춘추전국 between them) and its strip. */
@androidx.compose.runtime.Composable
private fun ThirtySeasons() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val seasons = (1..30).toList()
    val shares = seasons.map { s ->
        val t = s / 30.0
        // 보너스파 waxes and wanes, 족보파 takes over, 요트파 comes back late; the rest share what is left.
        val bonus = 107 + (120 * Math.sin(Math.PI * minOf(1.0, t * 2.2))).roundToInt().coerceAtLeast(0)
        val combo = 285 + (90 * Math.sin(Math.PI * ((t - 0.35).coerceIn(0.0, 0.5) * 2))).roundToInt()
        val yacht = 198 + if (s > 22) (s - 22) * 9 else -s * 2
        val rest = listOf(59, 172, 81, 42, 57)
        val left = 1000 - bonus - combo - yacht
        val scale = left / rest.sum().toDouble()
        val r = rest.map { (it * scale).roundToInt() }.toMutableList()
        r[1] += left - r.sum()
        val elite = listOf(r[0], yacht, combo, bonus, r[1], r[2], r[3], r[4])
        ShareSeason(s, elite, elite)
    }
    val eras = listOf(
        EraView(1, StyleFamily.BONUS, "보너스파의 시대", 5, 11, null),
        EraView(2, null, "춘추전국 시대", 12, 14, null),
        EraView(3, StyleFamily.COMBO, "족보파의 시대", 15, 21, null),
        EraView(4, StyleFamily.BONUS, "두 번째 보너스파의 시대", 23, 26, null),
        EraView(5, StyleFamily.YACHT, "요트파의 시대", 28, null, null),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("스타일 점유율 추이 (30시즌)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        EraStrip(eras, seasons, dark)
        ShareChart(shares, elite = true, dark = dark, modifier = Modifier.fillMaxWidth())
    }
}
