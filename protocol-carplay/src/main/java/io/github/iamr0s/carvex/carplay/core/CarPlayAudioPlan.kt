package io.github.iamr0s.carvex.carplay.core
import io.github.iamr0s.carvex.pcm.PcmFormat

/** Codec bits recovered in VENDOR_CARPLAY_AUDIO_FORMATS; this is not a wire-format specification. */
object CarPlayAudioPlan {
    enum class Codec { PCM16, AAC_LC, AAC_ELD, OPUS }
    data class Format(val bit:Long,val codec:Codec,val rate:Int,val channels:Int,val framesPerPacket:Int) {
        val decodedPcm get()=PcmFormat(rate,channels)
    }
    data class Route(val category:Int,val type:Int,val purpose:String)
    data class Selection(val route:Route,val format:Format)
    val media=Route(1,102,"media")
    val mediaLowLatency=Route(1,100,"media")
    val alert=Route(2,100,"alert")
    val alternate=Route(2,101,"default")
    val assistant=Route(3,100,"speechRecognition")
    /** 语音的**第二个**落点：`101/default`。
     *
     *  2026-11-03 真车 `/info`（session `9829c6ba`）广告了 `#7 streamType=101 audioType=default
     *  outputMask=2008000`，即 lpcm 48000/2 | ELD 48000/2 —— 与 `100/alert` 同一组格式。
     *  同一次会话里 `101/default` 被 SETUP 接受过三次（06:15:01 / 06:16:07 / 06:16:11），
     *  所以这不是猜出来的路由，是车机自己声明的。
     *
     *  为什么要它：车机的 `type 100` 只能同时存在一条流（谁先 SETUP 谁占住），
     *  而主音频 PCM 48k/2 一直占着它。于是 `100/speechRecognition` 的再 SETUP 一律 400 ——
     *  语音永远起不来。`type 101` 是**独立上下文**，把语音挪过去就不跟主音频抢。 */
    val speechAlternate=Route(3,101,"default")
    val routes=listOf(media,mediaLowLatency,alert,alternate,assistant,speechAlternate)
    private fun f(bit:Long,codec:Codec,rate:Int,channels:Int,frames:Int)=Format(bit,codec,rate,channels,frames)
    val formats:List<Format> = buildList {
        val pcm=listOf(0x4L to 8000,0x10L to 16000,0x40L to 24000,0x100L to 32000,0x400L to 44100,0x4000L to 48000)
        for((bit,rate) in pcm){add(f(bit,Codec.PCM16,rate,1,1));add(f(bit*2,Codec.PCM16,rate,2,1))}
        add(f(0x400000L,Codec.AAC_LC,44100,2,1024));add(f(0x800000L,Codec.AAC_LC,48000,2,1024))
        add(f(0x1000000L,Codec.AAC_ELD,44100,2,480));add(f(0x2000000L,Codec.AAC_ELD,48000,2,480))
        add(f(0x4000000L,Codec.AAC_ELD,16000,1,480));add(f(0x8000000L,Codec.AAC_ELD,24000,1,480))
        add(f(0x10000000L,Codec.OPUS,16000,1,320));add(f(0x20000000L,Codec.OPUS,24000,1,480))
        add(f(0x40000000L,Codec.OPUS,48000,1,960));add(f(0x80000000L,Codec.AAC_ELD,44100,1,480))
        add(f(0x100000000L,Codec.AAC_ELD,48000,1,480));add(f(0x80000000000L,Codec.AAC_ELD,32000,1,480))
    }
    /** 媒体承载决定候选掩码（指南 §9.3.3 / `X-0019`）。USB 是否启用缓冲由调用方在建立媒体前选择。 */
    enum class Transport(val mask:Long) {
        WIRELESS(0xf7fe0f8ffffc0000uL.toLong()),
        USB(0x1f0400003fffcL),
        USB_BUFFERED(0x41f04000c3fffcL)
    }
    /** 候选与传输掩码相与为空时，固件返回 OSStatus `0xffffe5cf`（= -6705）。本实现对应的表现是
     *  [selectPlayback] 返回 null、候选列表为空——调用方必须按失败处理，不回退 PCM。 */
    const val NO_FORMAT:Int=-6705
    @Volatile private var transport=Transport.WIRELESS
    /** 只影响之后的选择；一个会话应只设置一次。 */
    fun useTransport(value:Transport){transport=value}
    val currentTransport:Transport get()=transport
    /** Keep each announcement separate: masks for different purposes/directions are never combined. */
    /** 不做传输掩码过滤。用于本地兼容路径（PCM 回退、双工捕获判定）——它们不是固件的候选选择。 */
    const val NO_MASK:Long=-1L
    fun advertised(entries:List<AudioCapabilities.Entry>,route:Route,capture:Boolean=false,
                   mask:Long=NO_MASK):List<Format> =
        entries.filter{it.streamType==route.type&&it.audioType==route.purpose}.flatMap{entry->
            val announced=if(capture)entry.inputMask else entry.outputMask
            formats.filter{format->announced!=null&&announced and format.bit!=0L&&mask and format.bit!=0L}
        }.distinct()
    /** 24A446 getFormatsKeyForAudioType: Main default/telephony/speech use input formats.
     * This selects the format description; it does not by itself enable a microphone. */
    fun playbackFormats(entries:List<AudioCapabilities.Entry>,route:Route,mask:Long=NO_MASK):List<Format> =
        advertised(entries,route,capture=route.type==100&&route.purpose in setOf("default","telephony","speechRecognition"),mask=mask)
    /** 无线音频的编解码优先序 —— phone-integration-guide §6 / t3-carplay-spec §9.4：
     *  `AAC-ELD → AAC-LC → OPUS → ALAC → PCM`。
     *
     *  为什么必须是"顺序回退"而不是按车型/流类型固定一个：三台车机的交集首选是 AAC-ELD，而
     *  GM 系的 `audioFormats` 里**没有 AAC-LC**（AAC/AAC-ELD/OPUS），老平台又**没有 OPUS**。
     *  固定任何一个编码，都会在另一代车机上交不出格式（文档 §6 的两个坑）。
     *
     *  ALAC 的优先级位置在 OPUS 之后、PCM 之前，但它不在 [formats] 表里：三份固件证据都没有
     *  恢复 ALAC 在 `audioOutputFormats` 中的位置掩码，位未知就不能凭空造一个 —— 所以它只是
     *  优先级里的一个空位，不会选出任何格式。PCM 不进本表：它是 [pcmCompatibility] 的显式兼容路径。
     *
     *  注意 features 的编码位（[AirPlayFeature.AUDIO_AAC_LC] 等）**不参与**这里：老平台实际支持
     *  AAC-LC 却没有置该位，判决权只在 `/info` 的 `audioFormats`。 */
    /**
     * Ordered wireless codec preference.  This is deliberately a list rather than a set:
     * negotiation must retain the profile order after intersecting receiver and host
     * capabilities.  PCM is handled by the explicit compatibility path below.
     */
    val wirelessCodecPreference:List<Codec> = listOf(Codec.AAC_ELD,Codec.AAC_LC,Codec.OPUS)

