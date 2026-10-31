package yacht.engine

import yacht.engine.league.Comp
import yacht.engine.league.GameLogs
import yacht.engine.league.JobKind
import yacht.engine.league.LogClass
import yacht.engine.league.LoggedGame
import yacht.engine.league.MatchLog
import yacht.engine.league.MatchLogBook
import yacht.engine.league.MatchRef
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What matchlogs.bin depends on stays put: a log's keep and box numbers mean the same thing as
 * long as the order of rolls, keeps and sub-multisets ([Dice.subStart], [Dice.subKeep]) and of the
 * boxes ([Category.entries]) does. If this test fails after such a change, bump
 * [MatchLogBook.FORMAT] and add its hash below: older books are then dropped, not read as garbage.
 */
class LogFormatTest {
    /** The format hash of each [MatchLogBook.FORMAT]. */
    private val hashes = mapOf(1 to -1736351468)

    @Test
    fun theDiceAndBoxOrderIsTheFormats() {
        assertEquals(hashes[MatchLogBook.FORMAT], GameLogs.formatHash(), "dice or box order changed: a new MatchLogBook.FORMAT")
        assertEquals(
            listOf("ACES", "DEUCES", "THREES", "FOURS", "FIVES", "SIXES", "CHOICE", "FOUR_OF_A_KIND", "FULL_HOUSE", "SMALL_STRAIGHT", "LARGE_STRAIGHT", "YACHT"),
            Category.entries.map { it.name },
        )
        assertEquals(32, (0 until Dice.ROLLS).maxOf { Dice.subStart[it + 1] - Dice.subStart[it] }, "a keep fits in ${GameLogs.KEEP_BITS} bits")
        assertEquals(42, GameLogs.MAX_BYTES)
    }

    /** A small book byte for byte (UX design 3.4's layout): a change to it needs a new [MatchLogBook.FORMAT] too. */
    @Test
    fun theFileLayoutIsPinned() {
        val b = MatchLogBook()
        b.begin(3, 0x5EED_0003L)
        b.put(
            MatchLog(
                MatchRef(3, Comp.CUP, 17, 0), JobKind.TIE, 2, 4, 3, 99L, "g0001", "g0002", null, null, "결승", 44,
                LogClass.FINAL.bit or LogClass.FOLLOWED.bit, MatchLogBook.TAG_CUP_FINAL, listOf(LoggedGame(0, 251, 230, byteArrayOf(1, 2, 3)), LoggedGame(5, 199, 300, byteArrayOf(-1))),
            ),
        )
        b.picks = listOf(44 to listOf(MatchRef(3, Comp.CUP, 17)))
        val bytes = b.encode()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("a55059ed70ec60eb30860abf53777257753d58323c065c0221baf6a75b8c044a", sha, bytes.joinToString(" ") { "%02x".format(it) })
    }
}
