package app.inboxeditor.shell

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OE_Native bridge exposed to the editor page as window.OE_Native.
 * All synchronous methods run on the WebView bridge thread and return a JSON string:
 *   {"ok":true, ...} on success, {"ok":false,"error":"..."} on failure.
 * pickFile is asynchronous: it returns {"ok":true,"started":true} immediately and the
 * final result is delivered through window.OE_nativeCallback(callbackId, jsonString).
 * v42: pickFile no longer copies anything into the attachment folder. Its second
 * argument is a STAGING directory inside the editor's own store dir: photos are
 * saved there, and picked items whose content URI cannot be resolved to a real
 * file path are stream-copied there. Items that do resolve to a real path are
 * reported with staged=false and nothing is copied at pick time at all. The page
 * copies staged/real sources into the attachment folder only when a note is sent.
 * v56: the host is the shared EditorActivity base, not MainActivity — the same
 * bridge instance serves the full-screen window and the floating quick-capture
 * card, and every host operation (permission requests, pickers, recorder) runs
 * on whichever activity actually hosts the page.
 */
class NativeBridge(private val activity: EditorActivity) {

    private fun ok(extra: (JSONObject) -> Unit = {}): String {
        val o = JSONObject()
        o.put("ok", true)
        extra(o)
        return o.toString()
    }

    private fun err(msg: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("error", msg)
        return o.toString()
    }

    /**
     * v73: the effective store dir for this process, settled by
     * the shell (StoreMigration) before the page loaded: the app's
     * own dir, or the old Tasker-era dir for this session when the
     * one-time migration could not complete. The page adopts the
     * answer as its STORE_DIR before any store read or write.
     */
    @JavascriptInterface
    fun storeDir(): String {
        return ok { it.put("dir", activity.storeDirPath) }
    }

    /**
     * v77: pull one queued system-share payload (see
     * EditorActivity.handleShareIntent). Returns
     * {"ok":true,"share":{...}} with the oldest queued payload
     * REMOVED from the queue, or {"ok":true,"share":null} when
     * the queue is empty. The page pulls at the end of init,
     * from the OE_onShareIntent hook and on window focus;
     * because taking removes, each share is delivered exactly
     * once, and a share nobody has pulled yet is never lost.
     * Payload shape: {"text": string|null, "image":
     * {"name","path"}|null} — the image is a file the shell
     * already staged in the store's pending/ dir.
     */
    @JavascriptInterface
    fun takePendingShare(): String {
        val payload = activity.takePendingSharePayload()
            ?: return ok { it.put("share", JSONObject.NULL) }
        return try {
            val parsed = JSONObject(payload)
            ok { it.put("share", parsed) }
        } catch (e: Exception) {
            err("bad_share_payload")
        }
    }

