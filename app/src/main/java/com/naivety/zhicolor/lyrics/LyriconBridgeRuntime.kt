package com.naivety.zhicolor.lyrics

import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.naivety.zhicolor.bridge.ZhiBridgeRuntime
import io.github.proify.lyricon.central.BridgeCentral
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.*
import java.util.concurrent.atomic.AtomicBoolean

internal object LyriconBridgeRuntime : ZhiBridgeRuntime.Input {
    private val started=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private data class SourceSnapshot(val player:String?=null,val song:Song?=null,val encoded:String?=null)
    @Volatile private var source=SourceSnapshot()
    private data class SessionSnapshot(val player:String?=null,val title:String?=null,val artist:String?=null,val trackKey:String="")
    @Volatile private var session=SessionSnapshot()
    private data class TimedAvailability(val source:SourceSnapshot?=null,val session:SessionSnapshot?=null,val available:Boolean=false)
    @Volatile private var timedAvailability=TimedAvailability()
    private var subscriber: LyriconSubscriber?=null
    private var mode: String?=null
    private var embeddedInitialized=false
    private var embeddedRegistered=false
    private var embeddedReceiver: BroadcastReceiver?=null
    private var host:Context?=null
    @Volatile private var controllers:List<MediaController> = emptyList()
    private val controllerCallbacks=mutableMapOf<MediaSession.Token,Pair<MediaController,MediaController.Callback>>()
    private var activeListener:MediaSessionManager.OnActiveSessionsChangedListener?=null
    private val textWindow=LyricTextWindow()

