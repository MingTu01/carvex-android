package io.github.iamr0s.carvex.carplay

import io.github.iamr0s.carvex.carplay.core.*

/** Successful source-Link trust is a separate gate from candidate AirPlay routing. */
internal fun WifiBootstrap.requireAuthenticatedBootstrap(experimentalIntegrityOnly:Boolean=false) {
    if (authentication?.accepted(experimentalIntegrityOnly) != true) SecurityRequirements.requireAaTrust()
}

/** The production caller passes the binding saved before endpoint discovery. */
fun WifiBootstrap.securityInputs(
    expectedBinding: SecurityBinding, metadata: ReceiverMetadata,
    networkReady: Boolean, endpointValidated: Boolean, pairedCapabilityGate: Boolean? = null,
    experimentalIntegrityOnly: Boolean = false,
    sourceContextCurrent: Boolean = true,
    sapFallbackAfterGate: Boolean = false,
): SecurityInputs {
    if(!sourceContextCurrent)securityFailure(SecurityReason.STALE_CONTEXT,phase="bootstrap-context")
    val source = authentication ?: securityFailure(SecurityReason.SECURITY_INPUT_MISSING,phase="bootstrap-context")
    val endpoint = endpoint ?: securityFailure(SecurityReason.SECURITY_INPUT_MISSING,phase="endpoint-resolution")
    val binding = expectedBinding.copy(generation=source.generation,epoch=source.epoch)
    // The user's explicit v11 experiment permits a bounded HAP attempt after scoped
    // discovery too. This local profile is not a peer capability or production trust.
    val gate=pairedCapabilityGate ?: ExperimentalHapDiscovery.gate(this,metadata,expectedBinding,
        networkReady,endpointValidated,experimentalIntegrityOnly)
    return SecurityInputs(binding, expectedBinding=expectedBinding, controlVersion=source.controlVersion,
        bootstrapTransport=bootstrapTransport, endpointSource=endpoint.source,
        startReady=startSessionReady, bootstrapAuthenticationAccepted=source.accepted(experimentalIntegrityOnly),
        networkReady=networkReady, endpointValidated=endpointValidated,
        features=metadata.effectiveFeatures, pairedCapabilityGate=gate,
        peerHintPresent=metadata.pairingId?.isNotEmpty()==true || startSessionReady,
        setupProviderBound=false, featureEvidence=metadata.effectiveFeatureEvidence,
        sapFallbackAfterGate=sapFallbackAfterGate)
}

/** A candidate only: setup/verify, historical key pins and live epoch gates remain mandatory. */
internal object ExperimentalHapDiscovery {
    /** 最后一次评估的结果标签。只用于诊断：`gate` 返回 null 时调用方原本只能看到
     *  SECURITY_POLICY_UNRESOLVED，分不清是缺 `pk`、缺 `pi` 还是地址族不一致。不含任何身份或 TXT 原文。 */
    @Volatile var lastDecision:String="not_evaluated"

    fun gate(config:WifiBootstrap,metadata:ReceiverMetadata,binding:SecurityBinding,
             networkReady:Boolean,endpointValidated:Boolean,enabled:Boolean):Boolean? {
        fun no(reason:String):Boolean?{lastDecision=reason;return null}
        if(!enabled)return no("disabled")
        if(config.startSessionReady)return no("start_ready")
        if(config.bootstrapTransport!=SecurityTransport.BLUETOOTH_WIFI)return no("transport")
        if(config.endpointSource!=SecurityEntry.DISCOVERY)return no("source")
        if(!networkReady)return no("network_not_ready")
        if(!endpointValidated)return no("endpoint_not_validated")
        val auth=config.authentication?:return no("auth_missing")
        if(auth.generation!=binding.generation||auth.epoch!=binding.epoch)return no("auth_context_stale")
        if(!auth.challengeSignatureVerified)return no("auth_unsigned")
        if(metadata.effectiveFeatureEvidence.has(38)!=true)return no("no_f38")
        val pi=metadata.pairingId?:return no("pi_missing")
        if(pi.isBlank())return no("pi_blank")
        if(pi.toByteArray(Charsets.UTF_8).size !in 1..1024)return no("pi_size")
        if(metadata.publicKey==null)return no("pk_missing")
        if(metadata.publicKey.size!=32)return no("pk_size_"+metadata.publicKey.size)
        val endpoint=config.endpoint?:return no("endpoint_missing")
        if(metadata.endpoint==null)return no("bonjour_endpoint_missing")
        if(metadata.port!=endpoint.port)return no("port_mismatch_"+metadata.port+"_vs_"+endpoint.port)
        val sameAddress=runCatching{metadata.endpoint.address.contentEquals(IpLiteral.parse(endpoint.address).address.address)}.getOrDefault(false)
        if(!sameAddress)return no("address_mismatch bonjour="+metadata.endpoint.hostAddress+" iap2="+endpoint.address)
        val device=metadata.deviceId?:return no("device_id_missing")
        val samePeer=runCatching{ReceiverIdentity.canonical(device)==ReceiverIdentity.canonical(endpoint.receiver)}.getOrDefault(false)
        if(!samePeer)return no("peer_mismatch")
        lastDecision="accepted"
        return true
    }
}
