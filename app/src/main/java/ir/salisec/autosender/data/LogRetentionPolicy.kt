package ir.salisec.autosender.data

/**
 * Operational diagnostics retention. Message history and approved campaign
 * records are not affected by this policy.
 */
object LogRetentionPolicy {
    const val RETENTION_DAYS = 90L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun cutoff(nowMillis: Long): Long =
        (nowMillis - RETENTION_DAYS * MILLIS_PER_DAY).coerceAtLeast(0L)

    fun shouldDelete(timestampMillis: Long, nowMillis: Long): Boolean =
        timestampMillis < cutoff(nowMillis)
}
