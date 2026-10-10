package dev.hkgill.gillspeak

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * DownloadManager tells us when a model file has finished, even if the app isn't running; once all of them
 * have, they're checked and installed. Any app can send this broadcast, so it's only a hint: a file is installed
 * only if it's one of our own download ids, DownloadManager itself says it finished, and it matches its SHA256.
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val app = context.applicationContext
        when {
            !finished(app, id) -> Unit
            // goAsync keeps the process alive while Parakeet's files (about 670 MB) are checked and moved into place.
            LocalAsr.owns(app, id) -> goAsync().let { pending -> LocalAsr.install(app) { pending.finish() } }
            // Gemma's 2.6 GB can take longer than a broadcast may run. If the process is stopped part way, opening
            // the app finds the finished download and installs it again.
            LocalLlm.owns(app, id) -> LocalLlm.install(app)
        }
    }

    private fun finished(context: Context, id: Long): Boolean = runCatching {
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
    }.getOrDefault(false)
}
