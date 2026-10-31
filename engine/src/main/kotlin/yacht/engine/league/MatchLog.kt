package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.RuleSet
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * The decisions of a job's games as they are played ([LeagueMatch.playJob] with a sink), so a
 * match can be watched again without its AIs. Per half-turn: the keep after the first throw (5
 * bits, the keep's index among the roll's sub-multisets, `Dice.subKeep[subStart[r] until
 * subStart[r + 1]]`; the index of the whole roll means "stop"), the keep after the second throw
 * when it threw again (5 bits), and the box (4 bits, [Category.ordinal]). The dice come again from
 * the game's deal ([LeagueMatch.deal]), so a game takes at most 24 × 14 bits = 42 bytes. Recording
 * draws no randomness: a job plays the same with or without a sink. One sink per job, filled in
 * board and game order (the order of [BoardResult.games]). Not thread-safe (one worker plays a job).
 */
class LogSink {
    private class Game {
        var bytes = ByteArray(GameLogs.MAX_BYTES)
        var bits = 0

        /** A keep that was not a sub-multiset of its roll: this game cannot be logged. */
        var broken = false

        fun put(value: Int, width: Int) {
            if (bits + width > bytes.size * 8) bytes = bytes.copyOf(bytes.size * 2)
            for (i in width - 1 downTo 0) {
                if ((value shr i) and 1 != 0) bytes[bits ushr 3] = (bytes[bits ushr 3].toInt() or (0x80 ushr (bits and 7))).toByte()
                bits++
            }
        }
    }

    private val boards = ArrayList<MutableList<Game>>()
    private var board: MutableList<Game>? = null
    private var game: Game? = null

    /** Boards started (a match or tie has one). */
    val boardCount: Int get() = boards.size

    /** The next games are board [i]'s (a team match's boards in order; a match or tie is board 0). */
    internal fun board(i: Int) {
        while (boards.size <= i) boards += ArrayList<Game>()
        board = boards[i]
        game = null
    }

    /** A game of the current board starts. */
    internal fun newGame() {
        val b = board ?: run {
            board(0)
            boards[0]
        }
        game = Game().also { b += it }
    }

    /** The player threw [roll] and kept [keep] ([Dice.rollAsKeep] of the roll: it stopped). */
    internal fun keep(roll: Int, keep: Int) {
        val g = game ?: return
        val from = Dice.subStart[roll]
        val n = Dice.subStart[roll + 1] - from
        for (j in 0 until n) {
            if (Dice.subKeep[from + j] == keep) {
                g.put(j, GameLogs.KEEP_BITS)
                return
            }
        }
        g.broken = true
    }

    /** The player wrote its roll in [c]. */
    internal fun box(c: Category) {
        game?.put(c.ordinal, GameLogs.BOX_BITS)
    }

    /** Board [board]'s games in play order; empty when it was not played or a game could not be logged. */
    fun games(board: Int): List<ByteArray> {
        val list = boards.getOrNull(board) ?: return emptyList()
        if (list.any { it.broken }) return emptyList()
        return list.map { it.bytes.copyOf((it.bits + 7) / 8) }
    }
}

/** Reading and writing one game's decisions ([LogSink]). */
object GameLogs {
    const val KEEP_BITS = 5
    const val BOX_BITS = 4

    /** The most bytes a game takes: 24 half-turns of two keeps and a box. */
    const val MAX_BYTES = (2 * Category.COUNT * (2 * KEEP_BITS + BOX_BITS) + 7) / 8

    /**
     * The game dealt by [deal] played again from its decisions [bits], turn by turn as
     * [LeagueMatch.playGame] with `watch` gives it (the first player first); null when the bits do
     * not fit the dice (a keep or box that cannot be, too few bits, or bits left over).
     */
    fun turns(rules: RuleSet, deal: GameDeal, bits: ByteArray): List<WatchTurn>? {
        val read = Reader(bits)
        val streams = arrayOf(DiceStream(deal.dice0), DiceStream(deal.dice1))
        val cards = arrayOf(Board(rules), Board(rules))
        val out = ArrayList<WatchTurn>(2 * Category.COUNT)
        for (turn in 0 until Category.COUNT) {
            for (seat in 0..1) {
                val stream = streams[seat]
                val throws = ArrayList<List<Int>>(3)
                val held = ArrayList<List<Int>>(2)
                var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
                throws += Dice.rollFaces(r)
                for (rollsDone in 1..2) {
                    val j = read(KEEP_BITS) ?: return null
                    val from = Dice.subStart[r]
                    if (j >= Dice.subStart[r + 1] - from) return null
                    val keep = Dice.subKeep[from + j]
                    if (keep == Dice.rollAsKeep[r]) break
                    held += Dice.keepFaces(keep)
                    r = stream.throwDice(keep, turn, rollsDone)
                    throws += Dice.rollFaces(r)
                }
                val box = read(BOX_BITS) ?: return null
                if (box >= Category.COUNT) return null
                val c = Category.of(box)
                if (cards[seat].isFilled(c)) return null
                val points = rules.score(Dice.rollCounts[r], c)
                cards[seat] = cards[seat].with(c, points)
                out += WatchTurn(seat, throws, held, c, points, cards[0], cards[1])
            }
        }
        return if (read.rest()) out else null
    }

