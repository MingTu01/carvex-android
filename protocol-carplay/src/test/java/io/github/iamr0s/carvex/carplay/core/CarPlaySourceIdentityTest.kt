package io.github.iamr0s.carvex.carplay.core

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.*
import org.junit.Test

class CarPlaySourceIdentityTest {
    private val key=ByteArray(32){it.toByte()}

    private fun serializedIdentity(model:String="Xiaomi test device",version:String="16")=
        Plist.dictionary(Plist.encode(CarPlaySourceIdentity.setupDeviceFields(model,version)))

    /** Test-only model of the audited receiver decision, not a generic Apple version parser.
     * Toyota libAirPlay SHA256 ecc9213489e4705b65ecae3869d6a701392023f1f934a9c325005bf51be62370:
     * 0x17d20 parses %d%c%d; an invalid second operand returns +1.
     * 0x1af84 uses Compare("13A1", build) < 0; equality still selects legacy AAD.
     * The anchored prefix also accepts Android builds beginning with digits, so the
     * regression covers a parsable-but-old build as well as CP2A's parse failure. */
    private fun modernMainAlt(build:String):Boolean {
        val match=Regex("^([0-9]+)([^0-9])([0-9]+)").find(build)?:return false
        val major=match.groupValues[1].toInt()
        val letter=match.groupValues[2].single().uppercaseChar()
        val minor=match.groupValues[3].toInt()
        return major>13||(major==13&&(letter>'A'||(letter=='A'&&minor>1)))
    }

    private fun receiverOpen(packet:ByteArray,build:String,payloadStart:Int=12):ByteArray {
        val type=packet[1].toInt() and 127
        // Audited gen/102 path uses 8-byte AAD independently of osBuildVersion.
        val aadStart=if(type==102||modernMainAlt(build))4 else 0
        return Crypto.open(key,ByteArray(4)+packet.takeLast(8).toByteArray(),
            packet.copyOfRange(payloadStart,packet.size-8),packet.copyOfRange(aadStart,12))
    }

    @Test fun setupKeepsActualHardwareAndAndroidVersionButDeclaresTheWireProfile(){
        for((model,version) in listOf("Xiaomi test device" to "16","Other Android" to "14")){
            assertEquals(mapOf("model" to model,"osName" to "Android","osVersion" to version,
                "osBuildVersion" to "21A329"),serializedIdentity(model,version))
        }
        assertEquals("320.17",CarPlaySourceIdentity.sourceVersion)
        assertEquals("AirPlay/320.17",CarPlaySourceIdentity.userAgent)
    }

    @Test fun controlProtocolDeclarationIsIndependentOfSourceAndOsBuildVersions(){
        assertEquals("X-Apple-ProtocolVersion",CarPlayControlProfile.protocolVersionHeader)
        assertEquals("1",CarPlayControlProfile.protocolVersion)
        assertEquals("320.17",CarPlaySourceIdentity.sourceVersion)
        assertEquals("21A329",CarPlaySourceIdentity.compatibilityOsBuildVersion)
        assertFalse(serializedIdentity().containsKey(CarPlayControlProfile.protocolVersionHeader))
    }

    @Test fun declaredBuildSelectsModernAadWithTheStrictReceiverThreshold(){
        for(build in listOf("CP2A.260605.016","UP1A.231005.007","12A1","13A1","")){
            assertFalse(build,modernMainAlt(build))
        }
        assertTrue(modernMainAlt("13A2"))
        assertTrue(modernMainAlt(serializedIdentity()["osBuildVersion"] as String))
    }

    @Test fun actualMainAltPacketsFailWithTheAndroidBuildAndAuthenticateWithTheDeclaredProfile(){
        val build=serializedIdentity()["osBuildVersion"] as String
        val pcm=MediaAudioPackets.networkPcm(byteArrayOf(0x34,0x12,0xfe.toByte(),0xff.toByte()))
        val au=byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte())
        for(type in listOf(100,101)){
            val packets=listOf(
                pcm to MediaAudioPackets.packet(pcm,key,17,240,3,type),
                au to MediaAudioPackets.accessUnit(au,key,18,360,4,type))
            for((payload,packet) in packets){
                assertThrows(InvalidCipherTextException::class.java){receiverOpen(packet,"CP2A.260605.016")}
                assertArrayEquals(payload,receiverOpen(packet,build))
            }
        }
    }

    @Test fun negotiatedRedUsesTheSameIdentityForPrimaryOnlyAndHistoryPackets(){
        val build=serializedIdentity()["osBuildVersion"] as String
        val current=byteArrayOf(3,4);val history=byteArrayOf(1,2)
        for(type in listOf(100,101)){
            val first=MediaAudioPackets.redPacket(current,null,null,key,17,240,3,type)
            val next=MediaAudioPackets.redPacket(current,history,120,key,18,240,4,type)
            assertArrayEquals(current,receiverOpen(first,build,13))
            assertArrayEquals(history+current,receiverOpen(next,build,17))
            assertThrows(InvalidCipherTextException::class.java){receiverOpen(first,"CP2A.260605.016",13)}
            assertThrows(InvalidCipherTextException::class.java){receiverOpen(next,"CP2A.260605.016",17)}
        }
    }

    @Test fun mediaPacketRemainsValidUnderBothOldAndNewDeclarations(){
        val payload=byteArrayOf(0x21,0x10,0x56)
        // 这条测的是 receiverOpen 那个 **Toyota AEAD 模型**「按 PT 字节选 AAD 长度」的性质，
        // 不是我们的线上 PT 映射。模型看到 PT=102 时恒用 8 字节 AAD，所以两种 build 声明都能开。
        // 我们自己发 raw102 时线上 PT 是 100（见 MediaAudioPackets.wirePayloadType / rtp_timing A03.1），
        // 这里用 wirePtOverride 显式钉住 102，才能测到模型那条分支。
        val packet=MediaAudioPackets.accessUnit(payload,key,19,1024,5,102,wirePtOverride=102)
        assertArrayEquals(payload,receiverOpen(packet,"CP2A.260605.016"))
        assertArrayEquals(payload,receiverOpen(packet,serializedIdentity()["osBuildVersion"] as String))
    }
}
