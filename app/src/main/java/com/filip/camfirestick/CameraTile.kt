package com.filip.camfirestick

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/** Um quadrado do mosaico: um player RTSP + rótulos. Reconecta sozinho se cair. */
@OptIn(UnstableApi::class)
class CameraTile(
    private val context: Context,
    val camera: Camera,
    val root: View,
) {
    private val holder: FrameLayout = root.findViewById(R.id.video_holder)
    // SurfaceView é mais leve, mas não pode ser girada; câmeras com rotação usam TextureView.
    private val playerView: PlayerView = LayoutInflater.from(context).inflate(
        if (camera.rotation == 0) R.layout.player_surface else R.layout.player_texture, holder, false,
    ) as PlayerView
    private val status: TextView = root.findViewById(R.id.status)
    private val audioBadge: View = root.findViewById(R.id.audio_badge)
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null

    var audioOn: Boolean = false
        set(value) {
            field = value
            player?.volume = if (value) 1f else 0f
            audioBadge.visibility = if (value) View.VISIBLE else View.GONE
        }

    /**
     * Mostra/esconde o quadrado. No Fire TV esconder só o pai não basta: a SurfaceView
     * é uma camada de vídeo separada e o último quadro fica "fantasma" por cima.
     * Por isso a superfície de vídeo é escondida diretamente.
     */
    var visible: Boolean = true
        set(value) {
            field = value
            val v = if (value) View.VISIBLE else View.GONE
            playerView.videoSurfaceView?.visibility = v
            root.visibility = v
        }

    init {
        root.findViewById<TextView>(R.id.label).text = camera.name
        holder.addView(playerView)
        applyRotation()
    }

    private fun applyRotation() {
        val deg = camera.rotation
        if (deg == 0) return
        playerView.rotation = deg.toFloat()
        if (deg % 180 == 0) return
        // 90°/270°: o player recebe largura/altura trocadas e, girado, preenche o quadrado.
        holder.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            val lp = playerView.layoutParams
            if (w > 0 && h > 0 && (lp.width != h || lp.height != w)) {
                holder.post { playerView.layoutParams = FrameLayout.LayoutParams(h, w, Gravity.CENTER) }
            }
        }
    }

    private val retry = Runnable { currentUrl?.let { play(it) } }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> showStatus("Conectando…")
                Player.STATE_READY -> showStatus(null)
                Player.STATE_ENDED -> scheduleRetry("Stream encerrado — reconectando…")
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry("Sem sinal — tentando de novo…")
        }
    }

    fun play(url: String) {
        handler.removeCallbacks(retry)
        currentUrl = url
        val p = player ?: buildPlayer().also {
            player = it
            playerView.player = it
        }
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // evita imagem quebrada/perda de pacote UDP no Wi-Fi
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
        p.volume = if (audioOn) 1f else 0f
        showStatus("Conectando…")
    }

    /** Libera o player por completo (inclusive o decoder de hardware). */
    fun release() {
        handler.removeCallbacks(retry)
        currentUrl = null
        playerView.player = null
        player?.release()
        player = null
        showStatus(null)
    }

    private fun buildPlayer(): ExoPlayer {
        // Buffer pequeno = menos atraso em relação ao "ao vivo".
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 250, 500)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // false: são 4 players, não queremos que briguem pelo foco de áudio.
                setAudioAttributes(AudioAttributes.DEFAULT, false)
                addListener(listener)
            }
    }

    private fun scheduleRetry(message: String) {
        if (currentUrl == null) return
        showStatus(message)
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 3_000)
    }

    private fun showStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }
}
