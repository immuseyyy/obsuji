package app.inboxeditor.shell

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared WebView host for the editor page: owns the WebView, the
 * OE_Native bridge plumbing (pickers, recorder, file opening), the
 * window chrome theming and the layered back handling. Two hosts
 * share everything — MainActivity (full screen, launcher entry)
 * and QuickActivity (floating quick-capture card, QS tile entry) —
 * so the store dir, config, draft and attachment registry on disk
 * are literally the same files for both.
 */
abstract class EditorActivity : ComponentActivity() {

    /**
     * True only in the floating quick-capture host. The page reads
     * it through the isQuickCapture bridge method and switches to
     * quick behaviour (auto-focus, close-after-send, back closes
     * the window); the back handler below also finishes at the
     * root instead of the fullscreen double-press-to-exit.
     */
    open val quickCapture: Boolean
        get() = false

    /**
     * v73: the store dir this process settled on, resolved by
     * [StoreMigration] at the top of onCreate — before the WebView
     * exists, so before the page can touch the store at all. The
     * page reads it through the storeDir bridge method and uses it
     * for every store read/write of the session.
     */
    lateinit var storeDirPath: String
        private set

    protected lateinit var webView: WebView

    /**
     * v68: the system's own IME visibility, cached on the UI thread
     * by the OnApplyWindowInsetsListener installed in onCreate. The
     * page snapshots keyboard state before a temporary flow (path
     * picker, system picker, recorder) takes the keyboard down, so
     * when the flow ends it can restore exactly the state the user
     * had — never a guess derived from window heights.
     * v69: the keyboardOpen bridge no longer reads this cache as
     * its answer — on the user's device every v68 restore silently
     * never happened, so the snapshot source was replaced with a
     * live root-insets query (queryKeyboardOpenLive below). This
     * cache survives only as the fallback when that query cannot
     * complete.
     */
    @Volatile
    var imeVisibleCached: Boolean = false
        private set

    fun isKeyboardOpen(): Boolean = imeVisibleCached

    /**
     * v69: live IME visibility, read from the window's current
     * root insets at call time — the system's present-tense state,
     * not a listener-maintained copy. Must be called on the UI
     * thread (the bridge hops threads and waits briefly).
     */
    fun queryKeyboardOpenLive(): Boolean {
        return try {
            val insets = ViewCompat.getRootWindowInsets(webView)
            if (insets != null) {
                insets.isVisible(WindowInsetsCompat.Type.ime())
            } else {
                imeVisibleCached
            }
        } catch (e: Exception) {
            imeVisibleCached
        }
    }

