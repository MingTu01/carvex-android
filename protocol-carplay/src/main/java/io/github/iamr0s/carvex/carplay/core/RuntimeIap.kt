package io.github.iamr0s.carvex.carplay.core
import io.github.iamr0s.carvex.sessioncore.AcceptedIdentification
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

/** Nonblocking producer; stream consumer may read any fragment boundary. */
internal class BoundedByteInput(private val capacity:Int=128*1024):InputStream(){
    private val bytes=ByteArray(capacity)
    private val lock=Object()
    private var head=0
    private var count=0
    private var closed=false
    init{require(capacity>0)}
    fun offer(data:ByteArray):Boolean=synchronized(lock){
        if(closed||data.isEmpty()||data.size>capacity-count)return false
        val tail=(head+count)%capacity
        val first=minOf(data.size,capacity-tail)
        data.copyInto(bytes,tail,0,first)
        if(first<data.size)data.copyInto(bytes,0,first,data.size)
        count+=data.size;lock.notifyAll();true
    }
    override fun read():Int=synchronized(lock){
        while(count==0&&!closed)lock.wait()
        if(closed)return -1
        val value=bytes[head].toInt() and 255
        head=(head+1)%capacity;count--;value
    }
    override fun read(b:ByteArray,off:Int,len:Int):Int=synchronized(lock){
        require(off>=0&&len>=0&&off<=b.size-len)
        if(len==0)return 0
        while(count==0&&!closed)lock.wait()
        if(closed)return -1
        val n=minOf(count,len)
        val first=minOf(n,capacity-head)
        bytes.copyInto(b,off,head,head+first)
        if(first<n)bytes.copyInto(b,off+first,0,n-first)
        head=(head+n)%capacity;count-=n;n
    }
    override fun close(){synchronized(lock){closed=true;count=0;bytes.fill(0);lock.notifyAll()}}
}

enum class RuntimeIapState { WAITING_DATA,SYNCHRONIZING,LINK_READY,AUTHENTICATING,CONTROL_READY,DEGRADED,CLOSED }

