package com.justb81.compassduel.game.kids

/**
 * End-of-round awards for Kids Mode. There is no "winner vs. losers" screen:
 * every player receives exactly one award, and the fallback award is a
 * cheerful participation badge rather than a ranking.
 */
enum class KidsAward {

    /** Most stars collected this round. */
    STAR_CHAMPION,

    /** Most sparkles blocked with the magic bubble. */
    BUBBLE_HERO,

    /** Most sparkles tossed — rewards joining in, hit or miss. */
    BUSY_BEE,

    /** Everyone else: played the whole round, sparkled the whole time. */
    SUPER_SPARKLER,
}

/** Per-player counters the host accumulates over one Kids Mode round. */
data class KidsRoundStats(
    val playerId: Int,
    val stars: Int,
    val bubbleBlocks: Int,
    val sparklesThrown: Int,
)

/**
 * Assigns exactly one award to every player.
 *
 * Category awards are handed out in priority order (champion, bubble hero,
 * busy bee) and only when actually earned (metric > 0), so a single player
 * never collects two awards and a category nobody earned is simply skipped.
 * Ties go to the lower player id, keeping the result deterministic on every
 * device.
 */
fun assignAwards(stats: List<KidsRoundStats>): Map<Int, KidsAward> {
    val awards = mutableMapOf<Int, KidsAward>()
    val remaining = stats.sortedBy { it.playerId }.toMutableList()

    fun handOut(award: KidsAward, metric: (KidsRoundStats) -> Int) {
        val best = remaining.maxByOrNull(metric) ?: return
        if (metric(best) > 0) {
            awards[best.playerId] = award
            remaining.remove(best)
        }
    }

    handOut(KidsAward.STAR_CHAMPION) { it.stars }
    handOut(KidsAward.BUBBLE_HERO) { it.bubbleBlocks }
    handOut(KidsAward.BUSY_BEE) { it.sparklesThrown }
    remaining.forEach { awards[it.playerId] = KidsAward.SUPER_SPARKLER }
    return awards
}
