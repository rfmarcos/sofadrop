package com.rfmarcos.sofadrop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Forks of a title app under another package are found by the links they open (issue #25). */
@RunWith(AndroidJUnit4::class)
class TitleAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    /** The test APK opens nuvio:// links (see the androidTest manifest), as a fork of Nuvio would. */
    @Test
    fun aForkIsFoundByItsLinks() {
        assertEquals(instrumentation.context.packageName, TitleApp.NUVIO.installedPackage(app))
        assertEquals(TitleApp.NUVIO, TitleApp.installed(app).firstOrNull { it == TitleApp.NUVIO })
    }

    /** Without Stremio or a fork of it, nothing is found (the emulator may have WuPlay, so not that one). */
    @Test
    fun noAppNoFork() {
        assumeFalse(runCatching { app.packageManager.getPackageInfo("com.stremio.one", 0) }.isSuccess)
        assertNull(TitleApp.STREMIO.installedPackage(app))
    }
}
