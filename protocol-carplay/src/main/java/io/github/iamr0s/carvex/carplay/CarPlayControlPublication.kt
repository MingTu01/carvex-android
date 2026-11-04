package io.github.iamr0s.carvex.carplay

import android.content.Context
import android.net.nsd.*
import android.os.Build
import io.github.iamr0s.carvex.carplay.core.*
import io.github.iamr0s.carvex.sessioncore.Options
import io.github.iamr0s.carvex.sessioncore.Resources
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Attempt/Network-owned publication, deliberately independent of an AirPlay
 * endpoint. Reused by AirSession; never creates a second listener at cutover. */
class CarPlayControlPublication(
    private val context:Context,
    private val path:NetworkPath,
    private val options:Options,
    private val current:()->Boolean,
    private val log:(String)->Unit,
    private val failure:(Throwable)->Unit,
):AutoCloseable {
    data class Invitation(val peer:InetAddress,val receiverId:ULong)
    val published=CompletableDeferred<Unit>()
    val invited=CompletableDeferred<Invitation>()
    private val closed=AtomicBoolean(false)
    private val started=AtomicBoolean(false)
    private val pending=AtomicInteger()
    private val owned=Resources()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var knownPeers:Set<InetAddress> = emptySet()

    fun candidates(addresses:Collection<InetAddress>){
        if(!closed.get()&&current())knownPeers=knownPeers+addresses
    }
    fun start(){
        check(!closed.get()&&current()){"Publication context retired"}
        if(!started.compareAndSet(false,true))return
        val locals=NetworkAddressCandidates.local(path.properties.linkAddresses.map{it.address})
        require(locals.isNotEmpty())
        pending.set(locals.size)
        for(local in locals){
            try{publish(local)}catch(t:Exception){candidateFailed(t)}
        }
    }
    private fun candidateFailed(error:Throwable){
        log("Control publication candidate failed type="+error.javaClass.simpleName)
        if(pending.decrementAndGet()==0&&!published.isCompleted)published.completeExceptionally(error)
    }
    private fun publish(local:InetAddress){
        val server=owned.own(ServerSocket())
        server.bind(InetSocketAddress(local,0))
        val nsd=context.getSystemService(NsdManager::class.java)
        val service=NsdServiceInfo().apply{
            serviceName=options.carPlayName;serviceType="_carplay-ctrl._tcp.";port=server.localPort
            setAttribute("id",options.phoneMac);setAttribute("srcvers",CarPlaySourceIdentity.sourceVersion)
            if(Build.VERSION.SDK_INT>=33)setNetwork(path.network)
        }
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo){
                if(closed.get()||!current()){
                    runCatching{nsd.unregisterService(this)};return
                }
                pending.decrementAndGet();published.complete(Unit)
                log("Control publication registered on selected network; endpoint discovery independent")
            }
            override fun onRegistrationFailed(info:NsdServiceInfo,errorCode:Int){
                if(!closed.get())candidateFailed(IOException("NSD registration failed code="+errorCode))
            }
            override fun onServiceUnregistered(info:NsdServiceInfo){}
            override fun onUnregistrationFailed(info:NsdServiceInfo,errorCode:Int){log("Control publication unregister code="+errorCode)}
        }
        // Own cleanup before invoking an asynchronous Android API.
        owned.own(CallbackRegistration(
            {nsd.registerService(service,NsdManager.PROTOCOL_DNS_SD,listener)},
            {nsd.unregisterService(listener)})).start()
        scope.launch {
            try{while(isActive&&!closed.get()){
                val socket=owned.own(server.accept())
                try{socket.use{
                    it.soTimeout=3000
                    val request=RtspWire.read(it.getInputStream())
                    val locals=path.properties.linkAddresses.map{address->
                        ControlInvitationPolicy.Local(address.address,address.prefixLength)}
                    val receiver=if(current())ControlInvitationPolicy.receiver(request,it.inetAddress,locals,knownPeers)else null
                    val valid=receiver!=null&&!closed.get()&&current()
                    RtspWire.write(it.getOutputStream(),if(valid)"HTTP/1.1 200 OK"else"HTTP/1.1 400 Bad Request",emptyMap())
                    if(valid&&!closed.get()&&current()){
                        invited.complete(Invitation(it.inetAddress,receiver))
                        // 只记录头名，不记录值。真车 2026-11-03 两次自动重连都停在这里之后：
                        // 车机不广播 _airplay 却主动敲这个端口，邀请本身可能就是端点来源。
                        // 能否走那条路取决于它是否带得出 features/pk——先看有哪些头，再决定。
                        log("Control invitation accepted as candidate; authentication and endpoint still independent"+
                            " peer="+it.inetAddress.hostAddress+" headers=["+request.headers.keys.joinToString(",")+"]")
                    }
                }}catch(t:Exception){if(isActive)log("Control invitation rejected type="+t.javaClass.simpleName)}
                finally{owned.release(socket)}
            }}catch(t:Exception){if(isActive&&!closed.get()&&current())failure(t)}
        }
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        scope.cancel();published.cancel();invited.cancel();owned.close()
    }
}
