package com.filip.camfirestick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mosaico em páginas (até 4 câmeras por página, limite de decoders do Fire Stick HD).
 * Layout por página: 1 câmera = tela cheia, 2 = tela dividida ao meio, 3-4 = 2x2.
 *  - D-pad move o foco; o áudio segue a câmera focada.
 *  - →/← na borda (ou ⏩/⏪) troca de página.
 *  - OK abre em tela cheia (stream principal); ←/→ percorre todas as câmeras.
 *  - Voltar retorna ao mosaico. ☰ abre a configuração via QR code.
 *  - Ronda (opcional): por página, ou por câmera (cada uma em tela cheia por N segundos).
 */
class MainActivity : Activity() {

    private companion object {
        const val PAGE_SIZE = 4
        const val INDICATOR_MS = 2_500L
    }

    private lateinit var store: CameraStore
    private lateinit var grid: LinearLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var pageIndicator: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var pages: List<List<Camera>> = emptyList()
    private var cameras: List<Camera> = emptyList() // todas as exibidas, na ordem das páginas
    private var tourSeconds = 0
    private var tourMode = CameraStore.TOUR_PAGE
    private var setupOpenedForEmpty = false

    // Página atual do mosaico
    private val tiles = mutableListOf<CameraTile>()
    private var pageIndex = 0
    private var focusSlot = 0
    private var cols = 2

    // Tela cheia (índice em `cameras`; -1 = mosaico)
    private var fullscreenIndex = -1
    private var fullscreenTile: CameraTile? = null

    private val hideIndicator = Runnable { pageIndicator.visibility = View.GONE }
    private val tour = Runnable {
        when {
            tourMode == CameraStore.TOUR_CAMERA ->
                // Do mosaico, começa pela câmera focada; em tela cheia, vai para a próxima.
                showFullscreen(if (fullscreenIndex >= 0) fullscreenIndex + 1 else focusedGlobalIndex())
            fullscreenIndex < 0 -> showPage(pageIndex + 1, 0)
        }
    }

