package com.pix2doc

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Builds a PDF from shared images in the background. No UI; reports through notifications. */
class ConvertService : Service() {

    companion object {
        const val ACTION_CONVERT = "com.pix2doc.CONVERT"
        const val ACTION_CANCEL = "com.pix2doc.CANCEL"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = AtomicInteger(0)
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            if (pending.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROGRESS,
            Notifications.progressBuilder(this, 0, 0, getString(R.string.preparing)).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finishIfIdle()
            return START_NOT_STICKY
        }
        pending.incrementAndGet()
        scope.launch {
            try {
                mutex.withLock { runJob(uris) }
            } finally {
                pending.decrementAndGet()
                finishIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        val clip = intent?.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun runJob(uris: List<Uri>) {
        cancelled = false
        val builder = PdfBuilder(this, BuildSettings.from(this))
        val name = FileNamer.build(Prefs.filenamePattern(this), System.currentTimeMillis())
        val out = File(Storage.cacheDir(this, "out"), name)
        try {
            val images = PageSorter.sort(builder.describe(uris), Prefs.shareOrder(this))
            val pages = builder.build(
                images, out,
                onProgress = { d, t, n -> Notifications.progress(this, d, t, n) },
                isCancelled = { cancelled }
            )
            if (Prefs.askLocation(this)) {
                Notifications.askLocation(this, out, name, pages)
                if (!Notifications.canPost(this)) {
                    // No notifications: fall back to the default folder so the PDF is not lost.
                    saveDefault(out, name, pages)
                }
            } else {
                saveDefault(out, name, pages)
            }
        } catch (e: BuildCancelledException) {
            out.delete()
            toast(getString(R.string.cancelled))
        } catch (e: Throwable) {
            out.delete()
            val msg = e.message ?: e.javaClass.simpleName
            Notifications.error(this, msg)
            toast(getString(R.string.notif_error) + ": " + msg)
        }
    }

    private fun saveDefault(out: File, name: String, pages: Int) {
        val size = out.length()
        val uri = Storage.saveToDefault(this, out, name)
        out.delete()
        Recents.add(this, RecentFile(uri.toString(), name, pages, size, System.currentTimeMillis()))
        Notifications.done(this, uri, name, pages, size)
        if (!Notifications.canPost(this)) {
            toast(getString(R.string.saved_to, name, Storage.folderLabel(this)))
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    private fun finishIfIdle() {
        if (pending.get() > 0) return
        main.post {
            if (pending.get() == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
