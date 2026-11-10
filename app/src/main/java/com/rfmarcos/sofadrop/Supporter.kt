package com.rfmarcos.sofadrop

import android.app.Activity

/** The supporter purchase of Google Play builds: one payment for the clock's colours and a badge in About. See createSupporter. */
interface Supporter {

    enum class State { NOT_BOUGHT, PENDING, BOUGHT, UNAVAILABLE }

    /** Ask what the current Google account has bought. */
    fun refresh()

    fun buy(activity: Activity)

    fun close()

    companion object {
        /** Keep what Google Play said, for the screensaver's clock colour (see [Settings.screensaverClockColorShown]). */
        fun remember(state: State) {
            when (state) {
                State.BOUGHT -> Settings.supporterBought = true
                State.NOT_BOUGHT -> Settings.supporterBought = false
                State.PENDING, State.UNAVAILABLE -> Unit
            }
        }
    }
}
