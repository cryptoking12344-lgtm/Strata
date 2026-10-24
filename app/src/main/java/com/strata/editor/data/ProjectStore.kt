package com.strata.editor.data

import android.content.Context
import com.strata.editor.model.Clip
import com.strata.editor.model.ClipKind
import com.strata.editor.model.Keyframe
import com.strata.editor.model.Project
import com.strata.editor.model.Prop
import com.strata.editor.model.ProxyState
import com.strata.editor.model.Track
import com.strata.editor.model.TrackKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves projects as JSON files in app storage. */
class ProjectStore(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(p: Project) {
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(toJson(p).toString())
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    companion object {
        fun toJson(p: Project): JSONObject = JSONObject().apply {
            put("v", 1)
            put("id", p.id)
            put("name", p.name)
            put("canvasW", p.canvasW)
            put("canvasH", p.canvasH)
            put("fps", p.fps)
            put("createdAt", p.createdAt)
            put("updatedAt", p.updatedAt)
            put("tracks", JSONArray().apply { p.tracks.forEach { put(trackJson(it)) } })
        }

        private fun trackJson(t: Track) = JSONObject().apply {
            put("id", t.id)
            put("kind", t.kind.name)
            put("name", t.name)
            put("visible", t.visible)
            put("muted", t.muted)
            put("locked", t.locked)
            put("clips", JSONArray().apply { t.clips.forEach { put(clipJson(it)) } })
        }

        private fun clipJson(c: Clip) = JSONObject().apply {
            put("id", c.id)
            put("kind", c.kind.name)
            put("uri", c.uri)
            put("name", c.name)
            c.proxyPath?.let { put("proxyPath", it) }
            put("proxyState", c.proxyState.name)
            put("sourceDurationUs", c.sourceDurationUs)
            put("width", c.width)
            put("height", c.height)
            put("hasAudio", c.hasAudio)
            put("trimStartUs", c.trimStartUs)
            put("trimEndUs", c.trimEndUs)
            put("startUs", c.startUs)
            put("speed", c.speed.toDouble())
            put("volume", c.volume.toDouble())
            put("x", c.x.toDouble())
            put("y", c.y.toDouble())
            put("scale", c.scale.toDouble())
            put("rotation", c.rotation.toDouble())
            put("opacity", c.opacity.toDouble())
            put("brightness", c.brightness.toDouble())
            put("contrast", c.contrast.toDouble())
            put("saturation", c.saturation.toDouble())
            put("text", c.text)
            put("textColor", c.textColor)
            put("textSize", c.textSize.toDouble())
            put("textBold", c.textBold)
            put("keyframes", JSONObject().apply {
                c.keyframes.forEach { (p, ks) ->
                    put(p.name, JSONArray().apply {
                        ks.forEach { k -> put(JSONObject().put("t", k.timeUs).put("v", k.value.toDouble())) }
                    })
                }
            })
        }

        fun fromJson(o: JSONObject): Project {
            val tracks = o.getJSONArray("tracks").let { a -> (0 until a.length()).map { trackFrom(a.getJSONObject(it)) } }
            return Project(
                id = o.getString("id"),
                name = o.optString("name", "Untitled"),
                canvasW = o.optInt("canvasW", 1920),
                canvasH = o.optInt("canvasH", 1080),
                fps = o.optInt("fps", 30),
                tracks = if (tracks.any { it.kind == TrackKind.MAIN }) tracks else tracks + Track(kind = TrackKind.MAIN, name = "Main"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }

        private fun trackFrom(o: JSONObject): Track {
            val clips = o.getJSONArray("clips").let { a -> (0 until a.length()).map { clipFrom(a.getJSONObject(it)) } }
            return Track(
                id = o.getString("id"),
                kind = TrackKind.valueOf(o.getString("kind")),
                name = o.optString("name"),
                clips = clips,
                visible = o.optBoolean("visible", true),
                muted = o.optBoolean("muted", false),
                locked = o.optBoolean("locked", false),
            )
        }

        private fun clipFrom(o: JSONObject): Clip {
            val keys = mutableMapOf<Prop, List<Keyframe>>()
            o.optJSONObject("keyframes")?.let { ko ->
                for (p in Prop.entries) {
                    val a = ko.optJSONArray(p.name) ?: continue
                    keys[p] = (0 until a.length()).map {
                        val k = a.getJSONObject(it)
                        Keyframe(k.getLong("t"), k.getDouble("v").toFloat())
                    }
                }
            }
            val src = o.getLong("sourceDurationUs")
            // A proxy that was still rendering when the app closed is redone on next open.
            val ps = runCatching { ProxyState.valueOf(o.optString("proxyState", "NONE")) }.getOrDefault(ProxyState.NONE)
            return Clip(
                id = o.getString("id"),
                kind = ClipKind.valueOf(o.getString("kind")),
                uri = o.optString("uri"),
                name = o.optString("name"),
                proxyPath = if (o.has("proxyPath")) o.getString("proxyPath") else null,
                proxyState = if (ps == ProxyState.PENDING) ProxyState.NONE else ps,
                sourceDurationUs = src,
                width = o.optInt("width"),
                height = o.optInt("height"),
                hasAudio = o.optBoolean("hasAudio"),
                trimStartUs = o.optLong("trimStartUs", 0),
                trimEndUs = o.optLong("trimEndUs", src),
                startUs = o.optLong("startUs", 0),
                speed = o.optDouble("speed", 1.0).toFloat(),
                volume = o.optDouble("volume", 1.0).toFloat(),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                scale = o.optDouble("scale", 1.0).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 1.0).toFloat(),
                keyframes = keys,
                brightness = o.optDouble("brightness", 0.0).toFloat(),
                contrast = o.optDouble("contrast", 0.0).toFloat(),
                saturation = o.optDouble("saturation", 0.0).toFloat(),
                text = o.optString("text"),
                textColor = o.optInt("textColor", 0xFFFFFFFF.toInt()),
                textSize = o.optDouble("textSize", 0.08).toFloat(),
                textBold = o.optBoolean("textBold", true),
            )
        }
    }
}
