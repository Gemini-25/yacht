package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.LeagueTab
import yacht.app.core.MarketPages
import yacht.app.core.MarketTab
import yacht.app.core.Page
import yacht.app.core.drawnRatings
import yacht.app.ui.MARKET_SECTION
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubRanks
import yacht.engine.league.ClubTable
import yacht.engine.league.ClubWorld
import yacht.engine.league.CoachInput
import yacht.engine.league.CoachMarket
import yacht.engine.league.Comp
import yacht.engine.league.Derbies
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Market
import yacht.engine.league.MarketInput
import yacht.engine.league.MatchRef
import yacht.engine.league.PlayerTraits
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Stories
import yacht.engine.league.StyleFamily
import yacht.engine.league.valuer
import java.io.File
import java.util.Locale

/**
 * P16's shots (UX 15.3: lg85-lg85e, lg96; lg85f added by its review) on [MarketScene]'s variant of the
 * story world, restored into the real screen: 이적 센터's four tabs (이적설 with the first-screen check of
 * its first story, 이적 내역 with ☀ 다시 보기 stopped half-way and as the whole list, 최고 이적료, 감독 with
 * the hot seat), the centre opened before the rumour weeks (it lands on 이적 내역) and a rival match's
 * report. Restores the story world as it was before it returns.
 */
internal object ShotsMarket : LeagueShots {
    override fun story(c: AppController, out: File) {
        val t0 = System.currentTimeMillis()
        val scene = MarketScene.default
        StoryWorld.restore(c, scene.files)
        val lg = c.league
        fun market(tab: MarketTab?, replay: Int = -1) {
            lg.nav.go(LeagueTab.HOME)
            lg.nav.open(Page.Market(tab, replay))
            lg.marketPages.load()
            waitFor("이적 센터") { lg.marketPages.view?.centre == true }
        }
        market(null)
        check(MarketPages.tabOf(Page.Market(), lg.marketPages.view!!) == MarketTab.LIVE) { "이적 센터 in the rumour weeks opens on 이적설" }
        firstScreenCheck(c, out, "lg85_market_live", listOf(MARKET_SECTION), heightDp = 1300)
        shot(c, out, "lg85_market_live_360", heightDp = 1300, widthDp = 360)
        // The replay stopped a little past its second round's start (the newest move highlighted).
        val view = lg.marketPages.view!!
        val at = (view.rounds.getOrNull(2)?.second ?: (view.window.size / 2)).coerceIn(1, maxOf(1, view.window.size)) + 1
        lg.marketPages.replayPaused = true
        market(MarketTab.MOVES, replay = minOf(at, view.window.size))
        shot(c, out, "lg85b_market_moves", heightDp = 1300, settleMs = 300)
        lg.marketPages.replayPaused = false
        // The whole list: the winter's moves under their own heading, then last summer's with its numbers and marks.
        market(MarketTab.MOVES)
        check(lg.marketPages.view!!.let { v -> MarketPages.blocks(v).count { it.winter } == 1 && v.winterMoves.isNotEmpty() }) { "이적 내역 has one winter heading" }
        shot(c, out, "lg85f_market_history", heightDp = 1300)
        market(MarketTab.RECORDS)
        shot(c, out, "lg85c_market_records", heightDp = 1300)
        market(MarketTab.COACHES)
        shot(c, out, "lg85d_market_coaches", heightDp = 1300)
        shot(c, out, "lg85d_market_coaches_360", heightDp = 1300, widthDp = 360)
        // A rival match's report (UX 6.6): the rival line under the score.
        lg.nav.go(LeagueTab.HOME)
        val report = Page.Match(scene.rivalMatch)
        lg.nav.open(report)
        lg.center.loadReport(report)
        waitFor("rival match report", 120_000) { lg.center.report(report)?.let { it.rival != null && !it.loading } == true }
        shot(c, out, "lg96_report_rival", heightDp = 900)
        // Before the rumour weeks: the centre lands on 이적 내역.
        StoryWorld.restore(c, MarketScene.quiet)
        market(null)
        check(MarketPages.tabOf(Page.Market(), lg.marketPages.view!!) == MarketTab.MOVES) { "이적 센터 before the rumour weeks opens on 이적 내역" }
        shot(c, out, "lg85e_market_quiet", heightDp = 850)
        lg.nav.go(LeagueTab.HOME)
        StoryWorld.restore(c, StoryWorld.files())
        println("market shots: ${(System.currentTimeMillis() - t0) / 1000.0} s (variant built in ${scene.millis / 1000.0} s)")
    }
}

