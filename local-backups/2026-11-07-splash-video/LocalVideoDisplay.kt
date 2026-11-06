package io.github.iamr0s.carvex
import android.app.Presentation
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
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
import java.util.concurrent.CountDownLatch

/** Private own-content display fed by Compose, matching the reference implementation:
 * no MediaProjection token, no overlay permission and no Activity, so it also works while the app
 * is in the background. The VirtualDisplay output is the encoder input surface itself. */
object LocalVideoDisplay:LocalVideoContentFactory {
    /** Diagnostics go to the session log stream: without them a refused virtual display looks
     * exactly like one that was created but never produces a frame. */
    internal fun note(message:String)=BridgeStore.log(BridgeStore.state.value.id,"Local display: "+message)
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
            try{window.dismiss()}finally{
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
                    LocalVideoPhase.SPLASH->BrandPage(brand,"正在建立投屏通道")
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