    fun start(context: Context) {
        if(!started.compareAndSet(false,true))return
        val app=context.applicationContext
        ZhiBridgeRuntime.setInput(this)
        ZhiBridgeRuntime.attach(app)
        host=app
        Log.i("ZHIColorDelivery", "lyric runtime started in SystemUI")
        val manager=app.getSystemService(MediaSessionManager::class.java)
        activeListener=MediaSessionManager.OnActiveSessionsChangedListener { bindControllers(it.orEmpty()) }.also {
            runCatching { manager.addOnActiveSessionsChangedListener(it,null,handler);bindControllers(manager.getActiveSessions(null)) }
                .onFailure {Log.w("ZHIColorLyrics","media session access failed",it)}
        }
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){onSettingsChanged()}}
        ContextCompat.registerReceiver(app,receiver,IntentFilter().apply {addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED);addDataScheme("package")},ContextCompat.RECEIVER_EXPORTED)
        onSettingsChanged()
    }
    override fun onSettingsChanged() {
        handler.post {
            val app=host ?: return@post
            runCatching {
                val next=when(LyricSourceMode.choose(ZhiBridgeRuntime.enabled(),hasOfficial(app))){LyricSourceMode.OFF->"off";LyricSourceMode.OFFICIAL->"official";LyricSourceMode.DIRECT_PROVIDER->"direct"}
                if(next!=mode)changeMode(app,next)
                selectCurrent()
            }.onFailure {Log.w("ZHIColorLyrics","source switch failed",it)}
        }
    }
    private fun bindControllers(next:List<MediaController>) {
        controllerCallbacks.values.forEach {runCatching{it.first.unregisterCallback(it.second)}};controllerCallbacks.clear()
        controllers=next
        next.forEach {controller->
            val callback=object:MediaController.Callback(){
                override fun onPlaybackStateChanged(state:PlaybackState?){selectCurrent()}
                override fun onMetadataChanged(metadata:MediaMetadata?){selectCurrent()}
                override fun onSessionDestroyed(){controllers=controllers.filterNot{it.sessionToken==controller.sessionToken};selectCurrent()}
            }
            controller.registerCallback(callback,handler);controllerCallbacks[controller.sessionToken]=controller to callback
        }
        selectCurrent()
    }
    private fun selectCurrent() {
        val active=controllers.firstOrNull {it.playbackState?.state==PlaybackState.STATE_PLAYING}
            ?: controllers.firstOrNull {it.packageName==source.player} ?: controllers.firstOrNull()
        val metadata=active?.metadata
        val next=if(active!=null && metadata!=null)SessionSnapshot(
            active.packageName,
            metadataText(metadata,MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadataText(metadata,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM_ARTIST,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            sessionIdentity(metadata,active.packageName)) else SessionSnapshot(player=active?.packageName)
        val trackChanged=next.trackKey!=session.trackKey
        session=next
        refreshTimedAvailability()
        textWindow.retainOnlyTrack(next.trackKey)
        ZhiBridgeRuntime.setCurrentPackage(active?.packageName)
        if(trackChanged)ZhiBridgeRuntime.lyricsChanged()
    }

    private fun hasOfficial(context: Context)=listOf("io.github.proify.lyricon","io.github.proify.lyricon.core").any { runCatching {context.packageManager.getPackageInfo(it,0)}.isSuccess }
    private fun changeMode(context: Context,next: String) {
        subscriber?.let { runCatching {it.unregister();it.destroy()} };subscriber=null
        textWindow.clear();replaceSource(SourceSnapshot())
        if(next!="direct" && embeddedRegistered) {
            embeddedReceiver?.let { runCatching {context.unregisterReceiver(it)} };embeddedRegistered=false
        }

        mode=next
        Log.i("ZHIColorDelivery", "source mode=$next")
        if(next=="off") {ZhiBridgeRuntime.lyricsChanged();return}
        // The central keys subscribers by package/process. Keep this module separate from
        // any other SystemUI subscriber while delegating IPC to the real host Context.
        val subscriberContext=object:ContextWrapper(context) {
            override fun getPackageName()=com.naivety.zhicolor.BuildConfig.APPLICATION_ID
            override fun getApplicationContext():Context=this
        }
        val sub=LyriconFactory.createSubscriber(subscriberContext)
        fun current(action:()->Unit) {
            handler.post {if(subscriber===sub)action()}
        }
        fun reconnectLater() {
            handler.postDelayed({if(subscriber===sub && mode!="off")sub.register()},5000)
        }
        sub.addConnectionListener(object:ConnectionListener {
            override fun onConnected(subscriber:LyriconSubscriber) {
                current {Log.i("ZHIColorDelivery","lyric source connected");ZhiBridgeRuntime.lyricsChanged()}
            }
            override fun onReconnected(subscriber:LyriconSubscriber)=onConnected(subscriber)
            override fun onDisconnected(subscriber:LyriconSubscriber) {
                current {
                    textWindow.clear();replaceSource(SourceSnapshot())
                    Log.i("ZHIColorDelivery","lyric source disconnected")
                    selectCurrent();ZhiBridgeRuntime.lyricsChanged();reconnectLater()
                }
            }
            override fun onConnectTimeout(subscriber:LyriconSubscriber) {
                current {
                    Log.w("ZHIColorDelivery","lyric source connection timeout mode=$mode")
                    if(mode=="direct" && !embeddedRegistered) {
                        runCatching {enableEmbedded(context);sub.register()}
                            .onFailure {Log.w("ZHIColorLyrics","provider central start failed",it);reconnectLater()}
                    } else reconnectLater()
                }
            }
        })
        sub.subscribeActivePlayer(object:ActivePlayerListener {
            override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
                current {
                    providerInfo?.let {info->runCatching {context.contentResolver.call(android.net.Uri.parse("content://"+com.naivety.zhicolor.bridge.BridgeSettings.CONFIG_AUTHORITY),"bind",null,android.os.Bundle().apply{putString("player",info.playerPackageName);putString("provider",info.providerPackageName)})}
                        .onFailure {Log.w("ZHIColorDelivery","provider binding failed",it)}}
                    textWindow.clear();replaceSource(SourceSnapshot(player=providerInfo?.playerPackageName))
                    Log.i("ZHIColorDelivery","active lyric player="+source.player.orEmpty())
                    selectCurrent();ZhiBridgeRuntime.lyricsChanged()
                }
            }
            override fun onSongChanged(song: Song?) {
                current {
                    val previous=source
                    val encoded=song?.let {LyriconPayloadEncoder.encode(it,previous.player.orEmpty())}
                    textWindow.clear();replaceSource(previous.copy(song=song,encoded=encoded))
                    Log.i("ZHIColorDelivery","song received timed="+!encoded.isNullOrBlank())
                    ZhiBridgeRuntime.lyricsChanged()
                }
            }
            override fun onPlaybackStateChanged(isPlaying: Boolean)=Unit
            // The rendering clock is the native MediaSession clock, never the central's clock.
            override fun onPositionChanged(position: Long)=Unit
            override fun onSeekTo(position: Long)=Unit
            override fun onReceiveText(text: String?) {
                current {
                    val snapshot=source
                    if(snapshot.song?.lyrics.isNullOrEmpty()) {
                        val player=snapshot.player.orEmpty()
                        val current=session
                        if(player==current.player)textWindow.accept(text,current.trackKey)
                        ZhiBridgeRuntime.lyricsChanged()
                    }
                }
            }
            override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean)=Unit
            override fun onDisplayRomaChanged(isDisplayRoma: Boolean)=Unit
        })
        subscriber=sub;sub.register()
    }
    private fun enableEmbedded(context:Context) {

            if(!embeddedInitialized) {
                BridgeCentral.initialize(context)
                embeddedInitialized=true;embeddedRegistered=true
                embeddedReceiver=runCatching {BridgeCentral::class.java.getDeclaredField("receiver").apply {isAccessible=true}.get(null) as BroadcastReceiver}.getOrNull()
            } else if(!embeddedRegistered) {
                val receiver=embeddedReceiver ?: return
                ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
                    addAction("io.github.proify.lyricon.lyric.bridge.REGISTER_PROVIDER")
                    addAction("io.github.proify.lyricon.lyric.bridge.REGISTER_SUBSCRIBER")
                },ContextCompat.RECEIVER_EXPORTED)
                embeddedRegistered=true
            }
            BridgeCentral.sendBootCompleted()

    }
    override fun hasPayload(packageName: String): Boolean {
        val snapshot=source
        if(packageName!=snapshot.player)return false
        // View hooks call this gate for every frame. Session IPC happens only in callback-driven
        // selectCurrent(); this read never calls MediaController or encodes another payload.
        val current=session
        if(packageName!=current.player || current.trackKey.isEmpty())return false
        return if(!snapshot.song?.lyrics.isNullOrEmpty()) {
            val availability=timedAvailability
            availability.source===snapshot && availability.session===current && availability.available
        } else textWindow.available(current.trackKey)
    }
    override fun payload(metadata: MediaMetadata,packageName: String): String? {
        val snapshot=source
        if(packageName!=snapshot.player)return null
        val title=metadataText(metadata,MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist=metadataText(metadata,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM_ARTIST,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val current=snapshot.song
        if(current!=null && !current.lyrics.isNullOrEmpty()) return snapshot.encoded.takeIf {
            LyricSourceIdentityPolicy.timedPayloadAvailable(current,it,title,artist)
        }
        return textWindow.encode(title,artist,sessionIdentity(metadata,packageName))
    }
    private fun replaceSource(next:SourceSnapshot) {
        source=next
        refreshTimedAvailability()
    }
    private fun refreshTimedAvailability() {
        val snapshot=source
        val current=session
        timedAvailability=TimedAvailability(snapshot,current,
            snapshot.player==current.player && current.trackKey.isNotEmpty() &&
                LyricSourceIdentityPolicy.timedPayloadAvailable(snapshot.song,snapshot.encoded,current.title,current.artist))
    }
    private fun metadataText(metadata:MediaMetadata,vararg keys:String)=
        keys.firstNotNullOfOrNull {key->metadata.getText(key)?.toString()?.takeIf(String::isNotBlank)}
    private fun sessionIdentity(metadata:MediaMetadata,packageName:String)=LyricSourceIdentityPolicy.trackKey(
        packageName,
        metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadataText(metadata,MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        metadataText(metadata,MediaMetadata.METADATA_KEY_ARTIST,MediaMetadata.METADATA_KEY_ALBUM_ARTIST,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
}
