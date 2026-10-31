package yacht.uicheck

import kotlinx.coroutines.runBlocking
import yacht.app.core.AppController
import yacht.app.core.LeagueTab
import yacht.app.core.Screen
import yacht.app.core.UiPrefs
import yacht.app.core.rulesAfterClose
import yacht.engine.league.BoardResult
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieRule
import yacht.engine.league.familyOf
import java.util.Random
import kotlin.math.ceil

/**
 * The story world (UX 15.1): a national league of [PLAYERS] generated players under the default
 * rules (scaled to its size as NationalSmokeTest scales them), played with made-up results (the
 * key's hash makes a player stronger or weaker: no dice) through [SEASONS] closes and into the
 * next season up to week ⌈0.6 R⌉ + 4 of its R league weeks (past the winter checkpoint, with the
 * rumour weeks open; the World Cups of seasons 3 and 7 among them). Its files restore into the
 * real controller as a backup does ([restore]), so the story shots draw the real screen: the bar,
 * the page dispatch, back, the sheets and the pills.
 *
 * Until the engine wires the club world in (P17) the world has none of its own: the stages shoot
 * [variant]s with hand-written lines, decoded by the engine's own codecs at the restore.
 */
internal object StoryWorld {
    const val PLAYERS = 300
    const val SEASONS = 6
    const val SEED = 20261031L

    /** The rules: the national default with leagues and divisions small enough for [players]. */
    fun config(players: Int = PLAYERS): LeagueConfig = LeagueConfig.DEFAULT.copy(generated = players, leagueMin = 20, divisionSize = 16)

    /**
     * The world, played [seasons] seasons from [seed] and into the next one up to week [untilWeek]
     * (null: ⌈0.6 R⌉ + 4), deterministic: the same files every time.
     */
    fun build(players: Int = PLAYERS, seasons: Int = SEASONS, seed: Long = SEED, untilWeek: Int? = null): Built {
        var config = config(players)
        val w = LeagueWorld(Ladder(listOf(Ladder.ME)), config)
        w.players.ensureGenerated(config.generated)
        w.members = members(w)
        w.ladder = Ladder(w.members + Ladder.ME)
        // What the app's roster gives the league: each player's family (the 계열 대항전 draws on
        // them) and name (the news, the reviews and the records are written with it).
        w.family = { k -> w.players[k]?.spec?.let(::familyOf) }
        w.name = { k -> w.players[k]?.name ?: w.players.retired.firstOrNull { it.key == k }?.name ?: w.players.legends.firstOrNull { it.key == k }?.name ?: k }
        w.present = { true }
        w.openFirstSeason(seed)
        repeat(seasons) {
            play(w)
            config = rollover(w, config)
        }
        val p = w.plan!!
        val week = untilWeek ?: (ceil(0.6 * p.calendar.leagueWeeks).toInt() + 4)
        play(w) { it.plan!!.week >= week }
        // Last season's review read weeks ago (the screen would open it over every shot).
        w.archive.reviewShown()
        return Built(w, config)
    }

    /** A built world and the rules the app would have saved with it (the 요트협회's adopted changes in). */
    class Built(val world: LeagueWorld, val config: LeagueConfig)

    /** The default story world's files, built once a run. */
    private val default by lazy { files(build()) }

    /** The story world's files ([build] with the defaults, once a run): the world's, config.txt and ui.txt. */
    fun files(): Map<String, ByteArray> = default

