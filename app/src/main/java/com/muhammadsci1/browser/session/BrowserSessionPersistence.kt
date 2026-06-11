package com.muhammadsci1.browser.session

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BrowserSessionPersistence(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "browser_session",
        Context.MODE_PRIVATE
    )

    fun save(tabs: List<TabSnapshot>, selectedIndex: Int) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(JSONObject().apply {
                put("id", tab.id)
                put("title", tab.title)
                put("url", tab.url)
            })
        }
        preferences.edit()
            .putString(KEY_TABS, array.toString())
            .putInt(KEY_SELECTED_INDEX, selectedIndex.coerceAtLeast(0))
            .apply()
    }

    fun restore(): Pair<List<TabSnapshot>, Int> {
        val raw = preferences.getString(KEY_TABS, null) ?: return emptyList<TabSnapshot>() to 0
        return try {
            val array = JSONArray(raw)
            val tabs = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            TabSnapshot(
                                id = item.optLong("id", System.nanoTime() + i),
                                title = item.optString("title", "Tab ${i + 1}"),
                                url = url
                            )
                        )
                    }
                }
            }
            tabs to preferences.getInt(KEY_SELECTED_INDEX, 0)
        } catch (_: Throwable) {
            emptyList<TabSnapshot>() to 0
        }
    }

    companion object {
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED_INDEX = "selected_index"
    }
}
