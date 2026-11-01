package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.ClubRankKind
import yacht.app.core.ClubTab
import yacht.app.core.LeagueTab
import yacht.app.core.Page
import yacht.app.core.PressureLevel
import yacht.app.core.Sheet
import yacht.app.core.drawnRatings
import yacht.app.core.pressureOf
import yacht.app.ui.COACH_SECTION
import yacht.app.ui.FINANCE_SECTION
import yacht.engine.league.Club
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubHistory
import yacht.engine.league.ClubIdentity
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubRanks
import yacht.engine.league.ClubTable
import yacht.engine.league.CoachInput
import yacht.engine.league.CoachMarket
import yacht.engine.league.Derbies
import yacht.engine.league.Gender
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueWorld
import yacht.engine.league.LifeInput
import yacht.engine.league.PlayerBook
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StyleFamily
import yacht.engine.league.valuer
import java.io.File
import java.util.Locale

/**
 * The story world with a club world (P8's variants, until P17 migrates every league at its load):
 * the story world's files read back as the app reads them, the club world started on its club book
 * by the engine's own steps in the order of ENG 8.2 (money, identity, squads and history, coaches,
 * rivals), [SEASONS] seasons of accounts and club life on copies of the season in play numbered
 * before it, the club rankings of the last two closes, and clubs.txt written again by the engine's
 * codec. [Made.edit] adds text edits on top for the rare states (a club in administration, a
 * retired ex-player coach, legends of thirty seasons).
 */
internal object ClubStory {
    /** Seasons of accounts and club life before the season in play (the money history keeps 3 below 500 members). */
    const val SEASONS = 3

    /** A club world on the story world: its [world] (as decoded and started), and its [files] (clubs.txt the engine's). */
    class Made(val world: LeagueWorld, val files: Map<String, ByteArray>) {
        val book: ClubBook get() = world.clubs!!

        /** [files] with clubs.txt edited by [edit] (its lines, as the codec reads them). */
        fun edit(edit: (String) -> String): Map<String, ByteArray> = StoryWorld.variant(files) { clubs = clubs?.let(edit) }
    }

    /** [base] (the story world's files) with a club world. */
    fun build(base: Map<String, ByteArray> = StoryWorld.files()): Made {
        val w = decode(base)
        val p = w.plan ?: error("club story: no season in play")
        val b = w.clubs?.takeIf { it.format == ClubBook.AFFILIATION } ?: error("club story: no national club book")
        val s = p.number
        check(s > SEASONS + 1) { "club story: season $s is too early for $SEASONS seasons of accounts" }
        val rating: (String) -> Double = { w.ladder.rating(it) }
        val drawn = drawnRatings(w, p)
        val age: (String) -> Int? = { k -> w.players[k]?.age(s) }
        val value = b.valuer(rating, age, { k -> w.players[k]?.identity?.potential }, { k -> w.players[k]?.identity?.abilities?.overall })
        // The closes' rankings valued members at the ratings of then: the season's start ratings for the last one.
        val closeValue = b.valuer(drawn, age, { k -> w.players[k]?.identity?.potential }, { k -> w.players[k]?.identity?.abilities?.overall })
        val economy = LeagueEconomy.of(p, drawn)
        val honours = w.honours.all
        val names = (w.players.alive.map { it.name } + w.players.retired.map { it.name }).toSet()
        val input = ClubInput(
            w.members, rating, { k -> w.players[k]?.identity?.nation }, w.config, honours, age,
            economy = economy, drawn = drawn, coachInput = CoachInput(names = names, value = value),
            families = { k -> StyleFamily.of(w.players[k]?.spec?.play) },
        )
        // The money as a migration starts it, as of the season before the accounts below.
        val first = s - SEASONS - 1
        val order = b.places(s - 1).associate { it.league to it.clubs }
        val start = ClubEconomy.migrate(b, first, economy, order, value, p)
        ClubIdentity.start(b)
        ClubHistory.start(b, s, w.archive.lineKeys, w.archive::lines, honours, rating, { k -> s + Math.floorMod(k.hashCode(), 3) }, { k -> start.wages[k] ?: 1 })
        val leagueOf = b.clubs.associate { c -> c.id to (c.squad.firstNotNullOfOrNull { p.league.leagueOf(it)?.id } ?: c.nation) }
        CoachMarket.start(b, s, input, CoachInput.legends(w.players, s) { k -> w.honours.majorTitles(k) }, leagueOf = { leagueOf[it] ?: "" })
        for (x in maxOf(1, s - 20) until s) Derbies.recompute(b, x, honours, start = s)
        // Seasons of accounts and club life, each on a copy of the season in play numbered as it.
        val seasonText = base[LeagueWorld.SEASON]!!.decodeToString()
        val keys = w.players.alive.map { it.key } + Ladder.ME
        for (x in first + 1 until s) {
            val copy = SeasonPlan.decode(seasonText.replace(Regex("(?m)^number $s "), "number $x "), keys, LeagueWorld.CODECS)
            val tables = ClubTable.all(copy.league, b, honours.filter { it.season == x })
            ClubEconomy.accounts(b, copy, input, tables)
            ClubEconomy.life(b, copy, input, tables, LifeInput(target = CoachMarket.targets(copy, b, drawn)::get, value = value))
            if (x >= s - 2) ClubRanks.record(b, x, if (x == s - 1) closeValue else value)
        }
        val out = StoryWorld.variant(base) { clubs = b.encode() }
        return Made(decode(out), out)
    }