    /**
     * v69: rebuild the WebView's IME connection. Coming back from
     * a system picker can leave the input connection dead, in
     * which case showSoftInput is silently dropped even with the
     * caret sitting in the editor; restartInput rebinds the
     * connection to the currently focused node. The page's
     * restore chain calls this right before showSoftKeyboard()
     * on every round. Must be called on the UI thread.
     */
    fun restartWebViewInput() {
        if (!::webView.isInitialized) return
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.restartInput(webView)
        } catch (e: Exception) {
            // Input plumbing is best-effort; the restore chain retries.
        }
    }

    // ---- v77: system share-in (ACTION_SEND -> QuickActivity) ----
    // A share intent is parsed into a small JSON payload
    // ({"text": ...|null, "image": {"name","path"}|null}) and
    // QUEUED here. The page pulls one payload at a time through
    // the takePendingShare bridge call — at the end of its init
    // (cold start) and from the OE_onShareIntent hook (window
    // already running). Queueing in the shell instead of pushing
    // into the page is what makes a share un-losable: a payload
    // leaves the queue only when the page actually takes it, so
    // a share that arrives while the page is still loading (or
    // while a recording is live, where the page holds delivery
    // itself) simply waits its turn.
    private val shareQueueLock = Object()
    private val shareQueue = ArrayDeque<String>()

    /**
     * Parse a share intent into the queue. QuickActivity calls
     * this from onCreate (cold start via the launch intent) and
     * onNewIntent (the card is singleTop, so a second share
     * while it is open arrives there). Any action other than
     * ACTION_SEND is ignored, so the QS tile's explicit launch
     * never queues anything. A shared image stream is
     * stream-copied into the store's pending/ staging dir on a
     * background thread — the same treatment handlePickedUri
     * gives photo-picker URIs — and the payload is queued only
     * once the staged file exists, so the page can register it
     * exactly like a camera photo (staged attachment, copied
     * into the attachment folder at send time).
     */
    fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = try {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } catch (e: Exception) {
            null
        }
        val stream: Uri? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        } catch (e: Exception) {
            null
        }
        val type = intent.type ?: ""
        if (stream != null && type.startsWith("image/")) {
            Thread {
                var imageJson: JSONObject? = null
                try {
                    val dir = File(storeDirPath, "pending")
                    dir.mkdirs()
                    val name = queryDisplayName(stream) ?: fallbackName(stream)
                    val dest = NativeBridge.uniqueDest(dir, name)
                    contentResolver.openInputStream(stream).use { input ->
                        if (input == null) throw IllegalStateException("cannot_open_uri")
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    val o = JSONObject()
                    o.put("name", dest.name)
                    o.put("path", dest.absolutePath)
                    imageJson = o
                } catch (e: Exception) {
                    Log.w("ShareIn", "shared image staging failed", e)
                }
                queueSharePayload(text, imageJson)
                notifyPageOfShare()
            }.start()
        } else {
            if (text.isNullOrEmpty()) return
            queueSharePayload(text, null)
            notifyPageOfShare()
        }
    }

    private fun queueSharePayload(text: String?, image: JSONObject?) {
        if (text.isNullOrEmpty() && image == null) return
        val o = JSONObject()
        o.put("text", if (text.isNullOrEmpty()) JSONObject.NULL else text)
        o.put("image", image ?: JSONObject.NULL)
        synchronized(shareQueueLock) { shareQueue.addLast(o.toString()) }
    }

    /** Bridge pull: remove and return the oldest queued share payload, or null. */
    fun takePendingSharePayload(): String? {
        return synchronized(shareQueueLock) {
            if (shareQueue.isEmpty()) null else shareQueue.removeFirst()
        }
    }

    /**
     * Tell an already-loaded page that a share is waiting. The
     * page's hook pulls from the queue; if the page is not ready
     * yet the call is a no-op and the payload stays queued for
     * the init-end pull, so firing it is always safe.
     */
    fun notifyPageOfShare() {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.OE_onShareIntent && window.OE_onShareIntent()", null
                )
            }
        }
    }

    // ---- WebView <input type=file> chooser (fallback path) ----
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            if (cb == null) return@registerForActivityResult
            val results = if (result.resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            cb.onReceiveValue(results)
        }

    // ---- OE_Native pickFile state ----
    // v42: pendingPickStagingDir is a staging dir inside the editor's own
    // store dir (<store dir>/pending). Nothing is copied into the
    // vault's attachment folder at pick time any more; the page copies
    // the reported source into the attachment folder when a note is sent.
    private var pendingPickCallbackId: String? = null
    private var pendingPickStagingDir: String? = null
    private var pendingPhotoFile: File? = null
    private var pendingPhotoCallbackId: String? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val cbId = pendingPickCallbackId
            val stagingDir = pendingPickStagingDir
            pendingPickCallbackId = null
            pendingPickStagingDir = null
            if (cbId == null || stagingDir == null) return@registerForActivityResult
            if (uri == null) {
                deliverPickResult(cbId, false, "cancelled", null, null, false)
            } else {
                handlePickedUri(uri, stagingDir, cbId)
            }
        }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cbId = pendingPickCallbackId
            val stagingDir = pendingPickStagingDir
            pendingPickCallbackId = null
            pendingPickStagingDir = null
            if (cbId == null || stagingDir == null) return@registerForActivityResult
            if (uri == null) {
                deliverPickResult(cbId, false, "cancelled", null, null, false)
            } else {
                handlePickedUri(uri, stagingDir, cbId)
            }
        }

    // ---- OE_Native recording state (v52) ----
    // The recorder lives here, not in the page: the page only toggles
    // start/stop and times the button label itself. recordStartCallbackId
    // / recordStartStagingDir are set while a RECORD_AUDIO permission
    // request launched by startRecord is still unanswered.
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartElapsedMs = 0L
    private var recordStartCallbackId: String? = null
    private var recordStartStagingDir: String? = null
    // v53: pause bookkeeping. recordPausedTotalMs banks the time spent
    // paused across earlier pause/resume cycles of the current take;
    // recordPauseStartMs marks the current pause (valid only while
    // recordPaused). stopRecord's durationMs excludes all paused time.
    private var recordPaused = false
    private var recordPauseStartMs = 0L
    private var recordPausedTotalMs = 0L

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cbId = recordStartCallbackId
            val stagingDir = recordStartStagingDir
            recordStartCallbackId = null
            recordStartStagingDir = null
            if (cbId == null || stagingDir == null) return@registerForActivityResult
            if (granted) {
                beginRecording(stagingDir, cbId)
            } else {
                deliverRecordResult(cbId, false, "permission_denied", null, null, null)
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val cbId = pendingPhotoCallbackId
            val file = pendingPhotoFile
            pendingPhotoCallbackId = null
            pendingPhotoFile = null
            if (cbId == null) return@registerForActivityResult
            if (success && file != null && file.isFile && file.length() > 0L) {
                deliverPickResult(cbId, true, null, file.name, file.absolutePath, true)
            } else {
                if (file != null && file.exists() && file.length() == 0L) file.delete()
                deliverPickResult(cbId, false, "cancelled", null, null, false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v73: settle the store dir first — including the one-time
        // migration out of the Tasker-era folder — so no page read
        // or write can ever land on a half-migrated new dir.
        storeDirPath = StoreMigration.resolve(this)

        webView = WebView(this)
        setContentView(webView)

        // v68: keep a live cache of IME visibility. The listener
        // only observes: it returns the insets unconsumed, so the
        // window's existing adjustResize behaviour is untouched.
        // v69: keyboardOpen() queries live root insets instead and
        // uses this cache only as its fallback.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            imeVisibleCached = insets.isVisible(WindowInsetsCompat.Type.ime())
            insets
        }

        // v54: before the page has resolved its theme and called the
        // setTheme bridge, paint the window chrome from the system's
        // own light/dark truth so the first frame never opens with a
        // mismatched (black) status bar.
        applyChromeTheme(
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        )

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
        // v49: the page owns its palette (light / dark / follow-system via
        // the native systemTheme() bridge). Turn the WebView's algorithmic
        // darkening off so a page resolved to light is not darkened a
        // second time by the WebView itself. Guarded: older WebView
        // providers without the API just skip this.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            }
        } catch (e: Exception) {
            // No algorithmic-darkening API on this WebView; nothing to do.
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                return when (url.scheme?.lowercase(Locale.ROOT)) {
                    "http", "https", "file", "about" -> false
                    else -> {
                        // Non-web scheme (e.g. obsidian:// from History's
                        // "open" link): the WebView cannot load it as a page
                        // (ERR_UNKNOWN_URL_SCHEME), so hand it to the system
                        // and keep the editor page untouched.
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this@EditorActivity, "没有应用能打开这个链接", Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@EditorActivity, "没有应用能打开这个链接", Toast.LENGTH_LONG
                            ).show()
                        }
                        true
                    }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                if (intent == null) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    false
                }
            }
        }
        webView.addJavascriptInterface(NativeBridge(this), "OE_Native")
        webView.loadUrl("file:///android_asset/editor.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Ask the page first: window.OE_handleBack() returns "1" when
                // it consumed the press (closed a menu/panel, left History,
                // or saved-and-returned from the settings page), "0" (or the
                // function is absent) when it did not. The decision is made
                // inside the async evaluateJavascript callback.
                val js = "(function(){try{" +
                    "return (typeof window.OE_handleBack==='function')" +
                    " ? String(window.OE_handleBack()) : '0';" +
                    "}catch(e){return '0';}})()"
                webView.evaluateJavascript(js) { result ->
                    if (result == "\"1\"") {
                        // Page consumed the press; nothing more to do.
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else if (quickCapture) {
                        // v56: quick capture — back at the root closes
                        // the floating window; there is no double-press
                        // exit here. The page autosaves its draft, and
                        // in quick mode it usually closes the window
                        // itself via closeQuick before this fallback.
                        finish()
                    } else {
                        // Top level: double-press to exit. A second press
                        // within 2 s finishes; the first only warns.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastExitBackPressMs < EXIT_DOUBLE_PRESS_MS) {
                            finish()
                        } else {
                            lastExitBackPressMs = now
                            Toast.makeText(
                                this@EditorActivity, "再按一次返回退出", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })

        ensureStorageAccess()
    }

    /**
     * v56: the same draft.txt backs both windows (this full-screen
     * host and the quick-capture card). When this window returns to
     * the front, hand the decision to the page: it adopts the
     * on-disk draft only when its own text still equals what it
     * last synced, so a quick-capture edit shows up here without
     * ever overwriting unsaved local typing.
     */
    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.OE_reloadDraftIfChanged && window.OE_reloadDraftIfChanged()",
                null
            )
        }
    }

    /**
     * v57: the reliable "this window is now really in front" signal.
     * The page's startup focus attempts are timed shots that can land
     * while the window is still animating in (the quick-capture card
     * is launched over the collapsing quick-settings shade), and a
     * focus() issued before the window holds focus never raises the
     * IME — with nothing re-issuing it afterwards, the card opened
     * with no caret and no keyboard. So when window focus arrives,
     * first give the WebView itself view focus, then let the page
     * re-run its (quick-mode-only) focus routine. Full-screen mode
     * is unaffected: the page ignores OE_onWindowFocus there.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::webView.isInitialized) {
            webView.requestFocus()
            webView.evaluateJavascript(
                "window.OE_onWindowFocus && window.OE_onWindowFocus()",
                null
            )
        }
    }

    /**
     * v58: raise the soft keyboard for the editor, explicitly. The
     * page's JS focus() moves the DOM caret into the textarea but
     * does not bring the IME up in a WebView — after the v57 focus
     * fix the caret landed in the text on open, yet no keyboard
     * appeared. Only an explicit showSoftInput on the focused view
     * raises it. The page calls this through the showKeyboard
     * bridge from exactly its two startup focus paths, after
     * verifying the caret is in the editor; nothing else may call
     * it, so a keyboard the user dismissed by hand is never forced
     * back up by toolbar or insert refocuses. The show is posted so
     * it lands after the focus request has settled on the view — a
     * same-tick showSoftInput can be dropped by the IME.
     */
    fun showSoftKeyboard() {
        if (!::webView.isInitialized) return
        try {
            webView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            webView.post { imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT) }
        } catch (e: Exception) {
            // Keyboard raising is a convenience; never break over it.
        }
    }

    override fun onDestroy() {
        // v52: never leave the microphone held by a dying activity; a
        // half-finished recording is discarded with it.
        releaseRecorder(deleteFile = true)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /**
     * v54: window chrome follows the page's resolved theme. The
     * status bar and navigation bar take the page's own background
     * colour (light #ffffff / dark #121214, the --bg values in
     * editor.html) so the UI reads as one surface, the bar icons
     * flip with it (dark icons on the light bar, light icons on the
     * dark bar), and the WebView's own background gets the same
     * colour so no seam shows while the page paints. Driven by the
     * page through the setTheme bridge on every theme application;
     * onCreate seeds it once from the system truth.
     */
    fun applyChromeTheme(dark: Boolean) {
        val color = if (dark) CHROME_BG_DARK else CHROME_BG_LIGHT
        try {
            window.statusBarColor = color
            window.navigationBarColor = color
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        } catch (e: Exception) {
            // Chrome colouring is cosmetic; never let it break startup.
        }
        if (::webView.isInitialized) {
            try {
                webView.setBackgroundColor(color)
            } catch (e: Exception) {
                // Same: cosmetic only.
            }
        }
    }

    /**
     * v49: uiMode is in the manifest's configChanges, so a system
     * light/dark switch does not recreate the activity or reload the
     * page; instead the new truth is pushed into the page, which
     * re-resolves only when its theme setting is "follow system".
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::webView.isInitialized) return
        val mode = if (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) {
            "dark"
        } else {
            "light"
        }
        webView.evaluateJavascript(
            "window.OE_systemThemeChanged && window.OE_systemThemeChanged('$mode')",
            null
        )
    }

    // ---------- double-press back to exit (top level) ----------

    private var lastExitBackPressMs = 0L

    private companion object {
        const val EXIT_DOUBLE_PRESS_MS = 2000L

        // v54: window-chrome colours, kept byte-identical to the
        // page's --bg in editor.html (light / dark).
        val CHROME_BG_LIGHT: Int = Color.parseColor("#ffffff")
        val CHROME_BG_DARK: Int = Color.parseColor("#121214")
    }

    // ---------- storage access (MANAGE_EXTERNAL_STORAGE) ----------

    fun storageAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureStorageAccess() {
        if (storageAccessGranted()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage("收件箱编辑器需要「所有文件访问」权限，才能把笔记直接写进 Obsidian 库文件夹。接下来会打开系统设置页，请为本应用打开这个开关。")
                .setPositiveButton("去设置") { _, _ -> openStorageAccessSettings() }
                .setNegativeButton("稍后再说", null)
                .show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    fun openStorageAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开设置页，请到系统设置里手动授予「所有文件访问」", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- OE_Native pickFile implementation ----------

    fun startPick(kind: String, stagingDir: String, callbackId: String) {
        when (kind) {
            "image" -> {
                pendingPickCallbackId = callbackId
                pendingPickStagingDir = stagingDir
                try {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } catch (e: Exception) {
                    // No photo picker on this device: fall back to document picker.
                    try {
                        openDocumentLauncher.launch(arrayOf("image/*"))
                    } catch (e2: Exception) {
                        pendingPickCallbackId = null
                        pendingPickStagingDir = null
                        deliverPickResult(callbackId, false, e2.message ?: "picker_unavailable", null, null, false)
                    }
                }
            }
            "file" -> {
                pendingPickCallbackId = callbackId
                pendingPickStagingDir = stagingDir
                try {
                    openDocumentLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    pendingPickCallbackId = null
                    pendingPickStagingDir = null
                    deliverPickResult(callbackId, false, e.message ?: "picker_unavailable", null, null, false)
                }
            }
            "photo" -> {
                // v42: the camera writes into the staging dir, not the
                // attachment folder; the page copies it on send.
                val dir = File(stagingDir)
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val file = File(dir, "IMG_$stamp.jpg")
                val uri = try {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } catch (e: Exception) {
                    deliverPickResult(callbackId, false, e.message ?: "fileprovider_error", null, null, false)
                    return
                }
                pendingPhotoFile = file
                pendingPhotoCallbackId = callbackId
                try {
                    takePictureLauncher.launch(uri)
                } catch (e: Exception) {
                    pendingPhotoFile = null
                    pendingPhotoCallbackId = null
                    deliverPickResult(callbackId, false, e.message ?: "camera_unavailable", null, null, false)
                }
            }
            else -> deliverPickResult(callbackId, false, "unknown_kind", null, null, false)
        }
    }

    // ---------- OE_Native recording implementation (v52) ----------
    // Every entry point reports exactly one result through
    // window.OE_nativeCallback and never throws into the page: bad
    // states (double start, stop while idle, a recorder error) come
    // back as ok:false with a short reason string.

    fun startRecord(stagingDir: String, callbackId: String) {
        if (recorder != null || recordStartCallbackId != null) {
            deliverRecordResult(callbackId, false, "already_recording", null, null, null)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordStartCallbackId = callbackId
            recordStartStagingDir = stagingDir
            try {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } catch (e: Exception) {
                recordStartCallbackId = null
                recordStartStagingDir = null
                deliverRecordResult(
                    callbackId, false, e.message ?: "permission_request_failed", null, null, null
                )
            }
            return
        }
        beginRecording(stagingDir, callbackId)
    }

    private fun beginRecording(stagingDir: String, callbackId: String) {
        var rec: MediaRecorder? = null
        var file: File? = null
        try {
            val dir = File(stagingDir)
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            var out = File(dir, "录音-$stamp.m4a")
            var n = 1
            while (out.exists()) {
                out = File(dir, "录音-$stamp-$n.m4a")
                n++
            }
            file = out
            rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordFile = file
            recordStartElapsedMs = SystemClock.elapsedRealtime()
            recordPaused = false
            recordPauseStartMs = 0L
            recordPausedTotalMs = 0L
            val o = JSONObject()
            o.put("ok", true)
            o.put("started", true)
            o.put("name", file.name)
            deliverRecordCallback(callbackId, o)
        } catch (e: Exception) {
            try { rec?.release() } catch (ignored: Exception) {}
            if (file != null && file.exists()) file.delete()
            if (recorder === rec) {
                recorder = null
                recordFile = null
            }
            deliverRecordResult(
                callbackId, false, e.message ?: "recorder_start_failed", null, null, null
            )
        }
    }

    // v53: pause / resume. The state lives here with the recorder;
    // bad-state calls (pause while idle, double pause, resume while
    // not paused) come back ok:false with a reason, never a throw.
    fun pauseRecord(callbackId: String) {
        val rec = recorder
        if (rec == null) {
            deliverRecordResult(callbackId, false, "not_recording", null, null, null)
            return
        }
        if (recordPaused) {
            deliverRecordResult(callbackId, false, "already_paused", null, null, null)
            return
        }
        try {
            rec.pause()
            recordPaused = true
            recordPauseStartMs = SystemClock.elapsedRealtime()
            val o = JSONObject()
            o.put("ok", true)
            o.put("paused", true)
            deliverRecordCallback(callbackId, o)
        } catch (e: Exception) {
            deliverRecordResult(
                callbackId, false, e.message ?: "recorder_pause_failed", null, null, null
            )
        }
    }

    fun resumeRecord(callbackId: String) {
        val rec = recorder
        if (rec == null) {
            deliverRecordResult(callbackId, false, "not_recording", null, null, null)
            return
        }
        if (!recordPaused) {
            deliverRecordResult(callbackId, false, "not_paused", null, null, null)
            return
        }
        try {
            rec.resume()
            recordPausedTotalMs += SystemClock.elapsedRealtime() - recordPauseStartMs
            recordPaused = false
            recordPauseStartMs = 0L
            val o = JSONObject()
            o.put("ok", true)
            o.put("paused", false)
            deliverRecordCallback(callbackId, o)
        } catch (e: Exception) {
            deliverRecordResult(
                callbackId, false, e.message ?: "recorder_resume_failed", null, null, null
            )
        }
    }

    fun stopRecord(callbackId: String) {
        val rec = recorder
        val file = recordFile
        if (rec == null || file == null) {
            deliverRecordResult(callbackId, false, "not_recording", null, null, null)
            return
        }
        // v53: the reported duration is the EFFECTIVE recorded time —
        // wall clock minus every paused stretch, including a pause
        // still in progress. Stopping from the paused state is
        // unreliable on some devices, so resume first; the take is
        // ending either way, so a failed resume changes nothing.
        val nowMs = SystemClock.elapsedRealtime()
        var durationMs = nowMs - recordStartElapsedMs - recordPausedTotalMs
        if (recordPaused) {
            durationMs -= nowMs - recordPauseStartMs
            try { rec.resume() } catch (ignored: Exception) {}
        }
        if (durationMs < 0L) durationMs = 0L
        recordPaused = false
        recordPauseStartMs = 0L
        recordPausedTotalMs = 0L
        var stopError: String? = null
        try {
            rec.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop() throws when it captured too little
            // data; the partial file is unusable either way.
            stopError = e.message ?: "recorder_stop_failed"
        }
        try { rec.release() } catch (ignored: Exception) {}
        recorder = null
        recordFile = null
        if (stopError != null) {
            if (file.exists()) file.delete()
            deliverRecordResult(callbackId, false, stopError, null, null, null)
            return
        }
        if (!file.isFile || file.length() == 0L) {
            if (file.exists()) file.delete()
            deliverRecordResult(callbackId, false, "empty_recording", null, null, null)
            return
        }
        deliverRecordResult(callbackId, true, null, file.name, file.absolutePath, durationMs)
    }

    // v54: instantaneous mic level for the page's recording sheet.
    // MediaRecorder.getMaxAmplitude() reports the peak amplitude
    // (0..32767) since the previous call, which suits the page's
    // polling; anything but an actively recording take reads 0.
    fun currentRecordLevel(): Double {
        val rec = recorder ?: return 0.0
        if (recordPaused) return 0.0
        return try {
            (rec.maxAmplitude / 32767.0).coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            0.0
        }
    }

    fun cancelRecord(callbackId: String) {
        if (recorder == null) {
            deliverRecordResult(callbackId, false, "not_recording", null, null, null)
            return
        }
        releaseRecorder(deleteFile = true)
        val o = JSONObject()
        o.put("ok", true)
        o.put("cancelled", true)
        deliverRecordCallback(callbackId, o)
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        val rec = recorder
        val file = recordFile
        recorder = null
        recordFile = null
        recordPaused = false
        recordPauseStartMs = 0L
        recordPausedTotalMs = 0L
        if (rec != null) {
            try { rec.stop() } catch (ignored: Exception) {}
            try { rec.release() } catch (ignored: Exception) {}
        }
        if (deleteFile && file != null && file.exists()) file.delete()
    }

    private fun deliverRecordResult(
        callbackId: String,
        success: Boolean,
        error: String?,
        name: String?,
        path: String?,
        durationMs: Long?
    ) {
        val o = JSONObject()
        o.put("ok", success)
        if (success) {
            o.put("name", name ?: "")
            o.put("path", path ?: "")
            o.put("durationMs", durationMs ?: 0L)
        } else {
            o.put("error", error ?: "unknown")
        }
        deliverRecordCallback(callbackId, o)
    }

    private fun deliverRecordCallback(callbackId: String, payload: JSONObject) {
        val js = "window.OE_nativeCallback && window.OE_nativeCallback(" +
            JSONObject.quote(callbackId) + ", " + JSONObject.quote(payload.toString()) + ")"
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(js, null) }
    }

    // ---------- OE_Native openFile implementation ----------

    /**
     * Opens [path] in an external viewer: FileProvider content URI + ACTION_VIEW
     * started directly (no app chooser), so the system default viewer opens
     * immediately when one is set. The intent runs on the UI thread; returns
     * null on success, or an error string ("not_found", "no_viewer", ...)
     * for the bridge to wrap as JSON.
     */
    fun openFileExternally(path: String): String? {
        var error: String? = null
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                val file = File(path)
                if (!file.isFile) {
                    error = "not_found"
                } else {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val mime = try {
                        contentResolver.getType(uri)
                    } catch (e: Exception) {
                        null
                    } ?: mimeFromExtension(file.name) ?: "*/*"
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (viewIntent.resolveActivity(packageManager) == null) {
                        error = "no_viewer"
                    } else {
                        startActivity(viewIntent)
                    }
                }
            } catch (e: ActivityNotFoundException) {
                error = "no_viewer"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // Fall through with whatever error state we have.
        }
        return error
    }

    private fun mimeFromExtension(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return null
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
    }

    /**
     * v42: a picked item is reported back as a SOURCE, never copied into
     * the attachment folder here. If the content URI resolves to a real
     * file on disk (external-storage document URIs, MediaStore rows),
     * that path is the source and nothing is copied at all. Otherwise
     * (photo-picker URIs and other virtual providers expose no path)
     * the bytes are stream-copied into the staging dir inside the
     * editor's own store dir, and that staged file is the source. The
     * page copies the source into the attachment folder at send time.
     */
    private fun handlePickedUri(uri: Uri, stagingDir: String, callbackId: String) {
        Thread {
            try {
                val realPath = resolveRealPath(uri)
                if (realPath != null) {
                    val f = File(realPath)
                    deliverPickResult(callbackId, true, null, f.name, f.absolutePath, false)
                } else {
                    val name = queryDisplayName(uri) ?: fallbackName(uri)
                    val dir = File(stagingDir)
                    dir.mkdirs()
                    val dest = NativeBridge.uniqueDest(dir, name)
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw IllegalStateException("cannot_open_uri")
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    deliverPickResult(callbackId, true, null, dest.name, dest.absolutePath, true)
                }
            } catch (e: Exception) {
                deliverPickResult(callbackId, false, e.message ?: "copy_failed", null, null, false)
            }
        }.start()
    }

    /** Best-effort content-URI → real file path; null when unresolvable. */
    private fun resolveRealPath(uri: Uri): String? {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                val docId = try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { null }
                if (docId != null) {
                    if (docId.startsWith("raw:")) {
                        val p = docId.substring(4)
                        if (File(p).isFile) return p
                    }
                    if (uri.authority == "com.android.externalstorage.documents") {
                        val parts = docId.split(":", limit = 2)
                        if (parts.size == 2) {
                            val root = if (parts[0] == "primary") {
                                Environment.getExternalStorageDirectory().absolutePath
                            } else {
                                "/storage/" + parts[0]
                            }
                            val p = root + "/" + parts[1]
                            if (File(p).isFile) return p
                        }
                    }
                }
            }
            if (uri.authority == "media" || uri.authority == "com.android.providers.media.documents") {
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex("_data")
                        if (idx >= 0) {
                            val p = cursor.getString(idx)
                            if (!p.isNullOrEmpty() && File(p).isFile) return p
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Resolution is best-effort; the caller falls back to staging.
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fallbackName(uri: Uri): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        val ext = when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "application/pdf" -> ".pdf"
            else -> ""
        }
        return "picked_$stamp$ext"
    }

    private fun deliverPickResult(
        callbackId: String,
        success: Boolean,
        error: String?,
        name: String?,
        src: String?,
        staged: Boolean
    ) {
        val o = JSONObject()
        o.put("ok", success)
        if (success) {
            o.put("name", name ?: "")
            // v42: "src" is the source path the page registers and copies
            // from at send time; "path" mirrors it for older page code.
            o.put("src", src ?: "")
            o.put("path", src ?: "")
            o.put("staged", staged)
        } else {
            o.put("error", error ?: "cancelled")
        }
        val js = "window.OE_nativeCallback && window.OE_nativeCallback(" +
            JSONObject.quote(callbackId) + ", " + JSONObject.quote(o.toString()) + ")"
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }
}

