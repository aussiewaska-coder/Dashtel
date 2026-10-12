package dev.dialer.voice.data

import android.content.Context

enum class ThemeMode { System, Light, Dark }

object UiPreferences {
    private const val FILE = "dashphone_ui"
    private const val THEME = "theme"
    private const val SIDEBAR_WIDTH = "sidebar_width"

    fun theme(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(THEME, ThemeMode.System.name).orEmpty(),
        )
    }.getOrDefault(ThemeMode.System)

    fun setTheme(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(THEME, mode.name).apply()
    }

    fun sidebarWidth(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(SIDEBAR_WIDTH, 340f)

    fun setSidebarWidth(context: Context, width: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat(SIDEBAR_WIDTH, width).apply()
    }
}
