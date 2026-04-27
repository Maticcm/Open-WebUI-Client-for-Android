package com.maticcm.openwebuiclient

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isTouchExplorationEnabled) {
            WebView.enableSlowWholeDocumentDraw()
        }
    }
}
