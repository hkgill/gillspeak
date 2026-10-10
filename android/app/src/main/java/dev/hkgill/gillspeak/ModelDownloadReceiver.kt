package dev.hkgill.gillspeak

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * DownloadManager tells us when a model file has finished, even if the app isn't running; once all of them
 * have, they're checked and installed. A spoofed broadcast can't do harm: only our own download ids are acted
 * on, and every file must match its SHA256.
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val app = context.applicationContext
        // goAsync keeps the process alive while the download is checked and moved into place.
        when {
            LocalAsr.owns(app, id) -> goAsync().let { pending -> LocalAsr.install(app) { pending.finish() } }
            LocalLlm.owns(app, id) -> goAsync().let { pending -> LocalLlm.install(app) { pending.finish() } }
        }
    }
}
