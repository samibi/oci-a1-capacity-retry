package com.pix2doc

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager

object Prefs {
    const val KEY_FOLDER = "folder_uri"
    const val KEY_ASK_LOCATION = "ask_location"
    const val KEY_FILENAME = "filename_pattern"
    const val KEY_PAGE_SIZE = "page_size"
    const val KEY_MARGIN = "margin_mm"
    const val KEY_CAP_DPI = "cap_dpi"
    const val KEY_SHARE_ORDER = "share_order"
    const val KEY_LANGUAGE = "language"
    const val KEY_ASKED_NOTIF = "asked_notifications"

    fun sp(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun folderUri(ctx: Context): Uri? = sp(ctx).getString(KEY_FOLDER, null)?.let(Uri::parse)
    fun setFolderUri(ctx: Context, uri: Uri?) =
        sp(ctx).edit().putString(KEY_FOLDER, uri?.toString()).apply()

    fun askLocation(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ASK_LOCATION, false)
    fun filenamePattern(ctx: Context): String =
        sp(ctx).getString(KEY_FILENAME, FileNamer.DEFAULT_PATTERN) ?: FileNamer.DEFAULT_PATTERN
    fun pageSize(ctx: Context): PageSize = PageSize.fromKey(sp(ctx).getString(KEY_PAGE_SIZE, "a4"))
    fun marginMm(ctx: Context): Float = sp(ctx).getString(KEY_MARGIN, "5")?.toFloatOrNull() ?: 5f
    fun capDpi(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_CAP_DPI, false)
    fun shareOrder(ctx: Context): SortMode = SortMode.fromKey(sp(ctx).getString(KEY_SHARE_ORDER, "sent"))
}