/**
 * v73: one-time store-dir migration. Up to 1.46 the editor's store
 * (config.json, draft.txt, history.json, pending.json and the
 * pending/ staging folder) lived in the Tasker-era folder shared
 * with the Tasker build; from 1.47 the store lives in the app's
 * own external app-specific dir (getExternalFilesDir). [resolve]
 * runs at the top of EditorActivity.onCreate — before the page
 * loads — and decides which dir this process uses:
 *
 *  - new dir already holds a config.json -> new dir (migration
 *    already done, or a fresh install already started there);
 *  - old dir missing -> new dir (fresh install; the page creates
 *    the files there);
 *  - new dir has no config.json yet, old dir present, storage
 *    access granted -> copy the old dir to a sibling temp dir,
 *    verify the copy (every key file present with a matching
 *    size, config.json parses as JSON, recursive file count and
 *    total bytes match), merge the verified copy into the new
 *    dir, verify the merge, and use the new dir;
 *  - copy or verification fails -> temp dir deleted, the OLD dir
 *    is used for this process, and the failure is only logged.
 *    The old dir is never deleted or emptied: it belongs to the
 *    Tasker fallback build and doubles as the backup.
 *
 * The decision is cached per process, except the no-access case:
 * that returns the old dir uncached, so a window created later in
 * the same process — once the user has granted access — can still
 * migrate. Because resolve() settles before any page load, no read
 * or write can land on a half-copied new dir.
 */
