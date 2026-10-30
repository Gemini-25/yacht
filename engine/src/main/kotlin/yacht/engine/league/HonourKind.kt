package yacht.engine.league

/**
 * Reading an [Honour.kind]. A kind of a national season carries its league after a dot
 * ("L0.KR" 한국 리그 우승, "REG.KR", "L1.KR" 2부 우승, "LMVP.KR"), and a continental title its
 * confederation ("CCL.ASIA", [Confed.name]); a kind without a dot ("L0", "L3", "REG", "UP2",
 * "CUP", ...) means what it always did, the old single league's or a world title. '.' is safe in
 * honours.txt and in the archive's `kind:key` lists.
 */
object HonourKind {
    /** The kinds whose suffix is a confederation, not a league. */
    private val CONFED_BASES = setOf("CCL", "CCL2")
    private val DIVISION = Regex("L\\d+")

    /** The kind without its suffix: "L0.KR" -> "L0". */
    fun base(kind: String): String = kind.substringBefore('.')

    /** The suffix: a league id ("KR" in "L0.KR") or a confederation ("ASIA" in "CCL.ASIA"); null without one. */
    fun qualifier(kind: String): String? = kind.substringAfter('.', "").ifEmpty { null }

    /**
     * The league of a league honour ("KR" for "L0.KR"); null for a legacy kind and for a
     * confederation's ("CCL.ASIA"), so a league filter never reads "ASIA" as a league.
     */
    fun leagueOf(kind: String): String? = qualifier(kind)?.takeIf { base(kind) !in CONFED_BASES }

    /** [base] with [qualifier] ("L0", "KR" -> "L0.KR"); [base] alone for an empty one. */
    fun of(base: String, qualifier: String): String = if (qualifier.isEmpty()) base else "$base.$qualifier"

    /** A division title ("L0", "L3", "L1.KR"); not "L0R" (the title playoff's runner-up) or "LMVP". */
    fun isDivision(kind: String): Boolean = DIVISION.matches(base(kind))

    /** The tier of a division title (0 for "L0.KR", 2 for "L2"); null for other kinds. */
    fun tier(kind: String): Int? = base(kind).takeIf { DIVISION.matches(it) }?.drop(1)?.toIntOrNull()

    /** A league's title (1부 우승, after the title playoff): "L0" or "L0.<league>". */
    fun isTopTitle(kind: String): Boolean = base(kind) == "L0"
}