/**
 * The transfer market's variant of the story world (P16's shots and MarketPagesTest; until P17 wires the
 * market into the league): [ClubScene]'s club world with the engine's own market run on it, in the
 * order the league will run it: last summer's window ([Market.offseason] after a copy of the season in
 * play numbered as the season before, its talks given), then every closed week of the season in play
 * ([Stories.close]: the weekly ticks and, at its week, the winter checkpoint, with two boards out of
 * patience and two winter talks given). Text edits on top add the rare states: open stories when the
 * small world's ticks made few, and coaches under pressure for the hot seat. [rivalMatch] is a played
 * rival match of the season in play.
 */
internal class MarketScene private constructor(val files: Map<String, ByteArray>, val millis: Long, val rivalMatch: String) {
    val world: LeagueWorld by lazy { ClubStory.decode(files) }
    val book: ClubBook get() = world.clubs!!

    companion object {
        /** The default story world's scene, built once a run. */
        val default: MarketScene by lazy { of(ClubScene.default.files) }

        /**
         * The story world at week [QUIET_WEEK] (before the rumour weeks) with a club world ([ClubStory]):
         * the files of lg85e, built once a run.
         */
        val quiet: Map<String, ByteArray> by lazy {
            val t0 = System.currentTimeMillis()
            val files = ClubStory.build(StoryWorld.files(StoryWorld.build(untilWeek = QUIET_WEEK))).files
            println("market scene: the quiet world built in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            files
        }

        /** The week the quiet scene stops at. */
        const val QUIET_WEEK = 10

        fun of(base: Map<String, ByteArray>): MarketScene {
            val t0 = System.currentTimeMillis()
            val first = ClubStory.decode(base)
            val p0 = first.plan ?: error("market scene: no season in play")
            val b0 = first.clubs ?: error("market scene: no club book")
            val s = p0.number
            val w = ClubStory.decode(StoryWorld.variant(base) { clubs = given(first, b0, p0, clubs!!) })
            val p = w.plan!!
            val b = w.clubs!!
            val input = input(w, p)
            // Last summer's window: after the season before, on a copy of the season in play numbered as it.
            val seasonText = base[LeagueWorld.SEASON]!!.decodeToString()
            val keys = w.players.alive.map { it.key } + Ladder.ME
            val before = SeasonPlan.decode(seasonText.replace(Regex("(?m)^number $s "), "number ${s - 1} "), keys, LeagueWorld.CODECS)
            before.ranking = p.ranking
            val tables = ClubTable.all(before.league, b, w.honours.all.filter { it.season == s - 1 })
            val traits: (String) -> PlayerTraits? = { k -> w.players[k]?.let { PlayerTraits.of(it.spec.play, it.identity) } }
            val report = Market.offseason(b, before, MarketInput(input, tables, traits, value(w, p, b), w.archive::lines))
            ClubRanks.record(b, s - 1, value(w, p, b, drawnRatings(w, p)), report.leagueRecords)
            // The season in play's closed weeks: the ticks and the winter checkpoint.
            Stories.close(b, p, input, 1, p.week)
            val text = pressed(w, p, more(w, b, p, b.encode()))
            val files = StoryWorld.variant(base) { clubs = text }
            val made = ClubStory.decode(files)
            return MarketScene(files, System.currentTimeMillis() - t0, rivalMatch(made))
        }

        /** The in-season input of [w]'s season in play [p] (the ages of the season in play; no transfers abroad, so every move stays in its league). */
        private fun input(w: LeagueWorld, p: SeasonPlan): ClubInput {
            val s = p.number
            val drawn = drawnRatings(w, p)
            val names = (w.players.alive.map { it.name } + w.players.retired.map { it.name }).toSet()
            val b = w.clubs!!
            return ClubInput(
                w.members, { w.ladder.rating(it) }, { k -> w.players[k]?.identity?.nation }, w.config.copy(transfers = 0), w.honours.all, { k -> w.players[k]?.age(s) },
                ranking = p.ranking, economy = LeagueEconomy.of(p, drawn), drawn = drawn, coachInput = CoachInput(names = names, value = value(w, p, b)),
                families = { k -> StyleFamily.of(w.players[k]?.spec?.play) }, lines = w.archive::lines,
                hometown = { k -> w.players[k]?.hometown }, potential = { k -> w.players[k]?.identity?.potential }, overall = { k -> w.players[k]?.identity?.abilities?.overall },
                traits = { k -> w.players[k]?.let { PlayerTraits.of(it.spec.play, it.identity) } },
            )
        }

        private fun value(w: LeagueWorld, p: SeasonPlan, b: ClubBook, rating: (String) -> Double = { w.ladder.rating(it) }): (String) -> Int =
            b.valuer(rating, { k -> w.players[k]?.age(p.number) }, { k -> w.players[k]?.identity?.potential }, { k -> w.players[k]?.identity?.abilities?.overall })

        /** The clubs of nations with two clubs or more, the nations with the most members first. */
        private fun nations(b: ClubBook): List<List<yacht.engine.league.Club>> =
            b.clubs.groupBy { it.nation }.values.filter { it.size >= 2 }.sortedWith(compareByDescending<List<yacht.engine.league.Club>> { cs -> cs.sumOf { it.squad.size } }.thenBy { it.first().nation })

        /**
         * clubs.txt [text] with what the market is given before it runs: four talks of the season before
         * (last summer's window decides them), two winter talks of the season in play (its checkpoint
         * decides them) and two boards out of patience (their coaches go at the checkpoint).
         */
        private fun given(w: LeagueWorld, b: ClubBook, p: SeasonPlan, text: String): String {
            val s = p.number
            val ww = ClubWorld.winterWeek(p)
            val r = { k: String -> w.ladder.rating(k).takeIf { !it.isNaN() } ?: 0.0 }
            val value = value(w, p, b)
            val out = ArrayList<String>()
            val nations = nations(b)
            var id = 801
            for ((i, cs) in nations.withIndex()) {
                if (i >= 6) break
                val seller = cs.maxBy { c -> c.squad.maxOfOrNull(r) ?: 0.0 }
                val buyer = buyerOf(b, cs, seller.id)
                val captain = b.life(seller.id)?.captain
                val key = seller.squad.filter { it != captain }.sortedByDescending(r).getOrNull(i % 2) ?: continue
                val fee = maxOf(10, value(key))
                out += if (i < 4) "ms ${id++} ${s - 1} T ${esc(key)} ${buyer.id} ${seller.id} $fee ${p.leagueWeeks - 8} ${p.leagueWeeks - 5} - 0 -"
                else "ms ${id++} $s T ${esc(key)} ${buyer.id} ${seller.id} $fee ${ww - 2} ${ww - 1} - 1 -"
            }
            var t = text
            // Two boards out of patience: the coaches of two clubs well placed to be sacked at the checkpoint.
            val coached = b.clubs.filter { b.coachOf(it.id) != null }.sortedWith(compareBy<yacht.engine.league.Club> { it.balance }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            for (c in coached.drop(2).take(2)) t = line(t, "cl", c.id) { f -> f[7] = "300" }
            return t.trimEnd('\n') + "\n" + out.joinToString("\n") + "\n"
        }

        /** The richest club of [cs] (a nation's) but [seller], one that is not its rival when there is one. */
        private fun buyerOf(b: ClubBook, cs: List<yacht.engine.league.Club>, seller: String): yacht.engine.league.Club {
            val others = cs.filter { it.id != seller }
            return others.filter { !b.isRival(seller, it.id) }.maxByOrNull { it.balance } ?: others.maxBy { it.balance }
        }

        /** [text] with open stories of the season in play added up to [LIVE] (the small world's ticks start few), rumours and talks. */
        private fun more(w: LeagueWorld, b: ClubBook, p: SeasonPlan, text: String): String {
            val s = p.number
            val open = Stories.open(b, s)
            if (open.size >= LIVE) return text
            val busy = open.mapTo(HashSet()) { it.key }
            val r = { k: String -> w.ladder.rating(k).takeIf { !it.isNaN() } ?: 0.0 }
            val value = value(w, p, b)
            val out = ArrayList<String>()
            var id = 851
            val week = p.week - 1
            for (cs in nations(b)) {
                for (seller in cs.sortedByDescending { c -> c.squad.maxOfOrNull(r) ?: 0.0 }) {
                    if (open.size + out.size >= LIVE) break
                    val buyer = buyerOf(b, cs, seller.id)
                    val key = seller.squad.filter { it !in busy }.maxByOrNull(r) ?: continue
                    busy += key
                    val talks = out.size % 2 == 0
                    out += "ms ${id++} $s ${if (talks) "T" else "R"} ${esc(key)} ${buyer.id} ${seller.id} ${maxOf(10, value(key))} ${week - 4 + out.size % 3} ${if (talks) week - 1 else 0} - 0 -"
                }
            }
            return text.trimEnd('\n') + "\n" + out.joinToString("\n") + "\n"
        }

        /**
         * [text] with the coaches of the clubs of the best-ranked leagues furthest below their boards'
         * targets under pressure: enough for their next checkpoint to leave each a share of its sacking
         * threshold ([SHARES], the hot seat's 0.5 and up); coaches at or above their targets under none.
         */
        private fun pressed(w: LeagueWorld, p: SeasonPlan, text: String): String {
            val b = ClubBook.decode(text)
            val drawn = drawnRatings(w, p)
            val top = Derbies.topLeagues(p, drawn).take(MarketPages.HOT_LEAGUES).toSet()
            val clubs = b.clubs.filter { c -> c.squad.any { k -> p.league.leagueOf(k)?.id in top } }.map { it.id }
            val outlooks = CoachMarket.outlooks(p, b, p.week - 1, drawn, clubs, w.honours.of(p.number))
            // Clubs below their boards' targets, the furthest first.
            val worst = outlooks.entries.filter { it.value.place > it.value.target }
                .sortedWith(compareBy<Map.Entry<String, yacht.engine.league.BoardOutlook>> { it.value.gap }.thenComparing({ it.key }, ClubBook.ID_ORDER)).take(SHARES.size)
            var t = text
            // A coach at or above its board's target carries no pressure here (the club scene's shaky seat is P8's
            // shot; a pressure kept from the winter would read as "경질 직전" at second place).
            for ((club, o) in outlooks) if (o.place <= o.target && o.pressure > 0) t = line(t, "cl", club) { f -> f[7] = "0" }
            for ((i, e) in worst.withIndex()) {
                val o = e.value
                val mood = b.life(e.key)?.mood ?: 50
                // The pressure (tenths) whose next checkpoint leaves the share asked of the threshold.
                var lo = 0.0
                var hi = 100.0
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (CoachMarket.pressureAfter(mid, o.gap, mood) < SHARES[i] * o.threshold) lo = mid else hi = mid
                }
                t = line(t, "cl", e.key) { f -> f[7] = Math.round(hi * 10).toString() }
            }
            return t
        }

        /** A played rival match of the season in play, the report's: the latest one (its pair met before it), then the lower id. */
        private fun rivalMatch(w: LeagueWorld): String {
            val p = w.plan!!
            val s = p.league
            val rivals = Derbies.matches(w.clubs!!, drawnRatings(w, p))
            val id = (0 until s.fixturesTotal).filter { id -> s.fixture(id).let { f -> s.result(id) != null && rivals.name(s, f.a, f.b) != null } }
                .maxWithOrNull(compareBy<Int> { s.fixture(it).matchday }.thenByDescending { it })
                ?: error("market scene: no played rival match in season ${p.number}")
            return MatchRef(p.number, Comp.LEAGUE, id).encode()
        }

        /** Open stories the LIVE shot shows at least. */
        private const val LIVE = 6

        /** The hot seat's coaches and the share of its threshold each one's next checkpoint is to leave. */
        private val SHARES = listOf(1.05, 0.9, 0.78, 0.64, 0.55)

        /** A field as the club world's lines write it (the codec's escapes of its separators). */
        private fun esc(s: String): String = buildString { for (ch in s) if (ch in "% ,;:|\n\r\t\u001f") append('%').append(String.format(Locale.ROOT, "%02X", ch.code)) else append(ch) }

        /** [text] with the `prefix id …` line's fields changed by [f] (the prefix and id are fields 0 and 1). */
        private fun line(text: String, prefix: String, id: String, f: (MutableList<String>) -> Unit): String =
            text.lines().joinToString("\n") { l ->
                if (!l.startsWith("$prefix $id ")) return@joinToString l
                l.split(' ').toMutableList().also(f).joinToString(" ")
            }
    }
}
