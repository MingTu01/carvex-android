package io.github.iamr0s.carvex.sessioncore
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class Stage(val title:String) {
    IDLE("尚未连接"), HOST("初始化投屏会话"), BLUETOOTH("连接 CarPlay-Bluetooth"), DETECT("协议检测与同步"),
    AUTH("车机身份与签名"), WIFI("连接车机 Wi-Fi"), PAIR("AirPlay 配对"),
    SETUP("协商画面与输入"), WAIT_FRAME("等待首个视频帧"), STREAMING("视频发送中 · 待实机确认"), RETRY("等待重试"),
    STOPPING("正在释放资源"), FAILED("连接失败");

    /** The control link is established once display/input negotiation begins. */
    val connected: Boolean get() = this == SETUP || this == WAIT_FRAME || this == STREAMING
}

/** Shared by the home screen and foreground notification. */
val SessionState.progressText: String get() {
    val progress = detail.ifBlank { stage.title }
    return if (stage == Stage.FAILED && !error.isNullOrBlank() && error != progress) "$progress：$error" else progress
}
data class SessionState(val id:String="",val stage:Stage=Stage.IDLE,val detail:String="选择已配对车机后开始",
                        val attempt:Int=0,val hostDisplay:Int?=null,val width:Int=0,val height:Int=0,val frames:Long=0,
                        val bytes:Long=0,val inputs:Long=0,val touch:Boolean=false,val audioBytes:Long=0,val error:String?=null,
                        /** Set when the session is running in a degraded way that is not a failure (for example the
                         *  system refusing to pin sockets to the car network). Cleared by the next attempt. */
                        val warning:String?=null,
                        val runtimeIap:String="等待车机数据",val targetFps:Int=0,val maxFps:Int=0,
                        val sentFps:Double=0.0,val sentMbps:Double=0.0,val targetBitrate:Int=0,
                        val encodedFps:Double=0.0,val droppedFrames:Long=0,val queueMs:Long=0,val writeMs:Long=0,val idrRequests:Long=0,
                        val audioPackets:Long=0,val audioUnsentPackets:Long=0,val audioForwardedBytes:Long=0,val microphoneBytes:Long=0,val receiverAudioEntries:Int=0,val audioStatus:String="尚未建立媒体通道",
                        val carPlayAudioTestRunning:Boolean=false,val carPlayAudioTestStatus:String="尚未测试 CarPlay 发声",
                        val carWithAudioProbeRunning:Boolean=false,val carWithAudioProbeStatus:String="尚未测试 CarWith 音频输入",
                        val hostMusic:String="尚未收到宿主机音乐信息",val hostMusicProgress:Long=0,
                        val hostHud:String="尚未收到宿主机导航数据",val hostNightMode:Int?=null,
                        val hostUiRequest:String="尚未收到车机界面请求",val hostOptionalActions:Int=0)
data class Device(val name:String,val address:String)
/** 学习模式下捕获到的一条 CarPlay 按键事件：只给界面做诊断记录，不参与运行时转发。 */
data class CapturedKey(val id:String,val label:String)
enum class Appearance { SYSTEM,LIGHT,DARK }

/** 默认主题色：紫罗兰。 */
const val DEFAULT_COLOR_VIOLET=0xFF7A4FCF.toInt()

