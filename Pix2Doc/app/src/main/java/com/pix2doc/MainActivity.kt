package com.pix2doc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: RecentAdapter
    private lateinit var emptyView: TextView
    private var pendingFile: File? = null
    private var pendingPages = 0

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) buildFrom(uris)
        }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) buildFrom(uris)
        }

    private val openPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistable(uri)
                openEditor(uri)
            }
        }

    private val saveAs =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val f = pendingFile ?: return@registerForActivityResult
            if (uri == null) {
                f.delete()
                pendingFile = null
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    val size = f.length()
                    withContext(Dispatchers.IO) { Storage.copy(this@MainActivity, f, uri) }
                    takePersistable(uri)
                    finishSaved(uri, Storage.displayName(this@MainActivity, uri) ?: f.name, pendingPages, size)
                } catch (e: Exception) {
                    toast(e.message ?: getString(R.string.notif_error))
                } finally {
                    f.delete()
                    pendingFile = null
                }
            }
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }

        findViewById<View>(R.id.btn_pick_photos).setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.btn_pick_files).setOnClickListener {
            pickFiles.launch(arrayOf("image/*"))
        }
        findViewById<View>(R.id.btn_open_pdf).setOnClickListener {
            openPdf.launch(arrayOf("application/pdf"))
        }

        emptyView = findViewById(R.id.empty)
        adapter = RecentAdapter()
        findViewById<RecyclerView>(R.id.recents).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        if (savedInstanceState == null) maybeAskNotifications()
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun refreshRecents() {
        val items = Recents.list(this)
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val sp = Prefs.sp(this)
        if (sp.getBoolean(Prefs.KEY_ASKED_NOTIF, false)) return
        sp.edit().putBoolean(Prefs.KEY_ASKED_NOTIF, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun buildFrom(uris: List<Uri>) {
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.progress)
        val label = view.findViewById<TextView>(R.id.label)
        bar.max = uris.size
        var cancelled = false
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.creating_pdf)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .show()

        lifecycleScope.launch {
            val name = FileNamer.build(Prefs.filenamePattern(this@MainActivity), System.currentTimeMillis())
            val out = File(Storage.cacheDir(this@MainActivity, "out"), name)
            try {
                val pages = withContext(Dispatchers.IO) {
                    val builder = PdfBuilder(this@MainActivity, BuildSettings.from(this@MainActivity))
                    val images = builder.describe(uris) // picker order = selection order
                    builder.build(images, out, { d, t, n ->
                        runOnUiThread {
                            bar.setProgressCompat(d, true)
                            label.text = getString(R.string.progress_of, d, t, n)
                        }
                    }, { cancelled })
                }
                dialog.dismiss()
                if (Prefs.askLocation(this@MainActivity)) {
                    pendingFile = out
                    pendingPages = pages
                    saveAs.launch(name)
                } else {
                    val size = out.length()
                    val uri = withContext(Dispatchers.IO) { Storage.saveToDefault(this@MainActivity, out, name) }
                    out.delete()
                    finishSaved(uri, name, pages, size)
                }
            } catch (e: BuildCancelledException) {
                out.delete()
                dialog.dismiss()
            } catch (e: Throwable) {
                out.delete()
                dialog.dismiss()
                toast(getString(R.string.notif_error) + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun finishSaved(uri: Uri, name: String, pages: Int, size: Long) {
        Recents.add(this, RecentFile(uri.toString(), name, pages, size, System.currentTimeMillis()))
        toast(getString(R.string.saved, name))
        openEditor(uri)
    }

    private fun takePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun openEditor(uri: Uri) {
        startActivity(Intent(this, EditorActivity::class.java).setData(uri))
    }

    private fun viewExternally(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast(getString(R.string.no_viewer))
        }
    }

    private fun share(uri: Uri) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                getString(R.string.share)
            )
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.VH>() {
        private var items: List<RecentFile> = emptyList()

        fun submit(list: List<RecentFile>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val more: View = v.findViewById(R.id.more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            val uri = Uri.parse(r.uri)
            h.name.text = r.name
            h.meta.text = getString(
                R.string.recent_meta,
                r.pages,
                Formatter.formatShortFileSize(this@MainActivity, r.size),
                DateUtils.getRelativeTimeSpanString(r.time)
            )
            h.itemView.setOnClickListener { openEditor(uri) }
            h.more.setOnClickListener { anchor ->
                PopupMenu(this@MainActivity, anchor).apply {
                    menu.add(0, 1, 0, R.string.open_viewer)
                    menu.add(0, 2, 1, R.string.share)
                    menu.add(0, 3, 2, R.string.remove_from_list)
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> viewExternally(uri)
                            2 -> share(uri)
                            3 -> {
                                Recents.remove(this@MainActivity, r.uri)
                                refreshRecents()
                            }
                        }
                        true
                    }
                    show()
                }
            }
        }
    }
}
