package fi.jommi.rivi

/** A cancelled or failed request must be retryable, without hammering an offline provider. */
internal class ArtworkRequest {
    private var pending = false
    private var retryAt = 0L
    fun begin(now: Long): Boolean {
        if (pending || now < retryAt) return false
        pending = true
        return true
    }
    fun complete(success: Boolean, now: Long) {
        pending = false
        retryAt = if (success) 0L else now + 30_000L
    }
    fun cancel() { pending = false }
}
