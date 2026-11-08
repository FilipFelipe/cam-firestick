package com.filip.camfirestick

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Settings {
    /** No mosaico usa o substream (subtype=1), bem mais leve para o Fire Stick. */
    const val USE_SUBSTREAM_IN_GRID = true
    const val CONFIG_PORT = 8080
}

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 554,
    val channel: Int = 1,
    val user: String = "admin",
    val password: String = "",
    /** URL RTSP completa (outras marcas). Se preenchida, ignora host/porta/canal. */
    val customUrl: String = "",
    /** Rotação da imagem: 0, 90, 180 ou 270. */
    val rotation: Int = 0,
    /** Página do mosaico (1, 2, ...). 0 = não exibir. */
    val page: Int = 1,
) {
    /** subtype 0 = stream principal, 1 = substream. Padrão Dahua/Intelbras. */
    fun url(subtype: Int): String {
        if (customUrl.isNotBlank()) {
            return if (subtype == 1) customUrl.replace("subtype=0", "subtype=1") else customUrl
        }
        return "rtsp://${Uri.encode(user)}:${Uri.encode(password)}@$host:$port" +
            "/cam/realmonitor?channel=$channel&subtype=$subtype"
    }

    val mainUrl get() = url(0)
    val gridUrl get() = if (Settings.USE_SUBSTREAM_IN_GRID) url(1) else url(0)

    fun toJson(includePassword: Boolean) = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("channel", channel); put("user", user); put("customUrl", customUrl)
        put("rotation", rotation); put("page", page)
        if (includePassword) put("password", password) else put("hasPassword", password.isNotEmpty())
    }

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 554),
            channel = o.optInt("channel", 1),
            user = o.optString("user", "admin"),
            password = o.optString("password"),
            customUrl = o.optString("customUrl"),
            rotation = normalizeRotation(o.optInt("rotation", 0)),
            page = o.optInt("page", -1), // -1 = salvo antes de existir página; ver CameraStore.load
        )

        fun normalizeRotation(deg: Int) = ((deg / 90 * 90) % 360 + 360) % 360
    }
}

/** Guarda as câmeras no armazenamento privado do app. */
class CameraStore(context: Context) {
    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<Camera> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { i ->
            Camera.fromJson(arr.getJSONObject(i)).let { c ->
                if (c.page < 0) c.copy(page = i / 4 + 1) else c // distribui 4 por página
            }
        }
    }

    @Synchronized
    fun save(cameras: List<Camera>, defaultUser: String, defaultPassword: String, tourSeconds: Int, tourMode: String) {
        val arr = JSONArray().apply { cameras.forEach { put(it.toJson(includePassword = true)) } }
        prefs.edit()
            .putString("list", arr.toString())
            .putString("defaultUser", defaultUser)
            .putString("defaultPassword", defaultPassword)
            .putInt("tourSeconds", tourSeconds)
            .putString("tourMode", tourMode)
            .apply()
    }

    val defaultUser: String get() = prefs.getString("defaultUser", "admin") ?: "admin"
    val defaultPassword: String get() = prefs.getString("defaultPassword", "") ?: ""

    /** Ronda: troca de página a cada N segundos (0 = desligada). */
    val tourSeconds: Int get() = prefs.getInt("tourSeconds", 0)

    /** "page" = troca de página; "camera" = cada câmera em tela cheia. */
    val tourMode: String get() = prefs.getString("tourMode", TOUR_PAGE) ?: TOUR_PAGE

    companion object {
        const val TOUR_PAGE = "page"
        const val TOUR_CAMERA = "camera"
    }
}
