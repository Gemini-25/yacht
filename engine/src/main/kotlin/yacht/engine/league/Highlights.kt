package yacht.engine.league

import yacht.engine.RuleSet
import java.util.Locale
import kotlin.math.abs

/**
 * Text fields of the media files (highlights.txt, news.txt): each value is one space-separated
 * field, so a space becomes '_' and '%', '_', ',' and line breaks are percent-escaped; an empty
 * value is "-". Lists are comma-joined escaped values.
 */
internal object MediaText {
    fun esc(s: String): String {
        if (s.isEmpty()) return "-"
        if (s == "-") return "%2D"
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '%' -> b.append("%25")
                '_' -> b.append("%5F")
                ' ' -> b.append('_')
                ',' -> b.append("%2C")
                '\n' -> b.append("%0A")
                '\r' -> b.append("%0D")
                '\t' -> b.append("%09")
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    fun unesc(s: String): String {
        if (s == "-") return ""
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val code = if (ch == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                code != null -> {
                    b.append(code.toChar())
                    i += 3
                    continue
                }
                ch == '_' -> b.append(' ')
                else -> b.append(ch)
            }
            i++
        }
        return b.toString()
    }

    fun list(xs: List<String>): String = if (xs.isEmpty()) "-" else xs.joinToString(",") { esc(it) }

    fun unlist(s: String): List<String> = if (s == "-") emptyList() else s.split(',').map { unesc(it) }

    fun num(x: Double): String = String.format(Locale.ROOT, "%.1f", x)
}

/**
 * Games per match of a played board (1 single, 2 mirrored), read from its shape: the games
 * before the deciders are the tie's legs (or the one match) times this.
 */
internal fun gamesPerMatch(job: Fixture, board: BoardResult): Int {
    val legs = if (job.kind == JobKind.TIE) maxOf(1, job.legs) else 1
    return ((board.games.size - board.deciders) / legs).coerceIn(1, 2)
}

/** Short labels on a highlight card, stakes first. [code] is how highlights.txt stores it. */
enum class DramaTag(val code: Char, val label: String) {
    FINAL('F', "결승"),
    TITLE_RACE('T', "우승 경쟁"),
    PROMOTION('P', "승강전"),
    DERBY('R', "더비"),
    DECIDER('X', "결정전"),
    UPSET('U', "이변"),
    COMEBACK_BIG('C', "대역전"),
    COMEBACK('c', "역전승"),
    LAST_TURN('L', "막판 결정"),
    SEESAW('S', "시소게임"),
    DRAW('D', "무승부"),
    ONE_POINT('1', "1점 차"),
    CLOSE('N', "접전"),
    BIG_SCORE('B', "300점 돌파"),
    ;

