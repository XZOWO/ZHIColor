package com.naivety.zhicolor.home

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class HomePlayback(
    val title: String? = null,
    val artist: String? = null,
    val playerPackage: String? = null,
    val playing: Boolean = false,
    val cover: ByteArray? = null,
)

internal object HomeRepository {
    private val playbackState = MutableStateFlow(HomePlayback())
    val playback = playbackState.asStateFlow()
    private val connectedState = MutableStateFlow(false)
    val lyricConnected = connectedState.asStateFlow()
    private var subscriber: LyriconSubscriber? = null
    private var callback: PendingIntent? = null
    private var scope: CoroutineScope? = null
    @Volatile private var generation = 0L
    @Volatile private var nonce: String? = null
    @Volatile private var lyricSong: Song? = null
    @Volatile private var providerPackage: String? = null

    @Synchronized fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val epoch = ++generation
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        callback = PendingIntent.getBroadcast(app, 71,
            Intent(app, MediaSnapshotReceiver::class.java).setAction(MediaContract.RESPONSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        fun current() = generation == epoch && scope != null
        val listener = object : ActivePlayerListener {
            override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
                if (!current()) return
                providerInfo?.let {com.naivety.zhicolor.bridge.BridgeSettings.recordProvider(app,it.playerPackageName,it.providerPackageName)}
                val pkg = providerInfo?.playerPackageName
                if (pkg != providerPackage) {
                    providerPackage = pkg
                    lyricSong = null
                    playbackState.value = HomePlayback(playerPackage = pkg)
                }
            }
            override fun onSongChanged(song: Song?) {
                if (!current()) return
                val previous = lyricSong
                val changed = previous?.id != song?.id || previous?.name != song?.name || previous?.artist != song?.artist
                lyricSong = song
                val old = playbackState.value
                playbackState.value = old.copy(title = song?.name ?: old.title, artist = song?.artist ?: old.artist,
                    playerPackage = providerPackage ?: old.playerPackage, cover = if (changed) null else old.cover)
            }
            override fun onPlaybackStateChanged(isPlaying: Boolean) {
                if (current()) playbackState.value = playbackState.value.copy(playing = isPlaying)
            }
            override fun onReceiveText(text: String?) = Unit
            override fun onPositionChanged(position: Long) = Unit
            override fun onSeekTo(position: Long) = Unit
            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) = Unit
            override fun onDisplayRomaChanged(isDisplayRoma: Boolean) = Unit
        }
        runCatching {
            val sub = LyriconFactory.createSubscriber(app)
            sub.addConnectionListener(object : ConnectionListener {
                override fun onConnected(subscriber: LyriconSubscriber) { if (current()) connectedState.value = true }
                override fun onReconnected(subscriber: LyriconSubscriber) { if (current()) connectedState.value = true }
                override fun onDisconnected(subscriber: LyriconSubscriber) { if (current()) connectedState.value = false }
                override fun onConnectTimeout(subscriber: LyriconSubscriber) { if (current()) connectedState.value = false }
            })
            sub.subscribeActivePlayer(listener)
            subscriber = sub
            sub.register()
        }.onFailure { Log.w("ZHIColorHome", "Lyricon subscribe failed", it); connectedState.value = false }
        activeScope.launch {
            while (isActive) {
                val id = UUID.randomUUID().toString()
                nonce = id
                app.sendBroadcast(Intent(MediaContract.REQUEST).setPackage(MediaContract.SYSTEM_UI)
                    .putExtra(MediaContract.CALLBACK, callback)
                    .putExtra(MediaContract.NONCE, id)
                    .putExtra(MediaContract.PREFERRED_PACKAGE, providerPackage))
                delay(2000)
            }
        }
    }

    @Synchronized fun stop() {
        generation++
        scope?.cancel(); scope = null
        runCatching { subscriber?.unregister(); subscriber?.destroy() }; subscriber = null
        callback?.cancel(); callback = null
        nonce = null; connectedState.value = false
    }

    fun acceptSnapshot(intent: Intent) {
        if (scope == null || intent.getStringExtra(MediaContract.NONCE) != nonce) return
        if (!intent.getBooleanExtra(MediaContract.AVAILABLE, false)) {
            if (lyricSong == null) playbackState.value = HomePlayback()
            else playbackState.value = playbackState.value.copy(cover = null)
            return
        }
        val pkg = intent.getStringExtra(MediaContract.PACKAGE)
        val song = lyricSong.takeIf { providerPackage == pkg }
        val bytes = intent.getByteArrayExtra(MediaContract.COVER)?.takeIf { it.size <= MediaContract.MAX_COVER_BYTES }
        val old = playbackState.value
        val cover = if (bytes != null && old.cover != null && bytes.contentEquals(old.cover)) old.cover else bytes
        playbackState.value = HomePlayback(
            title = song?.name ?: intent.getStringExtra(MediaContract.TITLE),
            artist = song?.artist ?: intent.getStringExtra(MediaContract.ARTIST),
            playerPackage = pkg,
            playing = intent.getBooleanExtra(MediaContract.PLAYING, false),
            cover = cover,
        )
    }
}
