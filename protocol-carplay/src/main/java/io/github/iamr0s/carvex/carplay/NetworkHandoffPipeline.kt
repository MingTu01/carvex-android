package io.github.iamr0s.carvex.carplay

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.net.SocketTimeoutException

/** The actual ordering used by the Android runner, with injectable platform
 * operations for offline ordering/cancellation tests. Caller's Resources owns
 * the network and publication for the entire attempt, not just this function. */
class NetworkHandoffPipeline<N,P>(
    private val handoff:IapNetworkHandoff,
    private val join:suspend(WifiBootstrap)->N,
    private val publish:(N)->P,
    private val discover:suspend(N,WifiBootstrap)->WifiBootstrap,
    private val endpointWaitMs:Long=25_000,
) {
    data class Ready<N,P>(val network:N,val publication:P,val bootstrap:WifiBootstrap)

    suspend fun open(initial:WifiBootstrap):Ready<N,P> = coroutineScope {
        handoff.requireCurrent()
        val network=join(initial)
        ensureActive();handoff.requireCurrent()
        // MUST precede discovery and any wait for a late 4301.
        val publication=publish(network)
        handoff.requireCurrent()
        val now=requireNotNull(handoff.configuration())
        val selected=if(now.endpoint!=null)now else withTimeoutOrNull(endpointWaitMs){
            val late=async{handoff.awaitEndpoint()}
            // A discovery failure must not cancel the pending declared4301 route.
            val browse=async{
                try{Result.success(discover(network,now))}
                catch(t:TimeoutCancellationException){
                    // A resolver's own deadline is a failed candidate, not parent
                    // cancellation. The declared late4301 route remains live.
                    currentCoroutineContext().ensureActive()
                    Result.failure(t)
                }
                catch(t:CancellationException){throw t}
                catch(t:SecurityException){throw t}
                catch(t:Exception){Result.failure(t)}
            }
            try {
                select<WifiBootstrap> {
                    late.onAwait{it}
                    browse.onAwait{result->result.getOrNull()?:
                        if(handoff.canAwaitStart())late.await()else result.getOrThrow()}
                }
            }
            catch(t:TimeoutCancellationException){
                // 走到这里的是**发现器自己的**截止时间（ReceiverDiscovery.await 的 15 秒），
                // 不是父协程取消。它必须变成一条普通失败：TimeoutCancellationException 属于
                // CancellationException，一旦原样上抛，上层重试循环会把它读成"整条链被放弃"
                // 而静默收工（真车 2026-11-03：端点发现超时后一次都没重试过）。
                currentCoroutineContext().ensureActive()
                throw SocketTimeoutException(
                    "Bonjour endpoint deadline: no receiver candidate within the discovery window").also{it.initCause(t)}
            }
            finally {late.cancel();browse.cancel()}
        }?:throw SocketTimeoutException("Network handoff endpoint deadline; no usable4301/Bonjour candidate")
        ensureActive();handoff.requireCurrent()
        // Prefer a complete current-link4301 that arrived while discovery resolved.
        val current=handoff.configuration()
        Ready(network,publication,handoff.commit(current?.takeIf{it.endpoint!=null}?:selected))
    }
}
