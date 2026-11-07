package io.github.iamr0s.carvex
import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.session.LocalVideoContent
import io.github.iamr0s.carvex.session.LocalVideoContentFactory
import io.github.iamr0s.carvex.session.LocalVideoPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch

/** Private own-content display fed by Compose, matching the reference implementation:
 * no MediaProjection token, no overlay permission and no Activity, so it also works while the app
 * is in the background. The VirtualDisplay output is the encoder input surface itself. */
object LocalVideoDisplay:LocalVideoContentFactory {
    /** Diagnostics go to the session log stream: without them a refused virtual display looks
     * exactly like one that was created but never produces a frame. */
    internal fun note(message:String){
        val line="Local display: "+message
        BridgeStore.log(BridgeStore.state.value.id,line)
        // 同时打一份到 logcat。这些诊断只有连车时才用得上，而连车时手边唯一能看的就是
        // `adb logcat`；只走 BridgeStore 的那份要等会话结束才导得出，黑屏当场没法判断。
        // 量很小：每会话几条，心跳不走这里。
        android.util.Log.i("CarVexDisplay",line)
    }
    override suspend fun open(context:Context,surface:Surface,width:Int,height:Int,fps:Int):LocalVideoContent? =
        withContext(Dispatchers.Main) {
            if(width<=0||height<=0||!surface.isValid){
                note("refused width=$width height=$height surfaceValid=${surface.isValid}")
                return@withContext null
            }
            try{
                val application=context.applicationContext
                val density=(width*160/960).coerceIn(120,320)
                val flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                val display=requireNotNull(application.getSystemService(DisplayManager::class.java)
                    .createVirtualDisplay("CarWith & Carplay Bridge",width,height,density,surface,flags)){"createVirtualDisplay returned null"}
                val target=display.display
                note("virtual display created id=${target.displayId} state=${target.state} ${width}x$height density=$density")
                val window=BridgePresentation(application,target,application.getString(R.string.app_name))
                try{
                    window.show()
                    note("presentation shown showing=${window.isShowing} attached=${window.window?.decorView?.isAttachedToWindow}")
                    // Report again one frame later: attach finishing after show() returns is normal,
                    // so the value above may legitimately still be false.
                    window.window?.decorView?.post{
                        val decor=window.window?.decorView
                        note("presentation settled attached=${decor?.isAttachedToWindow} size=${decor?.width}x${decor?.height} visible=${decor?.isShown}")
                    }
                    if(android.os.Build.VERSION.SDK_INT>=30)surface.setFrameRate(fps.toFloat(),Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    LocalDisplay(display,window)
                }catch(t:Throwable){
                    note("presentation failed: "+t.javaClass.simpleName+" "+(t.message?:""))
                    runCatching{window.dismiss()};runCatching{display.release()};throw t
                }
            }catch(t:Throwable){
                // This used to be swallowed by runCatching{}.getOrNull(), which is why a failed
                // virtual display left no trace anywhere in the log.
                note("open failed: "+t.javaClass.simpleName+" "+(t.message?:""))
                null
            }
        }
}

private class LocalDisplay(private val display:VirtualDisplay,private val window:BridgePresentation):LocalVideoContent {
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    override fun show(phase:LocalVideoPhase){
        if(closed)return
        main.post{
            if(!closed){
                window.switchTo(phase)
                LocalVideoDisplay.note("phase switched to $phase")
            }
        }
    }
    override fun heartbeat(){
        if(closed)return
        main.post{
            // One composed frame is all the encoder needs: hwui hands a fresh buffer to the virtual
            // display for the invalidated window, and an unchanged picture codes as a skip P frame.
            // Posting keeps the caller - the encoder's timer - free of UI work.
            if(!closed)window.window?.decorView?.invalidate()
        }
    }
    /** 车机回传的触摸：派进这张画面的窗口，页面上的点按才真的发生。
     *
     *  解码在别的线程上，而 dispatchTouchEvent 必须在主线程：拷一份事件丢过去就好，不去同步等它——
     *  等主线程会把 HID 那条通道一起卡住，而它后面还排着别的报告。 */
    override fun touch(event:MotionEvent):Boolean {
        if(closed)return false
        val copy=MotionEvent.obtain(event)
        main.post{
            try{if(!closed)window.window?.decorView?.dispatchTouchEvent(copy)}
            finally{runCatching{copy.recycle()}}
        }
        return true
    }
    override fun close(){
        if(closed)return;closed=true
        val dismiss=CountDownLatch(1)
        main.post{
            try{
                window.dismiss()
                // 视频的解码器必须在宿主开始抓音频**之前**真正放手。
                //
                // 实车（1.0.76，19:12:36）：dismiss 只等窗口撤掉，而 MediaPlayer.release()
                // 挂在 Compose 的 onDispose 上，是**异步**执行的。于是出现了这样一段：
                //   36.576 NuPlayerDriver reset / decoder shutdown   ← 视频刚拆
                //   36.615 CarlifeConnService prepareCast            ← 39ms 后宿主接管
                //   36.699 CarlifeAudio: try to start music record
                //   36.700 AudioSource-java: audio channel close     ← 它抢不到输入，直接放弃
                // 之后 5 分钟宿主一帧 PCM 都没给过，车机自然全程静音，声音留在手机上。
                // 20060~20067 那几个没加启动视频的包都有正常音频包，加了视频才开始哑。
                //
                // 这里同步收掉，close() 返回即代表解码器已经释放完。
                SplashVideo.releaseActiveNow()
            }finally{
                runCatching{display.release()}
                // The presentation window must be detached before the host reuses this surface.
                dismiss.countDown()
            }
        }
        // A private display release is quick; the bound keeps a wedged main thread from stalling setup.
        runCatching{dismiss.await(8,java.util.concurrent.TimeUnit.SECONDS)}
    }
}

/** Compose host for the app-owned pages. Dismiss removes every window from the private display. */
private class BridgePresentation(context:Context,display:Display,private val brand:String):Presentation(context,display),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry=LifecycleRegistry(this)
    private val saved=SavedStateRegistryController.create(this)
    private val phase=mutableStateOf(LocalVideoPhase.SPLASH)
    override val lifecycle:Lifecycle get()=registry
    override val viewModelStore=ViewModelStore()
    override val savedStateRegistry:SavedStateRegistry get()=saved.savedStateRegistry
    fun switchTo(value:LocalVideoPhase){phase.value=value}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        saved.performAttach();saved.performRestore(null)
        val root=requireNotNull(window).decorView
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        registry.currentState=Lifecycle.State.CREATED
        // `context` here is Dialog.getContext(): the presentation's own display-bound, themed
        // context rather than the application default, which is what Compose should resolve against.
        setContentView(ComposeView(context).apply{
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent{
                when(phase.value){
                    LocalVideoPhase.SPLASH->SplashPage(brand)
                    LocalVideoPhase.SCREEN->LocalScreenPage(brand)
                }
            }
        })
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
    }
    override fun onStart(){super.onStart();registry.currentState=Lifecycle.State.RESUMED}
    override fun onStop(){
        registry.currentState=Lifecycle.State.DESTROYED;viewModelStore.clear()
        super.onStop()
    }
}

