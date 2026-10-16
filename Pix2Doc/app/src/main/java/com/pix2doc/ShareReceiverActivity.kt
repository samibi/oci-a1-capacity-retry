package com.pix2doc

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Invisible entry point for "Share to Pix2Doc".
 * Images: hand off to [ConvertService] and close immediately (no screen).
 * PDF: open the page editor.
 */
class ShareReceiverActivity : AppCompatActivity() {

    private var uris: List<Uri> = emptyList()

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startConvert() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val streams = extractUris(i)

        val isPdf = i.type == "application/pdf" || i.action == Intent.ACTION_VIEW
        if (isPdf) {
            val pdf = streams.firstOrNull() ?: i.data
            if (pdf != null && savedInstanceState == null) {
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .setData(pdf)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
            finish()
            return
        }

        uris = streams
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.no_images, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (savedInstanceState != null) return // permission dialog result will arrive

        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !Prefs.sp(this).getBoolean(Prefs.KEY_ASKED_NOTIF, false)
        if (needsPermission) {
            Prefs.sp(this).edit().putBoolean(Prefs.KEY_ASKED_NOTIF, true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startConvert()
        }
    }

    private fun startConvert() {
        val clip = ClipData.newRawUri("images", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val svc = Intent(this, ConvertService::class.java)
            .setAction(ConvertService.ACTION_CONVERT)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        svc.clipData = clip
        ContextCompat.startForegroundService(this, svc)
        Toast.makeText(this, getString(R.string.creating_from, uris.size), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun extractUris(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.toList()
                ?: clipUris(i)
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
                ?: clipUris(i)
        else -> emptyList()
    }

    private fun clipUris(i: Intent): List<Uri> {
        val c = i.clipData ?: return emptyList()
        return (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }
    }
}
