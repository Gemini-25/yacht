package yacht.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Saving and opening files through the system's file picker (Android); absent on the desktop UI check. */
interface FilePort {
    /** Asks where to save a backup named [name]; the backup is built then (see AppController.backupNote). */
    fun save(name: String)

    /** Asks for a backup to open and restores it (see AppController.backupNote). */
    fun open()
}

val LocalFilePort = staticCompositionLocalOf<FilePort?> { null }
