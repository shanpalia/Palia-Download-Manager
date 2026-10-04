package com.shanpalia.pdm

import android.content.Context

/**
 * Legacy compatibility wrapper. Browser history is now stored and rendered by
 * PdmMainActivity so there is only one history implementation.
 */
object PdmWebHistory {
    fun record(context: Context, title: String?, url: String?) {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        saveBrowserVisit(context, clean, title)
    }
}