    /** [files] read back as the app reads a league (the roster first, then the world with its keys). */
    fun decode(files: Map<String, ByteArray>): LeagueWorld {
        val texts = LeagueWorld.FILES.associateWith { files[it]?.decodeToString() }
        val players = texts[LeagueWorld.ROSTER]?.let { PlayerBook.decode(it) } ?: error("club story: no roster")
        val config = files["config.txt"]?.decodeToString()?.let { LeagueConfig.decode(it) } ?: StoryWorld.config()
        val w = LeagueWorld.decode(texts, players.alive.map { it.key } + Ladder.ME, emptyList(), config)
        w.players = players
        w.members = players.alive.map { it.key }
        return w
    }

}

/**
 * P8's shots (UX 15.3: lg80-lg84b, lg86, lg97) on [ClubScene]'s variant of the story world, restored
 * into the real screen: a club's four tabs (with the first-screen checks of 개요's 👔 and 구단's 💰),
 * a club in administration, its history, an employed coach on a shaky seat and a retired ex-player
 * coach, the club rankings and a player's 💼 line with its sheet. Restores the story world as it
 * was before it returns.
 */
internal object ShotsClubs : LeagueShots {
    override fun story(c: AppController, out: File) {
        val t0 = System.currentTimeMillis()
        val scene = ClubScene.default
        StoryWorld.restore(c, scene.files)
        val lg = c.league
        fun club(id: String, tab: ClubTab) {
            lg.nav.go(LeagueTab.HOME)
            lg.nav.open(Page.Club(id, tab))
            lg.worldPages.loadClub(id)
            waitFor("club page $id") { lg.worldPages.club(Page.Club(id))?.life != null }
        }
        club(scene.club, ClubTab.OVERVIEW)
        firstScreenCheck(c, out, "lg80_club_overview", listOf(COACH_SECTION), heightDp = 1400)
        shot(c, out, "lg80_club_overview_360", heightDp = 1400, widthDp = 360)
        shot(c, out, "lg80_club_overview_dark", dark = true, heightDp = 1400)
        club(scene.club, ClubTab.SQUAD)
        shot(c, out, "lg81_club_squad", heightDp = 1400)
        club(scene.business, ClubTab.BUSINESS)
        firstScreenCheck(c, out, "lg82_club_business", listOf(FINANCE_SECTION), heightDp = 1500)
        club(scene.trouble, ClubTab.BUSINESS)
        shot(c, out, "lg82b_club_trouble", heightDp = 1100)
        shot(c, out, "lg82b_club_trouble_dark", dark = true, heightDp = 1100)
        club(scene.club, ClubTab.HISTORY)
        shot(c, out, "lg83_club_history", heightDp = 1500)
        fun coach(id: String) {
            lg.nav.go(LeagueTab.HOME)
            lg.nav.open(Page.Coach(id))
            lg.worldPages.loadCoach(id)
            waitFor("coach page $id") { lg.worldPages.coach(Page.Coach(id)) != null }
        }
        coach(scene.coach)
        shot(c, out, "lg84_coach", heightDp = 1300)
        coach(ClubScene.RETIRED_COACH)
        shot(c, out, "lg84b_coach_explayer", heightDp = 1100)
        fun ranking(kind: ClubRankKind) {
            lg.nav.go(LeagueTab.HOME)
            lg.nav.open(Page.ClubRanking(kind))
            lg.worldPages.loadClubRanking(null)
            waitFor("club ranking") { lg.worldPages.clubRanking(Page.ClubRanking(kind)) != null }
        }
        ranking(ClubRankKind.VALUE)
        shot(c, out, "lg86_club_ranking", heightDp = 1300)
        shot(c, out, "lg86_club_ranking_360", heightDp = 1300, widthDp = 360)
        ranking(ClubRankKind.HEALTH)
        shot(c, out, "lg86b_club_ranking_health", heightDp = 1300)
        lg.nav.go(LeagueTab.HOME)
        lg.nav.open(Page.Player(scene.captain))
        lg.analytics.loadPlayer(scene.captain, Page.Player(scene.captain).scope)
        lg.worldPages.loadPlayerClub(scene.captain)
        waitFor("player page") { lg.analytics.player(Page.Player(scene.captain)) != null && lg.worldPages.playerClub(scene.captain)?.terms != null }
        lg.nav.sheet = Sheet.LIST
        shot(c, out, "lg97_player_contract", heightDp = 1200)
        shot(c, out, "lg97_player_contract_360", heightDp = 1200, widthDp = 360)
        lg.nav.sheet = Sheet.NONE
        lg.nav.go(LeagueTab.HOME)
        StoryWorld.restore(c, StoryWorld.files())
        println("club shots: ${(System.currentTimeMillis() - t0) / 1000.0} s (variant built in ${scene.millis / 1000.0} s)")
    }
}

