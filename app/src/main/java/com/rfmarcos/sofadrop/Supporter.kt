package com.rfmarcos.sofadrop

import android.app.Activity

/** The supporter purchase of Google Play builds: one payment for the clock's colours and a badge in About. See createSupporter. */
interface Supporter {

    enum class State { NOT_BOUGHT, PENDING, BOUGHT, UNAVAILABLE }

    /** Ask what the current Google account has bought. */
    fun refresh()

    fun buy(activity: Activity)

    fun close()
}
