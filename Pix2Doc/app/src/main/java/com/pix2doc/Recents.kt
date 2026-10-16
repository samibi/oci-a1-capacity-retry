package com.pix2doc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val pages: Int,
    val size: Long,
    val time: Long,
)

object Recents {
    private const val KEY = "recents"
    private const val MAX = 30

    fun list(ctx: Context): List<RecentFile> {
        val raw = Prefs.sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(
                    o.getString("uri"),
                    o.optString("name"),
                    o.optInt("pages"),
                    o.optLong("size"),
                    o.optLong("time"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, item: RecentFile) {
        val items = listOf(item) + list(ctx).filter { it.uri != item.uri }
        save(ctx, items.take(MAX))
    }

    fun remove(ctx: Context, uri: String) = save(ctx, list(ctx).filter { it.uri != uri })

    private fun save(ctx: Context, items: List<RecentFile>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("pages", it.pages)
                    .put("size", it.size)
                    .put("time", it.time)
            )
        }
        Prefs.sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