    /** Stable rank used by every direction of the audio negotiation. */
    fun codecPriority(codec:Codec):Int = wirelessCodecPreference.indexOf(codec)
        .takeIf{it>=0}?:wirelessCodecPreference.size

    /**
     * Keep an already ordered profile list and intersect it with local capability.
     * The first surviving entry is therefore the highest-priority common format;
     * callers must not sort the result again by an implementation-specific preference.
     */
    fun <T> orderedIntersection(ordered:List<T>,localSupports:(T)->Boolean):List<T> =
        ordered.filter(localSupports)
    fun mediaRoute(entries:List<AudioCapabilities.Entry>):Route {
        // Peer-only route view. Runtime also intersects local encoders with selectPlayback;
        // a specific codec such as AAC-LC must not be the only test for an advertised route.
        val choices=listOf(media,mediaLowLatency)
        return choices.firstOrNull{route->advertised(entries,route).any{wireSupported(route,it)}}?:media
    }
    fun routeForCategory(entries:List<AudioCapabilities.Entry>,category:Int):Route = when(category){
        1->mediaRoute(entries)
        2->alert
        3->assistant
        else->error("${"Unknown CarWith audio category="}${category}")
    }
    /** 编码候选按承载掩码过滤（§9.3.3）；PCM 回退另走 [pcmCompatibility]，那是本地的兼容放宽。 */
    fun wirelessCandidates(entries:List<AudioCapabilities.Entry>,route:Route,source:PcmFormat):List<Format> =
        playbackFormats(entries,route,transport.mask).filter{it.codec!=Codec.PCM16}
            .sortedWith(compareBy<Format>(
                {codecPriority(it.codec)},
                {if(it.rate==source.rate)0 else 1},{if(it.channels==source.channels)0 else 1},{-it.rate}))
    fun wireless(entries:List<AudioCapabilities.Entry>,route:Route,source:PcmFormat):Format? = wirelessCandidates(entries,route,source).firstOrNull()
    /** Candidates this client's existing packetizers can send. Local MediaCodec availability is a
     * separate intersection performed by the caller before SETUP; do not replace it with PCM-only. */
    fun playbackCandidates(entries:List<AudioCapabilities.Entry>,route:Route,source:PcmFormat):List<Format> =
        wirelessCandidates(entries,route,source).filter{wireSupported(route,it)}
    /** Intersect peer formats with local codecs. An input request must also have a capture path;
     * merely having an output encoder is insufficient for duplex SETUP.
     *
     *  [preferPcm] / [preferPcmAlert] 是 PCM 优先开关（见 [AudioDebugConfig]）。做成参数而不是
     *  只读全局，是为了让测试能确定性地钉住两个方向，不受可变全局状态与用例顺序影响。
     *
     *  **一条真机代价很高的教训（20032）**：车机在 `type 100` 上只维护**一个 PCM 上下文** ——
     *  谁先占住格式，后面的同类型 SETUP 就全被顶成 400。那一次让 category 3 也用 PCM
     *  （`100/speechRecognition` 24k/1，spf=120），结果：
     *
     *  ```
     *  cat3  100/speechRecognition  PCM 24k/1  → accepted
     *  cat2  100/alert              PCM 48k/2  → status=400
     *  cat1  100/media              PCM 48k/2  → StatusException（主音频直接建不起来）
     *  cat2  101/default            PCM 48k/2  → accepted（不同 type，另一个上下文）
     *  ```
     *
     *  所以 PCM 不能"三条流一起上"：`type 100` 的 PCM 上下文留给主音频。category 2 想用 PCM
     *  只能走 `101/default`，那是独立的 type。
     *
     *  **2026-11-03 追认这条规则的范围更宽**：不只是 PCM —— 车机在 `type 100` 上只容忍**一条**
     *  音频流。那天的日志（session `9829c6ba`）里 bootstrap 3→2→1 之所以全 200，只是因为前两条
     *  各自在 25 ms 内就被我们自己拆掉了；等主音频 PCM 落住之后，同一会话里
     *  `100/speechRecognition` 的 SETUP 直接 400，`100/alert` 也被顶掉（改走 `101/default` 才成）。
     *  语音因此永远起不来，而车机一旦为语音让出输入焦点就再也不还回来 —— 表现为"用完小爱之后
     *  触屏失效，只能重启车机"。所以 category 3 的 PCM 现在明确落到 `101/default`。 */
    fun selectPlayback(entries:List<AudioCapabilities.Entry>,category:Int,source:PcmFormat,
        requireDuplex:Boolean=false,supportsCapture:(Format)->Boolean={true},
        excludedRoutes:Set<Route> = emptySet(),
        preferPcm:Boolean=AudioDebugConfig.pcmMedia,
        preferPcmAlert:Boolean=AudioDebugConfig.pcmAlert,
        preferSpeechAlt:Boolean=AudioDebugConfig.speechAltPcm,
        supportsEncoded:(Format)->Boolean):Selection? {
        val choices=when(category){
            1->listOf(media,mediaLowLatency)
            2->listOf(alert,alternate)
            3->listOf(assistant,speechAlternate)
            else->throw IllegalArgumentException()
        }
        // PCM 优先：只在车机真的广告了可发的 PCM16 时生效，否则无声退回下面的压缩选择。
        // `pcmCompatibility` 用的是该路由的 outputMask，`wireSupported` 又会拒掉 type102 上的
        // PCM —— 所以 category 1 的 `media(102)` 在这里必然交出 null，自然落到
        // `mediaLowLatency(100)`，与实车 20030 出声的配置逐字节一致。
        //
        // category 3 的 PCM 只走 `101/default`，**绝不**走 `100/speechRecognition`：
        // 那会顶掉主音频的 type 100 上下文（20032 的代价）。
        val pcmRoutes=when(category){
            1->if(preferPcm)listOf(mediaLowLatency) else emptyList()
            2->if(preferPcm&&preferPcmAlert)listOf(alternate) else emptyList()
            3->if(preferSpeechAlt)listOf(speechAlternate) else emptyList()
            else->emptyList()
        }
        for(route in pcmRoutes){
            if(route !in choices)continue
            if(route in excludedRoutes)continue
            // `101/default` 是纯输出路由：本端从来给不出采集方向（A-44 的 SAP 输入密钥没有派生
            // 材料，`microphone not offered` 每次 SETUP 都会打），所以对它不要求 duplex ——
            // 这不是放松门禁，而是它本来就没有输入可以松。`assistant(100/…)` 仍然照旧要求。
            val outputOnly=route==speechAlternate
            fun pcmInputSupported(format:Format)=outputOnly||!requireDuplex||(duplexSupported(entries,route,format)&&supportsCapture(format))
            val pcm=pcmCompatibility(entries,route,source)?.takeIf(::pcmInputSupported)
            if(pcm!=null)return Selection(route,pcm)
        }
        for(route in choices){
            if(route in excludedRoutes)continue
            // Do not globally disable an advertised codec here. The firmware selects
            // from the per-route capability mask; a local encoder intersection decides
            // whether that candidate survives. Device-specific isolation belongs in an
            // explicit compatibility policy, not in the protocol selector.
            fun inputSupported(format:Format)=!requireDuplex||(duplexSupported(entries,route,format)&&supportsCapture(format))
            val candidates=playbackCandidates(entries,route,source).filter(::inputSupported)
            val encoded=candidates.firstOrNull(supportsEncoded)
            val format=encoded?:pcmCompatibility(entries,route,source)?.takeIf(::inputSupported)
            if(format!=null)return Selection(route,format)
        }
        return null
    }
    fun duplexSupported(entries:List<AudioCapabilities.Entry>,route:Route,format:Format):Boolean =
        route.type==100&&format in playbackFormats(entries,route)&&format in advertised(entries,route,capture=true)
    /** Existing local prewarm policy, retained for this field/format repair.
     * It is not a firmware guarantee of stream ordering or same-type concurrency.
     *
     * **为什么顺序是 2→1、category 3 不再预热（2026-11-03 真车）**：
     *
     * 车机在 `101/default` 上只有一个会话位。category 3 原本预热在这里，可它整场会话
     *  **发 0 个包**（心跳里恒为 `c3=0/-`，`Audio host callback category=3` 一次都没有；
     *  小爱的声音实际是从 category 1 的媒体流里出来的）。于是 `101/default` 一直被一条
     *  空流占着，导航真正要流时只能退回 `100/alert`，而 `type 100` 已被主音频占住 → 400：
     *
     *  ```
     *  12:19:38.335  Audio route refused category=2 type=100/alert status=400
     *  12:19:38.339  category=2 unavailable: Audio stream type=101/default already has a session
     *  ```
     *
     *  不预热 category 3 之后 `101/default` 归 category 2（PCM 48k/2），导航语音就有一条
     *  在会话建立阶段就已建好、中途只需要灌 PCM 的流 —— 不需要任何中途 SETUP。
     *  category 3 若真的来要流，仍会走 [selectPlayback] 的 `speechAlternate`，
     *  只是会因会话位被占而失败并留下日志，不再是"占着不用"。 */
    fun bootstrapPlayback(entries:List<AudioCapabilities.Entry>,source:PcmFormat,
        preferPcm:Boolean=AudioDebugConfig.pcmMedia,
        preferPcmAlert:Boolean=AudioDebugConfig.pcmAlert,
        preferSpeechAlt:Boolean=AudioDebugConfig.speechAltPcm,
        supportsEncoded:(Format)->Boolean):List<Selection> =
        listOf(2,1).mapNotNull{category->
            selectPlayback(entries,category,source,requireDuplex=category==3,
                preferPcm=preferPcm,preferPcmAlert=preferPcmAlert,preferSpeechAlt=preferSpeechAlt,
                supportsEncoded=supportsEncoded)
        }.distinctBy{it.route.type to it.route.purpose}
    /** 麦克风（车机采集方向）的候选序。
     *
     *  它必须与 [wirelessCandidates] 共用同一套 [wirelessCodecPreference] 优先序：麦克风原来在 AirSession 里
     *  自带一份 `ELD -> 其它` 的排序，于是"按优先序回退到 AAC-LC / OPUS"这条策略在麦克风路径上
     *  **静默失效** —— 车机若只广告 LC/OPUS，本端会判成"没有可用麦克风编码"，而同一个编码在
     *  主音频路径上本可选中。
     *
     *  差异只在两处，它们属于方向语义、不是优先序：[wirelessCodecPreference] 之外忽略 PCM16
     *  （PCM 由 [pcmCompatibility] 走显式兼容路径），并且只认 inputMask（车机采集）。 */
    fun captureCandidates(entries:List<AudioCapabilities.Entry>,source:PcmFormat):List<Format> =
        entries.filter{entry->entry.streamType==CAPTURE_STREAM_TYPE&&entry.audioType==CAPTURE_PURPOSE&&entry.inputMask!=null}
            .flatMap{entry->formats.filter{format->entry.inputMask!! and format.bit!=0L}}
            .distinct()
            .filter{it.codec!=Codec.PCM16}
            .sortedWith(compareBy<Format>(
                {codecPriority(it.codec)},
                {if(it.rate==source.rate)0 else 1},{if(it.channels==source.channels)0 else 1},{-it.rate}))
    fun capture(entries:List<AudioCapabilities.Entry>,source:PcmFormat):Format? = captureCandidates(entries,source).firstOrNull()
    /** Keep both established microphone paths: preferred compressed codecs, then audited PCM. */
    fun microphoneCandidates(entries:List<AudioCapabilities.Entry>,source:PcmFormat):List<Format> =
        captureCandidates(entries,source)+pcmCaptureCandidates(entries)
    /** 车机采集用 type100 流 + `speechRecognition` 方向（与 AirSession 原判据一致）。 */
    const val CAPTURE_STREAM_TYPE=100
    const val CAPTURE_PURPOSE="speechRecognition"
    /** Client compatibility coverage retained from v11/574d7d2: the compressed writer supports
     * Main/Alt/High raw access units. This is an implementation contract, not a claim that U2IW's
     * audited 100/101 PCM subset proves every compressed codec or type102 on every receiver. */
    fun wireSupported(route:Route,format:Format):Boolean {
        if(route !in routes||format !in formats)return false
        return when(format.codec){
            Codec.PCM16->route.type in setOf(100,101)&&format.rate%200==0
            Codec.AAC_LC,Codec.AAC_ELD,Codec.OPUS->route.type in setOf(100,101,102)
        }
    }
    /** Local category map when no peer capability exists yet; runtime uses [selectPlayback]. */
    fun implementedRouteForCategory(category:Int):Route=when(category){
        1->mediaLowLatency
        2->alert
        3->assistant
        else->throw IllegalArgumentException()
    }
    fun pcmCaptureCandidates(entries:List<AudioCapabilities.Entry>):List<Format> =
        advertised(entries,assistant,capture=true).filter{it.codec==Codec.PCM16&&wireSupported(assistant,it)}
            .sortedWith(compareBy<Format>({-it.rate},{-it.channels}))
    /** Explicit recovered PCM compatibility path, not the standard wireless codec policy. */
    fun pcmCompatibility(entries:List<AudioCapabilities.Entry>,route:Route,source:PcmFormat):Format? =
        playbackFormats(entries,route).filter{it.codec==Codec.PCM16&&wireSupported(route,it)}
            .sortedWith(compareBy<Format>({if(it.rate==source.rate)0 else 1},{if(it.channels==source.channels)0 else 1},{-it.rate})).firstOrNull()
}
