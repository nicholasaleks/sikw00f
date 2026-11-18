package com.sikw00f.android.alerts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A NetID the user has labelled, e.g. their own test aircraft. Friendly
 * drones never raise alerts, are skipped by auto-lock and go to TAK as
 * friendly tracks.
 */
data class KnownDrone(
    val netId: Int,
    val name: String = "",
    val friendly: Boolean = true,
    /** ARGB colour override for maps and lists; null keeps the automatic one. */
    val color: Int? = null,
    val note: String = ""
) {
    val label: String get() = name.ifBlank { "NetID $netId" }
}

class KnownDroneStore(context: Context) {
    private val prefs = context.getSharedPreferences("known_drones", Context.MODE_PRIVATE)

    fun load(): Map<Int, KnownDrone> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.associate { j ->
            val id = j.getInt("netId")
            id to KnownDrone(
                netId = id,
                name = j.optString("name", ""),
                friendly = j.optBoolean("friendly", true),
                color = if (j.has("color") && !j.isNull("color")) j.getInt("color") else null,
                note = j.optString("note", "")
            )
        }
    }.getOrDefault(emptyMap())

    fun save(drones: Map<Int, KnownDrone>) {
        val arr = JSONArray()
        drones.values.sortedBy { it.netId }.forEach { d ->
            arr.put(
                JSONObject()
                    .put("netId", d.netId)
                    .put("name", d.name)
                    .put("friendly", d.friendly)
                    .put("color", d.color ?: JSONObject.NULL)
                    .put("note", d.note)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "drones_v1"
    }
}
