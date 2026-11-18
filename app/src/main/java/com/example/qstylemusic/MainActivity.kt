package com.example.qstylemusic
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.qstylemusic.ui.components.MiniPlayer
import com.example.qstylemusic.ui.components.MusicBottomBar
import com.example.qstylemusic.ui.components.MusicSideRail
import com.example.qstylemusic.ui.components.MusicTab
import com.example.qstylemusic.ui.screens.HomeScreen
import com.example.qstylemusic.ui.screens.LocalScreen
import com.example.qstylemusic.ui.screens.MineHomeScreen
import com.example.qstylemusic.ui.screens.QStyleSettingsScreen
import com.example.qstylemusic.ui.screens.LocalScanSettingsScreen
import com.example.qstylemusic.ui.screens.MplugQrLoginDialog
import com.example.qstylemusic.ui.screens.NeteaseProfileDialog
import com.example.qstylemusic.ui.screens.QqMusicLoginScreen
import com.example.qstylemusic.plugin.auth.QqMusicSession
import com.example.qstylemusic.plugin.auth.NeteaseQrTransport
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountProfile
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.ui.screens.NeteasePlaylistDetailScreen
import com.example.qstylemusic.ui.screens.QqMusicPlaylistDetailScreen
import com.example.qstylemusic.ui.screens.LocalPlaylistDetailScreen
import com.example.qstylemusic.ui.screens.PlayerScreen
import com.example.qstylemusic.ui.screens.SearchScreen
import com.example.qstylemusic.ui.theme.MusicTheme
import com.example.qstylemusic.viewmodel.MusicViewModel
import com.example.qstylemusic.playback.OnlineFavorite
import com.example.qstylemusic.playback.OnlineFavoritesStore
import com.example.qstylemusic.playback.OnlineFavoriteResolver
import com.example.qstylemusic.playback.QqPlaylistNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val musicViewModel: MusicViewModel = viewModel(); MusicApp(musicViewModel) }
    }
}

