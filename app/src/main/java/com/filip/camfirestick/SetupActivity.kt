package com.filip.camfirestick

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/** Tela com QR code: o celular abre a página de configuração servida pelo Fire Stick. */
class SetupActivity : Activity() {

    private var server: ConfigServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_setup)
    }

    override fun onStart() {
        super.onStart()
        val urlView = findViewById<TextView>(R.id.setup_url)
        val countView = findViewById<TextView>(R.id.setup_count)
        val qrView = findViewById<ImageView>(R.id.setup_qr)

        val store = CameraStore(this)
        val n = store.load().size
        countView.text = if (n == 0) "Nenhuma câmera cadastrada ainda." else "$n câmera(s) cadastrada(s). Voltar = ver câmeras."

        val ip = NetUtils.localIpv4()
        if (ip == null) {
            urlView.text = "Fire Stick sem rede. Conecte ao Wi-Fi."
            return
        }
        val token = randomToken()
        val url = "http://$ip:${Settings.CONFIG_PORT}/?t=$token"

        server = ConfigServer(this, store, token) { cams ->
            runOnUiThread {
                Toast.makeText(this, "${cams.size} câmera(s) salva(s)", Toast.LENGTH_SHORT).show()
                finish() // volta para o mosaico, que recarrega as câmeras
            }
        }
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            urlView.text = url
            qrView.setImageBitmap(qrBitmap(url, 600))
        } catch (e: Exception) {
            urlView.text = "Erro ao abrir servidor: ${e.message}"
        }
    }

    override fun onStop() {
        super.onStop()
        server?.stop()
        server = null
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
