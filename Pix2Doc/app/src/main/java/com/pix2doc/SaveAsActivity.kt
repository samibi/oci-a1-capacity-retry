package com.pix2doc

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Invisible activity: lets the user choose where a freshly built PDF is saved. */
class SaveAsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_PAGES = "pages"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_DEFAULT = "default"
    }

    private lateinit var file: File
    private lateinit var name: String
    private var pages = 0

    private val create = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) finish() else save(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        name = intent.getStringExtra(EXTRA_NAME) ?: "Pix2Doc.pdf"
        pages = intent.getIntExtra(EXTRA_PAGES, 0)
        if (path == null || !File(path).exists()) {
            Toast.makeText(this, R.string.file_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        file = File(path)
        if (savedInstanceState != null) return
        if (intent.getBooleanExtra(EXTRA_DEFAULT, false)) save(null) else create.launch(name)
    }

    private fun save(target: Uri?) {
        lifecycleScope.launch {
            try {
                val size = file.length()
                val uri = withContext(Dispatchers.IO) {
                    if (target == null) {
                        Storage.saveToDefault(this@SaveAsActivity, file, name)
                    } else {
                        Storage.copy(this@SaveAsActivity, file, target)
                        target
                    }
                }
                file.delete()
                Notifications.cancel(this@SaveAsActivity, intent.getIntExtra(EXTRA_NOTIF_ID, 0))
                Recents.add(
                    this@SaveAsActivity,
                    RecentFile(uri.toString(), Storage.displayName(this@SaveAsActivity, uri) ?: name, pages, size,
                        System.currentTimeMillis())
                )
                Notifications.done(this@SaveAsActivity, uri, name, pages, size)
                Toast.makeText(this@SaveAsActivity, getString(R.string.saved, name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SaveAsActivity, e.message ?: getString(R.string.notif_error), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