    companion object {
        fun of(code: Char): DramaTag? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the facts of a game cannot tell about it: the players' ratings (NaN when unknown) and
 * what was at stake.
 */
data class DramaContext(
    /** Ratings of the player who moved first and of the other. */
    val firstRating: Double = Double.NaN,
    val secondRating: Double = Double.NaN,
    /** A knockout final ([Drama.isFinal]). */
    val final: Boolean = false,
    /** A game between title contenders late in the season ([Drama.titleRace]). */
    val titleRace: Boolean = false,
    /** Both players from the same hometown. */
    val derby: Boolean = false,
    /** A decider game of a level tie or match. */
    val decider: Boolean = false,
    /** A promotion playoff tie. */
    val promotion: Boolean = false,
)

/** How dramatic a game was ([score], about 0-150) and why ([tags], in [DramaTag] order). */
class DramaScore(val score: Double, val tags: List<DramaTag>)

/**
 * The drama score of a game (docs/LEAGUE_SPEC.md section 3): a comeback (the winner's lowest
 * P(win)), a late decision, lead changes, a tiny margin, 300-point scores and an upset add up;
 * finals, title races, promotion ties, deciders and derbies multiply. A blowout scores near 0,
 * a last-turn comeback by one point well over 80.
 */
object Drama {
    const val BIG_SCORE = 300

    /** A winner rated this much below the loser makes an upset tag (smaller gaps still add a little). */
    const val UPSET_GAP = 100.0

    fun of(f: GameFacts, ctx: DramaContext = DramaContext()): DramaScore {
        val m = abs(f.margin)
        val draw = m == 0
        val tags = ArrayList<DramaTag>()
        var s = 0.0
        val minP = f.winnerMinP
        if (!draw) s += 40 * ((0.5 - minP) / 0.5).coerceIn(0.0, 1.0)
        // Decided late: the winner went ahead for good near the end (a draw stays open to the last box).
        val last = maxOf(1, f.curve.size - 1)
        val late = f.decisiveHalfTurn.coerceIn(0, last).toDouble() / last
        s += 20 * late * late
        s += 3 * minOf(f.leadChanges, 6)
        s += if (draw) 18.0 else maxOf(0, 16 - 2 * (m - 1)).toDouble()
        val big = (if (f.first.total >= BIG_SCORE) 1 else 0) + (if (f.second.total >= BIG_SCORE) 1 else 0)
        s += 12 * big
        val gap = when {
            f.firstWon -> ctx.secondRating - ctx.firstRating
            f.secondWon -> ctx.firstRating - ctx.secondRating
            else -> Double.NaN
        }
        if (!gap.isNaN() && gap > UPSET_GAP / 2) s += minOf(20.0, (gap - UPSET_GAP / 2) / 10)
        var mult = 1.0
        if (ctx.final) mult *= 1.5
        if (ctx.titleRace) mult *= 1.25
        if (ctx.promotion) mult *= 1.2
        if (ctx.decider) mult *= 1.2
        if (ctx.derby) mult *= 1.15

        if (ctx.final) tags += DramaTag.FINAL
        if (ctx.titleRace) tags += DramaTag.TITLE_RACE
        if (ctx.promotion) tags += DramaTag.PROMOTION
        if (ctx.derby) tags += DramaTag.DERBY
        if (ctx.decider) tags += DramaTag.DECIDER
        if (!gap.isNaN() && gap >= UPSET_GAP) tags += DramaTag.UPSET
        if (!draw && minP <= 0.15) tags += DramaTag.COMEBACK_BIG else if (!draw && minP <= 0.3) tags += DramaTag.COMEBACK
        if (!draw && f.decisiveHalfTurn >= last - 1) tags += DramaTag.LAST_TURN
        if (f.leadChanges >= 4) tags += DramaTag.SEESAW
        when {
            draw -> tags += DramaTag.DRAW
            m == 1 -> tags += DramaTag.ONE_POINT
            m <= 5 -> tags += DramaTag.CLOSE
        }
        if (big > 0) tags += DramaTag.BIG_SCORE
        return DramaScore(s * mult, tags)
    }

    /** A knockout final: the "결승" round of a bracket, or the one tie of the super cup. */
    fun isFinal(f: Fixture): Boolean = f.label == "결승" || f.comp == Comp.SUPER

    /**
     * True when [f] is a title-race game: a title playoff tie, or a 1st-division league fixture
     * with at least 60% of the league played that involves an AI within [RACE_POINTS] of the
     * leader while there are two or more such AIs.
     */
    fun titleRace(league: Season, f: Fixture): Boolean {
        if (f.comp == Comp.TITLE_PLAYOFF) return true
        if (f.comp != Comp.LEAGUE || league.progress < RACE_FROM || league.divisions.isEmpty()) return false
        if (league.divisionOf(f.a) != 0) return false
        val table = league.table(0)
        val top = table.firstOrNull()?.points ?: return false
        val contenders = table.takeWhile { top - it.points <= RACE_POINTS }.map { it.key }
        return contenders.size >= 2 && (f.a in contenders || f.b in contenders)
    }

    /** League points from the leader that still count as in the title race (one win). */
    const val RACE_POINTS = 3
    const val RACE_FROM = 0.6
}

/**
 * One of the most dramatic games: where it was played ([fixture], [board], [game]; the fixture
 * keeps its seed, so the game can be played again), the board's scores from its side A, the
 * drama and its tags, the first player's P(win) curve and hashes of both AIs' versions then.
 */
class Highlight(
    val season: Int,
    val fixture: Fixture,
    val board: Int,
    /** The game's index in its board's games (deciders last). */
    val game: Int,
    /** Games per match of the board (1 single, 2 mirrored): where the game's deal came from. */
    val perMatch: Int,
    val scoreA: Int,
    val scoreB: Int,
    val drama: Double,
    val tags: List<DramaTag>,
    /** The first player's P(win) after each half-turn, in percent. */
    val curve: ByteArray,
    /** [HighlightBook.versionHash] of A's and B's AI version when the game was played. */
    val versionA: String,
    val versionB: String,
    /**
     * A's and B's genes when the game was played (generated and evolved players, who change every
     * season): the replay plays them as they were, even after they grew, declined or retired.
     */
    val specA: CustomAiSpec? = null,
    val specB: CustomAiSpec? = null,
) {
    /** The genes [key] had in this game, when they were kept. */
    fun snapshot(key: String): CustomAiSpec? = when (key) {
        a -> specA
        b -> specB
        else -> null
    }

    /** The board's sides: the fixture's AIs, or the lineups' AIs on [board] for a team match. */
    val a: String get() = if (fixture.kind == JobKind.TEAM) fixture.lineupA.getOrElse(board) { fixture.a } else fixture.a
    val b: String get() = if (fixture.kind == JobKind.TEAM) fixture.lineupB.getOrElse(board) { fixture.b } else fixture.b

    /** The game's deal: [LeagueMatch.playGame] with it and the board's AIs plays the game again. */
    val deal: GameDeal = LeagueMatch.deal(fixture, board, game, perMatch)

    /** Who moved first and second. */
    val first: String get() = if (deal.aFirst) a else b
    val second: String get() = if (deal.aFirst) b else a

    /** Identity within the league: the same game offered twice (a replayed fixture) is kept once. */
    val id: String get() = "$season:${fixture.comp.code}:${fixture.id}:$board:$game"

    /** A's P(win) after each half-turn, in percent (for the card's curve). */
    fun curveA(): IntArray = IntArray(curve.size) { if (deal.aFirst) curve[it].toInt() else 100 - curve[it] }

    /** Where it was played, for the card ("요트컵 결승 · 2번째 판", team matches with the board). */
    val caption: String
        get() {
            val where = listOf(fixture.comp.label, fixture.label).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val legs = if (fixture.kind == JobKind.TIE) maxOf(1, fixture.legs) else 1
            val gameText = if (game >= legs * perMatch) "결정전 ${game - legs * perMatch + 1}" else "${game + 1}번째 판"
            val boardText = if (fixture.kind == JobKind.TEAM) " · ${board + 1}번 보드" else ""
            return "$where$boardText · $gameText"
        }

    /** True when both AIs still have the version they had in the game ([version] as for [HighlightBook.offer]). */
    fun sameVersions(version: (String) -> String): Boolean =
        HighlightBook.versionHash(version(a)) == versionA && HighlightBook.versionHash(version(b)) == versionB

    /**
     * Plays the game again from its seed through the league's own code ([LeagueMatch.playGame]),
     * turn by turn and recording nothing. Null when an AI is no longer in the league; check
     * [HighlightReplay.same] before showing it (an AI that changed plays a different game).
     */
    fun replay(rules: RuleSet, ai: (String) -> LeagueAi?, cancelled: () -> Boolean = { false }): HighlightReplay? {
        val x = ai(a) ?: return null
        val y = ai(b) ?: return null
        return HighlightReplay(this, LeagueMatch.playGame(rules, deal, x, y, watch = true, cancelled = cancelled))
    }

    /** One line of fields (no tag): highlights.txt and the archive store it. */
    fun encode(): String = buildString {
        val f = fixture
        append(season).append(' ').append(f.comp.code).append(' ').append(f.id).append(' ')
        append(MediaText.esc(f.a)).append(' ').append(MediaText.esc(f.b)).append(' ').append(f.seed).append(' ')
        append(f.kind.name).append(' ').append(f.legs).append(' ')
        append(MediaText.list(f.lineupA)).append(' ').append(MediaText.list(f.lineupB)).append(' ')
        append(MediaText.esc(f.label)).append(' ').append(if (f.rated) 1 else 0).append(' ')
        append(board).append(' ').append(game).append(' ').append(perMatch).append(' ')
        append(scoreA).append(' ').append(scoreB).append(' ').append(MediaText.num(drama)).append(' ')
        append(if (tags.isEmpty()) "-" else tags.joinToString("") { it.code.toString() }).append(' ')
        append(versionA).append(' ').append(versionB).append(' ')
        // The curve of fixed length, so a line cut short does not decode; the genes after it,
        // closed by "end" (older versions read 22 fields and ignore the rest).
        for (v in curve) append(HEX[v.toInt() shr 4 and 15]).append(HEX[v.toInt() and 15])
        if (specA != null || specB != null) {
            append(" snap ").append(MediaText.esc(specA?.encode() ?: "")).append(' ').append(MediaText.esc(specB?.encode() ?: "")).append(" end")
        }
    }

    companion object {
        private const val HEX = "0123456789abcdef"
        private const val FIELDS = 22

        /** Reads [encode]'s fields; null when they are short, cut or out of range. */
        fun decode(f: List<String>): Highlight? = runCatching {
            require(f.size >= FIELDS)
            val hex = f[21]
            require(hex.length == 2 * HALF_TURNS && hex.all { it in HEX })
            val curve = ByteArray(HALF_TURNS) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            require(curve.all { it in 0..100 })
            val comp = requireNotNull(Comp.of(f[1].single()))
            val fixture = Fixture(
                comp, f[2].toInt(), MediaText.unesc(f[3]), MediaText.unesc(f[4]), f[5].toLong(), JobKind.valueOf(f[6]), f[7].toInt(),
                MediaText.unlist(f[8]), MediaText.unlist(f[9]), f[11] == "1", MediaText.unesc(f[10]),
            )
            val tags = if (f[18] == "-") emptyList() else f[18].mapNotNull { DramaTag.of(it) }
            // Genes kept with the game: only a whole snapshot (one cut short is left out).
            val snap = f.size >= FIELDS + 4 && f[FIELDS] == "snap" && f[FIELDS + 3] == "end"
            fun spec(s: String) = if (!snap || s == "-") null else CustomAiSpec.decode(MediaText.unesc(s))
            val h = Highlight(
                f[0].toInt(), fixture, f[12].toInt(), f[13].toInt(), f[14].toInt(), f[15].toInt(), f[16].toInt(), f[17].toDouble(),
                tags, curve, f[19], f[20], spec(f.getOrElse(FIELDS + 1) { "-" }), spec(f.getOrElse(FIELDS + 2) { "-" }),
            )
            require(h.board >= 0 && h.game >= 0 && h.perMatch in 1..2)
            require(fixture.kind != JobKind.TEAM || h.board < minOf(fixture.lineupA.size, fixture.lineupB.size))
            h
        }.getOrNull()

        /** Half-turns of a game: the curve's length. */
        const val HALF_TURNS = 24
    }
}

/** A highlight played again ([Highlight.replay]): its turns for the watch screen. */
class HighlightReplay(val highlight: Highlight, val game: SingleGame) {
    /** Both cards came out as stored; when not, an AI has changed and the game cannot be shown. */
    val same: Boolean get() = game.score.a == highlight.scoreA && game.score.b == highlight.scoreB

    /** Every turn, first player's first (see [WatchTurn]). */
    val turns: List<WatchTurn> get() = game.turns

    /** What the card says when [same] is false. */
    val message: String? get() = if (same) null else CANNOT_REPLAY

    companion object {
        const val CANNOT_REPLAY = "선수가 그동안 바뀌어서 이 경기를 그대로 다시 볼 수 없어요."
    }
}

/**
 * The highlights (docs/LEAGUE_SPEC.md section 4): the [SEASON_TOP] most dramatic games of the
 * current season and the [ALL_TIME] most dramatic ever; [archiveLines] hands the best
 * [ARCHIVED] of a season to the stats archive. Saved as league/highlights.txt. Not thread-safe:
 * callers synchronise.
 *
 * Per season: [offer] every finished season job (with facts); at the rollover take
 * [archiveLines] of the closing season, then [startSeason] with the next number.
 */
class HighlightBook {
    /** The season [thisSeason] belongs to (0 before the first offer). */
    var season: Int = 0
        private set
    private val current = ArrayList<Highlight>()
    private val best = ArrayList<Highlight>()

    // The best of the season before, until its archive lines are taken (or the next rollover).
    private var previousSeason = 0
    private val previous = ArrayList<Highlight>()

    /** This season's most dramatic games, best first (a copy: views may keep it outside the lock). */
    val thisSeason: List<Highlight> get() = current.toList()

    /** The most dramatic games ever (this season included), best first (a copy). */
    val allTime: List<Highlight> get() = best.toList()

    /** The highlights of [key] (as either side of the board), this season's first. */
    fun of(key: String): List<Highlight> = (current + best).distinctBy { it.id }.filter { it.a == key || it.b == key }

    /**
     * Scores every game of a finished job of [season] (friendlies and the lab are skipped, and
     * games without facts) and keeps the dramatic ones. [rating] is each AI's rating before the
     * game (upsets), [hometown] its town (derbies), [version] a string that changes whenever the
     * AI's play changes (only its hash is kept), [titleRace] from [Drama.titleRace]. A later
     * season starts it ([startSeason]); an earlier one is ignored. Returns the games that entered
     * this season's list.
     */
    fun offer(
        r: JobResult,
        season: Int,
        rating: (String) -> Double = { Double.NaN },
        hometown: (String) -> String = { "" },
        version: (String) -> String = { "" },
        titleRace: Boolean = false,
        genes: (String) -> CustomAiSpec? = { null },
    ): List<Highlight> {
        val f = r.fixture
        if (f.comp == Comp.FRIENDLY || f.comp == Comp.LAB || season < this.season) return emptyList()
        if (season > this.season) startSeason(season)
        val final = Drama.isFinal(f)
        val legs = if (f.kind == JobKind.TIE) maxOf(1, f.legs) else 1
        val out = ArrayList<Highlight>()
        for ((bi, bd) in r.boards.withIndex()) {
            if (bd.games.isEmpty() || bd.facts.size != bd.games.size) continue
            val per = gamesPerMatch(f, bd)
            val town = hometown(bd.a)
            val derby = town.isNotEmpty() && town == hometown(bd.b)
            val ra = rating(bd.a)
            val rb = rating(bd.b)
            for ((gi, facts) in bd.facts.withIndex()) {
                if (facts.curve.size != Highlight.HALF_TURNS) continue
                val g = bd.games[gi]
                val aFirst = LeagueMatch.deal(f, bi, gi, per).aFirst
                // The facts are the first player's: they must agree with the board's scores.
                val (fa, fb) = if (aFirst) facts.first.total to facts.second.total else facts.second.total to facts.first.total
                if (fa != g.a || fb != g.b) continue
                val ctx = DramaContext(
                    if (aFirst) ra else rb, if (aFirst) rb else ra, final, titleRace, derby,
                    decider = gi >= legs * per, promotion = f.comp == Comp.PROMOTION_PLAYOFF,
                )
                val d = Drama.of(facts, ctx)
                // Kept at the saved precision, so a book read back from its file ranks and admits
                // games exactly as the one in memory would have.
                val drama = Math.round(d.score * 10) / 10.0
                if (!fits(current, SEASON_TOP, drama) && !fits(best, ALL_TIME, drama)) continue
                val h = Highlight(
                    season, f, bi, gi, per, g.a, g.b, drama, d.tags, facts.curve.copyOf(), versionHash(version(bd.a)), versionHash(version(bd.b)),
                    genes(bd.a), genes(bd.b),
                )
                if (insert(current, h, SEASON_TOP)) out += h
                insert(best, h, ALL_TIME)
            }
        }
        return out
    }

    /** Starts season [n]: this season's list is put aside for [archiveLines] and emptied. */
    fun startSeason(n: Int) {
        if (n == season) return
        if (current.isNotEmpty()) {
            previous.clear()
            previous += current.take(ARCHIVED)
            previousSeason = season
        }
        current.clear()
        season = n
    }

    /** The best [ARCHIVED] games of [season] (this one or the one before) as archive lines ("hl ..."). */
    fun archiveLines(season: Int): List<String> {
        val src = when (season) {
            this.season -> current
            previousSeason -> previous
            else -> emptyList()
        }
        return src.take(ARCHIVED).map { "$ARCHIVE_TAG ${it.encode()}" }
    }

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("season ").append(season).append('\n')
        for (h in current) append("cur ").append(h.encode()).append('\n')
        for (h in previous) append("prev ").append(h.encode()).append('\n')
        for (h in best) append("top ").append(h.encode()).append('\n')
    }

    private fun fits(list: List<Highlight>, cap: Int, score: Double) = list.size < cap || score > list.last().drama

    /** Inserts [h] by drama (after equals); false when it is already there or does not make the list. */
    private fun insert(list: MutableList<Highlight>, h: Highlight, cap: Int): Boolean {
        if (list.any { it.id == h.id }) return false
        var at = list.indexOfFirst { it.drama < h.drama }
        if (at < 0) at = list.size
        if (at >= cap) return false
        list.add(at, h)
        while (list.size > cap) list.removeAt(list.size - 1)
        return true
    }

    companion object {
        const val FILE = "highlights.txt"
        const val HEADER = "highlights 1"
        const val SEASON_TOP = 20
        const val ALL_TIME = 20
        const val ARCHIVED = 3

        /** Tag of the archive lines. */
        const val ARCHIVE_TAG = "hl"

        /** The kept form of an AI version string: 8 hex digits. */
        fun versionHash(version: String): String = "%08x".format(version.hashCode())

        /** Reads highlights.txt; throws when it is not one (the caller starts fresh), skips bad lines. */
        fun decode(text: String): HighlightBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a highlights file" }
            val book = HighlightBook()
            for (line in lines.drop(1)) {
                runCatching {
                    val f = line.trim().split(' ')
                    when (f[0]) {
                        "season" -> book.season = f[1].toInt()
                        "cur" -> Highlight.decode(f.drop(1))?.let { book.insert(book.current, it, SEASON_TOP) }
                        "prev" -> Highlight.decode(f.drop(1))?.let { book.insert(book.previous, it, ARCHIVED) }
                        "top" -> Highlight.decode(f.drop(1))?.let { book.insert(book.best, it, ALL_TIME) }
                    }
                }
            }
            book.previousSeason = book.previous.firstOrNull()?.season ?: 0
            // A cut file may have lost its season line: the kept games tell.
            if (book.season == 0) book.season = book.current.maxOfOrNull { it.season } ?: 0
            return book
        }

        /** Reads archive lines written by [archiveLines] (with or without their tag); bad lines are skipped. */
        fun decodeArchive(lines: List<String>): List<Highlight> = lines.mapNotNull { line ->
            val f = line.trim().split(' ')
            Highlight.decode(if (f.firstOrNull() == ARCHIVE_TAG) f.drop(1) else f)
        }
    }
}
