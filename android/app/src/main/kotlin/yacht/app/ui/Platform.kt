package yacht.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Saving and opening files through the system's file picker (Android); absent on the desktop UI check. */
interface FilePort {
    /** Asks where to save [bytes] as [name]; [done] gets whether it was written. */
    fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit)

    /** Asks for a file to open; [got] gets its bytes, or null when cancelled. */
    fun open(got: (ByteArray?) -> Unit)
}

val LocalFilePort = staticCompositionLocalOf<FilePort?> { null }