    @JavascriptInterface
    fun readText(path: String): String {
        return try {
            val f = File(path)
            if (!f.isFile) return err("not_found")
            ok { it.put("text", f.readText(Charsets.UTF_8)) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun writeText(path: String, text: String, append: Boolean): String {
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            FileOutputStream(f, append).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            ok()
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun exists(path: String): String {
        return try {
            ok { it.put("exists", File(path).exists()) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun listDir(path: String): String {
        return try {
            val dir = File(path)
            if (!dir.isDirectory) return err("not_a_dir")
            val files = dir.listFiles() ?: return err("cannot_list")
            val arr = JSONArray()
            val sorted = files.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
            )
            for (f in sorted) {
                val e = JSONObject()
                e.put("name", f.name)
                e.put("dir", f.isDirectory)
                e.put("mtime", f.lastModified())
                e.put("size", if (f.isFile) f.length() else 0L)
                arr.put(e)
            }
            ok { it.put("entries", arr) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun copyFile(srcPath: String, destDir: String): String {
        return try {
            val src = File(srcPath)
            if (!src.isFile) return err("not_found")
            val dir = File(destDir)
            dir.mkdirs()
            val dest = uniqueDest(dir, src.name)
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            ok {
                it.put("name", dest.name)
                it.put("path", dest.absolutePath)
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun sizeOf(path: String): String {
        return try {
            val f = File(path)
            if (!f.exists()) return err("not_found")
            ok { it.put("size", f.length()) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun hasStorageAccess(): String {
        return ok { it.put("granted", activity.storageAccessGranted()) }
    }

    /**
     * v49: the system light/dark truth, read natively from the current
     * configuration's uiMode. The page's "follow system" theme used to ask
     * the WebView's matchMedia('(prefers-color-scheme: dark)'), which in
     * this shell always reports dark regardless of the phone's setting, so
     * the page now asks here first and only falls back to matchMedia when
     * this bridge is absent (browser / Tasker modes).
     */
    @JavascriptInterface
    fun systemTheme(): String {
        return try {
            val night = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            ok { it.put("theme", if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light") }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun requestStorageAccess(): String {
        activity.runOnUiThread { activity.openStorageAccessSettings() }
        return ok()
    }

    @JavascriptInterface
    fun pickFile(kind: String, stagingDir: String, callbackId: String): String {
        activity.runOnUiThread { activity.startPick(kind, stagingDir, callbackId) }
        return ok { it.put("started", true) }
    }

    /**
     * v42: delete one staged pick file. Guarded: the file's canonical path must
     * lie inside [guardDir] (the staging dir the page passes), so the page can
     * never use this to delete arbitrary files. A missing file is not an error.
     */
    @JavascriptInterface
    fun deleteFile(path: String, guardDir: String): String {
        return try {
            val f = File(path)
            val guard = File(guardDir).canonicalFile
            val target = f.canonicalFile
            if (target.path != guard.path && !target.path.startsWith(guard.path + File.separator)) {
                return err("outside_guard_dir")
            }
            if (target.isFile) target.delete()
            ok { it.put("exists", target.exists()) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun openFile(path: String): String {
        val error = activity.openFileExternally(path)
        return if (error == null) ok() else err(error)
    }

    /**
     * v52: voice-button recording. All three methods follow the pickFile
     * async pattern: the synchronous return is only an ack, the real
     * outcome is delivered through window.OE_nativeCallback(callbackId, …).
     * The recorder state lives in the activity; the file is written into
     * [stagingDir] (the editor store dir's pending/ folder, passed in by
     * the page exactly like pickFile's staging dir). startRecord may
     * first have to ask for the RECORD_AUDIO runtime permission — the
     * callback then fires after the user answers (ok:false with
     * error "permission_denied" when refused).
     */
    @JavascriptInterface
    fun startRecord(stagingDir: String, callbackId: String): String {
        activity.runOnUiThread { activity.startRecord(stagingDir, callbackId) }
        return ok { it.put("started", true) }
    }

    @JavascriptInterface
    fun stopRecord(callbackId: String): String {
        activity.runOnUiThread { activity.stopRecord(callbackId) }
        return ok { it.put("started", true) }
    }

    /**
     * v53: pause / resume the in-flight recording (MediaRecorder.pause /
     * resume, both API 24+; minSdk is 26). Same async pattern as
     * stopRecord: the sync return is an ack, the outcome arrives via
     * window.OE_nativeCallback as {"ok":true,"paused":true|false} or
     * {"ok":false,"error":...} ("not_recording" / "already_paused" /
     * "not_paused" for bad states).
     */
    @JavascriptInterface
    fun pauseRecord(callbackId: String): String {
        activity.runOnUiThread { activity.pauseRecord(callbackId) }
        return ok { it.put("started", true) }
    }

    @JavascriptInterface
    fun resumeRecord(callbackId: String): String {
        activity.runOnUiThread { activity.resumeRecord(callbackId) }
        return ok { it.put("started", true) }
    }

    @JavascriptInterface
    fun cancelRecord(callbackId: String): String {
        activity.runOnUiThread { activity.cancelRecord(callbackId) }
        return ok { it.put("started", true) }
    }

    /**
     * v54: the page reports its RESOLVED theme ("light" / "dark" —
     * after any follow-system resolution) on every theme
     * application, and the shell paints the status bar, navigation
     * bar and WebView background to match (see
     * MainActivity.applyChromeTheme). Sync ack like the other
     * one-way setters; an unknown mode is refused, not guessed.
     */
    @JavascriptInterface
    fun setTheme(mode: String): String {
        if (mode != "light" && mode != "dark") return err("bad_theme")
        activity.runOnUiThread { activity.applyChromeTheme(mode == "dark") }
        return ok { it.put("applied", true) }
    }

    /**
     * v58: system Toast for the page's toast() feedback. The page's
     * own bubble div dies with the window — most painfully in quick
     * capture, where a successful send closes the floating window
     * and takes the "saved" bubble with it. A system Toast is shown
     * by the system queue from the application context, so it
     * survives the host activity finishing. [long] picks
     * LENGTH_LONG (errors / warnings the page used to hold on
     * screen longer), otherwise LENGTH_SHORT (success / neutral).
     */
    @JavascriptInterface
    fun showToast(text: String, long: Boolean): String {
        activity.runOnUiThread {
            Toast.makeText(
                activity.applicationContext, text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
        return ok { it.put("shown", true) }
    }

    /**
     * v58: explicitly raise the soft keyboard for the editor. A JS
     * focus() inside the WebView moves the DOM caret into the
     * textarea but never brings the IME up by itself, so on open
     * the caret sat in the text with no keyboard. The page calls
     * this from exactly its two startup focus paths, and only
     * after verifying the caret really is in the editor; the
     * actual view work lives in EditorActivity.showSoftKeyboard()
     * so it always targets the hosting window's WebView.
     */
    @JavascriptInterface
    fun showKeyboard(): String {
        activity.runOnUiThread { activity.showSoftKeyboard() }
        return ok { it.put("shown", true) }
    }

    /**
     * v68: report the soft keyboard's current visibility so the
     * page can snapshot it before a temporary flow (the in-page
     * path picker, the system pickers, the recorder) and restore
     * it when the flow hands control back.
     * v69: v68 read a cache kept by an OnApplyWindowInsetsListener,
     * and on the user's device every restore silently never
     * happened — the cached answer could not be trusted end to
     * end. This now hops to the UI thread and queries the live
     * root window insets at call time; the listener cache is only
     * the fallback when the live query cannot complete in time.
     * Never a height estimate either way.
     * Contract: {"ok":true,"open":true|false}.
     */
    @JavascriptInterface
    fun keyboardOpen(): String {
        var open = activity.isKeyboardOpen()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                open = activity.queryKeyboardOpenLive()
            } catch (e: Exception) {
                // Keep the cached fallback already in `open`.
            }
            latch.countDown()
        }
        try {
            latch.await(300, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Fall through with whatever value we have.
        }
        val result = open
        return ok { it.put("open", result) }
    }

    /**
     * v69: restart the WebView's input connection. After the app
     * returns from a system picker, the IME connection bound to
     * the WebView can be dead — showSoftInput then silently does
     * nothing even with the caret in place. The page's keyboard
     * restore chain calls this right before showKeyboard() on
     * every round; the actual work lives in
     * EditorActivity.restartWebViewInput().
     */
    @JavascriptInterface
    fun restartInput(): String {
        activity.runOnUiThread { activity.restartWebViewInput() }
        return ok { it.put("restarted", true) }
    }

    /**
     * v59: read the system clipboard's text for the toolbar paste
     * button. The page's paste relay was built for Tasker (probe a
     * Scenes V2 clipboard method, then the Web Clipboard API, then
     * a long-press fallback panel); in this shell the first two
     * levels can never succeed — there is no Tasker bridge, and
     * the WebView cannot get the Clipboard API permission — so
     * every paste landed in the fallback panel. The shell can just
     * read the clipboard itself: the host is the foreground app
     * and the read is triggered by the user's button tap, so
     * MIUI's clipboard privacy guard does not block it.
     * Contract: {"ok":true,"text":"..."} when the primary clip
     * holds text; {"ok":true,"text":""} when the clipboard is
     * empty or its first item is not text; {"ok":false,...} on
     * any failure, which the page treats as "no answer" and falls
     * through to the rest of its relay.
     */
    @JavascriptInterface
    fun readClipboard(): String {
        return try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return err("no_clipboard_service")
            val clip = cm.primaryClip ?: return ok { it.put("text", "") }
            if (clip.itemCount == 0) return ok { it.put("text", "") }
            val item = clip.getItemAt(0)
            val text = item.text?.toString()
                ?: item.coerceToText(activity)?.toString()
                ?: ""
            ok { it.put("text", text) }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * v56: which host is showing the page. The quick-capture card
     * (QuickActivity, opened from the QS tile) reports quick=true;
     * the page then auto-focuses on open, closes the window after
     * a successful send, and closes instead of double-press-exit
     * on back at the root. The full-screen host reports false.
     */
    @JavascriptInterface
    fun isQuickCapture(): String {
        return ok { it.put("quick", activity.quickCapture) }
    }

    /**
     * v56: close the floating quick-capture window (finish the
     * host activity), returning the user to whatever app was in
     * front before the tile tap. A no-op in the full-screen host —
     * the page only calls it in quick mode, and the guard keeps a
     * stray call from ever exiting the main window.
     */
    @JavascriptInterface
    fun closeQuick(): String {
        if (activity.quickCapture) {
            activity.runOnUiThread { activity.finish() }
        }
        return ok { it.put("closed", activity.quickCapture) }
    }

    /**
     * v57: expand the floating quick-capture window into the full
     * app. From QuickActivity: launch MainActivity (singleTask, so
     * an existing full-screen instance is simply brought forward;
     * both hosts share the same store dir, config and draft files,
     * so nothing the user typed is lost), then finish the floating
     * window. A no-op in the full-screen host — the page only shows
     * the expand button in quick mode, and the guard keeps a stray
     * call harmless.
     */
    @JavascriptInterface
    fun openFullApp(): String {
        if (activity.quickCapture) {
            activity.runOnUiThread {
                val intent = Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }
        return ok { it.put("opened", activity.quickCapture) }
    }

    /**
     * v54: synchronous mic level for the recording sheet's meter.
     * Returns {"ok":true,"level":0..1} — the recorder's peak
     * amplitude normalized; 0 whenever not actively recording or
     * on any error, so the page can poll it blindly.
     */
    @JavascriptInterface
    fun recLevel(): String {
        val level = try { activity.currentRecordLevel() } catch (e: Exception) { 0.0 }
        return ok { it.put("level", level) }
    }

    companion object {
        /** If destDir/name already exists, insert a timestamp before the extension. */
        fun uniqueDest(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
            candidate = File(dir, "$base-$stamp$ext")
            var n = 1
            while (candidate.exists()) {
                candidate = File(dir, "$base-$stamp-$n$ext")
                n++
            }
            return candidate
        }
    }
}
