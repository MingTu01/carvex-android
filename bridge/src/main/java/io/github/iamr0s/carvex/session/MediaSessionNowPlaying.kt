package io.github.iamr0s.carvex.session

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.iamr0s.carvex.carplay.core.NowPlayingUpdate
import kotlinx.coroutines.*

/**
 * 手机侧的「正在播放」数据源。
 *
 * 为什么需要它：车机上的歌名/歌手走的是 iAP2 5001（NowPlaying），而这条链路一直是空的——
 * 实车日志里 `Host music` 一条都没有，也就是 CarWith 从来没调过它的 `sendMusicInfo`。
 * 既然我们本来就跑在手机上，就直接问系统"现在谁在放、放的是什么"，不指望宿主转发。
 *
 * 代价：`MediaSessionManager.getActiveSessions` 需要**通知使用权**
 * （[android.service.notification.NotificationListenerService] 被启用）。没有授权时
 * 这里只记一条日志，绝不影响会话——画面和触摸不依赖它。
 *
 * 只发文本：iAP2 的封面（artwork）是另一条消息，仓库里还没有实现。
 */
class MediaSessionNowPlaying(
    private val context:Context,
    private val log:(String)->Unit,
    private val onTrack:(NowPlayingUpdate.Track)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    /** 上一次发出的快照指纹：内容没变就不重复发，免得每秒一条 RTSP 请求。 */
    @Volatile private var lastSent:String?=null
    @Volatile private var permissionReported=false
    private var job:Job?=null

    fun start(){
        if(job!=null)return
        job=scope.launch{
            while(isActive){
                runCatching{poll()}.onFailure{
                    // 每会话只报一次：权限缺失是稳定状态，重复刷只会淹没别的日志。
                    if(!permissionReported){
                        permissionReported=true
                        log("now playing source unavailable: "+it.javaClass.simpleName+" "+(it.message?:"")+
                            "；若要车机显示歌名/歌手，请在系统设置里给本应用开启「通知使用权」")
                    }
                }
                delay(POLL_MS)
            }
        }
        log("now playing source started from the phone's media sessions; poll="+POLL_MS+"ms")
    }

    fun stop(){
        job?.cancel();job=null
        scope.cancel()
    }

    private fun poll(){
        val manager=context.getSystemService(MediaSessionManager::class.java) ?: return
        // null = "所有活动会话"。非 null 的 ComponentName 形式要求调用方就是那个监听器，
        // 而我们是同一个进程里被启用的那个监听器，null 同样成立且不必知道类名。
        val sessions=try{manager.getActiveSessions(null)}catch(t:SecurityException){throw t}
        val controller=sessions.firstOrNull{it.metadata!=null&&it.playbackState!=null} ?: return
        val metadata=controller.metadata ?: return
        val title=metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.trim()?.takeIf{it.isNotEmpty()}
            ?: return
        val artist=metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()?.trim()?.takeIf{it.isNotEmpty()}
        val album=metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()?.trim()?.takeIf{it.isNotEmpty()}
        val duration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf{it>0}
        val state=controller.playbackState
        val status=when(state?.state){
            PlaybackState.STATE_PLAYING->NowPlayingUpdate.PlaybackStatus.PLAYING
            PlaybackState.STATE_PAUSED->NowPlayingUpdate.PlaybackStatus.PAUSED
            PlaybackState.STATE_STOPPED,PlaybackState.STATE_NONE->NowPlayingUpdate.PlaybackStatus.STOPPED
            PlaybackState.STATE_FAST_FORWARDING->NowPlayingUpdate.PlaybackStatus.SEEK_FORWARD
            PlaybackState.STATE_REWINDING->NowPlayingUpdate.PlaybackStatus.SEEK_BACKWARD
            else->null
        }
        // playbackState.position 是**快照**，放着不动时不会自己长。差一个补偿，
        // 否则车机上的进度条永远停在开始那一刻。
        var elapsed:Long?=state?.position?.takeIf{it>=0}
        if(elapsed!=null&&state?.state==PlaybackState.STATE_PLAYING&&state.lastPositionUpdateTime>0){
            val drift=((android.os.SystemClock.elapsedRealtime()-state.lastPositionUpdateTime)*state.playbackSpeed).toLong()
            elapsed=(requireNotNull(elapsed)+drift).coerceAtLeast(0L)
        }
        if(elapsed!=null&&duration!=null)elapsed=elapsed.coerceAtMost(duration)
        val track=NowPlayingUpdate.Track(title=title,artist=artist,album=album,
            durationMs=duration,elapsedMs=elapsed,status=status,appName=controller.packageName)
        // 指纹里进度按 4 秒粒度：曲目不变时最多每 4 秒发一次进度。
        val fingerprint=listOf(title,artist,album,duration,status,controller.packageName).joinToString("|")+
            "|"+((elapsed?:0L)/PROGRESS_STEP_MS)
        if(fingerprint==lastSent)return
        lastSent=fingerprint
        log("now playing from "+controller.packageName+" title="+title+
            (artist?.let{" artist="+it}?:"")+(album?.let{" album="+it}?:"")+
            " status="+status+" elapsedMs="+elapsed)
        onTrack(track)
    }

    private companion object {
        const val POLL_MS=1000L
        const val PROGRESS_STEP_MS=4000L
    }
}
