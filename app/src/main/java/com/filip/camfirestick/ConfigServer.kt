package com.filip.camfirestick

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Servidor web local, aberto pelo celular via QR code.
 * Toda chamada de /api exige o token que está no QR.
 */
class ConfigServer(
    private val context: Context,
    private val store: CameraStore,
    private val token: String,
    private val onSaved: (List<Camera>) -> Unit,
) : NanoHTTPD(Settings.CONFIG_PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "erro"))
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/" || uri == "/index.html") {
            val html = context.assets.open("setup.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!uri.startsWith("/api/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
        if (session.parameters["t"]?.firstOrNull() != token) {
            return json(Response.Status.FORBIDDEN, JSONObject().put("error", "token inválido"))
        }
        return when {
            uri == "/api/cameras" && session.method == Method.GET -> listCameras()
            uri == "/api/cameras" && session.method == Method.POST -> saveCameras(session)
            uri == "/api/scan" -> json(Response.Status.OK, JSONObject().put("hosts", JSONArray(NetUtils.scanRtsp())))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    private fun listCameras(): Response {
        // Senhas nunca saem do Fire Stick; a página só sabe se existe uma.
        val arr = JSONArray().apply { store.load().forEach { put(it.toJson(includePassword = false)) } }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("cameras", arr)
                .put("defaultUser", store.defaultUser)
                .put("hasDefaultPassword", store.defaultPassword.isNotEmpty())
                .put("tourSeconds", store.tourSeconds)
                .put("tourMode", store.tourMode),
        )
    }

    private fun saveCameras(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = JSONObject(files["postData"] ?: "{}")

        val oldDefaultPassword = store.defaultPassword
        val defUser = body.optString("defaultUser").trim().ifBlank { "admin" }
        val defPass = body.optString("defaultPassword").ifEmpty { oldDefaultPassword }
        val previous = store.load().associateBy { it.id }

        val arr = body.optJSONArray("cameras") ?: JSONArray()
        val cameras = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            // Senha vazia no formulário = mantém a atual; se a atual era a padrão, segue a nova padrão.
            val keptPassword = previous[id]?.password?.takeIf { it.isNotEmpty() && it != oldDefaultPassword }
            Camera(
                id = id,
                name = o.optString("name").trim().ifBlank { "Câmera ${i + 1}" },
                host = o.optString("host").trim().replace(',', '.'),
                port = o.optString("port").toIntOrNull() ?: 554,
                channel = o.optString("channel").toIntOrNull() ?: 1,
                user = o.optString("user").trim().ifBlank { defUser },
                password = o.optString("password").ifEmpty { keptPassword ?: defPass },
                customUrl = o.optString("customUrl").trim(),
                rotation = Camera.normalizeRotation(o.optString("rotation").toIntOrNull() ?: 0),
                page = (o.optString("page").toIntOrNull() ?: 1).coerceIn(0, 99),
            )
        }.filter { it.host.isNotBlank() || it.customUrl.isNotBlank() }

        val tour = body.optString("tourSeconds").toIntOrNull()?.coerceIn(0, 3600) ?: 0
        val tourMode = if (body.optString("tourMode") == CameraStore.TOUR_CAMERA) CameraStore.TOUR_CAMERA else CameraStore.TOUR_PAGE
        store.save(cameras, defUser, defPass, tour, tourMode)
        onSaved(cameras)
        return json(Response.Status.OK, JSONObject().put("saved", cameras.size))
    }

    private fun json(status: Response.Status, obj: JSONObject) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
}