/** Link synchronization proves transport only; no Bluetooth handoff or business readiness claim. */
class RuntimeIap(private val emit:(ByteArray)->Boolean,private val log:(String)->Unit,
    private val stateChanged:(RuntimeIapState)->Unit,
    private val message:(IapCommand)->Unit={log("Unsupported runtime iAP command "+it.id.toString(16))},
    private val negotiate:((IapLink)->AcceptedIdentification)?=null,
    private val identificationAccepted:(AcceptedIdentification)->Unit={}):AutoCloseable {
    private val input=BoundedByteInput()
    private val closed=AtomicBoolean(false)
    private val degraded=AtomicBoolean(false)
    private val started=AtomicBoolean(false)
    @Volatile private var state=RuntimeIapState.WAITING_DATA
    @Volatile private var worker:Thread?=null
    @Volatile private var authorizedGeneration=-1
    @Volatile private var authorizedEpoch=-1L
    @Volatile private var identification:AcceptedIdentification?=null
    private val output=object:OutputStream(){
        override fun write(b:Int)=write(byteArrayOf(b.toByte()))
        override fun write(b:ByteArray,off:Int,len:Int){
            require(off>=0&&len>=0&&off<=b.size-len)
            if(len==0)return
            if(closed.get()||degraded.get())throw IOException("Runtime iAP closed")
            require(len in 1..65535)
            if(!emit(b.copyOfRange(off,off+len)))throw IOException("Runtime iAP outbound queue full or closed")
        }
    }
    private val link=IapLink(input,output,log,::fail)
    @Synchronized private fun transition(next:RuntimeIapState){
        if(closed.get()&&next!=RuntimeIapState.CLOSED)return
        if(degraded.get()&&next!=RuntimeIapState.DEGRADED&&next!=RuntimeIapState.CLOSED)return
        state=next;stateChanged(next)
    }
    fun accept(data:ByteArray):Boolean {
        if(closed.get()||degraded.get())return false
        if(data.size !in 1..65535||!input.offer(data)){
            fail(IOException("Runtime iAP inbound size/backpressure limit"));return false
        }
        start()
        return !closed.get()&&!degraded.get()
    }
    /** 不等车机先送数据就启动链路（type=130 专用流用）。
     *  注意本实现仍按 device 角色等对端 SYN，不会自己构造 `0x80`；对端若一直不说话，
     *  由 [IapLink] 的首字节/NO_SYN 兜底在 2+5 s 内判失败，而不是永久阻塞。
     *  `detectOnIdle=false` 用于专用承载：那里不该由 Detect 探路，只要等不到 SYN 就判死。 */
    fun start(detectOnIdle:Boolean=true) {
        if(closed.get()||degraded.get())return
        if(!started.compareAndSet(false,true))return
        transition(RuntimeIapState.SYNCHRONIZING)
        worker=Thread({
            try{
                link.start(allowDirectSyn=true,detectOnIdle=detectOnIdle)
                // 车机可以在链路中途重开 IDPS。真车 2026-11-05 22:29:38 就是刚重启完，
                // 发来一条 26 字节的控制消息要求重新识别配件：IapLink 会自己重跑 SYN、把 epoch
                // 推上去，我们的授权随之作废。**正确的反应是重走一遍识别**，而不是把整条 iAP 判死。
                // 这里以前是一句裸 `check(linkEpoch==authorizedEpoch)`，于是日志里只剩
                // "IllegalStateException Check failed."——谁也看不出是 epoch 变了。
                var reidentifications=0
                while(!closed.get()&&!degraded.get()){
                    transition(RuntimeIapState.LINK_READY)
                    if(negotiate!=null){
                        transition(RuntimeIapState.AUTHENTICATING)
                        val generation=link.generation;val epoch=link.linkEpoch
                        val accepted=negotiate.invoke(link)
                        try{
                            synchronized(this){
                                check(!closed.get()&&!degraded.get()){"Runtime iAP closed during identification"}
                                check(link.generation==generation&&link.linkEpoch==epoch&&link.negotiatedControlSession!=null){
                                    "iAP link moved during identification: generation "+generation+"->"+link.generation+
                                        ", epoch "+epoch+"->"+link.linkEpoch+
                                        ", controlSession="+(link.negotiatedControlSession!=null)}
                                check(accepted.generation==generation&&accepted.epoch==epoch){
                                    "identification answered on a stale link: answered generation "+accepted.generation+
                                        "/epoch "+accepted.epoch+", expected "+generation+"/"+epoch}
                                identification=accepted
                                authorizedGeneration=generation;authorizedEpoch=epoch
                                identificationAccepted(accepted)
                                transition(RuntimeIapState.CONTROL_READY)
                            }
                        }catch(t:Exception){accepted.invalidate();throw t}
                    }
                    log("Runtime iAP link synchronized; business handoff NOT verified; retain Bluetooth")
                    // 内层只做一件事：收命令，直到链路被车机重开（世代或 epoch 变了）。
                    // 没有 negotiate 的模式（诊断/直通）不存在"授权失效"，照旧一直收，
                    // 否则 authorized* 永远是 -1，内层一次都进不去。
                    val tracksEpoch=negotiate!=null
                    while(!closed.get()&&!degraded.get()&&
                        (!tracksEpoch||(link.generation==authorizedGeneration&&link.linkEpoch==authorizedEpoch))){
                        val command=link.poll()
                        if(tracksEpoch&&(link.generation!=authorizedGeneration||link.linkEpoch!=authorizedEpoch))break
                        if(command!=null){
                            val consumed=identification?.handle(command,link.generation,link.linkEpoch,log)==true
                            if(!consumed&&!closed.get()&&!degraded.get())message(command)
                        }
                    }
                    if(closed.get()||degraded.get()||!tracksEpoch)break
                    reidentifications++
                    log("Runtime iAP link moved under us (generation "+authorizedGeneration+"->"+link.generation+
                        ", epoch "+authorizedEpoch+"->"+link.linkEpoch+
                        "); the car re-opened IDPS, re-identifying (attempt "+reidentifications+
                        " of "+MAX_REIDENTIFICATIONS+")")
                    if(reidentifications>MAX_REIDENTIFICATIONS)throw IllegalStateException(
                        "the car re-opened IDPS "+reidentifications+" times; giving up instead of looping")
                    awaitResynchronization()
                }
            }catch(t:Exception){if(!closed.get())fail(t)}
        },"runtime-iap").apply{isDaemon=true;start()}
    }
    /** 车机重开 IDPS 之后，IapLink 在它自己的读线程里重跑 SYN 交换；识别必须等它公布新的控制会话。
     *  真车那次 8 毫秒就完成了，但不能假设——抢在前面发 CSM 只会撞上
     *  "iAP link synchronization changed during send"，把一次可恢复的重开变成真失败。 */
    private fun awaitResynchronization(){
        val deadline=System.nanoTime()+RESYNC_WAIT_MS*1_000_000L
        while(!closed.get()&&!degraded.get()&&link.negotiatedControlSession==null&&System.nanoTime()<deadline){
            try{Thread.sleep(20)}catch(t:InterruptedException){Thread.currentThread().interrupt();return}
        }
        check(link.negotiatedControlSession!=null){
            "iAP link did not re-synchronize within "+RESYNC_WAIT_MS+"ms after the car re-opened IDPS"}
    }
    /** 运行期提交一条控制会话消息。固件同样是提交即返回；ACK/重传失败
     *  由后台发送队列回调到 [fail]，不会阻塞事件/业务线程。 */
    fun send(id:Int,fields:List<Pair<Int,ByteArray>>):Boolean {
        if(closed.get()||degraded.get()||(state!=RuntimeIapState.CONTROL_READY&&(negotiate!=null||state!=RuntimeIapState.LINK_READY)))return false
        // 车机正在重开 IDPS 时 epoch 会变，这一条业务提交就该被丢掉：车机刚把链路重开，
        // 它自己的状态也全没了，补发没有意义。以前这里是裸 check，异常落到外层 catch 的
        // fail(t) 上——一次时序抖动就能把整条 iAP 判死，比丢掉一条 now-playing 严重得多。
        if(negotiate!=null&&(link.generation!=authorizedGeneration||link.linkEpoch!=authorizedEpoch||
            link.negotiatedControlSession==null))return false
        return try{
            link.sendAsync(id,fields,expectedGeneration=if(negotiate!=null)authorizedGeneration else null){t->
                if(t!=null)fail(t)
            }
        }catch(t:Throwable){fail(t);false}
    }
    fun fail(t:Throwable){
        if(closed.get()||!degraded.compareAndSet(false,true))return
        log("Runtime iAP degraded: "+t.javaClass.simpleName+" "+(t.message?:"")+"; retain Bluetooth/video")
        clearIdentification()
        transition(RuntimeIapState.DEGRADED)
        link.close();input.close();worker?.interrupt()
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        clearIdentification()
        link.close();input.close();worker?.interrupt();transition(RuntimeIapState.CLOSED)
    }
    private fun clearIdentification(){
        val accepted=synchronized(this){
            val current=identification
            identification=null
            authorizedGeneration=-1;authorizedEpoch=-1
            current
        }
        accepted?.invalidate()
    }
}

/** 同一条 iAP 链路上最多重走几次识别。车机那边在 [IapLink] 的 `resyncCount<maxResynchronization`
 *  里只有 3 次预算，所以这个上限只需要兜住"我们这边死循环"这一种情况。 */
private const val MAX_REIDENTIFICATIONS=4
/** 车机重开 IDPS 之后，等它重新公布控制会话的上限。真车那次 8 毫秒就完成，3 秒是留给它忙的时候。 */
private const val RESYNC_WAIT_MS=3_000L
