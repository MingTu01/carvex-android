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
    fun packet(pcm16be:ByteArray,key:ByteArray,sequence:Int,timestamp:Long,nonce:Long,type:Int=100):ByteArray {
        require(pcm16be.size%2==0)
        return accessUnit(pcm16be,key,sequence,timestamp,nonce,type)
    }
    /** 线上 PT 由流的内部 OSType 推出，不是 iAP2 的 raw 类型：gen(102)→96、main(100)→100、alt(101)→101。 */
    /** 线上 PT 就是流类型本身。102（`gen `/MainHighAudio）在固件的 FourCC→PT 表里没有映射（`X-0029`），
     *  Toyota 的 PT→RED 表里 102 那一格是 0（不做 RED）。早先映射成 96/97 是我们自造的值。 */
    private fun wireType(type:Int)=type
    fun accessUnit(payload:ByteArray,key:ByteArray,sequence:Int,timestamp:Long,nonce:Long,type:Int):ByteArray {
        require(type in setOf(100,101,102))
        require(key.size==32&&sequence in 0..65535&&nonce>=0&&payload.isNotEmpty()&&payload.size<=1400)
        val pt=wireType(type)
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
    fun sapPacket(payload:ByteArray,crypto:SapAudioCrypto,sequence:Int,timestamp:Long,type:Int):ByteArray {
        require(type in setOf(100,101,102))
        require(sequence in 0..65535&&payload.isNotEmpty()&&payload.size<=1400)
        val pt=wireType(type)
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
                  sequence:Int,timestamp:Long,nonce:Long,type:Int):ByteArray {
        require(type in setOf(100,101,102))
        require(key.size==32&&sequence in 0..65535&&nonce>=0&&payload.isNotEmpty()&&payload.size<=1400)
        val primaryType=wireType(type)
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
