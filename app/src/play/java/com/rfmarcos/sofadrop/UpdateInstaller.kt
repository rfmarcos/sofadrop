package com.rfmarcos.sofadrop

import android.content.Context

/** Google Play updates the app: this build has no installer, and [Updater.available] is never true in it. */
object UpdateInstaller {
    fun install(context: Context): Unit = throw UnsupportedOperationException("Google Play updates this build")
}
