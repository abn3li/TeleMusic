package com.abn3li.telemusic.data.quality

internal data class AutomaticCacheFile(val size: Long, val lastPlayed: Long,
    val remove: suspend () -> Long)

/** Protected files still count toward the budget, but their remove function returns zero. */
internal suspend fun trimAutomaticCache(files: List<AutomaticCacheFile>, limit: Long) {
    var total = files.sumOf { it.size }
    for (file in files.sortedBy { it.lastPlayed }) {
        if (total <= limit) break
        total -= file.remove()
    }
}
