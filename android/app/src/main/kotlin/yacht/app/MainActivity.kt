package yacht.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.Screen
import yacht.app.ui.AppRoot
import yacht.app.ui.YachtTheme
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class GameViewModel(app: Application) : AndroidViewModel(app) {
    val controller = AppController(viewModelScope, GameStorage(app.filesDir)) { loadTables(app) }

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
    return Tables(rules, TableIO.readEv(ev, rules), TableIO.readDist(dist, rules))
}

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YachtTheme(dark = isSystemInDarkTheme()) {
                val c = vm.controller
                BackHandler(enabled = c.screen != Screen.HOME) {
                    c.screen = if (c.screen == Screen.REVIEW) Screen.HISTORY else Screen.HOME
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    AppRoot(c)
                }
            }
        }
    }
}
