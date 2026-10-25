package yacht.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Saving and opening files through the system's file picker (Android); absent on the desktop UI check. */
interface FilePort {
    /** Asks where to save [bytes] as [name]; [done] gets whether it was written. */
    fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit)

    /**
     * Asks for a file to open; [got] gets its bytes (read off the main thread), or null when
     * cancelled, or a problem to show (too big, unreadable).
     */
    fun open(got: (bytes: ByteArray?, problem: String?) -> Unit)
}

val LocalFilePort = staticCompositionLocalOf<FilePort?> { null }
