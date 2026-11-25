package app.inboxeditor.shell

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: tap to summon the editor. It is a plain launcher —
 * the tile itself holds no on/off state, so it rests at STATE_INACTIVE.
 * v56: the tile opens QuickActivity — a floating editor card over the
 * current app — not the full-screen MainActivity (that stays bound to
 * the launcher icon). QuickActivity is singleTop, so a second tap while
 * the card is up just brings the same card forward.
 * v66/1.40: QuickActivity declares its own taskAffinity in the
 * manifest, so the NEW_TASK launch below resolves to the card's own
 * task. It used to share the app task with MainActivity, and raising
 * that task flashed the backgrounded main app into the foreground
 * before the card appeared; now only the card comes up, over the
 * app the user is in, and closing it returns there directly.
 */
class EditorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = packageManager.getApplicationLabel(applicationInfo)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: Intent-based startActivityAndCollapse is deprecated.
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
