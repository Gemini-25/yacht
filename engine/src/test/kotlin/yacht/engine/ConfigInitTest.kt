package yacht.engine

import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * The engine's classes loaded afresh, so that a test sees their initialisation in the order it
 * touches them (the test classes of a run share one JVM and the classes it has loaded).
 */
private object FreshEngine {
    private val engine = LeagueConfig::class.java.protectionDomain.codeSource.location
    private val stdlib = Unit::class.java.protectionDomain.codeSource.location

    fun loader(): ClassLoader = URLClassLoader(arrayOf(engine, stdlib), ClassLoader.getPlatformClassLoader())

    fun real(cl: ClassLoader): Any? {
        val preset = Class.forName("yacht.engine.league.LeaguePreset", true, cl)
        val real = preset.getMethod("valueOf", String::class.java).invoke(null, "REAL")
        return real.javaClass.getMethod("getConfig").invoke(real)
    }

    fun default(cl: ClassLoader): Any? {
        val config = Class.forName("yacht.engine.league.LeagueConfig", true, cl)
        val companion = config.getField("Companion").get(null)
        return companion.javaClass.getMethod("getDEFAULT").invoke(companion)
    }

    fun encode(config: Any?): String = assertNotNull(config).javaClass.getMethod("encode").invoke(config) as String

    /** Both are whole: they save as the classes loaded the usual way do. */
    fun check(real: Any?, default: Any?) {
        assertNotSame<Class<*>>(LeagueConfig::class.java, assertNotNull(default).javaClass, "a fresh copy of the classes")
        assertEquals(LeagueConfig.DEFAULT.encode(), encode(default))
        assertEquals(LeaguePreset.REAL.config.encode(), encode(real))
        // Before the flip to national leagues the preset is the default without them.
        assertEquals(LeagueConfig.DEFAULT.copy(national = false, doubleRoundRobin = false), LeaguePreset.REAL.config)
    }
}

/** [LeaguePreset.REAL] (which points at [LeagueConfig.DEFAULT]) touched first: both are whole. */
class ConfigInitPresetFirstTest {
    @Test
    fun thePresetFirstThenTheDefault() {
        val cl = FreshEngine.loader()
        val real = FreshEngine.real(cl)
        FreshEngine.check(real, FreshEngine.default(cl))
    }
}

/** [LeagueConfig.DEFAULT] touched first: both are whole. */
class ConfigInitDefaultFirstTest {
    @Test
    fun theDefaultFirstThenThePreset() {
        val cl = FreshEngine.loader()
        val default = FreshEngine.default(cl)
        FreshEngine.check(FreshEngine.real(cl), default)
    }
}
