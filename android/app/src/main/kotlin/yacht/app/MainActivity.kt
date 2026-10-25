package yacht.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.Screen
import yacht.app.ui.AppRoot
import yacht.app.ui.FilePort
import yacht.app.ui.LocalFilePort
import yacht.app.ui.YachtTheme
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes why the app closed (any uncaught exception, out of memory included) to crash.txt, then
 * lets Android close it as usual; the home screen shows it at the next start.
 */
private object CrashLog {
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
                java.io.File(app.filesDir, "crash.txt").writeText(
                    "$time · ${app.packageName} $version · ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n" +
                        "thread ${thread.name}\n${e.stackTraceToString().take(6000)}",
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }
}

class GameViewModel(app: Application) : AndroidViewModel(app) {
    init {
        CrashLog.install(app)
    }

    val controller = AppController(
        viewModelScope, GameStorage(app.filesDir), { loadTables(app) },
        loadAsset = { name -> runCatching { app.assets.open(name).use { String(it.readBytes()) } }.getOrNull() },
    )

    init {
        controller.start()
    }

    override fun onCleared() = controller.shutdown()
}

/** ev.bin is small and read into memory; dist.bin (~50 MB) is memory-mapped from the APK. */
private fun loadTables(context: Context): Tables {
    val rules = RuleSet.NINTENDO
    val ev = context.assets.open("ev.bin").use { ByteBuffer.wrap(it.readBytes()) }
    val dist = context.assets.openFd("dist.bin").use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
    val bonus = runCatching {
        context.assets.open("bonus.bin").use { TableIO.readBonus(ByteBuffer.wrap(it.readBytes()), rules) }
    }.getOrNull()
    return Tables(rules, TableIO.readEv(ev, rules), TableIO.readDist(dist, rules), bonus = bonus)
}

/** A file request waiting for the system picker. */
private class PendingFiles {
    var save: Pair<ByteArray, (Boolean) -> Unit>? = null
    var open: ((ByteArray?, String?) -> Unit)? = null
}

/** A backup is a few MB at most: anything far bigger is the wrong file (and would not fit in memory). */
private const val MAX_BACKUP_BYTES = 64L shl 20

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.controller.onForeground()
    }

    override fun onStop() {
        // Nothing runs in the background while the app is hidden (battery).
        vm.controller.onBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(application)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Backups go through the system file picker; the request waits here for its answer.
            val pending = remember { PendingFiles() }
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                val p = pending.save
                pending.save = null
                // Cancelled: nothing to report.
                if (p != null && uri != null) {
                    // Off the main thread: the target can be a cloud folder.
                    vm.viewModelScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(p.first) } }.isSuccess
                        }
                        p.second(ok)
                    }
                }
            }
            val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val cb = pending.open
                pending.open = null
                if (cb == null || uri == null) return@rememberLauncherForActivityResult
                // Off the main thread: a file in Drive downloads while it is read.
                vm.viewModelScope.launch {
                    val read = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openInputStream(uri)!!.use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buf = ByteArray(1 shl 16)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_BACKUP_BYTES) return@runCatching null
                                    out.write(buf, 0, n)
                                }
                                out.toByteArray()
                            }
                        }
                    }
                    when {
                        read.isFailure -> cb(null, "파일을 읽지 못했어요.")
                        read.getOrNull() == null -> cb(null, "백업 파일이 아니에요(너무 커요).")
                        else -> cb(read.getOrNull(), null)
                    }
                }
            }
            val port = remember(saver, opener) {
                object : FilePort {
                    override fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit) {
                        pending.save = bytes to done
                        saver.launch(name)
                    }

                    override fun open(got: (ByteArray?, String?) -> Unit) {
                        pending.open = got
                        opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }
            }
            YachtTheme(dark = isSystemInDarkTheme()) {
                val c = vm.controller
                BackHandler(enabled = c.screen != Screen.HOME) {
                    if (c.screen == Screen.WATCH) c.league.stopWatch()
                    c.screen = when (c.screen) {
                        Screen.REVIEW -> Screen.HISTORY
                        Screen.WATCH, Screen.BUILDER -> Screen.LEAGUE
                        else -> Screen.HOME
                    }
                }
                CompositionLocalProvider(LocalFilePort provides port) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppRoot(c)
                    }
                }
            }
        }
    }
}