    private fun focusedGlobalIndex() = pages.take(pageIndex).sumOf { it.size } + focusSlot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        store = CameraStore(this)
        grid = findViewById(R.id.grid)
        fullscreen = findViewById(R.id.fullscreen)
        pageIndicator = findViewById(R.id.page_indicator)
    }

    override fun onStart() {
        super.onStart()
        // Agrupa por número de página (pulando páginas vazias); por segurança, nunca mais de 4.
        pages = store.load()
            .filter { it.page > 0 }
            .groupBy { it.page }.toSortedMap().values
            .flatMap { it.chunked(PAGE_SIZE) }
        cameras = pages.flatten()
        tourSeconds = store.tourSeconds
        tourMode = store.tourMode

        if (cameras.isEmpty()) {
            // Nada para exibir: abre o QR. Se voltar de lá sem cadastrar nada, sai do app.
            if (setupOpenedForEmpty) finish() else { setupOpenedForEmpty = true; openSetup() }
            return
        }
        setupOpenedForEmpty = false
        if (fullscreenIndex in cameras.indices) showFullscreen(fullscreenIndex) else showPage(pageIndex, focusSlot)
    }

    override fun onStop() {
        super.onStop()
        // Ao sair (Home ou configuração), libera todas as conexões RTSP e decoders.
        handler.removeCallbacks(tour)
        clearGrid()
        fullscreenTile?.release()
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    // ---------- Mosaico ----------

    private fun clearGrid() {
        tiles.forEach { it.release(); it.visible = false }
        tiles.clear()
        grid.removeAllViews()
    }

    private fun showPage(page: Int, slot: Int) {
        closeFullscreen()
        clearGrid()
        pageIndex = Math.floorMod(page, pages.size)
        grid.visibility = View.VISIBLE

        val pageCams = pages[pageIndex]
        val start = pages.take(pageIndex).sumOf { it.size }
        val n = pageCams.size
        cols = if (n == 1) 1 else 2
        val rowCount = if (n <= 2) 1 else 2

        for (r in 0 until rowCount) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= n) {
                    // Espaço vazio (3 câmeras em 2x2): mantém os quadrados do mesmo tamanho.
                    row.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                    continue
                }
                val view = layoutInflater.inflate(R.layout.view_camera_tile, row, false)
                row.addView(view)
                view.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && fullscreenIndex < 0) { focusSlot = i; setActiveAudio(i) }
                }
                view.setOnClickListener { showFullscreen(start + i) } // OK do controle
                tiles += CameraTile(this, pageCams[i], view).also { it.play(it.camera.gridUrl) }
            }
        }

        focusSlot = slot.coerceIn(0, tiles.size - 1)
        tiles[focusSlot].root.requestFocus()
        setActiveAudio(focusSlot)
        if (pages.size > 1) showIndicator("Página ${pageIndex + 1}/${pages.size}")
        scheduleTour()
    }

    private fun setActiveAudio(i: Int) {
        tiles.forEachIndexed { j, tile -> tile.audioOn = (j == i) }
    }

    // ---------- Tela cheia ----------

    private fun showFullscreen(index: Int) {
        handler.removeCallbacks(tour)
        clearGrid() // standby: fecha conexões e libera decoders das outras câmeras
        grid.visibility = View.GONE
        fullscreenTile?.release()
        fullscreen.removeAllViews()

        fullscreenIndex = Math.floorMod(index, cameras.size)
        // Uma superfície nova a cada troca evita quadros "fantasmas" da câmera anterior.
        val view = layoutInflater.inflate(R.layout.view_camera_tile, fullscreen, false)
        fullscreen.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.setOnClickListener { exitFullscreen() }
        fullscreen.visibility = View.VISIBLE
        view.requestFocus()

        fullscreenTile = CameraTile(this, cameras[fullscreenIndex], view).also {
            it.audioOn = true
            it.play(it.camera.mainUrl)
        }
        if (cameras.size > 1) showIndicator("${fullscreenIndex + 1}/${cameras.size} · ${cameras[fullscreenIndex].name}")
        scheduleTour()
    }

    private fun closeFullscreen() {
        fullscreenTile?.release()
        fullscreenTile = null
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        fullscreenIndex = -1
    }

    /** Volta ao mosaico na página onde está a câmera que estava em tela cheia. */
    private fun exitFullscreen() {
        var idx = fullscreenIndex
        for (p in pages.indices) {
            if (idx < pages[p].size) { showPage(p, idx); return }
            idx -= pages[p].size
        }
        showPage(0, 0)
    }

    // ---------- Controle remoto ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) scheduleTour() // interação reinicia a ronda
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (cameras.isEmpty()) return super.onKeyDown(keyCode, event)
        val full = fullscreenIndex >= 0
        val col = focusSlot % cols
        val multiPage = pages.size > 1
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { openSetup(); return true }
            KeyEvent.KEYCODE_BACK -> if (full) { exitFullscreen(); return true }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> when {
                full -> { showFullscreen(fullscreenIndex + 1); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && multiPage -> { showPage(pageIndex + 1, 0); return true }
                multiPage && (col == cols - 1 || focusSlot == tiles.size - 1) -> {
                    showPage(pageIndex + 1, focusSlot - col); return true // entra pela esquerda, mesma linha
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> when {
                full -> { showFullscreen(fullscreenIndex - 1); return true }
                keyCode == KeyEvent.KEYCODE_MEDIA_REWIND && multiPage -> { showPage(pageIndex - 1, 0); return true }
                multiPage && col == 0 -> {
                    showPage(pageIndex - 1, focusSlot + cols - 1); return true // entra pela direita, mesma linha
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- Ronda e indicador ----------

    private fun scheduleTour() {
        handler.removeCallbacks(tour)
        val active = when (tourMode) {
            CameraStore.TOUR_CAMERA -> cameras.size > 1
            else -> pages.size > 1 && fullscreenIndex < 0
        }
        if (tourSeconds > 0 && active) {
            handler.postDelayed(tour, tourSeconds * 1000L)
        }
    }

    private fun showIndicator(text: String) {
        pageIndicator.text = text
        pageIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, INDICATOR_MS)
    }
}
