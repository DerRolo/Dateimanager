package de.dateimanager.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Favoriten, gesperrte Dateien und eigene Sammlungen (Tags). Wird in den Einstellungen gespeichert. */
class Marks(private val prefs: SharedPreferences) {

    var favorites by mutableStateOf(loadSet("fav"))
    var locked by mutableStateOf(loadSet("locked"))
    var tags by mutableStateOf(loadTags())

    private fun loadSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun loadTags(): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        try {
            val o = JSONObject(prefs.getString("tags", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.getJSONArray(k)
                val s = LinkedHashSet<String>()
                for (i in 0 until a.length()) s.add(a.getString(i))
                out[k] = s
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun saveTags() {
        val o = JSONObject()
        for ((k, v) in tags) {
            val a = JSONArray()
            for (p in v) a.put(p)
            o.put(k, a)
        }
        prefs.edit().putString("tags", o.toString()).apply()
    }

    /** Gesperrt ist eine Datei, wenn sie selbst oder ein übergeordneter Ordner gesperrt ist. */
    fun isLocked(p: String): Boolean {
        for (l in locked) {
            if (p == l || p.startsWith("$l/")) return true
        }
        return false
    }

    /** Geschützt ist auch ein Ordner, in dem etwas Gesperrtes liegt. */
    fun isProtected(p: String): Boolean {
        if (isLocked(p)) return true
        for (l in locked) {
            if (l.startsWith("$p/")) return true
        }
        return false
    }

    fun toggleFavorites(paths: List<String>) {
        val all = paths.all { it in favorites }
        favorites = if (all) favorites - paths.toSet() else favorites + paths
        prefs.edit().putStringSet("fav", favorites).apply()
    }

    fun toggleLock(paths: List<String>) {
        val all = paths.all { it in locked }
        locked = if (all) locked - paths.toSet() else locked + paths
        prefs.edit().putStringSet("locked", locked).apply()
    }

    fun addToTag(tag: String, paths: List<String>) {
        val cur = tags[tag] ?: emptySet()
        tags = tags + (tag to (cur + paths))
        saveTags()
    }

    fun removeFromTag(tag: String, paths: List<String>) {
        val cur = tags[tag] ?: return
        val rest = cur - paths.toSet()
        tags = if (rest.isEmpty()) tags - tag else tags + (tag to rest)
        saveTags()
    }

    fun deleteTag(tag: String) {
        tags = tags - tag
        saveTags()
    }

    /** Pfad wurde verschoben/umbenannt: Markierungen mitziehen (auch für Dateien in Ordnern). */
    fun remap(old: String, new: String) {
        fun m(p: String): String =
            if (p == old) new else if (p.startsWith("$old/")) new + p.substring(old.length) else p
        val f = favorites.map { m(it) }.toSet()
        if (f != favorites) {
            favorites = f
            prefs.edit().putStringSet("fav", f).apply()
        }
        val l = locked.map { m(it) }.toSet()
        if (l != locked) {
            locked = l
            prefs.edit().putStringSet("locked", l).apply()
        }
        val t = tags.mapValues { (_, v) -> v.map { m(it) }.toSet() }
        if (t != tags) {
            tags = t
            saveTags()
        }
    }
}
