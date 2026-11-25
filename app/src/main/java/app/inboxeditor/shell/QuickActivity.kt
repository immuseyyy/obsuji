package app.inboxeditor.shell

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager

/**
 * v56: floating quick-capture entry, launched by the Quick Settings
 * tile. A translucent window: the app behind stays visible under a
 * dim, and the editor page (the exact same page and bridge as the
 * full-screen host, sharing the same store dir, config and draft)
 * sits in a rounded card — 92% of the screen wide, 76% high,
 * centred horizontally and riding a little above centre. The page
 * detects this host through the isQuickCapture bridge method and
 * switches to quick behaviour: auto-focus on open, close the
 * window after a successful send, close on back at the root.
 */
class QuickActivity : EditorActivity() {

    override val quickCapture: Boolean
        get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent window background from the very first frame;
        // the base onCreate seeds the WebView background from the
        // system light/dark truth (applyChromeTheme), so the card
        // never flashes white or black while the page loads.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        val metrics = resources.displayMetrics
        val params = window.attributes
        params.width = (metrics.widthPixels * 0.92).toInt()
        params.height = (metrics.heightPixels * 0.76).toInt()
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = (metrics.heightPixels * 0.07).toInt()
        params.dimAmount = 0.45f
        window.attributes = params
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Round the card: clip the WebView (this host's only view)
        // to a rounded-rect outline; the elevation casts its shadow
        // onto the dimmed app behind.
        val radius = 24f * metrics.density
        webView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        webView.clipToOutline = true
        webView.elevation = 18f * metrics.density

        // v77: a cold start may BE a share — the user picked
        // Ob速记 in another app's share sheet. Parse the launch
        // intent into the shell's share queue; the page pulls
        // it at the end of its init. (The tile's explicit
        // intent has no ACTION_SEND and queues nothing.)
        handleShareIntent(intent)

        // v78: tapping the dimmed area outside the card closes
        // the window, as a second close gesture next to the back
        // key and the page's own close button. finishOnTouchOutside
        // ends in the same finish() as every other close path;
        // the draft is flushed by the page's pagehide /
        // visibilitychange handlers on the way down, exactly as
        // it is for the back key, so the saved draft and the
        // cross-window sync state come out identical.
        setFinishOnTouchOutside(true)
    }

    /**
     * v77: the card is singleTop, so a share picked while the
     * card is already open arrives here instead of in onCreate.
     * Queue it the same way and nudge the loaded page to pull.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        notifyPageOfShare()
    }
}