object StoreMigration {

    const val OLD_DIR = "/storage/emulated/0/Tasker/obsidian-editor"
    private const val TAG = "StoreMigration"

    /**
     * The app's own store dir: its external app-specific folder
     * (Android/data/<package>/files — no storage permission
     * needed; the system removes it only on uninstall), falling
     * back to internal storage when external storage is
     * unavailable. Never the cache dir: the store holds the
     * editor's working state (config, draft, history, pending
     * staging), not disposable cache.
     */
    private fun appStoreDir(context: Context): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }

    @Volatile
    private var cached: String? = null

    @Synchronized
    fun resolve(context: Context): String {
        cached?.let { return it }
        val newDir = appStoreDir(context)
        val newPath = newDir.absolutePath
        val oldDir = File(OLD_DIR)
        if (File(newDir, "config.json").isFile) {
            // Settled: an earlier launch already migrated (or a
            // fresh install already saved its config here).
            cached = newPath
            return newPath
        }
        if (!oldDir.isDirectory) {
            // Fresh install: nothing to migrate, everything
            // starts in the app's own dir.
            cached = newPath
            return newPath
        }
        if (!accessGranted(context)) {
            // Cannot read the old dir yet. Behave exactly as
            // before the move for this page load and let a later
            // window retry once access exists; do not cache.
            Log.i(TAG, "storage access not granted yet; migration deferred")
            return OLD_DIR
        }
        // One-time migration. The old tree is first copied to a
        // sibling temp dir and verified there; only a verified
        // copy is merged into place, so no read or write can land
        // on a half-copied new dir.
        val tmpDir = File(newDir.parentFile, ".store-migration-tmp")
        try {
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            if (!oldDir.copyRecursively(tmpDir, overwrite = false)) {
                throw IllegalStateException("copyRecursively returned false")
            }
            if (!verifyCopy(oldDir, tmpDir)) {
                throw IllegalStateException("copy verification failed")
            }
            // getExternalFilesDir() creates the new dir on call,
            // so it normally exists already — but config.json is
            // absent (that absence is the migration trigger), so
            // merging the verified copy over it is safe.
            if (!tmpDir.copyRecursively(newDir, overwrite = true)) {
                throw IllegalStateException("merge into place failed")
            }
            if (!verifyMigrated(oldDir, newDir)) {
                throw IllegalStateException("post-merge verification failed")
            }
            tmpDir.deleteRecursively()
            Log.i(TAG, "store migrated to $newPath")
            cached = newPath
            return newPath
        } catch (e: Exception) {
            Log.w(TAG, "store migration failed; using old dir for this process", e)
            try {
                if (tmpDir.exists()) tmpDir.deleteRecursively()
            } catch (ignored: Exception) {
                // Best effort cleanup only; the old dir is untouched either way.
            }
            cached = OLD_DIR
            return OLD_DIR
        }
    }

    private fun accessGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun verifyCopy(src: File, dst: File): Boolean {
        val keyFiles = listOf("config.json", "draft.txt", "history.json", "pending.json")
        for (name in keyFiles) {
            val s = File(src, name)
            if (s.isFile) {
                val d = File(dst, name)
                if (!d.isFile || d.length() != s.length()) return false
            }
        }
        if (File(src, "config.json").isFile) {
            try {
                JSONObject(File(dst, "config.json").readText(Charsets.UTF_8))
            } catch (e: Exception) {
                return false
            }
        }
        return treeStats(src) == treeStats(dst)
    }

    /**
     * Post-merge check: every file of the old tree is present in
     * the new dir with the same size, and config.json parses.
     * Extra files already sitting in the new dir are tolerated —
     * unlike the temp-dir check this is not an equality test.
     */
    private fun verifyMigrated(src: File, dst: File): Boolean {
        val keyFiles = listOf("config.json", "draft.txt", "history.json", "pending.json")
        for (name in keyFiles) {
            val s = File(src, name)
            if (s.isFile) {
                val d = File(dst, name)
                if (!d.isFile || d.length() != s.length()) return false
            }
        }
        if (File(src, "config.json").isFile) {
            try {
                JSONObject(File(dst, "config.json").readText(Charsets.UTF_8))
            } catch (e: Exception) {
                return false
            }
        }
        var intact = true
        src.walkTopDown().forEach { f ->
            if (intact && f.isFile) {
                val rel = f.relativeTo(src).path
                val d = File(dst, rel)
                if (!d.isFile || d.length() != f.length()) intact = false
            }
        }
        return intact
    }

    /** (file count, total bytes) over the whole tree. */
    private fun treeStats(root: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                bytes += f.length()
            }
        }
        return count to bytes
    }
}