@Composable
private fun MusicApp(vm: MusicViewModel) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val playQueue by vm.playQueue.collectAsStateWithLifecycle()
    val onlineLyrics by vm.onlineLyrics.collectAsStateWithLifecycle()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val recentIds by vm.recentIds.collectAsStateWithLifecycle()
    val darkTheme by vm.darkTheme.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val neteaseClient = remember(context) { NeteaseAccountClient(context.applicationContext) }
    val qqMusicSession = remember(context) { QqMusicSession(context.applicationContext) }
    val onlineFavoriteStore = remember(context) { OnlineFavoritesStore(context.applicationContext) }
    val onlineFavoriteResolver = remember(context) { OnlineFavoriteResolver(context.applicationContext) }
    val qqQueue = remember(context) { QqPlaylistNavigator(context.applicationContext) }
    val onlineScope = rememberCoroutineScope()
    var onlineFavorites by remember { mutableStateOf(onlineFavoriteStore.load()) }
    val uiPrefs = remember(context) { context.applicationContext.getSharedPreferences("qstyle_ui", android.content.Context.MODE_PRIVATE) }
    var segmentedPlaylistsEnabled by rememberSaveable { mutableStateOf(uiPrefs.getBoolean("segmented_playlists_enabled", false)) }
    LaunchedEffect(error) {
        if (error?.startsWith("网易云播放失败") == true) {
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) vm.refreshSongs()
    }
    LaunchedEffect(granted) { if (granted) vm.refreshSongs() }

    var selected by rememberSaveable { mutableStateOf(MusicTab.HOME) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    // A standalone cloud-playlist route, outside the My tab header and bottom tabs.
    var openedNeteasePlaylist by remember { mutableStateOf<NeteaseAccountPlaylist?>(null) }
    var openedQqPlaylist by remember { mutableStateOf<QqMusicAccountClient.Playlist?>(null) }
    var openedLocalPlaylist by remember { mutableStateOf<String?>(null) }
    var mineOpenLibrary by rememberSaveable { mutableStateOf(false) }
    var mineLibrarySource by rememberSaveable { mutableStateOf("netease") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showScanSettings by rememberSaveable { mutableStateOf(false) }
    var settingsInitialTab by rememberSaveable { mutableStateOf("plugins") }
    var showQrLogin by remember { mutableStateOf(false) }
    var showQqLogin by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var accountRefreshKey by remember { mutableIntStateOf(0) }
    var mineProfile by remember { mutableStateOf<NeteaseAccountProfile?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    val neteaseLoggedIn = remember(accountRefreshKey) { neteaseClient.hasLocalSession }
    val qqLoggedIn = remember(accountRefreshKey) { qqMusicSession.hasLocalSession }
    fun openSettings(tab: String) {
        settingsInitialTab = tab
        showSettings = true
    }
    fun setSegmentedPlaylistsEnabled(enabled: Boolean) {
        segmentedPlaylistsEnabled = enabled
        uiPrefs.edit().putBoolean("segmented_playlists_enabled", enabled).apply()
    }
    // QQ currently uses Media3's single item for authorized playback. We keep a lightweight
    // playlist of song IDs and resolve the next/previous track only when the user taps it.
    fun nextTrack() {
        if (!qqQueue.owns(playback.currentMediaId)) {
            vm.next()
            return
        }
        onlineScope.launch {
            val message = qqQueue.move(
                direction = 1,
                currentMediaId = playback.currentMediaId,
                wrap = playback.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL
            ) { track -> vm.playOnlineTrack(track) }
            if (message != null) android.widget.Toast.makeText(
                context, message, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    fun previousTrack() {
        if (!qqQueue.owns(playback.currentMediaId)) {
            vm.previous()
            return
        }
        // For online QQ playlists, a previous-button press always selects the previous track.
        onlineScope.launch {
            val message = qqQueue.move(
                direction = -1,
                currentMediaId = playback.currentMediaId,
                wrap = playback.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL
            ) { track -> vm.playOnlineTrack(track) }
            if (message != null) android.widget.Toast.makeText(
                context, message, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    fun toggleCurrentFavorite() {
        val mediaId = playback.currentMediaId.orEmpty()
        val isOnline = mediaId.startsWith("qqmusic:") ||
            mediaId.startsWith("netease:") ||
            (playback.hasSong && playback.currentSongId == null)
        if (isOnline && mediaId.isNotBlank()) {
            try {
                onlineFavorites = onlineFavoriteStore.toggle(
                    OnlineFavorite(
                        mediaId = mediaId,
                        title = playback.title,
                        artist = playback.artist,
                        artworkUrl = playback.artworkUri?.toString()
                    )
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(context,
                    "保存收藏失败：${e.localizedMessage.orEmpty()}",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            playback.currentSongId?.let(vm::toggleFavorite)
        }
    }
    fun playOnlineFavorite(favorite: OnlineFavorite) {
        onlineScope.launch {
            try {
                val track = onlineFavoriteResolver.resolve(favorite)
                qqQueue.clear()
                vm.playOnlineTrack(track)
                playerExpanded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context,
                    "播放收藏失败：${e.localizedMessage.orEmpty().take(180)}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(selected, accountRefreshKey) {
        if (selected == MusicTab.MINE && neteaseClient.hasLocalSession) {
            profileLoading = true
            profileError = null
            try {
                mineProfile = neteaseClient.profile()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) {
                mineProfile = null
                profileError = "读取个人信息失败：${e.localizedMessage.orEmpty()}"
            } finally { profileLoading = false }
        } else {
            mineProfile = null
            profileError = null
            profileLoading = false
        }
    }
    BackHandler(enabled = showSettings && !showScanSettings && !showQqLogin && !playerExpanded) {
        showSettings = false
    }
    BackHandler(enabled = showScanSettings && !playerExpanded) {
        showScanSettings = false
        if (granted) vm.refreshSongs()
    }
    BackHandler(enabled = showQqLogin && !playerExpanded) { showQqLogin = false }
    BackHandler(enabled = playerExpanded) { playerExpanded = false }
    BackHandler(enabled = openedNeteasePlaylist != null && !playerExpanded && !showSettings) {
        openedNeteasePlaylist = null
    }
    BackHandler(enabled = openedQqPlaylist != null && !playerExpanded && !showSettings) {
        openedQqPlaylist = null
    }
    BackHandler(enabled = openedLocalPlaylist != null && !playerExpanded && !showSettings) {
        openedLocalPlaylist = null
    }


// ================================

// 判断是否处于全屏播放界面
    val isFullPlayer = playerExpanded && playback.hasSong
    // 歌单详情同样是独立的全屏页面：背景绘制到状态栏和导航栏之后。
    // 状态栏保持可见，参照网易云歌单详情的效果。
    val isFullPlaylist = (openedNeteasePlaylist != null || openedQqPlaylist != null || openedLocalPlaylist != null) && !playerExpanded && !showSettings
    val edgeToEdgeScreen = isFullPlayer || isFullPlaylist

    DisposableEffect(isFullPlayer) {

        val activity = context as? ComponentActivity

        val controller = activity?.let {
            WindowCompat.getInsetsController(
                it.window,
                it.window.decorView
            )
        }

        if (isFullPlayer) {

            // 隐藏顶部状态栏
            // 包括时间、电量、网络、通知等
            controller?.hide(
                WindowInsetsCompat.Type.statusBars()
            )

            // 允许从顶部下滑临时查看状态栏
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        } else {

            // 退出播放界面，恢复状态栏
            controller?.show(
                WindowInsetsCompat.Type.statusBars()
            )
        }

        onDispose {
            if (isFullPlayer) {
                controller?.show(
                    WindowInsetsCompat.Type.statusBars()
                )
            }
        }
    }

// ================================
// 原来的界面代码继续保留
// ================================
    // Keep light/dark status and navigation bar icons consistent across all tabs.
    // Playlist details use a dark cover header, while normal tabs use the active theme.
    LaunchedEffect(darkTheme, isFullPlaylist, showSettings, showQqLogin) {
        val activity = context as? ComponentActivity
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = !darkTheme && !isFullPlaylist
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MusicTheme(darkTheme) {
        // Paint the ENTIRE window with the same color as the first three tabs.
        // Only the interactive content is inset, never the background.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = when {
                edgeToEdgeScreen -> androidx.compose.ui.graphics.Color.Transparent
                showSettings || showQqLogin -> if (darkTheme)
                    androidx.compose.ui.graphics.Color(0xFF1F1B18)
                else androidx.compose.ui.graphics.Color(0xFFFFF9F1)
                else -> MaterialTheme.colorScheme.background
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (edgeToEdgeScreen) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                )
            ) {
                if (isFullPlayer) {
                PlayerScreen(
                    playback = playback,
                    playQueue = playQueue,
                    isFavorite = if (playback.currentMediaId?.let { id ->
                            id.startsWith("qqmusic:") || id.startsWith("netease:") ||
                                playback.currentSongId == null
                        } == true) {
                        onlineFavorites.any { it.mediaId == playback.currentMediaId }
                    } else playback.currentSongId in favorites,
                    onBack = { playerExpanded = false },
                    onToggle = vm::togglePlay,
                    onNext = { nextTrack() },
                    onPrevious = { previousTrack() },
                    onSeek = vm::seekTo,
                    onToggleFavorite = { toggleCurrentFavorite() },
                    onCyclePlayMode = vm::cyclePlayMode,
                    onChooseQueueItem = vm::playQueueItem,
                    onlineLyrics = if (playback.currentSongId == null) onlineLyrics else null
                )
            } else if (showScanSettings) {
                LocalScanSettingsScreen(
                    onBack = {
                        showScanSettings = false
                        if (granted) vm.refreshSongs()
                        else permissionLauncher.launch(permission)
                    }
                )
            } else if (showSettings) {
                Column(Modifier.fillMaxSize()) {
                    QStyleSettingsScreen(
                        modifier = Modifier.weight(1f),
                        initialTab = settingsInitialTab,
                        darkTheme = darkTheme,
                        onDarkChange = vm::setDarkTheme,
                        neteaseLoggedIn = neteaseLoggedIn,
                        qqLoggedIn = qqLoggedIn,
                        neteaseName = mineProfile?.nickname,
                        neteaseAvatar = mineProfile?.avatarUrl,
                        qqAccountName = remember(accountRefreshKey) { com.example.qstylemusic.plugin.auth.QqMusicAccountClient(context.applicationContext).profile()?.displayName },
                        qqAccountAvatar = remember(accountRefreshKey) { com.example.qstylemusic.plugin.auth.QqMusicAccountClient(context.applicationContext).profile()?.avatarUrl },
                        segmentedPlaylistsEnabled = segmentedPlaylistsEnabled,
                        onSegmentedPlaylistsChange = ::setSegmentedPlaylistsEnabled,
                        onBack = { showSettings = false },
                        onNeteaseLogin = { showQrLogin = true },
                        onQqLogin = { showQqLogin = true },
                        onNeteaseProfile = { showProfileDialog = true },
                        onNeteaseLogout = {
                            NeteaseQrTransport(context.applicationContext).logoutLocal()
                            accountRefreshKey++
                        },
                        onQqLogout = {
                            qqMusicSession.clear()
                            accountRefreshKey++
                        },
                        onOpenStorage = { showScanSettings = true },
                        onPlayPluginTrack = { track ->
                            qqQueue.clear()
                            vm.playOnlineTrack(track)
                            playerExpanded = true
                        }
                    )
                    if (playback.hasSong) {
                        MiniPlayer(playback,
                            onOpen = { playerExpanded = true },
                            onToggle = vm::togglePlay,
                            onNext = { nextTrack() })
                    }
                    MusicBottomBar(selected) { tab ->
                        selected = tab
                        showSettings = false
                    }
                }
            } else if (openedQqPlaylist != null) {
                Box(Modifier.fillMaxSize()) {
                    QqMusicPlaylistDetailScreen(
                        playlist = requireNotNull(openedQqPlaylist),
                        onBack = { openedQqPlaylist = null },
                        onPlayTrack = { track ->
                            vm.playOnlineTrack(track)
                            playerExpanded = true
                        },
                        onQueueReady = { orderedTracks, index ->
                            qqQueue.start(orderedTracks, index)
                        },
                        showMiniPlayer = playback.hasSong
                    )
                    if (playback.hasSong) {
                        Box(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth().navigationBarsPadding().padding(bottom = 5.dp)
                        ) {
                            MiniPlayer(playback, onOpen = { playerExpanded = true },
                                onToggle = vm::togglePlay, onNext = { nextTrack() })
                        }
                    }
                }
            } else if (openedLocalPlaylist != null) {
                Box(Modifier.fillMaxSize()) {
                    LocalPlaylistDetailScreen(
                        kind = requireNotNull(openedLocalPlaylist),
                        allSongs = songs,
                        favorites = favorites,
                        recentIds = recentIds,
                        onBack = { openedLocalPlaylist = null },
                        onPlaySong = { song, queue ->
                            qqQueue.clear()
                            vm.playSong(song, queue)
                            playerExpanded = true
                        },
                        showMiniPlayer = playback.hasSong,
                        onlineFavorites = onlineFavorites,
                        onPlayOnlineFavorite = { favorite -> playOnlineFavorite(favorite) }
                    )
                    if (playback.hasSong) {
                        Box(
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth().navigationBarsPadding().padding(bottom = 5.dp)
                        ) {
                            MiniPlayer(playback, onOpen = { playerExpanded = true },
                                onToggle = vm::togglePlay, onNext = { nextTrack() })
                        }
                    }
                }
            } else if (openedNeteasePlaylist != null) {
                // 歌单详情真正占满整个屏幕，封面背景延伸到状态栏后方。
                // 底部迷你播放器浮在内容上层，而不是压缩歌单的可用高度。
                Box(Modifier.fillMaxSize()) {
                    NeteasePlaylistDetailScreen(
                        playlist = requireNotNull(openedNeteasePlaylist),
                        onBack = { openedNeteasePlaylist = null },
                        onPlayPlaylist = { detail, index ->
                            vm.playNeteasePlaylist(detail.songs, index)
                            playerExpanded = true
                        },
                        showMiniPlayer = playback.hasSong,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (playback.hasSong) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 5.dp)
                        ) {
                            MiniPlayer(
                                playback,
                                onOpen = { playerExpanded = true },
                                onToggle = vm::togglePlay,
                                onNext = { nextTrack() }
                            )
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    Row(Modifier.fillMaxSize()) {
                        if (wide) MusicSideRail(selected) { selected = it }
                        Column(Modifier.weight(1f)) {
                            if (selected != MusicTab.MINE) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 15.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(selected.headerTitle,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold)
                                    IconButton(onClick = {
                                        if (granted) vm.refreshSongs()
                                        else permissionLauncher.launch(permission)
                                    }) { Icon(Icons.Rounded.Refresh, "重新扫描本地音乐") }
                                }
                            }
                            if (error != null) {
                                Text(error.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Crossfade(targetState = selected, modifier = Modifier.weight(1f), label = "页面切换") { screen ->
                                when (screen) {
                                    MusicTab.HOME -> HomeScreen(
                                        songs, recentIds, favorites, granted,
                                        { permissionLauncher.launch(permission) },
                                        { selected = MusicTab.LOCAL },
                                        { selected = MusicTab.MINE },
                                        { song ->
                                            vm.playSong(song)
                                            playerExpanded = true
                                        }, vm::toggleFavorite,
                                        onPlayNetease = { tracks, index ->
                                            vm.playNeteasePlaylist(tracks, index)
                                            playerExpanded = true
                                        },
                                        onOpenNeteaseLogin = { openSettings("login") },
                                        accountRefreshKey = accountRefreshKey
                                    )
                                    MusicTab.SEARCH -> SearchScreen(
                                        songs, favorites,
                                        { song -> vm.playSong(song); playerExpanded = true },
                                        vm::toggleFavorite,
                                        onPlayNetease = { tracks, index ->
                                            vm.playNeteasePlaylist(tracks, index)
                                            playerExpanded = true
                                        },
                                        onOpenNeteaseLogin = { openSettings("login") },
                                        accountRefreshKey = accountRefreshKey
                                    )
                                    MusicTab.LOCAL -> LocalScreen(songs, favorites, granted,
                                        { permissionLauncher.launch(permission) },
                                        { vm.playSong(it) }, vm::toggleFavorite)
                                    MusicTab.MINE -> MineHomeScreen(
                                        songs = songs,
                                        favorites = favorites,
                                        recentIds = recentIds,
                                        darkTheme = darkTheme,
                                        onDarkChange = vm::setDarkTheme,
                                        neteaseLoggedIn = neteaseLoggedIn,
                                        qqLoggedIn = qqLoggedIn,
                                        neteaseName = mineProfile?.nickname,
                                        neteaseAvatar = mineProfile?.avatarUrl,
                                        showLibrary = mineOpenLibrary,
                                        librarySource = mineLibrarySource,
                                        onShowLibraryChange = { mineOpenLibrary = it },
                                        onLibrarySourceChange = { mineLibrarySource = it },
                                        onExit = { selected = MusicTab.HOME },
                                        onOpenSettings = ::openSettings,
                                        onRefresh = {
                                            accountRefreshKey++
                                            if (granted) vm.refreshSongs()
                                            else permissionLauncher.launch(permission)
                                        },
                                        onPlay = { song ->
                                            vm.playSong(song)
                                            playerExpanded = true
                                        },
                                        onFavorite = vm::toggleFavorite,
                                        onPlayNetease = { detail, index ->
                                            vm.playNeteasePlaylist(detail.songs, index)
                                            playerExpanded = true
                                        },
                                        onOpenNeteasePlaylist = { playlist ->
                                            openedQqPlaylist = null
                                            openedLocalPlaylist = null
                                            openedNeteasePlaylist = playlist
                                        },
                                        onOpenQqPlaylist = { playlist ->
                                            openedNeteasePlaylist = null
                                            openedLocalPlaylist = null
                                            openedQqPlaylist = playlist
                                        },
                                        onOpenLocalPlaylist = { kind ->
                                            openedNeteasePlaylist = null
                                            openedQqPlaylist = null
                                            openedLocalPlaylist = kind
                                        },
                                        onPlayQqTrack = { track ->
                                            qqQueue.clear()
                                            vm.playOnlineTrack(track)
                                            playerExpanded = true
                                        },
                                        accountRefreshKey = accountRefreshKey,
                                        segmentedPlaylistsEnabled = segmentedPlaylistsEnabled,
                                        onlineFavorites = onlineFavorites
                                    )
                                }
                            }
                            if (playback.hasSong) {
                                MiniPlayer(playback,
                                    onOpen = { playerExpanded = true },
                                    onToggle = vm::togglePlay,
                                    onNext = { nextTrack() })
                            }
                            if (!wide) MusicBottomBar(selected) { selected = it }
                        }
                    }
                }
            }
        }
        }
        if (showQqLogin) {
            // Full-width modal challenge: no Windows, WebView spoofing or PC bridge.
            Dialog(
                onDismissRequest = { showQqLogin = false },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (darkTheme) androidx.compose.ui.graphics.Color(0xFF1F1B18)
                        else androidx.compose.ui.graphics.Color(0xFFFFF9F1)
                ) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        QqMusicLoginScreen(
                            onBack = { showQqLogin = false },
                            onSessionSaved = {
                                showQqLogin = false
                                accountRefreshKey++
                            },
                            autoStart = true
                        )
                    }
                }
            }
        }
        if (showQrLogin) {
            // Keep the working NetEase QR module unchanged.
            MplugQrLoginDialog(
                pluginId = "netease.source",
                onDismiss = {
                    showQrLogin = false
                    accountRefreshKey++ // Reload My, Home and Search after login.
                }
            )
        }
        if (showProfileDialog) {
            NeteaseProfileDialog(
                profile = mineProfile,
                loading = profileLoading,
                error = profileError,
                onRefresh = { accountRefreshKey++ },
                onDismiss = { showProfileDialog = false }
            )
        }
    }
}

private val MusicTab.headerTitle: String get() = when (this) {
    MusicTab.HOME -> "推荐"
    MusicTab.SEARCH -> "搜索"
    MusicTab.LOCAL -> "本地音乐"
    MusicTab.MINE -> "我的"
}