/**
 * The club world variant P8's shots and ClubPagesTest read ([ClubStory] and text edits on its
 * clubs.txt for what three seasons of club life rarely bring): [club] (the most rivals, then the
 * most fans) with two open stories (it buys a star of another nation's club and its captain is
 * wanted), legends, retired numbers and big transfers; [business], a club building at its stadium;
 * [trouble], a club in 법정관리; [coach], [club]'s coach on a shaky seat (흔들려요); and a retired
 * ex-player coach ([RETIRED_COACH]).
 */
internal class ClubScene private constructor(
    val made: ClubStory.Made, val files: Map<String, ByteArray>, val millis: Long,
    val club: String, val business: String, val trouble: String,
) {
    val world: LeagueWorld get() = made.world
    val book: ClubBook get() = made.book
    val captain: String get() = book.life(club)?.captain ?: error("club scene: $club has no captain")
    val coach: String get() = book.coachOf(club)?.id ?: error("club scene: $club has no coach")

    private class Picks(val club: String, val business: String, val trouble: String)

    companion object {
        /** The retired ex-player coach the scene adds. */
        const val RETIRED_COACH = "k900"

        /** The default story world's scene, built once a run. */
        val default: ClubScene by lazy { of(StoryWorld.files()) }

        fun of(base: Map<String, ByteArray>): ClubScene {
            val t0 = System.currentTimeMillis()
            val made = ClubStory.build(base)
            val picks = pick(made)
            val text = made.files[LeagueWorld.CLUBS]!!.decodeToString()
            val edited = shaky(made, picks.club, edit(made, picks, text))
            val files = StoryWorld.variant(made.files) { clubs = edited }
            return ClubScene(ClubStory.Made(ClubStory.decode(files), files), files, System.currentTimeMillis() - t0, picks.club, picks.business, picks.trouble)
        }

        private fun pick(m: ClubStory.Made): Picks {
            val b = m.book
            val clubs = b.clubs.filter { b.coachOf(it.id) != null && b.life(it.id)?.captain != null }
            val club = clubs.maxWith(compareBy<Club>({ b.rivals(it.id).size }, { b.life(it.id)?.fans ?: 0 }).thenComparing({ it.id }, ClubBook.ID_ORDER.reversed())).id
            val business = clubs.filter { b.life(it.id)?.stadium?.project != null }.maxByOrNull { b.life(it.id)?.fans ?: 0 }?.id ?: club
            val trouble = clubs.filter { it.id != club && it.id != business }.minWith(compareBy<Club> { it.balance }.thenComparing({ it.id }, ClubBook.ID_ORDER)).id
            return Picks(club, business, trouble)
        }

        /** A field as the club world's lines write it (the codec's escapes of its separators). */
        private fun esc(s: String): String = buildString { for (ch in s) if (ch in "% ,;:|\n\r\t\u001f") append('%').append(String.format(Locale.ROOT, "%02X", ch.code)) else append(ch) }

        /** [text] with the `prefix id …` line's fields changed by [f] (the prefix and id are fields 0 and 1). */
        private fun line(text: String, prefix: String, id: String, f: (MutableList<String>) -> Unit): String =
            text.lines().joinToString("\n") { l ->
                if (!l.startsWith("$prefix $id ")) return@joinToString l
                l.split(' ').toMutableList().also(f).joinToString(" ")
            }

        private fun edit(m: ClubStory.Made, p: Picks, text: String): String {
            val w = m.world
            val b = m.book
            val s = w.plan!!.number
            val week = w.plan!!.week
            var t = text
            // The trouble club: deep in debt, in administration since this season (10% off its points).
            val income = maxOf(10, ClubEconomy.income(b, p.trouble))
            val debt = -income * 6 / 5
            t = line(t, "cl", p.trouble) { f ->
                f[4] = debt.toString()
                f[6] = "18"
                f[10] = "1"
                f[11] = "1"
                f[12] = "10"
            }
            // Its last accounts: a signing it could not afford (선수 영입) took it there; its money history agrees.
            // (`ac id season tv prize intl sponsor matchday owner solidarity sales wages coach upkeep building buys balance`)
            t = line(t, "ac", p.trouble) { f ->
                f[15] = (f[15].toInt() + f[16].toInt() - debt).toString()
                f[16] = debt.toString()
            }
            t = line(t, "ah", p.trouble) { f ->
                val years = f[2].split(';').toMutableList()
                years[0] = years[0].split(':').toMutableList().also { it[1] = debt.toString() }.joinToString(":")
                f[2] = years.joinToString(";")
            }
            val extra = ArrayList<String>()
            // Two open stories: the club wants another nation's star, and another club wants its captain.
            val captain = b.life(p.club)?.captain!!
            val star = b.clubs.filter { it.nation != b.club(p.club)!!.nation }.flatMap { it.squad }.maxBy { w.ladder.rating(it).takeIf { r -> !r.isNaN() } ?: 0.0 }
            val buyer = b.clubs.filter { it.id != p.club }.maxBy { b.life(it.id)?.balance ?: 0 }.id
            extra += "ms 901 $s T ${esc(star)} ${p.club} ${b.clubOf(star)} 185 ${week - 6} ${week - 2} - 0 -"
            extra += "ms 902 $s R ${esc(captain)} $buyer ${p.club} 120 ${week - 1} 0 - 0 -"
            // Its legends: the players who played longest for it (archive lines), the two longest with their numbers retired.
            val past = w.archive.lineKeys.filter { k -> b.clubOf(k) != p.club && w.archive.lines(k).any { it.club == p.club } }
                .sortedWith(compareByDescending<String> { k -> w.archive.lines(k).count { it.club == p.club } }.thenBy { it }).take(5)
            if (past.isNotEmpty()) {
                extra += "lg ${p.club} " + past.mapIndexed { i, k ->
                    val at = w.archive.lines(k).filter { it.club == p.club }
                    "${esc(k)}:${100 - 10 * i}:${at.size}:${w.honours.majorTitles(k)}:${if (w.archive.lines(k).all { it.club == p.club }) 1 else 0}:${at.last().season}:${7 + 3 * i}"
                }.joinToString(",")
                extra += "rn ${p.club} " + past.take(2).mapIndexed { i, k -> "${7 + 3 * i}:${esc(k)}:${w.archive.lines(k).last { it.club == p.club }.season}" }.joinToString(",")
            }
            // Its biggest transfers (tx): members it bought from its rivals (or another club) and one it sold, the last a league record.
            val others = (b.rivals(p.club).map { it.club } + b.clubs.map { it.id }).filter { it != p.club }.distinct()
            val bought = b.members(p.club).filter { it != captain }.take(3)
            for ((i, k) in bought.withIndex()) {
                extra += "tx ${s - 1 - i} T ${esc(k)} ${others[i % others.size]} ${p.club} ${60 + 35 * i} ${if (i == 2) "L" else "C"}"
            }
            b.members(others.first()).firstOrNull()?.let { k -> extra += "tx ${s - 2} T ${esc(k)} ${p.club} ${others.first()} 75 V" }
            // A retired coach who was a player (a legend of the roster's when there is one).
            val legend = w.players.legends.maxByOrNull { it.record.titles }?.record ?: w.players.retired.first()
            extra += "kr $RETIRED_COACH ${esc(legend.name)} ${legend.nation} ${(legend.gender ?: Gender.MALE).code} ${s - 1 - 64} ${s - 1} C 71 ${esc(legend.key)} CT:2,L0:5,COY:1,clubs:4,seasons:17,sacked:1,longest:8"
            return t.trimEnd('\n') + "\n" + extra.joinToString("\n") + "\n"
        }

        /**
         * [text] with club [club]'s coach under pressure enough for the board's outlook to read 흔들려요
         * (the next checkpoint would leave half to three quarters of the threshold): the `cl` line's
         * pressure (tenths) raised step by step until it does.
         */
        private fun shaky(m: ClubStory.Made, club: String, text: String): String {
            val w = m.world
            val p = w.plan!!
            for (tenths in 0..400 step 5) {
                val t = line(text, "cl", club) { f -> f[7] = tenths.toString() }
                val b = ClubBook.decode(t)
                val o = CoachMarket.outlook(p, b, club, p.week - 1, drawnRatings(w, p), w.honours.of(p.number)) ?: return t
                if (pressureOf(o) == PressureLevel.SHAKY) return t
            }
            return text
        }
    }
}
