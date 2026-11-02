package io.github.iamr0s.carvex.carplay.core
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

/** Recovered HomeKit MainAudio PCM profile; not all AirPlay audio transports. */
object MediaAudioPackets {
    fun networkPcm(pcm16le:ByteArray):ByteArray {
        require(pcm16le.size%2==0)
        return pcm16le.copyOf().also{for(i in it.indices step 2){val first=it[i];it[i]=it[i+1];it[i+1]=first}}
    }
    fun packet(pcm16be:ByteArray,key:ByteArray,sequence:Int,timestamp:Long,nonce:Long,type:Int=100,
               wirePtOverride:Int?=null):ByteArray {
        require(pcm16be.size%2==0)
        return accessUnit(pcm16be,key,sequence,timestamp,nonce,type,wirePtOverride)
    }
    /** 线上 PT 不是 SETUP 的 raw stream type，两套编号不能互换。
     *
     *  `spec/01-audio-streams.md` **R-1.1，级别 MUST NOT**：「禁止把 `type=100` 直接当 PT 100 用」；
     *  `type=102` 更是从来就不是合法 PT —— 接收准入掩码 `0x3d13`（R-1.2）里没有 102，
     *  发 102 会在车机入口处直接丢包。
     *
     *  **raw102 的线上 PT 是 100（`main`），不是 96（`gen`）**。硬证据是发送侧的
     *  FourCC→PT 硬编码映射 `_carAudioStream_getRTPPayloadType`（`0x2519c1d5c`，
     *  `airplay_rtp_timing/analysis/03` **A03.1 CONFIRMED**）：`'main'→100`、`'alt '→101`、
     *  `'mred'→104`、`'ared'→105`、`'auxo'→106`、`'oovc'→107`、`'axrd'→108`、`'ovrd'→109`。
     *  **表里没有 `gen`/`gred`**；未命中的四字符码记错误日志并把入参本身当返回值
     *  （A03.2），也就是说发送侧根本不存在产出 PT 96 的正规路径。`main` 是
     *  「主音频（媒体音与麦克风都走它）」（analysis/01 PT 表），媒体音（music）走它。
     *
     *  PT 96 `gen` 只出现在**接收侧**名字表（通用/screen 侧也用；R-2.7 的接收准入是
     *  「条件（RED 时）」）。唯一的固定 0x60 出处在**转码调用链**（analysis/11 **A11.6**），
     *  而 A11.6 明文写了「不推广为 CarPlay 当前必用链路」。
     *
     *  旧映射 `raw102→gen(96)`（A01.5）是 INFERRED，引证 RL-02 在整个 `research/` 里
     *  不存在；实车又两次全哑（20022 CTR、20023 CBC-frame），已废除。这里走过三次
     *  回头路，记下来免得再走：
     *  1) 初版写成恒等映射（PT=102），注释还引了一个不存在的 `X-0029`。后果是包在
     *     入口处就被准入掩码丢掉：包在发、日志全绿、全程无声，连明文诊断也无声。
     *  2) `b1cd2b2`/`52732e4` 改成 96，依据是 A01.5/RL-02 的 INFERRED；实车 20022、
     *     20023 两次全哑（CTR 与 CBC-frame 各一次）。车机还回过重传请求（diag20022），
     *     说明包进了 RTP 会话、序号被解析，但没有走通到播放 —— 与「PT 被分派到
     *     `gen` 管线、没进 `main` 播放管线」一致。
     *  3) `4ca82a6` 撤回 PT 改动时的两条理由（「接收端按 PT 字节选 AAD 长度」只对
     *     AEAD/ChaCha 成立；「音频和画面互斥」已被 `660d2ba` 证伪）都没有真车证据。
     *
     *  [wirePtOverride] 是真机逃生阀：只做一次 A/B 时传，不走规范映射。默认走 100。 */
    fun wirePayloadType(type:Int,wirePtOverride:Int?=null):Int=wirePtOverride?:if(type==102)100 else type
    /** ADTS 帧封装：把 raw AAC AU 包上 7 字节 ADTS 头（无 CRC）。
     *
     *  MediaCodec 默认输出 raw AAC AU（不含 ADTS 头），但 CarPlay 车机端可能期望
     *  ADTS 封装（HomeKit 的 raw-AU 假设未经 CarPlay 实证）。如果车机的 AAC 解码器
     *  期待 ADTS 同步字（0xFFF1）而收到裸 AU，解码直接失败、全程无声。
     *
     *  ADTS 头格式（7 字节，protection_absent=1 即无 CRC）：
     *  ```
     *  Byte 0: 0xFF          sync word 高 8 位
     *  Byte 1: 0xF1          sync 低 4 位 | ID=0(MPEG-4) | layer=00 | protection=1
     *  Byte 2: profile(2) | sfreq_idx(4) | private(1) | ch_cfg 高 1 位
     *  Byte 3: ch_cfg 低 2 位 | orig(1) | home(1) | copybits(2) | framelen 高 2 位
     *  Byte 4: framelen 中 8 位
     *  Byte 5: framelen 低 3 位 | fullness 高 5 位
     *  Byte 6: fullness 低 6 位 | num_blocks(2)=0
     *  ```
     *
     *  @param rawAu MediaCodec 输出的 raw AAC access unit
     *  @param rate 采样率（用于查 ADTS sampling_frequency_index 表）
     *  @param channels 声道数（1 或 2）
     *  @param profile AAC profile：1=AAC-LC（默认），2=HE-AAC(v1)，39=AAC-ELD
     */
    fun adtsFrame(rawAu:ByteArray,rate:Int,channels:Int,profile:Int=1):ByteArray {
        require(rawAu.isNotEmpty()){"Empty AAC AU cannot be ADTS-framed"}
        val sfreqIdx=when(rate){
            96000->0;88200->1;64000->2;48000->3;44100->4;32000->5;24000->6
            22050->7;16000->8;12000->9;11025->10;8000->11;7350->12
            else->error("Unsupported ADTS sample rate: "+rate)
        }
        require(channels in 1..2){"ADTS channel config supports 1-2, got "+channels}
        val frameLen=7+rawAu.size
        require(frameLen<=0x1FFF){"ADTS frame too long: "+frameLen}
        val h=ByteArray(7)
        h[0]=0xFF.toByte()
        h[1]=0xF1.toByte() // MPEG-4, layer 0, protection_absent=1
        h[2]=(((profile and 3) shl 6) or ((sfreqIdx and 0xF) shl 2) or ((channels shr 2) and 1)).toByte()
        h[3]=(((channels and 3) shl 6) or ((frameLen shr 11) and 3)).toByte()
        h[4]=((frameLen shr 3) and 0xFF).toByte()
        h[5]=(((frameLen and 7) shl 5) or 0x1F).toByte() // buffer fullness=0x7FF (VBR)
        h[6]=0xFC.toByte() // buffer fullness 低 6 位=0x3F, num_raw_blocks=0
        return h+rawAu
    }
    fun accessUnit(payload:ByteArray,key:ByteArray,sequence:Int,timestamp:Long,nonce:Long,type:Int,
                   wirePtOverride:Int?=null):ByteArray {
        require(type in setOf(100,101,102))
        require(key.size==32&&sequence in 0..65535&&nonce>=0&&payload.isNotEmpty()&&payload.size<=1400)
        val pt=wirePayloadType(type,wirePtOverride)
        require(pt in 0..127){"Wire payload type out of range: "+pt}
        val header=ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(pt.toByte())
            .putShort(sequence.toShort()).putInt(timestamp.toInt()).putInt(0).array()
        val iv=Crypto.nonce(nonce)
        return header+Crypto.seal(key,iv,payload,header.copyOfRange(4,12))+iv.copyOfRange(4,12)
    }
    /** MFi/SAP 音频记录：同一套头部 + AES-CTR，替换掉 HomeKit 的 ChaCha20-Poly1305。
     *
     *  头部逐字节沿用上面 [accessUnit] 的形状：`0x80` + PT + 10 字节音频消息
     *  （seq BE16 / timestamp BE32 / 第二字段 BE32，见音频 spec R-2.6）。密文只覆盖 payload。
     *  差别只有密码学：CTR 等长、没有 16 字节 tag、也没有尾部 8 字节 nonce
     *  （06-frame-crypto-aes R-6.1）。
     *
     *  边界要说清：MFi 音频的线上 profile 没有抓包证据（本 docset 的 U-7 / C10 SKIP），
     *  这里只做了“头部不变、密码学换成 MFi 流密钥”这一处最小改动，没有新增头部字段，
     *  也没有假定 tag/nonce 存在。真机不接受时，第一步该看的不是公式，而是这里的选择。
     */
    fun sapPacket(payload:ByteArray,crypto:SapAudioCrypto,sequence:Int,timestamp:Long,type:Int,
                  wirePtOverride:Int?=null):ByteArray {
        require(type in setOf(100,101,102))
        require(sequence in 0..65535&&payload.isNotEmpty()&&payload.size<=1400)
        val pt=wirePayloadType(type,wirePtOverride)
        require(pt in 0..127){"Wire payload type out of range: "+pt}
        val header=ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(pt.toByte())
            .putShort(sequence.toShort()).putInt(timestamp.toInt()).putInt(0).array()
        // 计数器由调用方持有的同一个 Cipher 推进：跨包连续，不在这里重置。
        return header+crypto.apply(payload)
    }
    /** RED 外层 PT：main→104、alt→105、gen→97（ch08 §3.3）。 */
    /** RED 后的 PT：只有 100/101/106 有映射（`X-0030`；Toyota 表 {104,105,—,—,—,—,108,109} 一致）。 */
    fun redType(type:Int)=when(type){100->104;101->105;106->108
        else->error("stream type "+type+" has no RED payload type")}
    /** Do not disguise a source gap as a recent historical block by truncating its age.
     * Accept unwrapped, nonnegative sample clocks, or a single wrap of two 32-bit wire
     * timestamps. Extended clocks retain their full difference: a gap of 2^32 samples
     * must not become zero. A small backwards step is not a wrap and has no history. */
    private fun redHistoryDelta(timestamp:Long,previous:Long):Int? {
        if(timestamp<0||previous<0)return null
        val direct=timestamp-previous
        val delta=if(direct<0&&timestamp<=0xffffffffL&&previous<=0xffffffffL)
            direct and 0xffffffffL else direct
        return delta.takeIf{it in 0..0x3fffL}?.toInt()
    }
    /** RED 封包：头是明文，历史块+当前块整体加密一次（ch08 §3.3）。 */
    fun redPacket(payload:ByteArray,history:ByteArray?,historyTimestamp:Long?,key:ByteArray,
                  sequence:Int,timestamp:Long,nonce:Long,type:Int,wirePtOverride:Int?=null):ByteArray {
        require(type in setOf(100,101,102))
        require(key.size==32&&sequence in 0..65535&&nonce>=0&&payload.isNotEmpty()&&payload.size<=1400)
        val primaryType=wirePayloadType(type,wirePtOverride)
        val red=redType(type)
        val header=ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(0x80.toByte()).put(red.toByte())
            .putShort(sequence.toShort()).putInt(timestamp.toInt()).putInt(0).array()
        val redHeader:ByteArray
        val clear:ByteArray
        val delta=historyTimestamp?.let{redHistoryDelta(timestamp,it)}
        // RED is still negotiated when history is unusable. Keep its primary-only
        // header/PT and encrypt just this access unit; never truncate a length or age.
        if(history!=null&&history.size in 1..0x3ff&&delta!=null){
            val word=((0x80 or primaryType) shl 24) or (delta shl 10) or history.size
            redHeader=byteArrayOf((word ushr 24).toByte(),(word ushr 16).toByte(),
                (word ushr 8).toByte(),word.toByte(),primaryType.toByte())
            clear=history+payload
        }else{
            redHeader=byteArrayOf(primaryType.toByte())
            clear=payload
        }
        val iv=Crypto.nonce(nonce)
        return header+redHeader+Crypto.seal(key,iv,clear,header.copyOfRange(4,12))+iv.copyOfRange(4,12)
    }
}

