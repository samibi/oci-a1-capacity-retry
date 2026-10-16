package com.pix2doc

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

data class PageItem(
    override val originalIndex: Int,
    override val sortName: String?,
    override val sortDate: Long?,
) : Sortable

/** Shows the pages of a PDF and lets the user reorder them (drag, or by name / date presets). */
class EditorActivity : AppCompatActivity() {

    private lateinit var uri: Uri
    private lateinit var toolbar: MaterialToolbar
    private lateinit var toggle: MaterialButtonToggleGroup
    private lateinit var hint: TextView
    private lateinit var loading: View
    private val pages = mutableListOf<PageItem>()
    private val adapter = PageAdapter()

    private var srcFile: File? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Mutex()
    private val thumbs = LruCache<Int, Bitmap>(80)
    private var dirty = false
    private var reversed = false
    private var programmaticToggle = false

    private val saveAs =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
            if (target != null) saveTo(target, isOverwrite = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        applySystemBarInsets(findViewById(R.id.root))

        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        uri = data

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.reorder_pages)
        toolbar.subtitle = Storage.displayName(this, uri)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_reverse -> {
                    reversed = !reversed
                    pages.reverse()
                    markChanged()
                    true
                }
                R.id.action_share -> {
                    share()
                    true
                }
                else -> false
            }
        }

        hint = findViewById(R.id.hint)
        loading = findViewById(R.id.loading)
        toggle = findViewById(R.id.sort_group)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || programmaticToggle) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.sort_name -> SortMode.NAME
                R.id.sort_date -> SortMode.DATE
                else -> SortMode.ORIGINAL
            }
            applySort(mode)
        }

        val list = findViewById<RecyclerView>(R.id.pages)
        list.layoutManager = GridLayoutManager(this, 3)
        list.adapter = adapter
        ItemTouchHelper(DragCallback()).attachToRecyclerView(list)

        findViewById<View>(R.id.btn_save).setOnClickListener { saveTo(uri, isOverwrite = true) }
        findViewById<View>(R.id.btn_save_as).setOnClickListener { saveAs.launch(suggestedName()) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) {
                    finish()
                    return
                }
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(R.string.unsaved_title)
                    .setMessage(R.string.unsaved_message)
                    .setPositiveButton(R.string.discard) { _, _ -> finish() }
                    .setNegativeButton(R.string.keep_editing, null)
                    .show()
            }
        })

        load()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    val f = File(Storage.cacheDir(this@EditorActivity, "edit"), "src.pdf")
                    Storage.copyToFile(this@EditorActivity, uri, f)
                    srcFile = f
                    val list = PDDocument.load(f).use { doc ->
                        doc.pages.mapIndexed { i, p ->
                            val d = p.cosObject
                            PageItem(
                                i,
                                d.getString(PdfMeta.KEY_NAME),
                                d.getLong(PdfMeta.KEY_DATE, -1L).takeIf { it >= 0 }
                            )
                        }
                    }
                    val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = fd
                    renderer = PdfRenderer(fd)
                    list
                }
                pages.clear()
                pages.addAll(items)
                adapter.notifyDataSetChanged()
                updateHint(SortMode.ORIGINAL)
                updateSubtitle()
            } catch (e: Throwable) {
                Toast.makeText(
                    this@EditorActivity,
                    getString(R.string.cannot_open_pdf) + ": " + (e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun applySort(mode: SortMode) {
        if (mode != SortMode.ORIGINAL && pages.none { if (mode == SortMode.NAME) it.sortName != null else it.sortDate != null }) {
            Toast.makeText(this, R.string.no_metadata, Toast.LENGTH_LONG).show()
        }
        val sorted = PageSorter.sort(pages, mode).let { if (reversed) it.reversed() else it }
        pages.clear()
        pages.addAll(sorted)
        updateHint(mode)
        markChanged(keepToggle = true)
    }

    private fun markChanged(keepToggle: Boolean = false) {
        dirty = true
        if (!keepToggle) {
            programmaticToggle = true
            toggle.clearChecked()
            programmaticToggle = false
        }
        adapter.notifyDataSetChanged()
        updateSubtitle()
    }

    private fun updateHint(mode: SortMode) {
        hint.setText(
            when (mode) {
                SortMode.ORIGINAL -> R.string.hint_original
                SortMode.NAME -> R.string.hint_name
                SortMode.DATE -> R.string.hint_date
            }
        )
    }

    private fun updateSubtitle() {
        val n = Storage.displayName(this, uri) ?: ""
        toolbar.subtitle = getString(R.string.editor_subtitle, n, pages.size) + if (dirty) " •" else ""
    }

    private fun suggestedName(): String {
        val n = Storage.displayName(this, uri) ?: "Pix2Doc.pdf"
        return if (n.endsWith(".pdf", true)) n else "$n.pdf"
    }

    private suspend fun writeReordered(name: String): File = withContext(Dispatchers.IO) {
        val src = srcFile ?: error("not loaded")
        val out = File(Storage.cacheDir(this@EditorActivity, "edit/out"), name)
        PDDocument.load(src).use { doc ->
            val all = doc.pages.toList()
            all.forEach { doc.removePage(it) }
            pages.forEach { doc.addPage(all[it.originalIndex]) }
            doc.save(out)
        }
        out
    }

    private fun saveTo(target: Uri, isOverwrite: Boolean) {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val out = writeReordered(suggestedName())
                val size = out.length()
                withContext(Dispatchers.IO) { Storage.copy(this@EditorActivity, out, target) }
                out.delete()
                if (!isOverwrite) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            target,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                    }
                }
                Recents.add(
                    this@EditorActivity,
                    RecentFile(
                        target.toString(),
                        Storage.displayName(this@EditorActivity, target) ?: suggestedName(),
                        pages.size, size, System.currentTimeMillis()
                    )
                )
                dirty = false
                updateSubtitle()
                Toast.makeText(this@EditorActivity, getString(R.string.saved, suggestedName()), Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                if (isOverwrite) {
                    // Read-only source (e.g. a PDF received via share): offer "Save as" instead.
                    Toast.makeText(this@EditorActivity, R.string.read_only_save_as, Toast.LENGTH_LONG).show()
                    saveAs.launch(suggestedName())
                } else {
                    Toast.makeText(
                        this@EditorActivity,
                        getString(R.string.notif_error) + ": " + (e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun share() {
        lifecycleScope.launch {
            try {
                val out = writeReordered(suggestedName())
                val shareUri = FileProvider.getUriForFile(this@EditorActivity, "$packageName.files", out)
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/pdf")
                            .putExtra(Intent.EXTRA_STREAM, shareUri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.share)
                    )
                )
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, e.message ?: getString(R.string.notif_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun render(index: Int, widthPx: Int): Bitmap? = renderLock.withLock {
        withContext(Dispatchers.IO) {
            val r = renderer ?: return@withContext null
            r.openPage(index).use { p ->
                val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    private fun preview(item: PageItem, number: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_preview, null)
        val img = view.findViewById<ImageView>(R.id.image)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.page_n, number) + (item.sortName?.let { " · $it" } ?: ""))
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
        lifecycleScope.launch {
            val w = resources.displayMetrics.widthPixels.coerceAtMost(1600)
            render(item.originalIndex, w)?.let { if (dialog.isShowing) img.setImageBitmap(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            renderer?.close()
            pfd?.close()
        } catch (_: Exception) {
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            if (from < to) for (i in from until to) Collections.swap(pages, i, i + 1)
            else for (i in from downTo to + 1) Collections.swap(pages, i, i - 1)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.7f
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            markChanged()
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.thumb)
            val number: TextView = v.findViewById(R.id.number)
            val name: TextView = v.findViewById(R.id.name)
            var job: Job? = null
            var bound = -1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

        override fun getItemCount() = pages.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = pages[position]
            h.number.text = (position + 1).toString()
            h.name.text = item.sortName ?: getString(R.string.page_n, item.originalIndex + 1)
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos >= 0) preview(pages[pos], pos + 1)
            }
            if (h.bound == item.originalIndex && h.image.drawable != null) return
            h.bound = item.originalIndex
            h.job?.cancel()
            val cached = thumbs.get(item.originalIndex)
            if (cached != null) {
                h.image.setImageBitmap(cached)
                return
            }
            h.image.setImageDrawable(null)
            val idx = item.originalIndex
            h.job = lifecycleScope.launch {
                val bmp = render(idx, 300) ?: return@launch
                thumbs.put(idx, bmp)
                if (h.bound == idx) h.image.setImageBitmap(bmp)
            }
        }
    }
}
