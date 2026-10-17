package fi.jommi.rivi

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Bounded streaming shared by downloads and deterministic failure/cancellation tests. */
internal object UpdateTransfer {
    fun copy(input: InputStream, output: OutputStream, expectedBytes: Long, maximumBytes: Long,
             checkActive: () -> Unit, progress: (Int) -> Unit = {}): Long {
        if (expectedBytes > maximumBytes) throw IOException("The update exceeds the download limit.")
        val bytes = ByteArray(32 * 1024)
        var total = 0L
        var lastProgress = -2
        while (true) {
            checkActive()
            val count = input.read(bytes)
            if (count < 0) break
            checkActive()
            total += count
            if (total > maximumBytes) throw IOException("The update exceeds the download limit.")
            output.write(bytes, 0, count)
            val percent = if (expectedBytes > 0) (total * 100 / expectedBytes).toInt().coerceAtMost(100) else -1
            if (percent != lastProgress) { lastProgress = percent; progress(percent) }
        }
        if (expectedBytes >= 0 && expectedBytes != total) throw IOException("The download ended before the APK was complete.")
        return total
    }
}