/** MFi/SAP 音频发送侧的帧密码上下文。
 *
 *  帧密码**不是全局一种**，由 raw stream type 在 G=0 下的分派表决定。
 *  证据：`research/protocol/airplay_fairplay_crypto/analysis/14-cryptor-gates-and-output-slots.md`
 *  **A-44**（`0x2519bca6c..bd120`，36 个原指令锚点，C14 `verify_cryptor_dispatch.py`）：
 *
 *  | type | G=0 | G≠0 |
 *  |---|---|---|
 *  | 96/102/103 | **A=CBCFrame** | A=ChaCha(flag=1) |
 *  | 100/101/106/107 | 不建密码器 | ChaCha |
 *  | 110/111（屏幕） | **A=CTR** | A=ChaCha(flag=0) |
 *
 *  实车对照已经把这张表验证了一半：视频是 MainScreen/AltScreen（110/111），走 CTR
 *  就出画面；媒体音是 MainHighAudio（102），**G=0 下该走 CBCFrame**，此前用 CTR
 *  表现为包在发、日志全绿、全程无声。G≠0（CoreUtils 配对）对应 HomeKit/HAP 的
 *  ChaCha 路线，本类不覆盖。
 *
 *  G=0 由实车反证：视频用 CTR 通了，而 A-44 里 110/111 只在 G=0 给 CTR。
 *  密钥材料与视频完全同源（`Mfi.streamFrameKeyIv` = A-46 的 Sender Legacy KDF），
 *  换的只有帧密码。
 *
 *  - [Mode.CTR]：128 位大端计数器跨包连续（A-41）。
 *  - [Mode.CBC_FRAME]：每次 Update 用**同一个初始 IV** 重置、整块 CBC、尾部直通
 *    （A-40 / spec06 R-6.3）。不是链接上一包的末密文块，也不是 PKCS#7。
 *  - [Mode.PLAINTEXT]：诊断档，12 字节头照旧、正文原样。依据是接收端在 stream+0xa0
 *    没有密码器时直接放行明文并返回成功（AirPlaySender `0x2519c0acc` → 0），
 *    所以"我们发密文、车机按明文解"是可能状态，日志上完全无声无息。
 */
