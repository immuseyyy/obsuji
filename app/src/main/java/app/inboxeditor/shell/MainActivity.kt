package app.inboxeditor.shell

/**
 * Full-screen editor entry — the launcher icon's activity. All of
 * the WebView hosting, bridge plumbing, picker/recorder state and
 * back handling lives in EditorActivity; this host is the non-quick
 * variant (fullscreen window, double-press-to-exit at the root).
 * The Quick Settings tile no longer opens this activity — it opens
 * the floating QuickActivity card instead.
 */
class MainActivity : EditorActivity()
