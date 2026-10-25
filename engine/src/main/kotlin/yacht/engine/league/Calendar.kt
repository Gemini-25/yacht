package yacht.engine.league

import java.util.Random

/** Fixture lists for round robins (the circle method). */
object Calendar {
    /**
     * Rounds of a single round robin of [keys]: in each round everyone plays at most once (one
     * rests per round with an odd count). Home/away alternates so each side moves first in about
     * half its matches. [rng] shuffles the order of the rounds' circle positions.
     */
    fun roundRobin(keys: List<String>, rng: Random? = null): List<List<Pair<String, String>>> {
        if (keys.size < 2) return emptyList()
        val list = keys.toMutableList<String?>()
        if (rng != null) list.shuffle(rng)
        if (list.size % 2 == 1) list += null
        val n = list.size
        val rounds = ArrayList<List<Pair<String, String>>>()
        val ring = list.subList(1, n).toMutableList()
        for (r in 0 until n - 1) {
            val round = ArrayList<Pair<String, String>>()
            val circle = listOf(list[0]) + ring
            for (i in 0 until n / 2) {
                val x = circle[i]
                val y = circle[n - 1 - i]
                if (x == null || y == null) continue
                round += if ((r + i) % 2 == 0) x to y else y to x
            }
            rounds += round
            ring.add(0, ring.removeAt(ring.size - 1))
        }
        return rounds
    }

    /** A double round robin: the single one, then again with the sides swapped. */
    fun doubleRoundRobin(keys: List<String>, rng: Random? = null): List<List<Pair<String, String>>> {
        val first = roundRobin(keys, rng)
        return first + first.map { r -> r.map { (a, b) -> b to a } }
    }
}