class SapAudioCrypto private constructor(val mode:Mode,private val ctr:Cipher?,
    private val cbcKey:ByteArray?,private val cbcIv:ByteArray?) {
    enum class Mode{PLAINTEXT,CTR,CBC_FRAME}
    val plaintext:Boolean get()=mode==Mode.PLAINTEXT
    /** 诊断串，用于把 A/B 的自变量钉在日志上。 */
    val modeName:String get()=when(mode){Mode.PLAINTEXT->"PLAINTEXT";Mode.CTR->"AES-CTR";Mode.CBC_FRAME->"AES-CBC-frame"}
    private var cbc:Cipher?=null
    fun apply(payload:ByteArray):ByteArray=when(mode){
        Mode.PLAINTEXT->payload
        Mode.CTR->requireNotNull(ctr).update(payload)
        Mode.CBC_FRAME->cbcFrame(payload)
    }
    /** AES-CBC-frame（A-40）：`full=len&~15`，只加密整块前缀，尾部 `len%16` 原样直通。
     *  长度 <16 时 `full=0`，整段直通。每次调用都用保存的初始 IV 重置，不链接上一调用。 */
    private fun cbcFrame(payload:ByteArray):ByteArray{
        val full=payload.size and 0x7ffffff0
        if(full==0)return payload.copyOf()
        val c=cbc?:Cipher.getInstance("AES/CBC/NoPadding").also{cbc=it}
        // init() 就是 A-40 第 2 步的 CCCryptorReset(ctx, 保存的初始 IV)：
        // 同一 key/IV 下相同输入得到相同输出，不把上一包的末密文块链进来。
        c.init(Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(requireNotNull(cbcKey),"AES"),
            javax.crypto.spec.IvParameterSpec(requireNotNull(cbcIv)))
        val head=c.doFinal(payload,0,full)
        check(head.size==full){"AES-CBC-frame produced ${head.size} bytes for full="+full}
        return head+payload.copyOfRange(full,payload.size)
    }
    companion object{
        fun plain()=SapAudioCrypto(Mode.PLAINTEXT,null,null,null)
        fun ctr(cipher:Cipher)=SapAudioCrypto(Mode.CTR,cipher,null,null)
        /** AES-CBC-frame：key/iv 各 16B（A-42 固定 128 位，R-6.5）。 */
        fun cbcFrame(key:ByteArray,iv:ByteArray):SapAudioCrypto{
            require(key.size==16&&iv.size==16){"CBC-frame requires a 16-byte key and a 16-byte IV"}
            return SapAudioCrypto(Mode.CBC_FRAME,null,key.copyOf(),iv.copyOf())
        }
    }
}