data class Options(val appearance:Appearance=Appearance.SYSTEM,val color:Int=DEFAULT_COLOR_VIOLET,val target:String="",
                   val phoneMac:String="",val listen:Boolean=false,val retryIntervalSeconds:Int=2,val dpi:Int=160,
                   // 实验认证 = iAP 隧道 + MFi 校验 + 认证后才允许的 bootstrap。
                   // 实车跑通靠的就是这条路，关掉它整个会话建立不起来，所以默认开、
                   // 也不再给界面开关 —— 留一个一按就没声音没画面的按钮没有意义。
                   val experiment:Boolean=true,val deviceName:String="",val bindNetwork:Boolean=true,
                   /** 「静态画面补帧」：是否在编码器前面架一层 EGL 前置画布。
                    *
                    * 这一层是**唯一**能让静止画面重新进到编码器里的办法：宿主的画面不是我们能
                    * 重画的，只能把它的最后一帧从画布上再 blit 一次，编码器才会吐出一个
                    * skip P 帧。没有它，画面一停，视频时间戳也停 —— 而音频时间戳还在走，
                    * 恢复时接收端就得靠丢帧追回这段 A/V 偏差，用户看到的就是"一动先卡一下"。
                    *
                    * 代价是每帧多一次全屏 blit、多一跳缓冲，实测增加约一帧延迟。默认开：
                    * 卡顿比这点延迟更显眼。真嫌手感钝，把这个默认值改回 false 重新打包即可
                    * （release 包 run-as 进不去，改不了盘上的值）；[DefaultOnSettingsMigration]
                    * 只搬一次，不会把手工改回去的值再翻上来。
                    *
                    * 补帧节奏是自适应的（`VideoEncoder.IDLE_STILL_AFTER_MS`）：静止 500 ms
                    * 补第一帧，之后每秒一帧续命，画面一动立刻停，正常出帧的路径完全不受影响。 */
                   val frontSurface:Boolean=true,
                   /** 「主动连接」：本端主动发起（关掉 = 只做被连接方，等价于原来的 listen=true）。
                    *  默认开：绝大多数用户是从本应用点一台车机去连，而不是等车机来找。 */
                   val autoConnect:Boolean=true,
                   /** 诊断开关：MFi/SAP 音频只发 12 字节明文头 + 原始 AAC 负载，不做 AES-CTR。
                    *  SETUP 200 只证明车机接受注册，不证明它能解开我们自推的线上格式；
                    *  开这个开关跑一次就能一刀切开"密码层错"还是"车机根本没在听这条流"。
                    *  默认关：默认仍按 R-6.1 加密发送。 */
                   val audioPlaintext:Boolean=false,
                   /** 「音频投送」：是否真的对车机建立 CarPlay 音频流。
                    *
                    *  **曾经的结论已被推翻，留在这里是为了不再走回头路。** 2026-11-02 那轮以为
                    *  "建音频流会弄停画面"，真实原因是 `d5b7a4f` 把"关掉我们自己的虚拟屏"挪到了
                    * `prepareCast` 之后：两块 VirtualDisplay 同时挂一块 surface，CarWith 那边就
                    * 一帧都不再合成。改回"先关再交"（`660d2ba`）之后，画面正常的同时音频
                    * SETUP 也能全部 200。
                    *
                    * 现在它是**正常功能开关**，不再是"这一版要不要测声音"。而且关掉的代价
                    * 不是"不测声音"——[AirSession] 在它为 false 时**直接抛异常、跳过整条
                    * MFi/SAP 音频流**，表现就是车机里音乐和导航全哑。所以默认必须是开，
                    * 也不再暴露成能误触的界面开关。
                    *
                    * 老版本把 false 落过盘的用户由 [DefaultOnSettingsMigration] 一次性提升。 */
                   val audioEnabled:Boolean=true,
                   /** 诊断开关：音频建立后向车机发 `SET_PARAMETER volume`。
                    *
                    *  AirPlay 的收端音量是**发送方**设的（dB，-144 约等于静音）。如果车机在收到
                    *  音量命令前一直保持静音，表现就是"流正常、包在发、日志全绿、就是没声"——
                    *  和密文解不开的样子一模一样。NaN = 不发（默认，保持原有行为）。 */
                   val audioVolumeDb:Double=Double.NaN,
                  /** 诊断开关：线上 RTP 的 PT 直接用 SETUP 的 raw stream type（旧行为）。
                   *
                   *  默认关 = 走规范映射 `raw102 → main(100)`，依据是发送侧 FourCC→PT 硬编码表
                   *  `_carAudioStream_getRTPPayloadType`（`airplay_rtp_timing/analysis/03`
                   *  **A03.1 CONFIRMED**：`'main'→100`，表里没有 `gen`；未命中返回入参本身）。
                   *  旧映射 `raw102→gen(96)`（A01.5，INFERRED）已废除：实车 20022/20023 两次全哑。
                   *  接收准入掩码 `0x3d13` 不含 102，发 102 会被车机在入口处就丢掉。
                   *
                   *  打开 = 恢复恒等映射，只在真机上需要做一次 A/B、排除"是不是 PT 这个变量"
                   *  时用。用 `run-as` 改 `shared_prefs/options.xml` 即可，不给 UI。
                   *  日志里 `Audio wire payload type ... wirePT=` 会写清实际发的是哪个。 */
                  val audioRawWirePt:Boolean=false){
    /**
     * Name presented to the CarPlay receiver.
     *
     * 空值时**不再**写死成 "CarWith Bridge"，而是用本机名字（见 [localDeviceName]）。
     * 这样默认行为就是"以手机自己的名字上报"，用户不必先去设置里改一遍；
     * 显式填了就用填写值。
     *
     * 代价：这个 property 需要 Context。所以由 [BridgeStore] 在 init 时把解析结果
     * 缓存进 [resolvedDeviceName]，本类只读缓存，保持 Options 是纯数据。
     */
    val carPlayName:String get()=deviceName.trim().ifBlank{resolvedDeviceName.ifBlank{DEVICE_NAME_DEFAULT}}
    companion object {
        const val DEVICE_NAME_DEFAULT="CarVeX"
        const val DEVICE_NAME_MAX=32
        /**
         * 本机名字的进程级缓存。由 BridgeStore.init 填充。
         *
         * 做成 @Volatile 顶层状态而不是塞进 Options：Options 是 data class，
         * 会被反复 copy 与持久化，把一个"每次读设置都要重算"的值放进去会污染相等性与存储。
         */
        @Volatile @JvmStatic
        var resolvedDeviceName:String=""
        /** 重连间隔的合法区间，与 configure() 的校验保持一致。 */
        val RETRY_INTERVAL_RANGE=2..15
        /** 蓝牙地址格式（与 ShizukuBluetoothMac 的正则一致），供界面校验复用。 */
        val MAC_REGEX=Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /**
         * 本机对外显示的名字 —— 「上报给车机的名称」默认值的来源。
         *
         * 取值顺序有讲究：
         *  1. `Settings.Global.device_name`：用户在系统设置里给本机起的名字，
         *     这正是"手机的名称"，也与其他设备在车机上看到的名字一致。
         *     它是 Global 设置，普通应用读得到（不需要特权），只是部分 ROM 没有这一项。
         *  2. `Build.MODEL`：系统没设名字时兜底，例如 "Xiaomi 17"。
         *     比写死的 "CarWith Bridge" 有意义 —— 车机上至少认得出是哪台手机。
         *  3. 常量兜底：连 MODEL 都取不到时。
         *
         * `device_name` 可能是 "unknown" 这类占位值，一并过滤。
         */
        fun localDeviceName(context:Context):String {
            val fromSettings=runCatching{
                android.provider.Settings.Global.getString(context.contentResolver,"device_name")
            }.getOrNull()
            val sanitized=fromSettings?.trim()
                ?.takeIf{it.isNotEmpty()&&!it.equals("unknown",true)&&it.none{ch->ch.isISOControl()}}
                ?.take(DEVICE_NAME_MAX)
            if(sanitized!=null)return sanitized
            val model=android.os.Build.MODEL?.trim().orEmpty()
            if(model.isNotEmpty()&&!model.equals("unknown",true))return model.take(DEVICE_NAME_MAX)
            return DEVICE_NAME_DEFAULT
        }
    }
}
object BridgeStore {
    private val mutable=MutableStateFlow(SessionState())
    val state=mutable.asStateFlow()
    private val logState=MutableStateFlow<List<String>>(emptyList())
    private val sessionStartup=mutableListOf<String>()
    private val audioEvidence=linkedMapOf<String,String>()
    val logs=logState.asStateFlow()
    private val optionState=MutableStateFlow(Options())
    val options=optionState.asStateFlow()
    private lateinit var context:Context
    private data class LogWrite(val generation:Long,val line:String?)
    @Volatile private var logGeneration=0L
    // Disk access never holds the BridgeStore monitor used by live audio/video logging.
    private val logFileLock=Any()
    private var diskLogGeneration=-1L // guarded by logFileLock
    private val writer=Channel<LogWrite>(512)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Synchronized fun init(c:Context){
        if(::context.isInitialized)return;context=c.applicationContext
        // 先解析本机名字：Options.carPlayName 在 deviceName 为空时用它作默认上报名。
        // 放在这里而不是每次读时算，是因为它要访问 ContentResolver，不该在 UI 每帧调用。
        Options.resolvedDeviceName=Options.localDeviceName(context)
        val p=context.getSharedPreferences("options",Context.MODE_PRIVATE)
        // 下面两行都必须在**读**选项之前跑，否则读到的还是旧盘上的值。
        //
        // 顺序有讲究：先把已退休的诊断键清掉，再把该开的开关一次性提升为开。
        // 两步都是幂等的，重复启动不会反复改写用户的存量设置。
        RetiredAudioSettingsMigration.removeFrom(p.edit()).apply()
        if(DefaultOnSettingsMigration.applyTo(p)){
            // 一次性提升的结果必须留下痕迹。否则"装完新版本还是老行为"这件事只能靠猜盘上的值，
            // 而 release 包 run-as 进不去 —— 那正是上一次多花一整轮排查的原因。
            android.util.Log.i("CarVex","defaults migration applied: promoted switches are now on (revision="+
                DefaultOnSettingsMigration.REVISION+")")
        }
        val default = Options()
        // phoneMac 在**读**的时候也要 uppercase（不只是写的时候）。
        //
        // 原因：`checkBluetoothAddress` 大小写敏感（详见 configure 里的说明）。
        // 已经装过旧版本的用户，SharedPreferences 里可能存着一条小写 MAC ——
        // 只在写入路径归一化的话，这些用户升级后**依然是坏的**，
        // 而且他们看输入框会看到"一条格式正常的地址"，更难自查。
        // 读的时候顺手归一化，等于给存量数据做了一次静默迁移。
        val savedMac=(p.getString("phoneMac",default.phoneMac)?:"").trim().uppercase()
        optionState.value=default.copy(Appearance.entries.getOrElse(p.getInt("appearance",0)){default.appearance},p.getInt("color",default.color),
            p.getString("target",default.target)?:"",savedMac,p.getBoolean("listen",default.listen),p.getInt("retryIntervalSeconds",default.retryIntervalSeconds),
            p.getInt("dpi",default.dpi),p.getBoolean("experiment",default.experiment),
            p.getString("deviceName",default.deviceName)?:"",p.getBoolean("bindNetwork",default.bindNetwork),p.getBoolean("frontSurface",default.frontSurface),
            p.getBoolean("autoConnect",default.autoConnect),p.getBoolean("audioPlaintext",default.audioPlaintext),
            p.getBoolean("audioEnabled",default.audioEnabled),
            p.getFloat("audioVolumeDb",default.audioVolumeDb.toFloat()).toDouble(),
            p.getBoolean("audioRawWirePt",default.audioRawWirePt))
        scope.launch {
            val f=File(context.filesDir,"diagnostic.log")
            val saved=synchronized(logFileLock){
                runCatching{DiagnosticLogText.readDisk(f)}.getOrDefault(emptyList()).also{diskLogGeneration=0L}
            }
            synchronized(this@BridgeStore){
                if(logGeneration==0L)logState.value=DiagnosticLogText.retain((saved+logState.value).distinct())
            }
            for(entry in writer){runCatching{
                synchronized(logFileLock){
                    if(entry.line==null){f.writeText("");diskLogGeneration=entry.generation}
                    else if(entry.generation==logGeneration){
                        if(f.length()>1024*1024){
                            val tail=buildList {
                                var bytes=0
                                logState.value.asReversed().forEach { line ->
                                    val size=line.toByteArray(Charsets.UTF_8).size+1
                                    if(bytes<512*1024 || isEmpty()){add(line);bytes+=size}
                                }
                            }.asReversed()
                            f.writeText(tail.joinToString("\n")+"\n")
                        }
                        f.appendText(entry.line+"\n")
                    }
                }
            }}
        }
    }
    @Synchronized fun configure(o:Options){
        // 重连间隔的区间收在 Options 里，避免这里和界面各写一份、改一处忘一处。
        require(o.dpi in 120..320 && o.retryIntervalSeconds in Options.RETRY_INTERVAL_RANGE)
        // The receiver, Bonjour and iAP2 all reject oversized or control-character names.
        val name=o.deviceName.trim()
        require(name.length<=Options.DEVICE_NAME_MAX&&name.none{it.isISOControl()}){"${"设备名称最多 "}${Options.DEVICE_NAME_MAX}${" 个字符，且不能包含控制字符"}"}
        // listen 与 autoConnect 是同一件事的两面（是否由车机主动发起），
        // 在写入前统一派生，避免两个开关互相矛盾。
        //
        // ════════════════════════════════════════════════════════════════
        // phoneMac 必须在这里就**大写归一化**
        // ════════════════════════════════════════════════════════════════
        // 为什么不能只靠调用方自觉：
        // `BluetoothAdapter.checkBluetoothAddress()` 是**大小写敏感**的，
        // 平台源码（android-36 BluetoothAdapter.java:3904）逐字符判断
        //     if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) { ... }
        //     return false;
        // 只认大写 A~F，小写 a~f 一律 false。官方文档也写明地址必须大写。
        //
        // 曾经出过的事故：Shizuku 自动读取返回了小写 MAC，输入框里显示正常，
        // 但点连接时被 SessionController 的身份校验拦下，提示"请填写真实蓝牙 MAC" ——
        // 用户看着一个格式完全正确的地址，完全无从下手。
        //
        // 修在**这里**而不是修在 Shizuku 那一处：这是所有写入路径的收口点，
        // 在这里归一化，以后无论谁再传小写进来都不会重演。
        // 注意 optionState 与 SharedPreferences 必须写**同一个值** ——
        // 原来内存里存原样、存储里存 uppercase，两者不一致，本身就是个隐患。
        val mac = o.phoneMac.trim().uppercase()
        val normalized=o.copy(deviceName=name,listen=!o.autoConnect,phoneMac=mac)
        optionState.value=normalized
        RetiredAudioSettingsMigration.removeFrom(context.getSharedPreferences("options",Context.MODE_PRIVATE).edit())
            .putInt("appearance",normalized.appearance.ordinal).putInt("color",normalized.color).putString("target",normalized.target)
            .putString("phoneMac",mac).putBoolean("listen",normalized.listen).putInt("retryIntervalSeconds",normalized.retryIntervalSeconds)
            .putInt("dpi",normalized.dpi).putBoolean("experiment",normalized.experiment)
            .putString("deviceName",name).putBoolean("bindNetwork",normalized.bindNetwork).putBoolean("frontSurface",normalized.frontSurface)
            .putBoolean("autoConnect",normalized.autoConnect).putBoolean("audioPlaintext",normalized.audioPlaintext)
            .putBoolean("audioEnabled",normalized.audioEnabled)
            // NaN 落盘会变成 Float.NaN，读回来还是 NaN，正好当"不发音量命令"的哨兵值。
            .putFloat("audioVolumeDb",normalized.audioVolumeDb.toFloat())
            .putBoolean("audioRawWirePt",normalized.audioRawWirePt)
            .remove("pairingPin").remove("airPlaySecurity").apply()
    }
    @Synchronized fun update(id:String,transform:(SessionState)->SessionState){if(mutable.value.id==id)mutable.value=transform(mutable.value)}
    @Synchronized fun begin(attempt:Int):String {
        sessionStartup.clear();audioEvidence.clear()
        val id=UUID.randomUUID().toString().take(8);mutable.value=SessionState(id=id,stage=Stage.HOST,attempt=attempt);return id
    }
    @Synchronized fun idle(){mutable.value=SessionState()}
    fun redact(s:String):String = s.replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"),"**:**:**:**:**:**")
        .replace(Regex("(?i)(password|psk|seed|secret|ssid|pairingPin|setupCode)\\s*[:=]\\s*[^,\\s]+"),"$1=<redacted>").take(500)
    @Synchronized fun log(id:String,message:String){
        val line=Instant.now().toString()+" ["+id+"] "+redact(message)
        if(id==mutable.value.id){
            if(sessionStartup.size<220)sessionStartup.add(line)
            val relevant=message.startsWith("Audio category=")||message.startsWith("CarPlay audio SETUP")||
                    message.startsWith("CarWith audio")||message.startsWith("CarPlay audio category=")||
                    message.startsWith("Compressed RTP")||message.startsWith("Compressed audio")||
                    message.startsWith("Audio RED diagnostic")||
                    message.startsWith("Audio packet")||message.startsWith("INFO audio")||
                    message.startsWith("Active media key")||message.startsWith("Audio transport degraded")
            if(relevant){
                val key=if(message.startsWith("Audio RED diagnostic")||message.startsWith("Audio packet")){
                    message.substringBefore(" category=")+" category="+message.substringAfter("category=", "unknown").substringBefore(' ')
                }else message.substringBefore(" packets=").substringBefore(" PCM ").substringBefore(" encoders=").take(150)
                audioEvidence[key]=line
                while(audioEvidence.size>80)audioEvidence.remove(audioEvidence.keys.first())
            }
        }
        // UI 预览仍可限制 400 行；原始日志保留 2000 行，其中包括前 24 行启动记录。
        logState.value=DiagnosticLogText.retain(logState.value+line)
        writer.trySend(LogWrite(logGeneration,line))
    }
    @Synchronized fun clearLogs(){
        logGeneration++;logState.value=emptyList();sessionStartup.clear();audioEvidence.clear()
        val clear=LogWrite(logGeneration,null)
        if(writer.trySend(clear).isFailure){
            // Clear remains ordered before new writes even when the disk queue is full.
            while(writer.tryReceive().isSuccess){}
            check(writer.trySend(clear).isSuccess)
        }
    }
    /** 诊断头里的运行环境。诊断文件常被单独转发出去 —— 缺了版本/机型就无法确定
     *  "这是哪个构建、哪台设备、对哪台车机"，而这三件事决定日志该怎么读。 */
    private fun environment():String = runCatching{
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        val debug=(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
        val o=options.value
        "Version: "+(info.versionName?:"unknown")+" ("+info.longVersionCode+") · "+(if(debug)"debug" else "release")+"\n"+
            "Device: "+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" · Android "+android.os.Build.VERSION.RELEASE+" (API "+android.os.Build.VERSION.SDK_INT+")\n"+
            "Target: "+o.target+" · phoneMac "+redact(o.phoneMac)+" · listen="+o.listen+" · experiment="+o.experiment+" · bindNetwork="+o.bindNetwork+"\n"
    }.getOrElse{"Version: unavailable\n"}
    private data class LogSnapshot(val generation:Long,val session:SessionState,
        val evidence:List<String>,val recent:List<String>)
    private fun logSnapshot()=synchronized(this){
        LogSnapshot(logGeneration,mutable.value,(sessionStartup+audioEvidence.values).distinct(),logState.value)
    }

    /** Read on an IO dispatcher. Copy only memory under the live-state monitor. */
    fun export():String {
        var snapshot=logSnapshot()
        val disk=synchronized(logFileLock){
            diskLogGeneration to runCatching{
                if(::context.isInitialized&&snapshot.generation==diskLogGeneration)
                    DiagnosticLogText.readDisk(File(context.filesDir,"diagnostic.log")) else emptyList()
            }.getOrDefault(emptyList())
        }
        // If Clear ran during the disk read, discard the old snapshot as well as old disk data.
        synchronized(this){if(snapshot.generation!=logGeneration)snapshot=logSnapshot()}
        val current=snapshot.session
        val lines=DiagnosticLogText.merge(snapshot.generation,disk.first,disk.second,snapshot.evidence,snapshot.recent)
        return "CarVeX diagnostics\n"+
                environment()+
                "Authentication: experimental signature integrity; Apple root chain unverified\n"+
                "Audio: HomeKit codec/transport implementation; audible acceptance requires device verification\n"+
                "Session=${current.id} audioStatus=${current.audioStatus}\n"+
                "Downstream sourcePCMBytes=${current.audioBytes} sourceBlocks=${current.audioPackets} droppedBlocks=${current.audioUnsentPackets} submittedPCMBytes=${current.audioForwardedBytes}\n"+
                "Upstream microphoneDeliveredPCMBytes=${current.microphoneBytes}\n"+
                "--- Retained raw log (disk, startup evidence and recent memory; oldest first) ---\n"+
                lines.joinToString("\n")
    }
    /**
     * 历史监听配置的持久存储。运行时不读取它来转发 CarPlay 事件；协议映射由会话直接处理，
     * 这里仅供诊断界面查看和保留历史记录。
     */
    private val keyBindings:KeyBindings by lazy{KeyBindings.open(context)}
    val bindings:KeyBindings get()=keyBindings
    /** 学习模式：只在监听时把收到的 CarPlay 事件记下来，供界面上选绑定。 */
    private val captureState=MutableStateFlow<List<CapturedKey>>(emptyList())
    val captured=captureState.asStateFlow()
    @Volatile private var capturing=false
    fun capturing()=capturing
    /** 进入监听：清空旧样本，之后车机发来的事件依次进入 [captured]。 */
    fun beginCapture(){capturing=true;captureState.value=emptyList()}
    fun endCapture(){capturing=false}
    /** 收到一条 CarPlay 事件：同一 id 只留一条，最近的排后面。 */
    @Synchronized fun recordCarPlayKey(id:String){
        if(!capturing)return
        val list=captureState.value
        if(list.any{it.id==id})return
        captureState.value=(list+CapturedKey(id,CarPlayEvents.label(id))).takeLast(40)
    }

}
class Resources(private val onClose:((CloseTrace)->Unit)?=null):AutoCloseable {
    data class CloseTrace(val type:String,val elapsedMs:Long?=null,val error:String?=null)
    private val closed=AtomicBoolean(false);private val objects=mutableListOf<AutoCloseable>()
    @Synchronized fun <T:AutoCloseable> own(t:T):T {if(closed.get()){runCatching{t.close()};error("Session closed")};objects+=t;return t}
    private fun closeOne(t:AutoCloseable){
        val type=t.javaClass.simpleName.ifEmpty{t.javaClass.name.substringAfterLast('.')}
        runCatching{onClose?.invoke(CloseTrace(type))}
        val began=System.nanoTime()
        val result=runCatching{t.close()}
        runCatching{onClose?.invoke(CloseTrace(type,(System.nanoTime()-began)/1_000_000,result.exceptionOrNull()?.javaClass?.simpleName))}
    }
    // A blocking codec/Binder close must not hold the registry lock and prevent socket cleanup.
    fun release(t:AutoCloseable){synchronized(this){objects.remove(t)};closeOne(t)}
    override fun close(){val release=synchronized(this){if(!closed.compareAndSet(false,true))return;objects.reversed().also{objects.clear()}};release.forEach(::closeOne)}
}
