package com.naivety.zhicolor.update

/** Main-thread state shared by the retained Activity ViewModel. */
class UpdateCheckSession {
    private var foreground = false
    private var automaticChecked = false
    private var inFlight = false
    private var manualRequested = false

    fun enterForeground(): Boolean {
        if (foreground) return false
        foreground = true
        if (automaticChecked) return false
        automaticChecked = true
        return begin(manual = false)
    }

    fun leaveForeground(changingConfigurations: Boolean) {
        if (!changingConfigurations) foreground = false
    }

    fun requestManual(): Boolean = begin(manual = true)

    private fun begin(manual: Boolean): Boolean {
        manualRequested = manualRequested || manual
        if (inFlight) return false
        inFlight = true
        return true
    }

    /** Returns whether this request acquired an explicit user action while it ran. */
    fun complete(): Boolean {
        val manual = manualRequested
        inFlight = false
        manualRequested = false
        return manual
    }
}
