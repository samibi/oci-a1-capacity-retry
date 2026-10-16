package com.pix2doc

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarInsets(findViewById(R.id.root))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val pickFolder =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                if (tree != null) {
                    val ctx = requireContext()
                    ctx.contentResolver.takePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    Prefs.setFolderUri(ctx, tree)
                    updateFolderSummary()
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>(Prefs.KEY_FOLDER)?.setOnPreferenceClickListener {
                pickFolder.launch(null)
                true
            }
            findPreference<Preference>("folder_reset")?.setOnPreferenceClickListener {
                Prefs.setFolderUri(requireContext(), null)
                updateFolderSummary()
                true
            }

            findPreference<ListPreference>(Prefs.KEY_LANGUAGE)?.apply {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                value = when {
                    current.startsWith("he") || current.startsWith("iw") -> "he"
                    current.startsWith("en") -> "en"
                    else -> ""
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val tag = newValue as String
                    AppCompatDelegate.setApplicationLocales(
                        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                        else LocaleListCompat.forLanguageTags(tag)
                    )
                    true
                }
            }
            updateFolderSummary()
        }

        private fun updateFolderSummary() {
            findPreference<Preference>(Prefs.KEY_FOLDER)?.summary = Storage.folderLabel(requireContext())
        }
    }
}
