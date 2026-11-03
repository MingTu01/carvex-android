package io.github.iamr0s.carvex.carplay.core

import io.github.iamr0s.carvex.pcm.PcmFormat
import org.junit.Test

/** 诊断用：打印真车 entries 下的选路结果，定位 cat3 落到 100/speechRecognition 的原因。 */
class AudioSelectionDiagnosticTest {
    private val entries=listOf(
        AudioCapabilities.Entry(100,"compatibility",0x54L,0x8054L),
        AudioCapabilities.Entry(101,"compatibility",null,0x8000L),
        AudioCapabilities.Entry(100,"alert",null,0x2008000L),
        AudioCapabilities.Entry(100,"default",0xc000050L,0xc000050L),
        AudioCapabilities.Entry(100,"media",null,0x8000L),
        AudioCapabilities.Entry(100,"telephony",0xc000054L,0xc000054L),
        AudioCapabilities.Entry(100,"speechRecognition",0x8000040L,0x8000040L),
        AudioCapabilities.Entry(101,"default",null,0x2008000L),
        AudioCapabilities.Entry(102,"media",null,0x800000L)
    )
    @Test fun dumpSelections(){
        fun desc(f:CarPlayAudioPlan.Format)="${f.codec}/${f.rate}/${f.channels}/f${f.framesPerPacket}"
        for(source in listOf(PcmFormat(48000,2),PcmFormat(16000,1),PcmFormat(24000,1))){
            println("=== source="+source.rate+"/"+source.channels+" ===")
            for(category in listOf(1,2,3)){
                val sel=CarPlayAudioPlan.selectPlayback(entries,category,source,
                    requireDuplex=category==3,supportsCapture={true}){true}
                println("  cat"+category+" -> "+(sel?.let{"${it.route.type}/${it.route.purpose} "+desc(it.format)}?:"null"))
            }
            println("  -- pcmCompatibility per route --")
            for(route in listOf(CarPlayAudioPlan.mediaLowLatency,CarPlayAudioPlan.alert,CarPlayAudioPlan.alternate,
                CarPlayAudioPlan.assistant,CarPlayAudioPlan.speechAlternate)){
                val pcm=CarPlayAudioPlan.pcmCompatibility(entries,route,source)
                println("    ${route.type}/${route.purpose} -> "+(pcm?.let{desc(it)}?:"null"))
            }
        }
        println("=== bootstrap (default flags) ===")
        val plan=CarPlayAudioPlan.bootstrapPlayback(entries,PcmFormat(48000,2)){true}
        plan.forEach{println("  cat="+it.route.category+" ${it.route.type}/${it.route.purpose} "+desc(it.format))}
    }
}