    /** [b]'s files as a backup holds them: every league file, the rules and the UI preferences ([ui]). */
    fun files(b: Built, ui: UiPrefs = UiPrefs()): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, text) in b.world.encodeFiles()) out[name] = text.toByteArray()
        out[CONFIG] = b.config.encode().toByteArray()
        out[UiPrefs.FILE] = ui.encode().toByteArray()
        return out
    }

    /**
     * [files] with text edits before a restore, for the rare states a played world seldom reaches
     * (a club in administration, an arc at its showdown, 30 seasons of eras, a club book without
     * club world lines so that it migrates at load).
     */
    fun variant(files: Map<String, ByteArray>, edit: StoryFiles.() -> Unit): Map<String, ByteArray> {
        val texts = LinkedHashMap<String, String?>()
        for ((name, bytes) in files) texts[name] = bytes.decodeToString()
        StoryFiles(texts).edit()
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, text) in texts) if (text != null) out[name] = text.toByteArray()
        return out
    }

    /**
     * Restores [files] into [c]'s league as a backup is restored (the league stops; a file they
     * lack is removed) and waits until the screen shows them: the league screen's 홈 tab, no page.
     */
    fun restore(c: AppController, files: Map<String, ByteArray>) {
        val lg = c.league
        val season = seasonOf(files)
        // A league read again resets the week the screen shows (after its home is cleared): this
        // mark tells the restored league from the one before it, which a variant shares its season with.
        lg.nav.week = RESTORING
        runBlocking { lg.restore(files) }
        waitFor("the restored league", 120_000) { lg.nav.week == null && lg.view?.clock?.season == season && lg.home.view?.season == season }
        c.screen = Screen.LEAGUE
        lg.nav.go(LeagueTab.HOME)
    }

    /** The season in play in [files] (season.txt's number line), as the controller reads it. */
    fun seasonOf(files: Map<String, ByteArray>): Int =
        files[LeagueWorld.SEASON]?.decodeToString()?.let { Regex("(?m)^number (\\d+) ").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1

    /**
     * The members: every generated player (the hand-made AIs sit out by default; the invited guest
     * too, here: its name and play come from the app's roster, which needs the tables).
     */
    private fun members(w: LeagueWorld): List<String> = w.players.alive.map { it.key }

    /** Plays [w]'s season with made-up results until [stop] or its end. */
    private fun play(w: LeagueWorld, stop: (LeagueWorld) -> Boolean = { false }) {
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("story world: season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} fixtures left")
            w.recordJob(result(f))
            check(++guard < 400_000) { "story world: season ${w.plan?.number} does not end" }
        }
    }

    /**
     * The rollover as the app does it (LeagueController.rollover): the close under the season's own
     * rules, the next season under [config] with the 요트협회's adopted changes; returns those rules.
     */
    private fun rollover(w: LeagueWorld, config: LeagueConfig): LeagueConfig {
        val p = w.plan!!
        val rules = p.closing ?: config
        p.closing = rules
        w.config = rules
        w.ladder.fit()
        val closing = w.closeSeason()
        if (closing.rosterChanged) {
            w.members = members(w)
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        val (next, opening) = rulesAfterClose(config, rules, closing)
        w.config = opening
        w.openSeason()
        return next
    }

    /** A side's made-up strength: its key's hash (0..63). */
    private fun strength(k: String): Int = k.hashCode() and 63

    /**
     * [n] games of [a] against [b]: the stronger side wins more often (from 33% to 67%), the scores
     * spread between 150 and 299, a Yacht or the bonus now and then, all from the fixture's seed.
     */
    private fun games(f: Fixture, a: String, b: String, n: Int, salt: Int): List<GameScore> {
        val rng = Random(f.seed * 31 + salt)
        val p = 1 / (1 + Math.pow(10.0, (strength(b) - strength(a)) / 100.0))
        return List(n) {
            val win = 200 + rng.nextInt(100)
            val lose = 150 + rng.nextInt(win - 150)
            val aWins = rng.nextDouble() < p
            GameScore(
                if (aWins) win else lose, if (aWins) lose else win,
                aYacht = rng.nextInt(4) == 0, bYacht = rng.nextInt(4) == 0, aBonus = rng.nextInt(3) == 0, bBonus = rng.nextInt(3) == 0,
            )
        }
    }

    private fun result(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f, f.a, f.b, 2, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f, f.a, f.b, 2 * f.legs, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).mapIndexed { i, (x, y) -> BoardResult(x, y, games(f, x, y, 2, i + 1), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    /** The rules' file beside the league's (LeagueController's). */
    private const val CONFIG = "config.txt"

    /** `nav.week` while a restore is read (no week has this number). */
    private const val RESTORING = -1
}

/**
 * The story world's text files while a [StoryWorld.variant] edits them: any file by its name (null:
 * left out of the restore, so it is removed), and the two the club world lives in.
 */
internal class StoryFiles(private val texts: MutableMap<String, String?>) {
    operator fun get(name: String): String? = texts[name]

    operator fun set(name: String, text: String?) {
        texts[name] = text
    }

    /** clubs.txt: the club book (`clubs 2`, and the club world's lines once the engine writes them). */
    var clubs: String?
        get() = this[LeagueWorld.CLUBS]
        set(v) {
            this[LeagueWorld.CLUBS] = v
        }

    /** world.txt: the world book (meta, eras, media, world events, the encyclopedia's finals); null before the engine writes one. */
    var world: String?
        get() = this[WORLD]
        set(v) {
            this[WORLD] = v
        }

    companion object {
        /** The world book's file (the engine's LeagueWorld.WORLD once it has one). */
        const val WORLD = "world.txt"
    }
}
