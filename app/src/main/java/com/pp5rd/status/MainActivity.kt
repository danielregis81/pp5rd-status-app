package com.pp5rd.status

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.preference.PreferenceManager
import com.pp5rd.status.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var apiClient: ApiClient? = null
    
    private val allScreens = listOf("Sistema", "TETRA", "Pi-Star", "Docker", "Rede", "Leitura")
    private val screenKeys = listOf("screen_sistema", "screen_tetra", "screen_pistar", "screen_docker", "screen_rede", "screen_leitura")
    private var enabledScreens = mutableListOf<String>()
    private var currentScreenIndex = 0
    
    private var serverStats: ServerStats? = null
    private var piStarData: PiStarData? = null
    private var lastValidCur: PiStarEntry? = null  // Guarda último QSO válido
    private var lastValidList: List<PiStarEntry> = emptyList()  // Guarda última lista válida
    private var lastValidToday: Int = 0
    private var lastValidCountries: Int = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private var rotationInterval = 10000L
    private var pollInterval = 2000L
    private var isPaused = false
    
    // Cache de bandeiras e capa
    private val flagCache = mutableMapOf<String, Bitmap?>()
    private var lastCoverUrl = ""
    private var coverBitmap: Bitmap? = null
    
    // TETRA audio player (ExoPlayer igual ao telive-app)
    private var tetraPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var selectedTrunk = 0  // 0 = nenhum, 1-4 = tronco
    private var tetraVolume = 0.8f  // 0.0 a 1.0
    
    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (enabledScreens.isNotEmpty() && !isPaused) {
                currentScreenIndex = (currentScreenIndex + 1) % enabledScreens.size
                showScreen(currentScreenIndex)
            }
            handler.postDelayed(this, rotationInterval)
        }
    }
    
    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchData()
            handler.postDelayed(this, pollInterval)
        }
    }
    
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupFullscreen()
        loadSettings()
        setupDots()
        
        // Botão de configurações
        binding.root.findViewById<ImageButton>(R.id.settingsButton)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // Botão de pause/play
        val pauseButton = binding.root.findViewById<ImageButton>(R.id.pauseButton)
        pauseButton?.setOnClickListener {
            isPaused = !isPaused
            pauseButton.setImageResource(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            )
        }
        
        // Swipe na tela: esquerda volta, direita avança
        var startX = 0f
        binding.contentContainer.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val minSwipe = 100 // pixels mínimos pra considerar swipe
                    
                    if (enabledScreens.isNotEmpty() && kotlin.math.abs(deltaX) > minSwipe) {
                        handler.removeCallbacks(rotateRunnable)
                        
                        if (deltaX > 0) {
                            // Swipe pra direita - volta
                            currentScreenIndex = if (currentScreenIndex > 0) currentScreenIndex - 1 else enabledScreens.size - 1
                        } else {
                            // Swipe pra esquerda - avança
                            currentScreenIndex = (currentScreenIndex + 1) % enabledScreens.size
                        }
                        showScreen(currentScreenIndex)
                        
                        if (!isPaused) {
                            handler.postDelayed(rotateRunnable, rotationInterval)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        setupFullscreen()  // Re-aplicar fullscreen ao voltar
        loadSettings()
        setupDots()
        currentScreenIndex = 0
        showConnecting()
        handler.post(pollRunnable)
        handler.post(clockRunnable)
        if (!isPaused) {
            handler.postDelayed(rotateRunnable, rotationInterval)
        }
        // Atualizar ícone do botão pause
        binding.root.findViewById<ImageButton>(R.id.pauseButton)?.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(clockRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopTetraAudio()
    }
    
    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    
    private fun setupDots() {
        binding.dotsContainer.removeAllViews()
        enabledScreens.forEachIndexed { index, _ ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                    marginStart = 8
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (index == 0) getColor(R.color.accent_orange) else getColor(R.color.card_border))
                }
            }
            binding.dotsContainer.addView(dot)
        }
    }
    
    private fun updateDots() {
        for (i in 0 until binding.dotsContainer.childCount) {
            val dot = binding.dotsContainer.getChildAt(i)
            (dot.background as? GradientDrawable)?.setColor(
                if (i == currentScreenIndex) getColor(R.color.accent_orange) else getColor(R.color.card_border)
            )
        }
    }
    
    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Modo de conexão: local ou externo
        val connectionMode = prefs.getString("connection_mode", "local") ?: "local"
        val isExternal = connectionMode == "external"
        
        // URL do servidor de stats
        val serverUrl = if (isExternal) {
            LocalConfig.EXTERNAL_STATS_URL
        } else {
            prefs.getString("local_server_url", LocalConfig.DEFAULT_LOCAL_URL) ?: LocalConfig.DEFAULT_LOCAL_URL
        }
        
        // URL do Pi-Star (direto, não via collector)
        val pistarUrl = if (isExternal) {
            LocalConfig.EXTERNAL_PISTAR_URL
        } else {
            LocalConfig.LOCAL_PISTAR_URL
        }
        
        rotationInterval = (prefs.getString("rotation_interval", "10")?.toLongOrNull() ?: 10) * 1000
        apiClient = ApiClient(serverUrl, pistarUrl, isExternal)
        
        // Carregar telas habilitadas
        enabledScreens.clear()
        allScreens.forEachIndexed { index, screen ->
            if (prefs.getBoolean(screenKeys[index], true)) {
                enabledScreens.add(screen)
            }
        }
        if (enabledScreens.isEmpty()) {
            enabledScreens.add("Sistema") // Pelo menos uma tela
        }
    }
    
    private fun showConnecting() {
        binding.statusMessage.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.connecting)
        binding.contentContainer.removeAllViews()
    }
    
    private fun showError(message: String) {
        binding.statusMessage.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = message
    }
    
    private fun hideStatus() {
        binding.statusMessage.visibility = View.GONE
    }
    
    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.clock.text = sdf.format(Date())
    }
    
    private fun fetchData() {
        val client = apiClient ?: return
        
        lifecycleScope.launch {
            client.fetchStats().onSuccess { stats ->
                serverStats = stats
                hideStatus()
                updateCurrentScreen()
            }.onFailure { e ->
                if (serverStats == null) {
                    showError("${getString(R.string.error_connection)}\n${e.message}\n\nToque na engrenagem para configurar")
                }
            }
            
            client.fetchPiStar().onSuccess { data ->
                android.util.Log.d("PP5RD", "fetchPiStar OK: cur=${data.cur?.call}")
                piStarData = data
                // Guarda último dados válidos
                if (data.cur != null) {
                    lastValidCur = data.cur
                }
                if (data.last.isNotEmpty()) {
                    lastValidList = data.last
                }
                if (data.today > 0) {
                    lastValidToday = data.today
                    lastValidCountries = data.countries
                }
                if (enabledScreens.getOrNull(currentScreenIndex) == "Pi-Star") updateCurrentScreen()
            }.onFailure { e ->
                android.util.Log.e("PP5RD", "fetchPiStar ERRO: ${e.message}")
            }
        }
    }
    
    private fun showScreen(index: Int) {
        currentScreenIndex = index
        val screenName = enabledScreens.getOrNull(index) ?: return
        binding.screenTitle.text = screenName
        updateDots()
        updateCurrentScreen()
    }
    
    private fun updateCurrentScreen() {
        val screenName = enabledScreens.getOrNull(currentScreenIndex) ?: return
        
        binding.contentContainer.removeAllViews()
        
        // Pi-Star tem dados próprios, não depende de serverStats
        if (screenName == "Pi-Star") {
            showPiStar()
            return
        }
        
        val stats = serverStats ?: return
        
        when (screenName) {
            "Sistema" -> showSistema(stats)
            "TETRA" -> showTetra(stats)
            "Docker" -> showDocker(stats)
            "Rede" -> showRede(stats)
            "Leitura" -> showLeitura(stats)
        }
    }
    
    private fun showSistema(stats: ServerStats) {
        val view = layoutInflater.inflate(R.layout.screen_sistema, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        view.findViewById<TextView>(R.id.cpuPercent).text = "${stats.cpu}%"
        view.findViewById<TextView>(R.id.cpuLoad).text = "load %.1f/%d".format(stats.load1, stats.cores)
        setBarWidth(view.findViewById(R.id.cpuBar), stats.cpu, getBarColor(stats.cpu))
        
        view.findViewById<TextView>(R.id.memPercent).text = "${stats.memPct}%"
        view.findViewById<TextView>(R.id.memUsage).text = "%.1f / %.1f GB".format(stats.memUsed, stats.memTotal)
        setBarWidth(view.findViewById(R.id.memBar), stats.memPct, getBarColor(stats.memPct))
        
        view.findViewById<TextView>(R.id.diskPercent).text = "${stats.diskPct}%"
        view.findViewById<TextView>(R.id.diskUsage).text = "${stats.diskUsed} / ${stats.diskTotal} GB"
        setBarWidth(view.findViewById(R.id.diskBar), stats.diskPct, getBarColor(stats.diskPct))
        
        view.findViewById<TextView>(R.id.temperature).text = "${stats.temp}°C"
        view.findViewById<TextView>(R.id.temperature).setTextColor(getBarColor(stats.temp))
        
        // Uptime - calcular dias a partir das horas totais
        val totalHours = stats.upHours
        val days = totalHours / 24
        val hours = totalHours % 24
        val minutes = stats.upMinutes
        
        val uptime = buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }
        view.findViewById<TextView>(R.id.uptime).text = uptime
    }
    
    private fun showTetra(stats: ServerStats) {
        val view = layoutInflater.inflate(R.layout.screen_tetra, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        val tetra = stats.tetra
        val freqs = LocalConfig.TETRA_FREQS
        // t1-t4 = decodificando, a1-a4 = áudio ativo, l1-l4 = segundos desde última chamada
        val decoding = listOf(tetra?.t1 ?: 0, tetra?.t2 ?: 0, tetra?.t3 ?: 0, tetra?.t4 ?: 0)
        val audioActive = listOf(tetra?.a1 ?: 0, tetra?.a2 ?: 0, tetra?.a3 ?: 0, tetra?.a4 ?: 0)
        val lastCall = listOf(tetra?.l1 ?: 9999, tetra?.l2 ?: 9999, tetra?.l3 ?: 9999, tetra?.l4 ?: 9999)
        
        // Status SDR
        val sdrActive = tetra?.sdr == 1
        view.findViewById<View>(R.id.tetraSdrLed)?.setBackgroundColor(
            if (sdrActive) getColor(R.color.accent_green) else getColor(R.color.accent_red)
        )
        view.findViewById<TextView>(R.id.tetraSdrStatus)?.text = if (sdrActive) "SDR Ativo" else "SDR Inativo"
        view.findViewById<TextView>(R.id.tetraListeners)?.text = "${tetra?.listeners ?: 0}"
        
        // Troncos
        for (i in 0..3) {
            val ledId = resources.getIdentifier("trunk${i+1}Led", "id", packageName)
            val freqId = resources.getIdentifier("trunk${i+1}Freq", "id", packageName)
            val voiceId = resources.getIdentifier("trunk${i+1}Voice", "id", packageName)
            
            // LED: amarelo = áudio agora, verde = decodificando, cinza = parado
            val hasAudio = audioActive[i] == 1
            val isDecoding = decoding[i] == 1
            
            view.findViewById<View>(ledId)?.setBackgroundColor(
                when {
                    hasAudio -> getColor(R.color.accent_yellow)
                    isDecoding -> getColor(R.color.accent_green)
                    else -> getColor(R.color.text_secondary)
                }
            )
            view.findViewById<TextView>(freqId)?.text = "${freqs[i]} MHz"
            
            // Tempo desde última chamada
            val age = lastCall[i]
            val ageText = when {
                hasAudio -> "● ATIVO"
                age >= 9999 -> "—"
                age < 60 -> "há ${age}s"
                age < 3600 -> "há ${age / 60}m"
                else -> "há ${age / 3600}h"
            }
            view.findViewById<TextView>(voiceId)?.apply {
                text = ageText
                setTextColor(
                    when {
                        hasAudio -> getColor(R.color.accent_yellow)
                        age < 300 -> getColor(R.color.accent_green)
                        else -> getColor(R.color.text_secondary)
                    }
                )
            }
        }
        
        // Setup audio player controls
        setupTetraPlayer(view)
    }
    
    private fun setupTetraPlayer(view: View) {
        val btnTrunk1 = view.findViewById<Button>(R.id.btnTrunk1)
        val btnTrunk2 = view.findViewById<Button>(R.id.btnTrunk2)
        val btnTrunk3 = view.findViewById<Button>(R.id.btnTrunk3)
        val btnTrunk4 = view.findViewById<Button>(R.id.btnTrunk4)
        val btnPlay = view.findViewById<Button>(R.id.btnPlayStop)
        val status = view.findViewById<TextView>(R.id.playerStatus)
        
        val trunkButtons = listOf(btnTrunk1, btnTrunk2, btnTrunk3, btnTrunk4)
        
        // Atualizar visual dos botões de tronco
        fun updateTrunkButtons() {
            trunkButtons.forEachIndexed { index, btn ->
                val selected = (index + 1) == selectedTrunk
                btn?.setBackgroundColor(
                    if (selected) getColor(R.color.accent_orange) else getColor(R.color.card_border)
                )
                btn?.setTextColor(
                    if (selected) getColor(R.color.background) else getColor(R.color.text_secondary)
                )
            }
        }
        
        // Atualizar botão play e status
        fun updatePlayState() {
            if (isPlaying) {
                btnPlay?.text = "⏸  Pause"
                btnPlay?.setBackgroundColor(getColor(R.color.accent_orange))
                status?.text = "Tocando T$selectedTrunk"
            } else {
                btnPlay?.text = "▶  Play"
                btnPlay?.setBackgroundColor(getColor(R.color.accent_green))
                status?.text = if (selectedTrunk == 0) "Escolha um tronco" else "T$selectedTrunk selecionado"
            }
        }
        
        // Selecionar tronco
        fun selectTrunk(trunk: Int) {
            val wasPlaying = isPlaying
            if (trunk != selectedTrunk) {
                stopTetraAudio()
                selectedTrunk = trunk
                updateTrunkButtons()
                if (wasPlaying) {
                    startTetraAudio()
                }
            }
            updatePlayState()
        }
        
        // Restaurar estado visual
        updateTrunkButtons()
        updatePlayState()
        
        // Listeners dos botões de tronco
        btnTrunk1?.setOnClickListener { selectTrunk(1) }
        btnTrunk2?.setOnClickListener { selectTrunk(2) }
        btnTrunk3?.setOnClickListener { selectTrunk(3) }
        btnTrunk4?.setOnClickListener { selectTrunk(4) }
        
        // Play/Pause
        btnPlay?.setOnClickListener {
            if (selectedTrunk == 0) {
                status?.text = "Escolha um tronco primeiro"
                return@setOnClickListener
            }
            if (isPlaying) {
                stopTetraAudio()
            } else {
                startTetraAudio()
            }
            updatePlayState()
        }
        
        // Volume SeekBar
        val volumeBar = view.findViewById<SeekBar>(R.id.volumeBar)
        volumeBar?.progress = (tetraVolume * 100).toInt()
        volumeBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tetraVolume = progress / 100f
                tetraPlayer?.volume = tetraVolume
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun updatePlayButton(btn: Button?, status: TextView?) {
        // Não usado mais, mas mantido para evitar erro de compilação
    }
    
    private fun startTetraAudio() {
        if (selectedTrunk == 0) return
        
        stopTetraAudio()
        
        // Igual ao telive-app: ExoPlayer com Basic Auth
        val url = LocalConfig.TETRA_STREAM_PREFIX + selectedTrunk + LocalConfig.TETRA_STREAM_SUFFIX
        val user = ServiceToken.TETRA_USER
        val pass = ServiceToken.TETRA_PASS
        val cred = "$user:$pass"
        val authHeader = "Basic " + Base64.encodeToString(cred.toByteArray(), Base64.NO_WRAP)
        
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
        tetraPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                volume = tetraVolume
                prepare()
                play()
            }
        
        isPlaying = true
    }
    
    private fun stopTetraAudio() {
        tetraPlayer?.release()
        tetraPlayer = null
        isPlaying = false
    }
    
    private fun showPiStar() {
        val view = layoutInflater.inflate(R.layout.screen_pistar, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        val data = piStarData
        // Usa cur atual ou último válido
        val cur = data?.cur ?: lastValidCur
        
        if (cur == null) {
            view.findViewById<TextView>(R.id.pistarCall).text = "—"
            view.findViewById<TextView>(R.id.pistarName).text = "Nenhuma estação ouvida"
            return
        }
        
        val isActive = data?.cur?.active == true
        
        view.findViewById<TextView>(R.id.pistarCall).apply {
            text = cur.call
            setTextColor(if (isActive) getColor(R.color.accent_yellow) else getColor(R.color.text_primary))
        }
        view.findViewById<TextView>(R.id.pistarName).text = cur.name
        view.findViewById<TextView>(R.id.pistarCountry).text = cur.country
        view.findViewById<TextView>(R.id.pistarTg).text = cur.tg
        view.findViewById<TextView>(R.id.pistarMode).text = "${cur.mode} ${cur.slot}"
        
        // Calcular duração: se dur vazio, calcula a partir do start
        val duration = if (cur.dur.isNotBlank()) {
            cur.dur
        } else if (cur.start > 0) {
            val seconds = (System.currentTimeMillis() / 1000) - cur.start
            if (seconds >= 0) "${seconds}+" else ""
        } else {
            ""
        }
        view.findViewById<TextView>(R.id.pistarDuration).text = if (duration.isNotBlank()) "TX ${duration} s" else ""
        view.findViewById<TextView>(R.id.pistarBer).text = if (cur.ber.isNotBlank()) "BER ${cur.ber}" else "BER"
        
        view.findViewById<TextView>(R.id.pistarStatus).apply {
            visibility = if (cur.active) View.VISIBLE else View.GONE
            text = "NO AR"
        }
        
        view.findViewById<TextView>(R.id.pistarToday).text = "Hoje: ${data?.today?.takeIf { it > 0 } ?: lastValidToday} QSOs • ${data?.countries?.takeIf { it > 0 } ?: lastValidCountries} países"
        
        // Carregar bandeira
        val flagView = view.findViewById<ImageView>(R.id.pistarFlag)
        loadFlag(cur.cc, flagView)
        
        val lastContainer = view.findViewById<LinearLayout>(R.id.pistarLastContainer)
        lastContainer.gravity = android.view.Gravity.CENTER
        val lastList = data?.last?.takeIf { it.isNotEmpty() } ?: lastValidList
        lastList.take(5).forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 5, 0, 5)
                
                // Bandeira grande
                val flag = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(66, 44).apply {
                        marginEnd = 16
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(flag)
                loadFlag(entry.cc, flag)
                
                // Indicativo
                addView(TextView(context).apply {
                    text = entry.call
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 22f
                })
            }
            lastContainer.addView(row)
        }
    }
    
    private fun loadFlag(cc: String, imageView: ImageView) {
        if (cc.isBlank() || cc.length != 2) return
        
        // Verificar cache
        if (flagCache.containsKey(cc)) {
            flagCache[cc]?.let { imageView.setImageBitmap(it) }
            return
        }
        
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val url = URL("https://flagcdn.com/w80/${cc.lowercase()}.png")
                    BitmapFactory.decodeStream(url.openStream())
                }
                flagCache[cc] = bitmap
                imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                flagCache[cc] = null
            }
        }
    }
    
    private fun showDocker(stats: ServerStats) {
        val view = layoutInflater.inflate(R.layout.screen_docker, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        val cont = stats.cont
        val contStats = stats.contStats ?: emptyMap()
        
        // Resumo no topo
        val upCount = cont?.up ?: 0
        val totalCount = cont?.total ?: 0
        val memMb = stats.dockerMem
        view.findViewById<TextView>(R.id.dockerSummary).text = "$upCount/$totalCount containers • $memMb MB RAM"
        
        // Top CPU
        val topName = stats.topcpu.ifBlank { "—" }
        val topPct = if (stats.topcpuPct > 0) "%.0f%%".format(stats.topcpuPct) else ""
        view.findViewById<TextView>(R.id.dockerTopCpu).text = "$topName $topPct"
        
        // LEDs Docker
        val dockerLedGrid = view.findViewById<LinearLayout>(R.id.dockerLedGrid)
        contStats.forEach { _ ->
            dockerLedGrid.addView(createLed(true))
        }
        val stoppedCount = (totalCount - contStats.size).coerceAtLeast(0)
        repeat(stoppedCount) {
            dockerLedGrid.addView(createLed(false))
        }
        
        // Containers em 2 colunas
        val col1 = view.findViewById<LinearLayout>(R.id.dockerCol1)
        val col2 = view.findViewById<LinearLayout>(R.id.dockerCol2)
        
        // Containers rodando
        val containerList = contStats.toList().toMutableList()
        
        // Adicionar containers parados conhecidos
        val knownContainers = listOf("telive", "tetra-auth", "tetra-icecast", "tetra-logger-guardian", 
            "lab-temp", "calibre-web", "koreader-sync", "openwebrx")
        val runningNames = contStats.keys
        knownContainers.forEach { name ->
            if (name !in runningNames) {
                containerList.add(name to com.pp5rd.status.ContainerUsage(0, 0))
            }
        }
        
        containerList.forEachIndexed { index, (name, usage) ->
            val isRunning = name in runningNames
            val row = createContainerRowCompact(name, isRunning, usage.cpu, usage.mem)
            if (index % 2 == 0) col1.addView(row) else col2.addView(row)
        }
        
        // LEDs Sistema
        val svcLedGrid = view.findViewById<LinearLayout>(R.id.svcLedGrid)
        stats.svc?.forEach { (_, status) ->
            svcLedGrid.addView(createLed(status == 1))
        }
        
        // Lista serviços compacta
        val servicesList = view.findViewById<LinearLayout>(R.id.dockerServiceList)
        stats.svc?.forEach { (name, status) ->
            servicesList.addView(createServiceRowCompact(name, status == 1))
        }
    }
    
    private fun createLed(isUp: Boolean): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(10, 10).apply {
                marginEnd = 3
            }
            setBackgroundColor(if (isUp) getColor(R.color.accent_green) else getColor(R.color.accent_red))
        }
    }
    
    private fun createContainerRowCompact(name: String, isRunning: Boolean, cpu: Int, mem: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
            
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(8, 8)
                setBackgroundColor(if (isRunning) getColor(R.color.accent_green) else getColor(R.color.accent_red))
            })
            
            addView(TextView(context).apply {
                text = name
                setTextColor(if (isRunning) getColor(R.color.text_primary) else getColor(R.color.text_secondary))
                textSize = 13f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 8
                }
            })
            
            // Mostrar RAM se rodando
            addView(TextView(context).apply {
                text = if (isRunning) "${mem}M" else "off"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            })
        }
    }
    
    private fun createServiceRowCompact(name: String, isUp: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
            
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(8, 8)
                setBackgroundColor(if (isUp) getColor(R.color.accent_green) else getColor(R.color.accent_red))
            })
            
            addView(TextView(context).apply {
                text = name
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
            })
        }
    }
    
    private fun showRede(stats: ServerStats) {
        val view = layoutInflater.inflate(R.layout.screen_rede, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        // Tráfego atual
        view.findViewById<TextView>(R.id.netRxNow).text = "${stats.netRx} KB/s"
        view.findViewById<TextView>(R.id.netTxNow).text = "${stats.netTx} KB/s"
        
        // WiFi
        val wifi = stats.wifi
        view.findViewById<TextView>(R.id.wifiSsid).text = wifi?.ssid ?: "—"
        view.findViewById<TextView>(R.id.wifiSignal).apply {
            val signal = wifi?.signal ?: 0
            text = "$signal dBm"
            setTextColor(when {
                signal >= -50 -> getColor(R.color.accent_green)
                signal >= -70 -> getColor(R.color.accent_yellow)
                else -> getColor(R.color.accent_red)
            })
        }
        // Canal e frequência
        val chan = wifi?.chan ?: 0
        val freq = if (chan in 1..14) 2407 + (chan * 5) else 0
        view.findViewById<TextView>(R.id.wifiChan).text = "Ch $chan ($freq MHz)"
        view.findViewById<TextView>(R.id.wifiSpeed).text = "${wifi?.speed ?: 0} Mb/s"
        
        // Cloudflare Tunnels
        val tunnels = stats.tunnels ?: emptyList()
        
        // casa-pp5rd
        val casaTunnel = tunnels.find { it.name == "casa-pp5rd" }
        val casaUp = casaTunnel?.status == "healthy"
        view.findViewById<View>(R.id.tunnelCasaLed).setBackgroundColor(
            if (casaUp) getColor(R.color.accent_green) else getColor(R.color.accent_red)
        )
        view.findViewById<TextView>(R.id.tunnelCasaUptime).text = formatUptime(casaTunnel?.uptime ?: 0)
        
        // Rotas em 2 colunas (URLs completas)
        val casaRoutes = casaTunnel?.routes ?: emptyList()
        val half = (casaRoutes.size + 1) / 2
        view.findViewById<TextView>(R.id.tunnelCasaRoutesCol1).text = casaRoutes.take(half).joinToString("\n")
        view.findViewById<TextView>(R.id.tunnelCasaRoutesCol2).text = casaRoutes.drop(half).joinToString("\n")
        
        // pistar-rpi
        val pistarTunnel = tunnels.find { it.name == "pistar-rpi" }
        val pistarUp = pistarTunnel?.status == "healthy"
        view.findViewById<View>(R.id.tunnelPistarLed).setBackgroundColor(
            if (pistarUp) getColor(R.color.accent_green) else getColor(R.color.accent_red)
        )
        view.findViewById<TextView>(R.id.tunnelPistarUptime).text = formatUptime(pistarTunnel?.uptime ?: 0)
        val pistarRoutes = pistarTunnel?.routes?.joinToString("\n") ?: ""
        view.findViewById<TextView>(R.id.tunnelPistarRoutes).text = pistarRoutes
    }
    
    private fun formatUptime(sec: Int): String {
        return when {
            sec >= 86400 -> "${sec / 86400}d ${(sec % 86400) / 3600}h"
            sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            sec >= 60 -> "${sec / 60}m"
            sec > 0 -> "${sec}s"
            else -> "—"
        }
    }
    
    private fun showLeitura(stats: ServerStats) {
        val view = layoutInflater.inflate(R.layout.screen_leitura, binding.contentContainer, false)
        binding.contentContainer.addView(view)
        
        val reading = stats.reading
        
        if (reading == null || reading.title.isBlank()) {
            view.findViewById<TextView>(R.id.bookTitle).text = "Nenhum livro"
            view.findViewById<TextView>(R.id.bookAuthor).text = "—"
            view.findViewById<TextView>(R.id.bookProgress).text = "—"
            view.findViewById<TextView>(R.id.bookDevice).text = ""
        } else {
            view.findViewById<TextView>(R.id.bookTitle).text = reading.title
            view.findViewById<TextView>(R.id.bookAuthor).text = reading.author
            view.findViewById<TextView>(R.id.bookProgress).text = "${reading.pct}%"
            view.findViewById<TextView>(R.id.bookDevice).text = reading.device
            
            // Barra de progresso
            val progressBar = view.findViewById<View>(R.id.bookProgressBar)
            setBarWidth(progressBar, reading.pct, getColor(R.color.accent_orange))
            
            // Carregar capa do livro
            if (reading.cover.isNotBlank()) {
                val coverView = view.findViewById<ImageView>(R.id.bookCover)
                loadBookCover(reading.cover, coverView)
            }
            
            // Livros lidos recentemente
            val recentContainer = view.findViewById<LinearLayout>(R.id.recentBooksContainer)
            reading.recent.forEach { book ->
                val row = TextView(this).apply {
                    text = "• ${book.title}"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 2, 0, 2)
                }
                recentContainer.addView(row)
            }
        }
        
        view.findViewById<TextView>(R.id.bookCount).text = "${stats.ebooks} ebooks"
    }
    
    private fun loadBookCover(coverPath: String, imageView: ImageView) {
        val client = apiClient ?: return
        // usa o mesmo servidor do /stats (local ou externo com token)
        val coverUrl = "${client.hashCode()}/$coverPath"
        
        // Se já tem a mesma capa em cache, usa direto
        if (coverUrl == lastCoverUrl && coverBitmap != null) {
            imageView.setImageBitmap(coverBitmap)
            return
        }
        
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    client.fetchBytes(coverPath)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } ?: return@launch
                lastCoverUrl = coverUrl
                coverBitmap = bitmap
                imageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                // Falhou ao carregar capa, manter placeholder
            }
        }
    }
    
    private fun setBarWidth(bar: View, percent: Int, color: Int) {
        bar.setBackgroundColor(color)
        bar.post {
            val parent = bar.parent as View
            val targetWidth = (parent.width * percent / 100f).toInt().coerceAtLeast(1)
            bar.layoutParams = bar.layoutParams.apply { width = targetWidth }
            bar.requestLayout()
        }
    }
    
    private fun getBarColor(value: Int): Int {
        return when {
            value >= 90 -> getColor(R.color.accent_red)
            value >= 70 -> getColor(R.color.accent_yellow)
            else -> getColor(R.color.accent_green)
        }
    }
}