    /**
     * The decisions of a game watched turn by turn ([turns]' or [LeagueMatch.playGame]'s), checked
     * against [deal]: every throw must be the deal's. Throws when a turn does not fit it.
     */
    fun of(turns: List<WatchTurn>, deal: GameDeal): ByteArray {
        require(turns.size == 2 * Category.COUNT) { "a game has ${2 * Category.COUNT} half-turns" }
        val streams = arrayOf(DiceStream(deal.dice0), DiceStream(deal.dice1))
        val sink = LogSink()
        sink.newGame()
        for ((h, t) in turns.withIndex()) {
            val seat = h % 2
            require(t.side == seat) { "half-turn $h is seat $seat's" }
            require(t.throws.size in 1..3 && t.held.size == t.throws.size - 1) { "half-turn $h: ${t.throws.size} throws, ${t.held.size} holds" }
            var r = streams[seat].throwDice(Dice.EMPTY_KEEP, h / 2, 0)
            require(Dice.rollFaces(r) == t.throws[0]) { "half-turn $h: not the deal's first throw" }
            for ((k, faces) in t.held.withIndex()) {
                val keep = Dice.keepIndex(Dice.countsOf(faces))
                sink.keep(r, keep)
                r = streams[seat].throwDice(keep, h / 2, k + 1)
                require(Dice.rollFaces(r) == t.throws[k + 1]) { "half-turn $h: not the deal's throw ${k + 2}" }
            }
            if (t.throws.size < 3) sink.keep(r, Dice.rollAsKeep[r])
            sink.box(t.category)
        }
        val logged = sink.games(0)
        require(logged.size == 1) { "a hold that is not in its throw" }
        return logged[0]
    }

    /**
     * What the log format depends on: the order of rolls, keeps and sub-multisets ([Dice.subStart],
     * [Dice.subKeep]) and of the boxes ([Category.entries]). LogFormatTest pins it: a change must
     * come with a new [MatchLogBook.FORMAT], so older books are dropped instead of read wrongly.
     */
    fun formatHash(): Int {
        var h = 17
        for (x in Dice.subStart) h = 31 * h + x
        for (x in Dice.subKeep) h = 31 * h + x
        for (c in Category.entries) h = 31 * h + c.name.hashCode()
        return h
    }

    /** Reads [bits] most significant bit first. */
    private class Reader(private val bytes: ByteArray) {
        private var at = 0

        operator fun invoke(width: Int): Int? {
            if (at + width > bytes.size * 8) return null
            var v = 0
            repeat(width) {
                v = (v shl 1) or ((bytes[at ushr 3].toInt() ushr (7 - (at and 7))) and 1)
                at++
            }
            return v
        }

        /** True when only the last byte's zero padding is left. */
        fun rest(): Boolean {
            if (bytes.size * 8 - at >= 8) return false
            while (at < bytes.size * 8) if (invoke(1) != 0) return false
            return true
        }
    }
}

/**
 * Why a board is kept in the [MatchLogBook] (a bit set per record). [saved] classes go to
 * matchlogs.bin; the others live in memory only.
 */
enum class LogClass(val bit: Int, val saved: Boolean) {
    /** A final: [Drama.isFinal], the super cup, a title playoff's, a CL, CCL, nation cup or club cup final. */
    FINAL(1, true),

    /** A game of the board is one of the season's or all-time highlights. */
    HIGHLIGHT(2, true),

    /** One of its week's broadcast picks. */
    BROADCAST(4, true),

    /** The news desk took it as an upset or comeback. */
    NEWS(8, true),

    /** A side adapts to its opponent: re-simulating it later would not play the same. */
    ADAPTIVE(16, true),

    /** A knockout tie from the 8강 on, CL and CCL knockouts, the title playoffs of the best-ranked leagues. */
    KO(32, true),

    /** A followed player's or club's match (memory only, the newest [MatchLogBook.FOLLOWED_KEPT]). */
    FOLLOWED(64, false),

    /** A friendly watched on the screen (memory only, the newest [MatchLogBook.FRIENDLIES_KEPT]). */
    FRIENDLY(128, false),
    ;

