package yacht.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import yacht.app.core.AppController
import yacht.app.core.LeagueBackground
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

    /**
     * The app's work. Not viewModelScope: Android cancels that before onCleared, which would
     * drop the league's last save when the app is closed; this one ends after that save.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = AppController(
        scope, GameStorage(app.filesDir), { loadTables(app) },
        loadAsset = { name -> runCatching { app.assets.open(name).use { String(it.readBytes()) } }.getOrNull() },
    )

    init {
        controller.start()
        // The league kept going while the app is hidden: its service runs while the league does
        // in that mode (a short stop, like a speed change, does not end it).
        LeagueService.stopLeague = { scope.launch { controller.league.stop() } }
        scope.launch {
            snapshotFlow { controller.league.running && controller.league.background == LeagueBackground.KEEP }
                .distinctUntilChanged()
                .collectLatest { keep ->
                    if (keep) {
                        LeagueService.start(app)
                    } else {
                        delay(1_500)
                        LeagueService.stop(app)
                    }
                }
        }
    }

    override fun onCleared() {
        LeagueService.stopLeague = null
        LeagueService.stop(getApplication())
        val save = controller.shutdown()
        scope.launch {
            save?.join()
            scope.cancel()
        }
    }
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

/** A backup is a few MB at most: anything far bigger is the wrong file (and would not fit in memory). */
private const val MAX_BACKUP_BYTES = 64L shl 20

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.controller.onForeground()
        // 나가도 계속: Android may have refused the service while the app was hidden; in view it can start.
        val lg = vm.controller.league
        if (lg.running && lg.background == LeagueBackground.KEEP) LeagueService.start(this)
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
            // Backups go through the system file picker. Nothing waits in the composition for
            // the answer (the activity can be recreated meanwhile): the backup is built when the
            // place to save it arrives, and the result goes to the controller.
            val c0 = vm.controller
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                vm.viewModelScope.launch {
                    c0.backupNote = "백업 파일을 저장하는 중…"
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = c0.backupBytes()
                            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                        }.isSuccess
                    }
                    c0.backupNote = if (ok) "백업 파일을 저장했어요." else "저장하지 못했어요."
                }
            }
            val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                // Off the main thread: a file in Drive downloads while it is read.
                vm.viewModelScope.launch {
                    c0.backupNote = "불러오는 중…"
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
                    val bytes = read.getOrNull()
                    when {
                        read.isFailure -> c0.backupNote = "파일을 읽지 못했어요."
                        bytes == null -> c0.backupNote = "백업 파일이 아니에요(너무 커요)."
                        else -> c0.restore(bytes) { c0.backupNote = it }
                    }
                }
            }
            // ‘나가도 계속’ shows a notification while the league runs: asked for when chosen.
            val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            DisposableEffect(notify) {
                c0.requestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                onDispose { c0.requestNotifications = {} }
            }
            val port = remember(saver, opener) {
                object : FilePort {
                    override fun save(name: String) = saver.launch(name)
                    override fun open() = opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            }
            YachtTheme(dark = isSystemInDarkTheme()) {
                val c = vm.controller
                // Back on the home screen sends the app back (as Android 12+ does) instead of closing
                // it: closing would stop a league that keeps going while the app is hidden.
                BackHandler {
                    if (c.screen == Screen.HOME) {
                        moveTaskToBack(true)
                        return@BackHandler
                    }
                    if (c.screen == Screen.WATCH) c.league.stopWatch()
                    c.screen = when (c.screen) {
                        Screen.REVIEW -> Screen.HISTORY
                        Screen.WATCH, Screen.BUILDER, Screen.MAKER -> Screen.LEAGUE
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
