package com.german.dronnk.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import android.content.ClipData
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.german.dronnk.R
import com.german.dronnk.BuildConfig
import com.german.dronnk.data.LibraryRepository
import com.german.dronnk.download.DownloadRepository
import com.german.dronnk.model.Song
import com.german.dronnk.network.ApiClient
import com.german.dronnk.player.PlayerManager
import com.german.dronnk.update.AppUpdateManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: SongAdapter
    private lateinit var loading: ProgressBar
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniCover: ImageView
    private lateinit var miniPlay: ImageButton
    private lateinit var songList: RecyclerView
    private lateinit var searchInput: AutoCompleteTextView
    private lateinit var searchBox: View
    private lateinit var genreScroll: View
    private lateinit var screenTitle: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var emptyPanel: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var emptyAction: TextView
    private lateinit var playlistListContainer: LinearLayout
    private val suggestionHandler = Handler(Looper.getMainLooper())
    private var suggestionRunnable: Runnable? = null
    private var suggestionSongs: List<Song> = emptyList()
    private var selectingSuggestion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()

        requestNotificationPermissionIfNeeded()
        bindViews()
        setupList()
        setupSearch()
        setupGenres()
        setupNavigation()
        setupMiniPlayer()
        showSearchHome()
    }

    private fun bindViews() {
        loading = findViewById(R.id.loading)
        miniPlayer = findViewById(R.id.miniPlayer)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniCover = findViewById(R.id.miniCover)
        miniPlay = findViewById(R.id.miniPlay)
        songList = findViewById(R.id.songList)
        searchInput = findViewById(R.id.searchInput)
        searchBox = findViewById(R.id.searchBox)
        genreScroll = findViewById(R.id.genreScroll)
        screenTitle = findViewById(R.id.screenTitle)
        sectionTitle = findViewById(R.id.sectionTitle)
        emptyPanel = findViewById(R.id.emptyPanel)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyText = findViewById(R.id.emptyText)
        emptyAction = findViewById(R.id.emptyAction)
        playlistListContainer = findViewById(R.id.playlistListContainer)
    }

    private fun setupList() {
        adapter = SongAdapter(
            onClick = ::downloadThenPlay,
            onOptions = ::showOptions,
            onFavorite = { song ->
                val active = LibraryRepository.toggleFavorite(this, song)
                Toast.makeText(this, if (active) "Añadida a favoritos" else "Quitada de favoritos", Toast.LENGTH_SHORT).show()
            },
            isFavorite = { LibraryRepository.isFavorite(this, it) }
        )
        songList.layoutManager = LinearLayoutManager(this)
        songList.adapter = adapter
    }

    private fun setupSearch() {
        val suggestionAdapter = ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )
        searchInput.setAdapter(suggestionAdapter)

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || searchInput.text.isNotBlank()) {
                performSearch(searchInput.text.toString())
                true
            } else false
        }

        searchInput.setOnItemClickListener { _, _, position, _ ->
            suggestionSongs.getOrNull(position)?.let { song ->
                selectingSuggestion = true
                searchInput.setText(song.titulo ?: "")
                searchInput.setSelection(searchInput.text.length)
                selectingSuggestion = false
                performSearch(song.titulo ?: "")
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (selectingSuggestion) return
                suggestionRunnable?.let(suggestionHandler::removeCallbacks)
                val query = s?.toString()?.trim().orEmpty()
                if (query.length < 2) {
                    suggestionSongs = emptyList()
                    suggestionAdapter.clear()
                    return
                }

                suggestionRunnable = Runnable {
                    lifecycleScope.launch {
                        runCatching { ApiClient.api.buscar(query) }
                            .onSuccess { response ->
                                if (searchInput.text.toString().trim() != query) return@onSuccess
                                suggestionSongs = response.canciones.take(7)
                                suggestionAdapter.clear()
                                suggestionAdapter.addAll(
                                    suggestionSongs.map {
                                        val artist = it.canal?.takeIf { a -> a.isNotBlank() }
                                        if (artist != null) "${it.titulo ?: "Canción"} — $artist"
                                        else it.titulo ?: "Canción"
                                    }
                                )
                                suggestionAdapter.notifyDataSetChanged()
                                if (suggestionSongs.isNotEmpty() && searchInput.hasFocus()) {
                                    searchInput.showDropDown()
                                }
                            }
                    }
                }.also { suggestionHandler.postDelayed(it, 350L) }
            }
        })

        findViewById<ImageButton>(R.id.clearSearch).setOnClickListener {
            searchInput.setText("")
            suggestionAdapter.clear()
            showSearchHome()
        }
    }

    private fun setupGenres() {
        mapOf(
            R.id.chipReggaeton to "reggaeton",
            R.id.chipTrap to "trap latino",
            R.id.chipPop to "pop",
            R.id.chipSalsa to "salsa",
            R.id.chipCumbia to "cumbia"
        ).forEach { (id, query) ->
            findViewById<View>(id).setOnClickListener {
                searchInput.setText(query)
                performSearch(query)
            }
        }
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.navSearch).setOnClickListener { showSearchHome() }
        findViewById<View>(R.id.navDownloads).setOnClickListener { showDownloads() }
        findViewById<View>(R.id.navFavorites).setOnClickListener { showFavorites() }
        findViewById<View>(R.id.navPlaylists).setOnClickListener { showPlaylists() }
        findViewById<View>(R.id.navSettings).setOnClickListener { showSettings() }
    }

    private fun setupMiniPlayer() {
        miniPlay.setOnClickListener {
            PlayerManager.toggle()
            refreshMiniPlayIcon()
        }
        miniPlayer.setOnClickListener {
            if (PlayerManager.currentSong != null) {
                startActivity(Intent(this, PlayerActivity::class.java))
            }
        }
    }

    private fun showSearchHome() {
        playlistListContainer.visibility = View.GONE
        screenTitle.text = "Dronnk"
        searchBox.visibility = View.VISIBLE
        genreScroll.visibility = View.VISIBLE
        emptyPanel.visibility = View.GONE
        songList.visibility = View.VISIBLE
        val history = LibraryRepository.history(this)
        if (history.isNotEmpty()) {
            sectionTitle.text = "Reproducidas recientemente"
            adapter.submit(history.take(12))
        } else {
            sectionTitle.text = "Busca lo que quieras escuchar"
            adapter.submit(emptyList())
            showEmpty(
                "Tu música empieza aquí",
                "Busca una canción o artista. Al tocarla, Dronnk la descargará y reproducirá desde tu teléfono.",
                null
            )
        }
    }

    private fun performSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        LibraryRepository.addSearch(this, clean)
        sectionTitle.text = "Resultados para “$clean”"
        emptyPanel.visibility = View.GONE
        songList.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = ApiClient.api.buscar(clean)
                adapter.submit(result.canciones)
                if (result.canciones.isEmpty()) {
                    showEmpty("Sin resultados", "Prueba con otro artista o nombre de canción.", null)
                }
            } catch (e: Exception) {
                showEmpty("No se pudo buscar", "Revisa tu conexión e inténtalo nuevamente.", "Reintentar") {
                    performSearch(clean)
                }
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun downloadThenPlay(song: Song) {
        // Si ya existe el video local, Dronnk lo prefiere sobre el MP3.
        DownloadRepository.preferredLocalMedia(this, song)?.let { local ->
            LibraryRepository.addHistory(this, local)
            PlayerManager.playLocal(this, local)
            showMiniPlayer(local)
            sectionTitle.text = if (local.mediaType == "video") {
                "Reproduciendo video desde el dispositivo"
            } else {
                "Reproduciendo desde el dispositivo"
            }
            return
        }

        loading.visibility = View.VISIBLE
        sectionTitle.text = "Preparando ${song.titulo ?: "canción"}…"
        lifecycleScope.launch {
            val result = DownloadRepository.ensureLocalMp3(this@MainActivity, song)
            loading.visibility = View.GONE
            result.onSuccess { local ->
                LibraryRepository.addHistory(this@MainActivity, local)
                PlayerManager.playLocal(this@MainActivity, local)
                showMiniPlayer(local)
                sectionTitle.text = "Reproduciendo desde el dispositivo"
            }.onFailure {
                Toast.makeText(this@MainActivity, "No se pudo descargar el MP3: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMiniPlayer(song: Song) {
        miniPlayer.visibility = View.VISIBLE
        miniTitle.text = song.titulo ?: "Canción"
        miniArtist.text = song.canal ?: "Dronnk"
        miniCover.load(song.thumbnail) {
            placeholder(R.drawable.dronnk_app_icon)
            error(R.drawable.dronnk_app_icon)
        }
        refreshMiniPlayIcon()
    }

    private fun refreshMiniPlayIcon() {
        miniPlay.setImageResource(if (PlayerManager.player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun showOptions(song: Song) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_song_options, null)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.optionsTitle).text = song.titulo ?: "Dronnk"
        view.findViewById<TextView>(R.id.optionsArtist).text = song.canal ?: ""

        val isFavorite = LibraryRepository.isFavorite(this, song)
        view.findViewById<TextView>(R.id.actionFavoriteText).text = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos"
        view.findViewById<ImageView>(R.id.actionFavoriteIcon).setImageResource(if (isFavorite) R.drawable.ic_heart_solid else R.drawable.ic_heart_outline)

        view.findViewById<View>(R.id.actionAudio).setOnClickListener {
            sheet.dismiss()
            downloadThenPlay(song)
        }
        view.findViewById<View>(R.id.actionVideo).setOnClickListener {
            sheet.dismiss()
            downloadVideo(song)
        }
        view.findViewById<View>(R.id.actionFavorite).setOnClickListener {
            LibraryRepository.toggleFavorite(this, song)
            adapter.notifyDataSetChanged()
            sheet.dismiss()
        }
        view.findViewById<View>(R.id.actionPlaylist).setOnClickListener {
            sheet.dismiss()
            showAddToPlaylist(song)
        }
        view.findViewById<View>(R.id.actionShare).setOnClickListener {
            sheet.dismiss()
            shareSong(song)
        }
        sheet.show()
    }

    private fun downloadVideo(song: Song) {
        loading.visibility = View.VISIBLE
        Toast.makeText(this, "Preparando video…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            DownloadRepository.downloadVideo(this@MainActivity, song)
                .onSuccess { uri ->
                    val localVideo = DownloadRepository.asDownloadedVideo(song, uri)
                    LibraryRepository.addHistory(this@MainActivity, localVideo)
                    PlayerManager.playLocal(this@MainActivity, localVideo)
                    showMiniPlayer(localVideo)
                    loading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Video guardado en Movies/Dronnk", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
                }
                .onFailure {
                    loading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "No se pudo descargar el video: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showDownloads() {
        playlistListContainer.visibility = View.GONE
        screenTitle.text = "Descargas"
        searchBox.visibility = View.GONE
        genreScroll.visibility = View.GONE
        sectionTitle.text = "Audio guardado en Music/Dronnk"
        val songs = DownloadRepository.downloadedAudio(this)
        if (songs.isEmpty()) {
            showEmpty("Aún no tienes descargas", "Las canciones que reproduzcas aparecerán aquí automáticamente.", null)
        } else {
            emptyPanel.visibility = View.GONE
            songList.visibility = View.VISIBLE
            adapter.submit(songs)
        }
    }

    private fun showFavorites() {
        playlistListContainer.visibility = View.GONE
        screenTitle.text = "Favoritos"
        searchBox.visibility = View.GONE
        genreScroll.visibility = View.GONE
        sectionTitle.text = "Tus canciones guardadas"
        val songs = LibraryRepository.favorites(this)
        if (songs.isEmpty()) {
            showEmpty("Sin favoritos", "Usa el icono de corazón para guardar canciones aquí.", null)
        } else {
            emptyPanel.visibility = View.GONE
            songList.visibility = View.VISIBLE
            adapter.submit(songs)
        }
    }

    private fun showPlaylists() {
        screenTitle.text = "Playlists"
        searchBox.visibility = View.GONE
        genreScroll.visibility = View.GONE
        sectionTitle.text = "Organiza tu música"

        val playlists = LibraryRepository.playlists(this)
        songList.visibility = View.GONE
        emptyPanel.visibility = View.VISIBLE
        emptyTitle.text = "Tus playlists"
        emptyText.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = "Crea tu primera playlist y añade canciones desde el menú de cada resultado."
        emptyAction.visibility = View.VISIBLE
        emptyAction.text = "Nueva playlist"
        emptyAction.setOnClickListener { createPlaylistDialog() }

        playlistListContainer.removeAllViews()
        playlistListContainer.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE

        playlists.forEach { playlist ->
            val row = TextView(this).apply {
                text = "${playlist.name}  ·  ${playlist.songs.size} canciones"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(22, 18, 22, 18)
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener { showPlaylist(playlist.id) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            playlistListContainer.addView(row, params)
        }
    }

    private fun showPlaylist(playlistId: String) {
        val playlist = LibraryRepository.playlist(this, playlistId) ?: return
        screenTitle.text = playlist.name
        searchBox.visibility = View.GONE
        genreScroll.visibility = View.GONE
        sectionTitle.text = "${playlist.songs.size} canciones"
        playlistListContainer.visibility = View.GONE

        if (playlist.songs.isEmpty()) {
            showEmpty(
                playlist.name,
                "Esta playlist todavía no tiene canciones.",
                null
            )
        } else {
            emptyPanel.visibility = View.GONE
            songList.visibility = View.VISIBLE
            adapter.submit(playlist.songs)
        }
    }

    private fun showSettings() {
        screenTitle.text = "Ajustes"
        searchBox.visibility = View.GONE
        genreScroll.visibility = View.GONE
        sectionTitle.text = "Configuración"
        playlistListContainer.visibility = View.GONE

        showEmpty(
            "Dronnk ${BuildConfig.VERSION_NAME}",
            "Audio: MP3 · 192 kbps\nCarpeta de audio: Music/Dronnk\nCarpeta de video: Movies/Dronnk\nReproducción: archivo local",
            "Buscar actualización"
        ) {
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            AppUpdateManager.checkLatest(this@MainActivity)
                .onSuccess { release ->
                    loading.visibility = View.GONE
                    if (release == null) {
                        Toast.makeText(this@MainActivity, "Dronnk ya está actualizado", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Nueva versión disponible")
                            .setMessage("Dronnk ${release.versionName} está disponible. ¿Descargar actualización?")
                            .setPositiveButton("Actualizar") { _, _ ->
                                AppUpdateManager.startDownload(this@MainActivity, release)
                            }
                            .setNegativeButton("Ahora no", null)
                            .show()
                    }
                }
                .onFailure {
                    loading.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "No se pudo comprobar la actualización",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun showEmpty(title: String, text: String, action: String?, onAction: (() -> Unit)? = null) {
        playlistListContainer.visibility = View.GONE
        songList.visibility = View.GONE
        emptyPanel.visibility = View.VISIBLE
        emptyTitle.text = title
        emptyText.text = text
        if (action != null) {
            emptyAction.visibility = View.VISIBLE
            emptyAction.text = action
            emptyAction.setOnClickListener { onAction?.invoke() }
        } else {
            emptyAction.visibility = View.GONE
            emptyAction.setOnClickListener(null)
        }
    }

    private fun createPlaylistDialog(afterCreate: ((String) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Nombre de la playlist"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(36, 18, 36, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva playlist")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val playlist = LibraryRepository.createPlaylist(this, name)
                    afterCreate?.invoke(playlist.id)
                    showPlaylists()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddToPlaylist(song: Song) {
        val playlists = LibraryRepository.playlists(this)
        val names = playlists.map { it.name }.toMutableList()
        names.add("Nueva playlist")
        AlertDialog.Builder(this)
            .setTitle("Añadir a playlist")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    createPlaylistDialog { id ->
                        LibraryRepository.addToPlaylist(this, id, song)
                        Toast.makeText(this, "Canción añadida", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    LibraryRepository.addToPlaylist(this, playlists[which].id, song)
                    Toast.makeText(this, "Añadida a ${playlists[which].name}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun shareSong(song: Song) {
        val local = song.localPath ?: song.url
        if (!local.isNullOrBlank() && (local.startsWith("content://") || local.startsWith("file://"))) {
            val uri = Uri.parse(local)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "${song.titulo ?: "Canción"} — ${song.canal ?: "Dronnk"}")
                clipData = ClipData.newRawUri("Dronnk audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Compartir canción"))
            return
        }

        val source = song.sourceUrl ?: song.url
        val text = buildString {
            append(song.titulo ?: "Canción")
            song.canal?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            source?.takeIf { it.startsWith("http") }?.let { append("\n").append(it) }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Compartir canción"))
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                bars.top,
                view.paddingRight,
                bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
        }
    }

    override fun onResume() {
        super.onResume()
        PlayerManager.currentSong?.let { showMiniPlayer(it) }
    }

    override fun onDestroy() {
        suggestionRunnable?.let(suggestionHandler::removeCallbacks)
        super.onDestroy()
    }
}