    companion object {
        /** The bits of the classes saved to the file. */
        val SAVED: Int = entries.filter { it.saved }.fold(0) { m, c -> m or c.bit }

        /** The classes in [bits]. */
        fun of(bits: Int): Set<LogClass> = entries.filterTo(LinkedHashSet()) { bits and it.bit != 0 }
    }
}

/** One logged game of a board: its index in the board's games (deciders last), the score from side A, and its decisions. */
class LoggedGame(val index: Int, val scoreA: Int, val scoreB: Int, val bits: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is LoggedGame && other.index == index && other.scoreA == scoreA && other.scoreB == scoreB && other.bits.contentEquals(bits)

    override fun hashCode(): Int = ((index * 31 + scoreA) * 31 + scoreB) * 31 + bits.contentHashCode()

    override fun toString(): String = "LoggedGame($index, $scoreA:$scoreB, ${bits.size} B)"
}

/**
 * The logged games of one board of a played fixture. [ref] names the board (its [MatchRef.board]
 * is 0 for a one-on-one job); [a] and [b] are the board's sides, [teamA]/[teamB] the teams of a
 * team match; the shape ([kind], games per match [perMatch], [legs], [firstTo]) and [seed] are the
 * fixture's, so its deals can be made again. [games] may hold a subset (last season's and the
 * archive's keep the deciding game).
 */
data class MatchLog(
    val ref: MatchRef,
    val kind: JobKind,
    val perMatch: Int,
    val legs: Int,
    val firstTo: Int,
    val seed: Long,
    val a: String,
    val b: String,
    val teamA: String?,
    val teamB: String?,
    val label: String,
    val week: Int,
    /** [LogClass] bits. */
    val classes: Int,
    /** 0 none; 1-3 the season's highlight rank; [MatchLogBook.TAG_CL_FINAL] and the other final tags. */
    val tag: Int,
    val games: List<LoggedGame>,
) {
    fun has(c: LogClass): Boolean = classes and c.bit != 0

    /** The fixture again, enough for [LeagueMatch.deal] (a team match's lineups are not kept). */
    fun fixture(): Fixture =
        Fixture(ref.comp, ref.id, teamA ?: a, teamB ?: b, seed, kind, legs, label = label, week = week, firstTo = firstTo)

    /** The deal of the board's game [game] (its index in the board, as [LoggedGame.index]). */
    fun deal(game: Int): GameDeal = LeagueMatch.deal(fixture(), maxOf(0, ref.board), game, perMatch)

    /**
     * Game [game] (its index in the board) turn by turn, checked against its stored score; null
     * when it is not stored, does not fit its dice, or ends on another score (a corrupt log).
     */
    fun turns(rules: RuleSet, game: Int): List<WatchTurn>? {
        val g = games.firstOrNull { it.index == game } ?: return null
        val deal = deal(game)
        val turns = GameLogs.turns(rules, deal, g.bits) ?: return null
        val last = turns.last()
        val (sa, sb) = if (deal.aFirst) last.first.total to last.second.total else last.second.total to last.first.total
        return if (sa == g.scoreA && sb == g.scoreB) turns else null
    }

    companion object {
        /**
         * Board [board] of [r] as season [season]'s record of [classes] (and [tag]), from the
         * decisions [sink] took while it was played; null when the sink has no complete log of it.
         */
        fun of(season: Int, r: JobResult, sink: LogSink, board: Int, classes: Int, tag: Int = 0): MatchLog? {
            val f = r.fixture
            val bd = r.boards.getOrNull(board) ?: return null
            val logs = sink.games(board)
            if (logs.isEmpty() || logs.size != bd.games.size) return null
            val team = f.kind == JobKind.TEAM
            return MatchLog(
                MatchRef(season, f.comp, f.id, board), f.kind, gamesPerMatch(f, bd), f.legs, f.firstTo, f.seed, bd.a, bd.b,
                if (team) f.a else null, if (team) f.b else null, f.label, f.week, classes, tag,
                bd.games.mapIndexed { i, g -> LoggedGame(i, g.a, g.b, logs[i]) },
            )
        }
    }
}

/**
 * The kept decision logs of the league (file `league/matchlogs.bin`, UX design 3.4): the current
 * season's block, last season's, and an archive of single games from up to [ARCHIVE_SEASONS]
 * seasons, each block stamped with its season's seed so a book of another league is never read
 * for this one ([dropForeign]). [put] replaces a record of the same board and ORs the classes, so
 * a fixture played again after a kill is logged once. Records of no saved class live in memory
 * only. Not thread-safe: callers synchronise.
 */
