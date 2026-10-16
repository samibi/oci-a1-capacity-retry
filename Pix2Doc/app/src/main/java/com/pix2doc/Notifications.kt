package com.pix2doc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

object Notifications {
    const val CH_PROGRESS = "progress"
    const val CH_DONE = "done"
    const val ID_PROGRESS = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, ctx.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, ctx.getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canPost(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            // permission revoked meanwhile
        }
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun progressBuilder(ctx: Context, done: Int, total: Int, name: String): NotificationCompat.Builder {
        val cancel = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, ConvertService::class.java).setAction(ConvertService.ACTION_CANCEL),
            flags()
        )
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_pdf)
            .setContentTitle(ctx.getString(R.string.notif_progress, done, total))
            .setContentText(name)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, ctx.getString(R.string.cancel), cancel)
    }

    fun progress(ctx: Context, done: Int, total: Int, name: String) =
        post(ctx, ID_PROGRESS, progressBuilder(ctx, done, total, name))

    fun done(ctx: Context, uri: Uri, name: String, pages: Int, size: Long) {
        val id = newId()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            ctx.getString(R.string.share)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val edit = Intent(ctx, EditorActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_pdf)
            .setContentTitle(ctx.getString(R.string.notif_done, name))
            .setContentText(ctx.getString(R.string.pages_size, pages, Formatter.formatShortFileSize(ctx, size)))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id * 3, view, flags()))
            .addAction(0, ctx.getString(R.string.share), PendingIntent.getActivity(ctx, id * 3 + 1, share, flags()))
            .addAction(0, ctx.getString(R.string.reorder), PendingIntent.getActivity(ctx, id * 3 + 2, edit, flags()))
        post(ctx, id, b)
    }

    fun askLocation(ctx: Context, file: File, name: String, pages: Int) {
        val id = newId()
        fun intent(def: Boolean) = Intent(ctx, SaveAsActivity::class.java)
            .putExtra(SaveAsActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(SaveAsActivity.EXTRA_NAME, name)
            .putExtra(SaveAsActivity.EXTRA_PAGES, pages)
            .putExtra(SaveAsActivity.EXTRA_NOTIF_ID, id)
            .putExtra(SaveAsActivity.EXTRA_DEFAULT, def)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_pdf)
            .setContentTitle(ctx.getString(R.string.notif_choose_location))
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, id * 3, intent(false), flags()))
            .addAction(0, ctx.getString(R.string.save_as), PendingIntent.getActivity(ctx, id * 3 + 1, intent(false), flags()))
            .addAction(0, ctx.getString(R.string.save_default), PendingIntent.getActivity(ctx, id * 3 + 2, intent(true), flags()))
        post(ctx, id, b)
    }

    fun error(ctx: Context, message: String) {
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_pdf)
            .setContentTitle(ctx.getString(R.string.notif_error))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
        post(ctx, newId(), b)
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    private fun newId(): Int = ((System.currentTimeMillis() / 1000L) % 100_000L).toInt() + 100
}
