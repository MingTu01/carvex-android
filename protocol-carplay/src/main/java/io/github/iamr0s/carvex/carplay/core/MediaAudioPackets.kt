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
     *  `spec/01-audio-streams.md` **R-1.1，级别 MUST NOT**：「禁止把 `type=100` 直接当 PT 100 用」，
     *  并且「唯一已知的二次映射是 `raw102` → `gen`(96)」（`A01.5`）。
     *  发送侧标签→PT 表见 `analysis/02-codecs-and-rtp.md` §2.4：`gen`=96、`main`=100、`alt`=101。
     *  接收准入掩码 `0x3d13`（R-1.2）里**根本没有 102**：pt ∈ {96,97,100,104,106,107,108,109}。
     *
     *  所以 raw102 必须发成 96；100/101 与标签表同名同值，保持原样。
     *
     *  这里走过两次回头路，记下来免得再走：
     *  1) 初版写成恒等映射，注释还引了一个 `X-0029` 证据编号 —— 那个编号在整个
     *     `research/` 里并不存在。后果是放音乐那条流（category=1，raw 102）线上 PT=102，
     *     落在接收准入掩码之外，车机在**入口处就丢包**，表现是包在发、日志全绿、全程无声。
     *  2) `b1cd2b2` 改对了，又被 `4ca82a6` 撤回。撤回理由是「接收端按 PT 字节选 AAD 长度，
     *     发 96 会让 MAC 失败」—— 那条只对 **AEAD（ChaCha20-Poly1305）**成立，依据是
     *     `CarPlaySourceIdentityTest.receiverOpen` 里 Toyota libAirPlay 的审计模型。
     *     而 MFi/SAP 路线是 **AES-CTR**（06-frame-crypto-aes R-6.1：等长、无 tag、无 AAD），
     *     明文诊断更是完全没有密码学，"MAC 失败"在这两条路上无从发生。
     *     同一次撤回的另一条依据"音频和画面互斥"也已被 `660d2ba` 证伪。
     *     两次撤回都没有真车证据，只有 PT=102 被反复实测为无声。
     *
     *  [wirePtOverride] 是真机逃生阀：只有实车证实这台接收端确实要 raw PT 时才传，
     *  用来做一次 A/B。默认走规范映射。 */
    fun wirePayloadType(type:Int,wirePtOverride:Int?=null):Int=wirePtOverride?:if(type==102)96 else type
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

/** MFi/SAP 音频发送侧的密码上下文。
 *
 *  [cipher] 非空 = 走 AES-CTR（06-frame-crypto-aes R-6.1，等长、无 tag、计数器跨包连续）。
 *  [cipher] 为空 = **诊断用的明文直发**：12 字节头照旧，payload 原样送出。
 *
 *  为什么需要这个空档：SETUP 被接受只证明车机愿意收这条流，不证明它能解开。
 *  接收端固件在 `stream+0xa0` 没有密码器时是**直接放行明文并返回成功**的
 *  （AirPlaySender 0x2519c0acc → 返回 0），所以"车机没给音频装解密器、我们却发了密文"
 *  是完全可能的状态，而它在日志上表现为一切正常、只是没声音。
 *  真机上一旦确认改用明文能出声，就说明线上格式该按明文定稿，而不是继续调 CTR。 */
class SapAudioCrypto(private val cipher:Cipher?) {
    val plaintext:Boolean get()=cipher==null
    fun apply(payload:ByteArray):ByteArray=cipher?.update(payload)?:payload
}