/** 启动页：先放 `assets/splash.mp4`，放不出来就退回原来的品牌页。
 *
 *  为什么是 TextureView 而不是 SurfaceView：这块画面是画进 VirtualDisplay、再由编码器读走的。
 *  SurfaceView 会自开一个合成层，走私有显示时经常整块黑；TextureView 走普通视图合成，
 *  和 Compose 画在同一层上，编码器才看得到。
 *
 *  音频恒静音：片源里确实带一条 AAC 轨，但这是车机上的开机画面，不该在车里突然响一声。
 *
 *  失败一律退回品牌页，不抛也不报会话失败：启动画面是给人看的，不是协议的一部分。 */
@Composable private fun SplashPage(brand:String){
    val context=LocalContext.current
    var fallback by remember{ mutableStateOf(false) }
    // 用户上传的素材优先，没有才回内置视频。图片走静态绘制 —— 一张图没必要为它开解码器。
    val custom=remember{ SplashAsset.current(context.applicationContext) }
    val image=remember(custom){ custom?.takeIf{SplashAsset.isImage(it)}
        ?.let{SplashAsset.decodeImage(context.applicationContext,it)} }
    if(custom!=null&&image!=null){
        Box(Modifier.fillMaxSize().background(Color.Black)){
            // Fit 而不是 Crop：上传的多半是带字的品牌图，裁掉边缘比留黑边更糟。
            Image(bitmap=image.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxSize())
        }
        return
    }
    if(fallback){
        BrandPage(brand,"正在建立投屏通道")
        return
    }
    val video=remember{SplashVideo()}
    DisposableEffect(video){ onDispose{ video.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)){
        AndroidView(
            modifier=Modifier.fillMaxSize(),
            factory={viewContext->TextureView(viewContext).apply{
                surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                    override fun onSurfaceTextureAvailable(texture:SurfaceTexture,width:Int,height:Int){
                        val started=video.attach(viewContext.applicationContext,texture){fallback=true}
                        if(!started)fallback=true
                    }
                    override fun onSurfaceTextureSizeChanged(texture:SurfaceTexture,width:Int,height:Int)=Unit
                    override fun onSurfaceTextureDestroyed(texture:SurfaceTexture):Boolean{
                        video.release();return true
                    }
                    override fun onSurfaceTextureUpdated(texture:SurfaceTexture)=Unit
                }
            }}
        )
    }
}

