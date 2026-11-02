package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
class MediaAudioPacketsTest {
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun redDelta(packet:ByteArray)=((packet[13].toInt() and 255) shl 6) or
        ((packet[14].toInt() and 255) ushr 2)
    private fun redLength(packet:ByteArray)=((packet[14].toInt() and 3) shl 8) or
        (packet[15].toInt() and 255)
    private fun openRed(packet:ByteArray,headerSize:Int,nonce:Long)=Crypto.open(ByteArray(32),
        Crypto.nonce(nonce),packet.copyOfRange(12+headerSize,packet.size-8),packet.copyOfRange(4,12))
    private fun assertPrimaryOnly(history:ByteArray?,historyTimestamp:Long?,timestamp:Long){
        val current=byteArrayOf(4,5)
        val packet=MediaAudioPackets.redPacket(current,history,historyTimestamp,ByteArray(32),7,timestamp,9,101)
        assertEquals(105,packet[1].toInt() and 127)
        assertEquals(101,packet[12].toInt() and 255)
        assertEquals(12+1+current.size+24,packet.size)
        assertArrayEquals(current,openRed(packet,1,9))
    }
    @Test fun headerAndExplicitNonceAreIndependentOfVideoCounter(){
        val key=ByteArray(32){it.toByte()}
        val pcm=MediaAudioPackets.networkPcm(byteArrayOf(0x34,0x12,0xfe.toByte(),0xff.toByte()))
        assertArrayEquals(byteArrayOf(0x12,0x34,0xff.toByte(),0xfe.toByte()),pcm)
        val packet=MediaAudioPackets.packet(pcm,key,65535,0xffffffffL,7)
        assertArrayEquals(byteArrayOf(0x80.toByte(),100,0xff.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),0,0,0,0),packet.copyOfRange(0,12))
        assertArrayEquals(byteArrayOf(7,0,0,0,0,0,0,0),packet.takeLast(8).toByteArray())
        assertArrayEquals(pcm,Crypto.open(key,Crypto.nonce(7),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
        packet[4]=(packet[4].toInt() xor 1).toByte()
        assertThrows(org.bouncycastle.crypto.InvalidCipherTextException::class.java){Crypto.open(key,Crypto.nonce(7),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12))}
    }
    @Test fun alternateAudioHasIndependentPayloadType(){
        val packet=MediaAudioPackets.packet(byteArrayOf(0,1),ByteArray(32),12,34,56,101)
        assertEquals(101,packet[1].toInt() and 127)
        assertArrayEquals(byteArrayOf(0,1),Crypto.open(ByteArray(32),Crypto.nonce(56),packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
        assertThrows(IllegalArgumentException::class.java){MediaAudioPackets.packet(byteArrayOf(0,0),ByteArray(32),0,0,0,106)}
        // raw102 的线上 PT 是 100（main），不是 102，也不是 96（gen）。spec/01 R-1.1
        // 级别 MUST NOT：SETUP 的 raw stream type 与 RTP 的 PT 是两套编号。
        // 依据是发送侧 FourCC→PT 硬编码表 `_carAudioStream_getRTPPayloadType`
        // （rtp_timing analysis/03 A03.1 CONFIRMED）：'main'→100，表里没有 gen；
        // 未命中返回入参本身（A03.2）⇒ 发送侧不存在产出 PT 96 的正规路径。
        // 102 不在接收准入掩码 0x3d13（R-1.2）里，发 102 会被车机在入口处丢掉。
        assertEquals(100,MediaAudioPackets.packet(byteArrayOf(0,0),ByteArray(32),0,0,0,102)[1].toInt() and 127)
        // main(100) 与标签表同名同值，保持不变。
        assertEquals(100,MediaAudioPackets.packet(byteArrayOf(0,0),ByteArray(32),0,0,0,100)[1].toInt() and 127)
        // 逃生阀：显式覆盖时按调用方给的 PT 发，只为真机 A/B 保留。
        assertEquals(102,MediaAudioPackets.packet(byteArrayOf(0,0),ByteArray(32),0,0,0,102,wirePtOverride=102)[1].toInt() and 127)
        // SAP 路线走同一个映射。
        val sap=MediaAudioPackets.sapPacket(byteArrayOf(0,0),SapAudioCrypto.plain(),0,0,102)
        assertEquals(100,sap[1].toInt() and 127)
    }
    @Test fun malformedPcmAndSequenceCannotBeSent(){
        assertThrows(IllegalArgumentException::class.java){MediaAudioPackets.networkPcm(byteArrayOf(1))}
        assertThrows(IllegalArgumentException::class.java){MediaAudioPackets.packet(byteArrayOf(0,0),ByteArray(32),65536,0,0)}
    }
    @Test fun redPacketUsesPlaintextHeaderAndSealsHistoryWithCurrentBlock(){
        val key=ByteArray(32){it.toByte()}
        val history=byteArrayOf(1,2,3)
        val current=byteArrayOf(4,5)
        val packet=MediaAudioPackets.redPacket(current,history,1000L,key,7,1002L,9,100)
        assertEquals(104,packet[1].toInt() and 127)
        // 5 字节 RED 头：F=1|primaryPT, delta14=2, len10=3, 末尾 primaryPT
        assertEquals(0x80 or 100,packet[12].toInt() and 0xff)
        assertEquals(0,packet[13].toInt() and 0xff)
        assertEquals(8,packet[14].toInt() and 0xff)
        assertEquals(3,packet[15].toInt() and 0xff)
        assertEquals(100,packet[16].toInt() and 0xff)
        val clear=Crypto.open(key,Crypto.nonce(9),packet.copyOfRange(17,packet.size-8),packet.copyOfRange(4,12))
        assertArrayEquals(history+current,clear)
        val noHistory=MediaAudioPackets.redPacket(current,null,0L,key,8,10L,10,100)
        assertEquals(104,noHistory[1].toInt() and 127)
        assertEquals(100,noHistory[12].toInt() and 0xff)
        assertArrayEquals(current,Crypto.open(key,Crypto.nonce(10),noHistory.copyOfRange(13,noHistory.size-8),noHistory.copyOfRange(4,12)))
    }
    @Test fun validAlternateRedPacketsRetainExistingCiphertextFixtures(){
        // Existing independent C sender fixtures: synthetic key/sequence/clock, not a live capture.
        val key=ByteArray(32){it.toByte()}
        val first=byteArrayOf(0x11,0,0xff.toByte())
        assertArrayEquals(hex("8069FFFF000003E8000000006509B8BD4A5D89A6FB55235CA21F6240887A635D0000000000000000"),
            MediaAudioPackets.redPacket(first,null,0L,key,65535,1000,0,101))
        assertArrayEquals(hex("80690000000003F200000000E5002803658E570C7D86DC60C231A5DDA83F10967FE6B636F99D0100000000000000"),
            MediaAudioPackets.redPacket(byteArrayOf(0x22,0x33),first,1000L,key,0,1010,1,101))
    }
    @Test fun redHistoryAgeAndLengthKeepTheirLargestRepresentableValues(){
        val history=ByteArray(1023){it.toByte()}
        val current=byteArrayOf(7)
        val packet=MediaAudioPackets.redPacket(current,history,20L,ByteArray(32),8,20+16383L,9,101)
        assertEquals(16383,redDelta(packet));assertEquals(1023,redLength(packet))
        assertArrayEquals(history+current,openRed(packet,5,9))
    }
    @Test fun overAgeHistoryIsOmittedInsteadOfWrappingItsTimestamp(){
        assertPrimaryOnly(byteArrayOf(1),0L,16384L)
        // Observed 48 kHz navigation shape: 20 ms packet plus an 800 ms source pause.
        assertPrimaryOnly(byteArrayOf(1,2,3),100000L,139360L)
        assertPrimaryOnly(byteArrayOf(1),0L,0x100000000L+960)
        assertPrimaryOnly(byteArrayOf(1),0L,Long.MAX_VALUE)
    }
    @Test fun missingEmptyOrOversizedHistoryStillProducesNegotiatedPrimaryOnlyRed(){
        assertPrimaryOnly(null,null,960L)
        assertPrimaryOnly(byteArrayOf(),0L,960L)
        assertPrimaryOnly(byteArrayOf(1),null,960L)
        assertPrimaryOnly(ByteArray(1024),0L,960L)
        assertPrimaryOnly(ByteArray(1400),0L,960L)
    }
    @Test fun backwardsOrInvalidHistoryTimesAreNotMaskedIntoRecentAudio(){
        assertPrimaryOnly(byteArrayOf(1),1001L,1000L)
        assertPrimaryOnly(byteArrayOf(1),-1L,960L)
        assertPrimaryOnly(byteArrayOf(1),1000L,-1L)
        assertPrimaryOnly(byteArrayOf(1),Long.MAX_VALUE,0L)
    }
    @Test fun oneWireTimestampWrapAndUnwrappedClockUseTheSameSmallPositiveAge(){
        val previous=0xfffffff0L
        for(timestamp in listOf(0x10L,0x100000010L)){
            val packet=MediaAudioPackets.redPacket(byteArrayOf(2),byteArrayOf(1),previous,
                ByteArray(32),7,timestamp,9,101)
            assertEquals(32,redDelta(packet));assertEquals(1,redLength(packet))
            assertArrayEquals(byteArrayOf(1,2),openRed(packet,5,9))
        }
        val zero=MediaAudioPackets.redPacket(byteArrayOf(2),byteArrayOf(1),10L,ByteArray(32),7,10,9,101)
        assertEquals(0,redDelta(zero))
    }
    /** MFi/SAP 的流密钥：与 AccessoryAuth.Mfi.streamCipher 同一公式
     *  （SHA512("AirPlayStreamKey"‖dec(id)‖K)[0:16]，04-kdf / A-46）。 */
    private fun sapCipher(id:Long,material:ByteArray,mode:Int)=javax.crypto.Cipher
        .getInstance("AES/CTR/NoPadding").apply{
            fun derive(label:String)=Crypto.hash((label+id).toByteArray(Charsets.US_ASCII),material).copyOf(16)
            init(mode,javax.crypto.spec.SecretKeySpec(derive("AirPlayStreamKey"),"AES"),
                javax.crypto.spec.IvParameterSpec(derive("AirPlayStreamIV")))
        }
    @Test fun sapPacketKeepsTheHomeKitHeaderAndAddsNoTagOrNonceTail(){
        val material=ByteArray(16){it.toByte()}
        val payload=byteArrayOf(1,2,3,4)
        val sap=MediaAudioPackets.sapPacket(payload,
            SapAudioCrypto.ctr(sapCipher(7,material,javax.crypto.Cipher.ENCRYPT_MODE)),9,0x11223344L,100)
        val hap=MediaAudioPackets.packet(payload,ByteArray(32),9,0x11223344L,0,100)
        // 两条路线共用同一套头：换的只是密码学，不是封装。
        assertArrayEquals(hap.copyOfRange(0,12),sap.copyOfRange(0,12))
        assertEquals(100,sap[1].toInt() and 127)
        // CTR 等长：12B 头 + 原长正文，没有 16B tag，也没有 8B nonce 尾。
        assertEquals(12+payload.size,sap.size)
        assertEquals(12+payload.size+16+8,hap.size)
    }
    @Test fun sapPacketRunsOneContinuousCounterAcrossPackets(){
        val material=ByteArray(16){it.toByte()}
        val sender=SapAudioCrypto.ctr(sapCipher(7,material,javax.crypto.Cipher.ENCRYPT_MODE))
        val same=byteArrayOf(9,9,9,9)
        val first=MediaAudioPackets.sapPacket(same,sender,1,100L,100)
        val second=MediaAudioPackets.sapPacket(same,sender,2,101L,100)
        // 同明文、不同包 → 密文必须不同，说明计数器在推进而不是每包重置。
        assertFalse(first.copyOfRange(12,first.size).contentEquals(second.copyOfRange(12,second.size)))
        // 接收端用同一个上下文顺序解，两包都要还原。CTR 的“连续”是双向约定。
        val receiver=sapCipher(7,material,javax.crypto.Cipher.DECRYPT_MODE)
        assertArrayEquals(same,receiver.update(first.copyOfRange(12,first.size)))
        assertArrayEquals(same,receiver.update(second.copyOfRange(12,second.size)))
    }
    @Test fun sapPacketRejectsUnknownTypesAndOversizedPayload(){
        val cipher=SapAudioCrypto.ctr(sapCipher(7,ByteArray(16),javax.crypto.Cipher.ENCRYPT_MODE))
        assertThrows(IllegalArgumentException::class.java){
            MediaAudioPackets.sapPacket(byteArrayOf(1,2),cipher,0,0,106)}
        assertThrows(IllegalArgumentException::class.java){
            MediaAudioPackets.sapPacket(ByteArray(1401),cipher,0,0,100)}
        assertThrows(IllegalArgumentException::class.java){
            MediaAudioPackets.sapPacket(byteArrayOf(),cipher,0,0,100)}
    }
    @Test fun plaintextSapPacketKeepsTheHeaderAndCopiesThePayloadVerbatim(){
        // 诊断档：cipher 为 null 时不加密，但 12 字节头必须一模一样。
        // 依据是接收端固件在 stream+0xa0 没有密码器时直接放行明文（0x2519c0acc → 返回 0），
        // 所以"头不变、正文原样"是一个真实可能被车机接受的状态，而不是随便漏掉加密。
        val payload=byteArrayOf(1,2,3,4,5,6)
        val plain=MediaAudioPackets.sapPacket(payload,SapAudioCrypto.plain(),9,0x11223344L,100)
        val encrypted=MediaAudioPackets.sapPacket(payload,
            SapAudioCrypto.ctr(sapCipher(7,ByteArray(16){it.toByte()},javax.crypto.Cipher.ENCRYPT_MODE)),9,0x11223344L,100)
        assertArrayEquals(encrypted.copyOfRange(0,12),plain.copyOfRange(0,12))
        assertArrayEquals(payload,plain.copyOfRange(12,plain.size))
        assertEquals(12+payload.size,plain.size)
        assertTrue(SapAudioCrypto.plain().plaintext)
        assertFalse(SapAudioCrypto.ctr(sapCipher(7,ByteArray(16),javax.crypto.Cipher.ENCRYPT_MODE)).plaintext)
    }

    /** 参照加密：AES/CBC/NoPadding，一次整块，用来核对 CBC-frame 的输出确实就是它。 */
    private fun cbcEncrypt(key:ByteArray,iv:ByteArray,block:ByteArray)=javax.crypto.Cipher
        .getInstance("AES/CBC/NoPadding").apply{
            init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"),
                javax.crypto.spec.IvParameterSpec(iv))}.doFinal(block)

    /** A-40 / spec06 R-6.3：CBC-frame 每次 Update 用**保存的初始 IV** 重置，
     *  不链接上一包的末密文块。所以同 key/iv/明文 → 同密文，与 CTR 的"必须不同"正好相反。 */
    @Test fun cbcFrameResetsIvPerPacketInsteadOfChaining(){
        val key=ByteArray(16){it.toByte()};val iv=ByteArray(16){(it+3).toByte()}
        val sender=SapAudioCrypto.cbcFrame(key,iv)
        val same=byteArrayOf(9,8,7,6,5,4,3,2,1,0,1,2,3,4,5,6)   // 16B = 恰好一块
        val first=MediaAudioPackets.sapPacket(same,sender,1,100L,102)
        val second=MediaAudioPackets.sapPacket(same,sender,2,101L,102)
        assertArrayEquals(first.copyOfRange(12,first.size),second.copyOfRange(12,second.size))
        // 与 CTR 对照：CTR 的计数器跨包推进，同样两包必须不同（见上面的连续计数器测试）。
        val ctr=SapAudioCrypto.ctr(sapCipher(7,ByteArray(16){it.toByte()},javax.crypto.Cipher.ENCRYPT_MODE))
        val c1=MediaAudioPackets.sapPacket(same,ctr,1,100L,102)
        val c2=MediaAudioPackets.sapPacket(same,ctr,2,101L,102)
        assertFalse(c1.copyOfRange(12,c1.size).contentEquals(c2.copyOfRange(12,c2.size)))
        // 输出必须就是同 key/iv 的一次 AES-CBC。
        assertArrayEquals(cbcEncrypt(key,iv,same),first.copyOfRange(12,first.size))
        // raw102 的线上 PT 仍然走 R-1.1 的映射（main=100），与帧密码无关。
        assertEquals(100,first[1].toInt() and 127)
    }

    /** A-40 第 1/4 步：`full=len&~15`，只加密整块前缀，尾部 `len%16` 原样直通。 */
    @Test fun cbcFrameEncryptsOnlyWholeBlocksAndPassesTheTailThrough(){
        val key=ByteArray(16){it.toByte()};val iv=ByteArray(16){(it*3).toByte()}
        val sender=SapAudioCrypto.cbcFrame(key,iv)
        val payload=ByteArray(20){(it*7+1).toByte()}              // 16 + 4
        val packet=MediaAudioPackets.sapPacket(payload,sender,1,0L,102)
        // 等长：没有 tag、没有 padding 块、没有 nonce 尾。
        assertEquals(12+payload.size,packet.size)
        val body=packet.copyOfRange(12,packet.size)
        // 整块前缀 = 一次 AES-CBC；尾部 4 字节逐字节相同。
        assertArrayEquals(cbcEncrypt(key,iv,payload.copyOfRange(0,16)),body.copyOfRange(0,16))
        assertArrayEquals(payload.copyOfRange(16,20),body.copyOfRange(16,20))
    }

    /** A-40：长度 <16 时 `full=0`，整段直通，不加密也不报错。 */
    @Test fun cbcFrameShortPayloadsPassThroughUntouched(){
        val key=ByteArray(16){it.toByte()};val iv=ByteArray(16){it.toByte()}
        val sender=SapAudioCrypto.cbcFrame(key,iv)
        for(size in 1..15){
            val payload=ByteArray(size){(it+1).toByte()}
            val packet=MediaAudioPackets.sapPacket(payload,sender,1,0L,102)
            assertEquals(12+size,packet.size)
            assertArrayEquals(payload,packet.copyOfRange(12,packet.size))
        }
    }

    /** A-42 / R-6.5：key/iv 必须恰好 16B，不提供 AES192/256。 */
    @Test fun cbcFrameRejectsWrongKeyOrIvSizes(){
        assertThrows(IllegalArgumentException::class.java){SapAudioCrypto.cbcFrame(ByteArray(32),ByteArray(16))}
        assertThrows(IllegalArgumentException::class.java){SapAudioCrypto.cbcFrame(ByteArray(16),ByteArray(24))}
        assertThrows(IllegalArgumentException::class.java){SapAudioCrypto.cbcFrame(ByteArray(0),ByteArray(16))}
    }

    /** ADTS 帧封装：7 字节头 + raw AU；同步字 0xFFF1、帧长 = 7 + AU 大小。 */
    @Test fun adtsFrameWrapsRawAuWithCorrectHeader(){
        val rawAu=ByteArray(100){(it+1).toByte()}
        val adts=MediaAudioPackets.adtsFrame(rawAu,48000,2)
        assertEquals(107,adts.size)
        // 同步字 + MPEG-4 + layer 0 + protection_absent=1
        assertEquals(0xFF,adts[0].toInt() and 0xFF)
        assertEquals(0xF1,adts[1].toInt() and 0xFF)
        // AAC-LC(profile=1), 48kHz(idx=3), 2ch → byte2 = 0b01_0011_00 = 0x4C
        assertEquals(0x4C,adts[2].toInt() and 0xFF)
        // 帧长字段验证: (byte3&3)<<11 | byte4<<3 | byte5>>5 = 107
        val frameLen=((adts[3].toInt() and 3) shl 11) or ((adts[4].toInt() and 0xFF) shl 3) or ((adts[5].toInt() and 0xFF) shr 5)
        assertEquals(107,frameLen)
        assertArrayEquals(rawAu,adts.copyOfRange(7,adts.size))
    }

    /** ADTS 头对不同采样率和声道数做正确映射。 */
    @Test fun adtsFrameMapsSampleRateAndChannels(){
        val rawAu=ByteArray(50)
        // 44100Hz(idx=4), mono(ch=1) → byte2 = 0b01_0100_00 = 0x50
        val mono=MediaAudioPackets.adtsFrame(rawAu,44100,1)
        assertEquals(0x50,mono[2].toInt() and 0xFF)
        // 16000Hz(idx=8), stereo(ch=2) → byte2 = 0b01_1000_00 = 0x60
        val lowRate=MediaAudioPackets.adtsFrame(rawAu,16000,2)
        assertEquals(0x60,lowRate[2].toInt() and 0xFF)
    }
}
