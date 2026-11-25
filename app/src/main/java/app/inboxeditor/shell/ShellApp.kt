package app.inboxeditor.shell

import android.app.Application
import android.webkit.WebView

/**
 * v62: process-entry WebView warm-up. A cold start pays the
 * Chromium engine initialisation exactly once per process; doing
 * it here, at process start, lets it overlap everything that
 * follows instead of sitting serially inside the first activity's
 * onCreate. The process is often started before any editor
 * activity exists at all — the system binds EditorTileService
 * when the quick-settings panel is built — so by the time the
 * user taps the tile (or the launcher icon) the engine is
 * already initialised and the activity's own WebView is cheap.
 *
 * The warm-up instance is created from the application context
 * (never an activity, so nothing can leak), is never attached to
 * a window, and is destroyed immediately: its only job is to make
 * the WebView provider load its native libraries and spin up the
 * engine processes. Any failure is swallowed — warm-up is a
 * best-effort optimisation, never a startup dependency.
 */
class ShellApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            val warm = WebView(applicationContext)
            warm.destroy()
        } catch (e: Exception) {
            // Engine warm-up failed (provider missing/disabled);
            // the activities will initialise on demand as before.
        }
    }
}