/** `assets/splash.mp4` 的播放器。一个实例对应一次启动页显示，close 后释放。
 *
 *  这是唯一会往编码器送运动画面的地方：其余两页都是静态 Compose，靠心跳每秒重画一次，
 *  视频自己每帧都在更新，心跳对它没有意义。 */
private class SplashVideo{
    private var player:MediaPlayer?=null
    /** 返回 false 表示这段视频放不了，调用方应当退回静态页。 */
    fun attach(context:Context,texture:SurfaceTexture,onFailed:()->Unit):Boolean{
        release()
        val file=prepareFile(context)?:return false
        return try{
            val mp=MediaPlayer()
            player=mp
            active.add(this)
            mp.setDataSource(file.absolutePath)
            mp.setSurface(Surface(texture))
            mp.isLooping=true
            mp.setVolume(0f,0f)
            mp.setOnPreparedListener{prepared->
                LocalVideoDisplay.note("splash video playing ${file.length()}B ${prepared.videoWidth}x${prepared.videoHeight}")
                prepared.start()
            }
            mp.setOnErrorListener{_,what,extra->
                // 解不出来就退回静态页：宁可见不到视频，也不要给车机留一块黑屏。
                LocalVideoDisplay.note("splash video failed what=$what extra=$extra")
                onFailed();release();true
            }
            mp.prepareAsync()
            true
        }catch(t:Throwable){
            LocalVideoDisplay.note("splash video rejected: "+t.javaClass.simpleName+" "+(t.message?:""))
            release();false
        }
    }
    fun release(){
        val mp=player?:return
        player=null
        active.remove(this)
        // 先摘 surface 再 reset：顺序反过来的话，解码器可能仍在向一块已经要交出去的
        // BufferQueue 推帧，宿主拿到 surface 后第一帧就被顶掉。
        runCatching{mp.setSurface(null)}
        runCatching{mp.reset()}
        runCatching{mp.release()}
    }
    /** 拷到 cache 再播，不走 openFd。
     *
     *  `openFd` 只对**未压缩**的资产成立，而资产是否被压缩取决于打包配置——一旦哪天被压缩，
     *  启动画面就会在车上变黑，且只在车上才看得出来。多拷一次 1.6MB 换掉这个隐患值得。
     *  文件名带 versionCode，升级后旧文件不会顶替新片源。 */
    /** 先看用户上传的素材，没有再走内置 asset。
     *
     *  上传文件已经在应用私有目录里，不需要再拷一次；内置那份仍然要拷到 cache ——
     *  `openFd` 只对未压缩资产成立，一旦哪天被打包压缩，启动画面就会在车上变黑。 */
    private fun prepareFile(context:Context):File?{
        SplashAsset.current(context)?.takeIf{SplashAsset.isVideo(it)}?.let{file->
            LocalVideoDisplay.note("splash video from uploaded asset ${file.name} ${file.length()}B")
            return file
        }
        val target=File(context.cacheDir,"splash-"+BuildConfig.VERSION_CODE+".mp4")
        if(target.isFile&&target.length()>0L)return target
        return try{
            context.assets.open(ASSET).use{input->target.outputStream().use{input.copyTo(it)}}
            target
        }catch(t:Throwable){
            LocalVideoDisplay.note("splash video missing: "+t.javaClass.simpleName+" "+(t.message?:""))
            null
        }
    }
    // 不是 private companion：同文件的 LocalDisplay.close() 要能调 releaseActiveNow()。
    // 外泄风险为零 —— SplashVideo 本身就是 file-private。
    companion object{
        const val ASSET="splash.mp4"
        /** 还活着的播放器。交给宿主前要能凭着它同步收干净，不能等 Compose 的 dispose。 */
        private val active=java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<SplashVideo,Boolean>())