class MatchLogBook {
    /** Byte budgets ([caps]): the current block, the previous one, and an archived season's bytes and games. */
    data class Caps(val current: Int, val previous: Int, val archiveSeason: Int, val archiveGames: Int) {
        companion object {
            /** No budget (tests, and until the app sets one). */
            val NONE = Caps(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    /** A game of a closing season's record for the archive: its [tag] (the order it is kept in) and its index (-1: the last game). */
    data class ArchiveGame(val tag: Int, val game: Int = -1)

    private class Block(val season: Int, val seed: Long, val kind: Int) {
        val records = LinkedHashMap<MatchRef, MatchLog>()
        var bytes = 0
    }

    private var current: Block? = null
    private var previous: Block? = null

    /** Archived seasons, oldest first. */
    private val archive = ArrayList<Block>()

    /** Records of no saved class (followed players' and friendlies), least recently used first. */
    private val memory = LinkedHashMap<MatchRef, MatchLog>(16, 0.75f, true)

    /** The broadcast picks of weeks w and w+1: (week, refs), saved in the file's trailer. */
    var picks: List<Pair<Int, List<MatchRef>>> = emptyList()

    /** The budget the current block keeps to (records are evicted by class, see [put]); none until set. */
    var caps: Caps = Caps.NONE
        set(value) {
            field = value
            current?.let { evict(it) }
        }

    /** The season of the current block (0 before [begin]) and its seed. */
    val season: Int get() = current?.season ?: 0
    val seed: Long get() = current?.seed ?: 0L

    /**
     * Starts the current block of season [season] (seed [seed]) unless it is the current one
     * already; a current block of another season or seed is dropped (call [rollover] first to keep
     * a closing season).
     */
    fun begin(season: Int, seed: Long) {
        val cur = current
        if (cur != null && cur.season == season && cur.seed == seed) return
        current = Block(season, seed, CURRENT)
        memory.clear()
        picks = emptyList()
    }

    /**
     * Keeps [log] (of the current season: false for any other, or one of no class). An existing
     * record of the same board is replaced and keeps its classes too (OR); with the current block
     * over [caps], saved classes are evicted in [EVICTION] order (a broadcast pick oldest week
     * first, the others oldest first), and a record goes once its last saved class is evicted.
     */
    fun put(log: MatchLog): Boolean {
        val cur = current ?: return false
        if (log.ref.season != cur.season) return false
        val ref = key(log.ref)
        val old = cur.records[ref] ?: memory[ref]
        val merged = log.copy(ref = ref, classes = log.classes or (old?.classes ?: 0), tag = if (log.tag != 0 || old == null) log.tag else old.tag)
        place(cur, ref, merged)
        return merged.classes != 0
    }

    /** The record of [ref]'s board (board -1 reads board 0) in the block of its season; null when none is kept. */
    operator fun get(ref: MatchRef): MatchLog? {
        val k = key(ref)
        val cur = current
        if (cur != null && cur.season == k.season) return cur.records[k] ?: memory[k]
        return past(k)
    }

    /** Every kept board of [ref]'s fixture, board order. */
    fun boards(ref: MatchRef): List<MatchLog> {
        fun mine(k: MatchRef) = k.season == ref.season && k.comp == ref.comp && k.id == ref.id
        val cur = current
        if (cur != null && cur.season == ref.season) {
            val out = cur.records.values.filter { mine(it.ref) } + memory.values.filter { mine(it.ref) }
            return out.distinctBy { it.ref.board }.sortedBy { it.ref.board }
        }
        return pastBlocks(ref.season).flatMap { b -> b.records.keys.filter(::mine) }.distinct().sortedBy { it.board }.mapNotNull(::past)
    }

    /** Adds class [c] to the kept record of [ref]'s board; false when none is kept. */
    fun addClass(ref: MatchRef, c: LogClass): Boolean {
        val k = key(ref)
        val cur = current
        if (cur != null && cur.season == k.season) {
            val old = cur.records[k] ?: memory[k] ?: return false
            place(cur, k, old.copy(classes = old.classes or c.bit))
            return true
        }
        var found = false
        for (b in pastBlocks(k.season)) {
            val old = b.records[k] ?: continue
            found = true
            // Old blocks keep their classes as saved (memory classes mean nothing there).
            if (c.saved) b.records[k] = old.copy(classes = old.classes or c.bit)
        }
        return found
    }

    /** The blocks of past season [season]: last season's, then its archive entry (either may be missing). */
    private fun pastBlocks(season: Int): List<Block> =
        listOfNotNull(previous?.takeIf { it.season == season }, archive.lastOrNull { it.season == season })

    /**
     * [k]'s record of a past season from both of its blocks: last season's keeps a series'
     * deciding game, its archive entry may keep another (a highlight), and either may hold a
     * record the other does not, so the games of both are read together.
     */
    private fun past(k: MatchRef): MatchLog? {
        val p = previous?.takeIf { it.season == k.season }?.records?.get(k)
        val a = archive.lastOrNull { it.season == k.season }?.records?.get(k)
        if (p == null || a == null) return p ?: a
        val more = a.games.filter { g -> p.games.none { it.index == g.index } }
        if (more.isEmpty()) return p
        return p.copy(games = (p.games + more).sortedBy { it.index }, tag = if (p.tag != 0) p.tag else a.tag)
    }

    /** Bytes of the saved records of the blocks of [kind]: [CURRENT], [PREVIOUS] or [ARCHIVE] (all archived seasons). */
    fun bytes(kind: Int): Int = when (kind) {
        CURRENT -> current?.bytes ?: 0
        PREVIOUS -> previous?.bytes ?: 0
        ARCHIVE -> archive.sumOf { it.bytes }
        else -> 0
    }

    /** Records kept in the blocks of [kind] (memory ones not counted). */
    fun count(kind: Int): Int = when (kind) {
        CURRENT -> current?.records?.size ?: 0
        PREVIOUS -> previous?.records?.size ?: 0
        ARCHIVE -> archive.sumOf { it.records.size }
        else -> 0
    }

    /** The archived seasons, oldest first. */
    val archivedSeasons: List<Int> get() = archive.map { it.season }

    /**
     * The close of season [closing] (seed [closingSeed]) into [nextSeason] ([nextSeed]): the old
     * previous block goes; the closing season's archive entry takes single games ([archive]: the
     * games of a record to archive, by tag) in tag order until [Caps.archiveGames] or
     * [Caps.archiveSeason] bytes (the first always fits); the closing block becomes the previous
     * one, its records in [keep] order (lower first, -1 not kept) until [Caps.previous] bytes, a
     * record of more than 2 games keeping only its last (deciding) game; an empty current block
     * starts for the next season, and memory records and picks are dropped. Done once per closing
     * season: called again after it, nothing changes.
     */
    fun rollover(
        closing: Int,
        closingSeed: Long,
        nextSeason: Int,
        nextSeed: Long,
        caps: Caps,
        keep: (MatchLog) -> Int,
        archive: (MatchLog) -> List<ArchiveGame>,
    ) {
        val cur = current
        if (cur != null && cur.season == nextSeason && cur.seed == nextSeed) return
        previous = null
        if (cur != null && cur.season == closing && cur.seed == closingSeed) {
            this.archive.removeAll { it.season >= closing }
            archiveEntry(cur, caps, archive)?.let { this.archive += it }
            while (this.archive.size > ARCHIVE_SEASONS) this.archive.removeAt(0)
            previous = previousOf(cur, caps, keep)
        }
        current = Block(nextSeason, nextSeed, CURRENT)
        memory.clear()
        picks = emptyList()
        this.caps = caps
    }

    private fun archiveEntry(cur: Block, caps: Caps, archive: (MatchLog) -> List<ArchiveGame>): Block? {
        val entry = Block(cur.season, cur.seed, ARCHIVE)
        val wanted = cur.records.values.flatMap { log -> archive(log).map { log to it } }.sortedBy { it.second.tag }
        var games = 0
        for ((log, pick) in wanted) {
            if (games >= caps.archiveGames) break
            val g = (if (pick.game < 0) log.games.lastOrNull() else log.games.firstOrNull { it.index == pick.game }) ?: continue
            val old = entry.records[log.ref]
            if (old != null && old.games.any { it.index == g.index }) continue
            val rec = old?.copy(games = (old.games + g).sortedBy { it.index }) ?: log.copy(games = listOf(g), tag = pick.tag)
            val more = size(rec, entry.seed) - (old?.let { size(it, entry.seed) } ?: 0)
            if (games > 0 && entry.bytes + more > caps.archiveSeason) break
            entry.records[log.ref] = rec
            entry.bytes += more
            games++
        }
        return entry.takeIf { it.records.isNotEmpty() }
    }

    private fun previousOf(cur: Block, caps: Caps, keep: (MatchLog) -> Int): Block? {
        val prev = Block(cur.season, cur.seed, PREVIOUS)
        val ranked = cur.records.values.mapNotNull { l -> keep(l).takeIf { it >= 0 }?.let { l to it } }.sortedBy { it.second }
        for ((log, _) in ranked) {
            val rec = if (log.games.size > 2) log.copy(games = listOf(log.games.last())) else log
            val s = size(rec, prev.seed)
            if (prev.bytes + s > caps.previous) break
            prev.records[rec.ref] = rec
            prev.bytes += s
        }
        return prev.takeIf { it.records.isNotEmpty() }
    }

    /**
     * Drops every block whose seed is not its season's seed in this league ([seedOf]; null: not
     * known, dropped too), and the memory records with the current block.
     */
    fun dropForeign(seedOf: (Int) -> Long?) {
        if (current?.let { seedOf(it.season) != it.seed } == true) {
            current = null
            memory.clear()
            picks = emptyList()
        }
        if (previous?.let { seedOf(it.season) != it.seed } == true) previous = null
        archive.removeAll { seedOf(it.season) != it.seed }
    }

    /**
     * [dropForeign] for a league now in season [season] of seed [seed]: the current block must be
     * that season's, and an older block's seed must lead to [seed] by the off-season's rule
     * ([nextSeed], as [Offseason.next] derives each season's seed from the one before). A current
     * block of the season before, whose seed leads to [seed], is kept too: the book was saved
     * before that season's close and the league after it (the league's files are written first),
     * so the caller rolls it over ([rollover] from [season] - 1 into [season]) before [begin],
     * which would drop it.
     */
    fun dropForeign(season: Int, seed: Long) {
        fun leads(b: Block): Boolean {
            var x = b.seed
            for (n in b.season + 1..season) x = nextSeed(x, n)
            return x == seed
        }
        val ours = (listOfNotNull(current, previous) + archive)
            .filter { b ->
                when {
                    b.kind != CURRENT -> b.season < season && leads(b)
                    b.season == season -> b.seed == seed
                    else -> b.season == season - 1 && leads(b)
                }
            }
            .associate { it.season to it.seed }
        dropForeign { ours[it] }
    }

    // ---- saving --------------------------------------------------------------------------------

    /** The file: "YML" [FORMAT], the blocks (current, previous, archive oldest first), the picks trailer, a CRC32. */
    fun encode(): ByteArray {
        val w = Writer()
        w.raw(MAGIC)
        w.u8(FORMAT)
        val blocks = listOfNotNull(current, previous) + archive
        w.varint(blocks.size)
        for (b in blocks) {
            w.varint(b.season)
            w.i64(b.seed)
            w.u8(b.kind)
            val records = b.records.values.filter { it.classes and LogClass.SAVED != 0 }
            w.varint(records.size)
            for (r in records) record(w, r, b.seed)
        }
        w.u8(TRAILER)
        w.varint(current?.season ?: 0)
        w.varint(picks.size)
        for ((week, refs) in picks) {
            w.varint(maxOf(0, week))
            w.varint(refs.size)
            for (r in refs) {
                w.u8(r.comp.code.code)
                w.varint(maxOf(0, r.id))
            }
        }
        val crc = CRC32().apply { update(w.bytes()) }.value
        w.u32(crc)
        return w.bytes()
    }

    private fun place(cur: Block, ref: MatchRef, log: MatchLog) {
        cur.records[ref]?.let { cur.bytes -= size(it, cur.seed) }
        if (log.classes and LogClass.SAVED != 0) {
            memory.remove(ref)
            // A record replaced keeps its place in the eviction order.
            cur.records[ref] = log
            cur.bytes += size(log, cur.seed)
            evict(cur)
        } else {
            cur.records.remove(ref)
            if (log.classes != 0) {
                memory[ref] = log
                trimMemory()
            } else {
                memory.remove(ref)
            }
        }
    }

    private fun evict(cur: Block) {
        val cap = caps.current
        for (c in EVICTION) {
            if (cur.bytes <= cap) return
            val victims = cur.records.values.filter { it.has(c) }.let { v -> if (c == LogClass.BROADCAST) v.sortedBy { it.week } else v }
            for (v in victims) {
                if (cur.bytes <= cap) return
                val left = v.classes and c.bit.inv()
                if (left and LogClass.SAVED != 0) {
                    cur.records[v.ref] = v.copy(classes = left)
                } else {
                    cur.records.remove(v.ref)
                    cur.bytes -= size(v, cur.seed)
                    if (left != 0) {
                        memory[v.ref] = v.copy(classes = left)
                        trimMemory()
                    }
                }
            }
        }
    }

    private fun trimMemory() {
        var friendlies = memory.values.count { it.has(LogClass.FRIENDLY) }
        var others = memory.size - friendlies
        val it = memory.values.iterator()
        while (it.hasNext() && (friendlies > FRIENDLIES_KEPT || others > FOLLOWED_KEPT)) {
            val friendly = it.next().has(LogClass.FRIENDLY)
            if (friendly && friendlies > FRIENDLIES_KEPT) {
                it.remove()
                friendlies--
            } else if (!friendly && others > FOLLOWED_KEPT) {
                it.remove()
                others--
            }
        }
    }

    /** A record's key: its board (0 for a whole one-on-one fixture), no game. */
    private fun key(ref: MatchRef): MatchRef = if (ref.board >= 0 && ref.game < 0) ref else ref.copy(board = maxOf(0, ref.board), game = -1)

    companion object {
        /** The file's version; a book of another version is not read (an empty book instead). */
        const val FORMAT = 1

        const val CURRENT = 0
        const val PREVIOUS = 1
        const val ARCHIVE = 2

        /** Archived seasons kept. */
        const val ARCHIVE_SEASONS = 30

        /** Memory-only records kept: followed players' matches, and friendlies. */
        const val FOLLOWED_KEPT = 200
        const val FRIENDLIES_KEPT = 5

        /** Archive tags of the season's finals (1-3 are its highlights by rank). */
        const val TAG_CL_FINAL = 10
        const val TAG_CUP_FINAL = 11
        const val TAG_CCL_FINAL = 12
        const val TAG_SUPER = 16

        /** The current block evicts saved classes in this order when over budget. */
        val EVICTION = listOf(LogClass.KO, LogClass.BROADCAST, LogClass.NEWS, LogClass.ADAPTIVE, LogClass.HIGHLIGHT, LogClass.FINAL)

        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'M'.code.toByte(), 'L'.code.toByte())
        private const val TRAILER = 0xB0

        /**
         * The budget for a league of [members] players this season: the current block 48 B a
         * member (4 KB to 96 KB); last season's a quarter of it from 500 members (none below); an
         * archived season 0.6 B a member (92 B to 830 B), a single game below 500 members.
         */
        fun caps(members: Int): Caps {
            val current = (48L * members).coerceIn(4_000L, 96_000L).toInt()
            val previous = if (members >= 500) current / 4 else 0
            val archive = Math.round(0.6 * members).toInt().coerceIn(92, 830)
            return Caps(current, previous, archive, if (members >= 500) Int.MAX_VALUE else 1)
        }

        /** The seed of season [season] after the season seeded [prev], as [Offseason.next] derives it. */
        fun nextSeed(prev: Long, season: Int): Long = DiceStream.mix(prev, 0x5EA51, season.toLong())

        /** Reads [encode]'s bytes; anything unreadable (none, cut, another version, a CRC error) gives an empty book. */
        fun decode(bytes: ByteArray?): MatchLogBook = bytes?.let { runCatching { read(it) }.getOrNull() } ?: MatchLogBook()

        private fun read(bytes: ByteArray): MatchLogBook {
            require(bytes.size >= MAGIC.size + 5) { "too short" }
            val body = bytes.size - 4
            val crc = CRC32().apply { update(bytes, 0, body) }.value
            val r = Reader(bytes, body)
            require((0 until MAGIC.size).all { bytes[it] == MAGIC[it] }) { "not a match log book" }
            r.skip(MAGIC.size)
            require(r.u8() == FORMAT) { "another version" }
            require(Reader(bytes, bytes.size).also { it.skip(body) }.u32() == crc) { "CRC" }
            val book = MatchLogBook()
            repeat(r.varint()) {
                val season = r.varint()
                val seed = r.i64()
                val kind = r.u8()
                require(kind in CURRENT..ARCHIVE) { "block kind $kind" }
                val b = Block(season, seed, kind)
                repeat(r.varint()) {
                    val log = record(r, season, seed)
                    b.records[log.ref] = log
                    b.bytes += size(log, seed)
                }
                when (kind) {
                    CURRENT -> book.current = b
                    PREVIOUS -> book.previous = b
                    else -> {
                        book.archive.removeAll { it.season == season }
                        book.archive += b
                    }
                }
            }
            book.archive.sortBy { it.season }
            if (r.left > 0) {
                require(r.u8() == TRAILER) { "trailer" }
                val season = r.varint()
                book.picks = List(r.varint()) {
                    val week = r.varint()
                    week to List(r.varint()) {
                        val comp = requireNotNull(Comp.of(r.u8().toChar())) { "comp" }
                        MatchRef(season, comp, r.varint())
                    }
                }
            }
            require(r.left == 0) { "bytes left over" }
            return book
        }

        // Shape byte: bits 0-1 the job kind, 2-3 games per match, 4 an explicit seed, 5 team ids.
        private const val SEED_BIT = 16
        private const val TEAM_BIT = 32

        private fun derivedSeed(seasonSeed: Long, comp: Comp, id: Int): Long = Knockout.fixtureSeed(seasonSeed, comp, id)

        private fun record(w: Writer, l: MatchLog, seasonSeed: Long) {
            val explicit = l.seed != derivedSeed(seasonSeed, l.ref.comp, l.ref.id)
            val team = l.teamA != null && l.teamB != null
            w.u8(l.ref.comp.code.code)
            w.varint(maxOf(0, l.ref.id))
            w.u8(maxOf(0, l.ref.board))
            w.u8(l.kind.ordinal or (l.perMatch shl 2) or (if (explicit) SEED_BIT else 0) or (if (team) TEAM_BIT else 0))
            w.u8(l.legs)
            w.u8(l.firstTo)
            if (explicit) w.i64(l.seed)
            w.str(l.a)
            w.str(l.b)
            if (team) {
                w.str(l.teamA!!)
                w.str(l.teamB!!)
            }
            w.str(l.label)
            w.u8(if (l.week in 1..255) l.week else 0)
            w.u8(l.classes and LogClass.SAVED)
            w.u8(l.tag)
            w.u8(l.games.size)
            for (g in l.games) {
                w.u8(g.index)
                w.u16(g.scoreA)
                w.u16(g.scoreB)
                w.u8(g.bits.size)
                w.raw(g.bits)
            }
        }

        private fun record(r: Reader, season: Int, seasonSeed: Long): MatchLog {
            val comp = requireNotNull(Comp.of(r.u8().toChar())) { "comp" }
            val id = r.varint()
            val board = r.u8()
            val shape = r.u8()
            val kind = JobKind.entries[shape and 3]
            val perMatch = (shape shr 2) and 3
            require(perMatch in 1..2) { "games per match" }
            val legs = r.u8()
            val firstTo = r.u8()
            val seed = if (shape and SEED_BIT != 0) r.i64() else derivedSeed(seasonSeed, comp, id)
            val a = r.str()
            val b = r.str()
            val team = shape and TEAM_BIT != 0
            val teamA = if (team) r.str() else null
            val teamB = if (team) r.str() else null
            val label = r.str()
            val week = r.u8().let { if (it == 0) SeasonCalendar.NONE else it }
            val classes = r.u8()
            val tag = r.u8()
            val games = List(r.u8()) { LoggedGame(r.u8(), r.u16(), r.u16(), r.raw(r.u8())) }
            return MatchLog(MatchRef(season, comp, id, board), kind, perMatch, legs, firstTo, seed, a, b, teamA, teamB, label, week, classes, tag, games)
        }

        /** The bytes [record] writes for [l] in a block seeded [seasonSeed]. */
        internal fun size(l: MatchLog, seasonSeed: Long): Int {
            fun varint(v: Int): Int {
                var n = 1
                var x = v ushr 7
                while (x != 0) {
                    n++
                    x = x ushr 7
                }
                return n
            }
            fun str(s: String): Int = s.toByteArray(Charsets.UTF_8).size.let { varint(it) + it }
            val explicit = l.seed != derivedSeed(seasonSeed, l.ref.comp, l.ref.id)
            val team = l.teamA != null && l.teamB != null
            return 1 + varint(maxOf(0, l.ref.id)) + 4 + (if (explicit) 8 else 0) + str(l.a) + str(l.b) +
                (if (team) str(l.teamA!!) + str(l.teamB!!) else 0) + str(l.label) + 4 + l.games.sumOf { 6 + it.bits.size }
        }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) {
            require(v in 0..255) { "u8 $v" }
            out.write(v)
        }

        fun u16(v: Int) {
            require(v in 0..0xFFFF) { "u16 $v" }
            out.write(v ushr 8)
            out.write(v and 0xFF)
        }

        fun u32(v: Long) {
            for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
        }

        fun i64(v: Long) {
            for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt())
        }

        fun varint(v: Int) {
            require(v >= 0) { "varint $v" }
            var x = v
            while (x >= 0x80) {
                out.write((x and 0x7F) or 0x80)
                x = x ushr 7
            }
            out.write(x)
        }

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            varint(b.size)
            out.write(b)
        }

        fun raw(b: ByteArray) = out.write(b)

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Reads [bytes] up to [end] (exclusive); throws past it. */
    private class Reader(private val bytes: ByteArray, private val end: Int) {
        private var at = 0
        val left: Int get() = end - at

        fun skip(n: Int) {
            require(at + n <= end) { "cut" }
            at += n
        }

        fun u8(): Int {
            require(at < end) { "cut" }
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long {
            var v = 0L
            repeat(4) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun i64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun varint(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val b = u8()
                v = v or ((b and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
                require(shift < 32) { "varint" }
            }
            require(v >= 0) { "varint" }
            return v
        }

        fun raw(n: Int): ByteArray {
            require(n >= 0 && at + n <= end) { "cut" }
            return bytes.copyOfRange(at, at + n).also { at += n }
        }

        fun str(): String = String(raw(varint()), Charsets.UTF_8)
    }
}