        /** 同步释放全部播放器。必须在主线程调；`MediaPlayer.release()` 自己会阻塞到 native 侧放手。 */
        fun releaseActiveNow(){
            val count=active.size
            if(count>0)LocalVideoDisplay.note("splash video released synchronously before the handoff: instances=$count")
            active.toList().forEach{runCatching{it.release()}}
        }
    }
}

@Composable private fun BrandPage(name:String,caption:String){
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1830),Color(0xFF2A4D8F))))){
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            Text(name,color=Color.White,fontSize=34.sp,fontWeight=FontWeight.Bold)
            Text(caption,color=Color.White.copy(alpha=.72f),fontSize=16.sp)
            // 余下的空间全给二维码：车机上扫得清楚比画面好看重要。
            DonationCodes(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable private fun LocalScreenPage(name:String){
    val state by BridgeStore.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D1830),Color(0xFF22335C))))){
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            Text(name,color=Color.White,fontSize=30.sp,fontWeight=FontWeight.Bold)
            Text("未检测到 CarWith 宿主 · 当前显示本机投屏页",color=Color.White.copy(alpha=.72f),fontSize=15.sp)
            Text(state.stage.title,color=Color.White.copy(alpha=.9f),fontSize=18.sp)
            DonationCodes(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** 作者的三张捐赠码。
 *
 * 直接贴在画面底色上，不套底板也不裁圆角：这三张图各自都带品牌底色，加一层壳只会让二维码变小。
 * 排布按画面比例定——横屏摆三列、竖屏摆三行，每一格都让图按自身比例尽量撑满；三张图的宽高比本来
 * 就不一样，靠 ContentScale.Fit 各自适配，不强行拉成同一形状。
 *
 * 解码结果记在 remember 里：这块画面会被反复重绘（静止时每秒一次心跳），每次重组都重新解三张
 * JPG 会白占主线程。缺哪张就跳过哪张。 */
@Composable private fun DonationCodes(modifier:Modifier=Modifier){
    val context=LocalContext.current
    val codes=remember{
        listOf("alipay.jpg" to "支付宝","binance.jpg" to "Binance","wxpay.jpg" to "微信")
            .mapNotNull{(asset,label)->
                runCatching{BitmapFactory.decodeStream(context.assets.open(asset))?.asImageBitmap()}
                    .getOrNull()?.let{it to label}
            }
    }
    if(codes.isEmpty())return
    BoxWithConstraints(modifier){
        // 竖屏里三张并排会窄到扫不出来，所以按画面比例换一种摆法。
        if(maxWidth>maxHeight){
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement=Arrangement.spacedBy(12.dp),
                verticalAlignment=Alignment.CenterVertically
            ){
                codes.forEach{(image,label)->
                    Image(bitmap=image,contentDescription=label,contentScale=ContentScale.Fit,
                        modifier=Modifier.weight(1f).fillMaxHeight())
                }
            }
        }else{
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement=Arrangement.spacedBy(12.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ){
                codes.forEach{(image,label)->
                    Image(bitmap=image,contentDescription=label,contentScale=ContentScale.Fit,
                        modifier=Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
