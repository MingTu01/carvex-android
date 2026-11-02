package io.github.iamr0s.carvex.carplay.core
/** E18 local contracts. These reasons are not HTTP/wire status values. */
enum class SecurityReason {
    STALE_CONTEXT, PEER_BINDING_CONFLICT, CONTROL_SCHEMA_UNSUPPORTED, SECURITY_PROFILE_UNSUPPORTED, ENDPOINT_SOURCE_UNSUPPORTED,
    SECURITY_INPUT_MISSING, SECURITY_INPUT_MALFORMED, SECURITY_POLICY_UNRESOLVED, PAIRING_MECHANISM_UNSUPPORTED, PAIR_IDENTIFIER_MISSING, SETUP_INPUT_UNAVAILABLE,
    PAIR_STORE_IO_ERROR, PAIR_STORE_CORRUPT, PAIR_QUARANTINED, LOCAL_IDENTITY_LOST,
    PAIR_PEER_AUTH_REJECTED, PAIR_PEER_ERROR, PAIR_BACKOFF, PAIR_STATE_INVALID,
    PAIR_SIGNATURE_INVALID, PAIR_PROOF_INVALID, PAIR_TAG_INVALID, AIRPLAY_REJECTED, TRANSACTION_MISMATCH,
    AA_TRUST_MATERIAL_UNAVAILABLE, SAP_SIGNATURE_SCHEMA_UNSUPPORTED, SAP_TRUST_MATERIAL_UNAVAILABLE,
    TRUST_BUNDLE_AUTH_INVALID, TRUST_BUNDLE_ROLLBACK, TRUST_PURPOSE_UNSUPPORTED,
    CERT_LEAF_AMBIGUOUS, CERT_CHAIN_INVALID, CERT_CRITICAL_EXTENSION_UNSUPPORTED,
    AA_SIGNATURE_INVALID, SAP_SIGNATURE_INVALID, CERT_EXTENSION_INVALID, TRUST_TIME_UNAVAILABLE, CERT_TIME_INVALID, CERT_REVOKED
}

data class SecurityDiagnostic(
    val layer: String, val phase: String, val localReason: SecurityReason,
    val wireStatus: Int? = null, val pairingError: Int? = null, val sourceStatus: Int? = null,
) {
    override fun toString() = "layer=$layer phase=$phase wireStatus=$wireStatus pairingError=$pairingError sourceStatus=$sourceStatus localReason=${localReason.name}"
}
class SecurityFailure(val diagnostic: SecurityDiagnostic, val retryAfterSeconds: Long? = null, cause: Throwable? = null) :
    IllegalArgumentException(diagnostic.toString(), cause)
fun securityFailure(reason: SecurityReason, layer: String = "AirPlay", phase: String = "selection"): Nothing =
    throw SecurityFailure(SecurityDiagnostic(layer, phase, reason))

enum class SecurityEntry { IAP_START, DISCOVERY, UNKNOWN }
enum class SecurityTransport { USB_BOOTSTRAP_NCM, BLUETOOTH_WIFI, DIRECT_DISCOVERY }
enum class PairRecordStatus { ABSENT, ACTIVE, IO_ERROR, CORRUPT, QUARANTINED, OWNER_LOST, PENDING_SAP }
enum class SecurityRoute { CONTINUE_VERIFY, PAIRED, SAP, WAIT_START_PARAMETERS }
enum class SecurityAction { CONTINUE_VERIFY, PAIR_SETUP, PAIR_VERIFY, AUTH_SETUP, WAIT_START_PARAMETERS }
data class SecurityBinding(val generation: Int, val peer: String, val epoch: Long)
data class SecurityInputs(
    val binding: SecurityBinding, val expectedBinding: SecurityBinding = binding,
    val verifyContext: SecurityBinding? = null,
    val identityConflict: Boolean = false, val controlVersion: Int = 2,
    val endpointSource: SecurityEntry = SecurityEntry.IAP_START,
    val bootstrapTransport: SecurityTransport = SecurityTransport.BLUETOOTH_WIFI,
    val startReady: Boolean = false, val bootstrapAuthenticationAccepted: Boolean = false,
    val networkReady: Boolean = false, val endpointValidated: Boolean = false,
    val features: ULong? = null, val pairedCapabilityGate: Boolean? = null,
    val peerHintPresent: Boolean = true, val record: PairRecordStatus = PairRecordStatus.ABSENT,
    val setupProviderBound: Boolean = false,
    val featureEvidence: AirPlayFeatureEvidence? = null,
    /** Off by default. When the receiver advertises MFi/SAP (`AES-128 MFi SAP v1`, feature 26) but
     *  withholds the TXT `pk` that the CU gate requires, an experimental caller may fall back to the
     *  `/auth-setup` route instead of failing as SECURITY_POLICY_UNRESOLVED. It never relaxes any
     *  other gate and never applies outside the discovery entry. */
    val sapFallbackAfterGate: Boolean = false,
)

/** Route is selected before record lookup; records never choose SAP vs paired. */
object SecurityRouter {
    fun route(i: SecurityInputs): SecurityRoute {
        if (i.binding != i.expectedBinding || (i.verifyContext != null && i.verifyContext != i.binding))
            securityFailure(SecurityReason.STALE_CONTEXT)
        if (i.identityConflict) securityFailure(SecurityReason.PEER_BINDING_CONFLICT)
        if (i.controlVersion !in 1..2) securityFailure(SecurityReason.CONTROL_SCHEMA_UNSUPPORTED)
        if (i.verifyContext != null) return SecurityRoute.CONTINUE_VERIFY
        val paired = if (i.startReady) {
            if (!i.bootstrapAuthenticationAccepted || !i.networkReady || !i.endpointValidated)
                securityFailure(SecurityReason.SECURITY_INPUT_MISSING)
            // 4301 readiness is not evidence that a newly advertised AuthenticationType
            // may be ignored. Only the live bound verify-context continuation above bypasses
            // fresh selection; a missing TXT observation is still allowed on this entry.
            if (i.featureEvidence?.malformed == true)
                securityFailure(SecurityReason.SECURITY_INPUT_MALFORMED)
            if (AirPlaySecurityCapabilities(i.features, i.featureEvidence).mutualMfiAuthentication == true)
                securityFailure(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED)
            true
        } else if (i.endpointSource == SecurityEntry.IAP_START) return SecurityRoute.WAIT_START_PARAMETERS
        else {
            if(i.endpointSource!=SecurityEntry.DISCOVERY)securityFailure(SecurityReason.ENDPOINT_SOURCE_UNSUPPORTED)
            val evidence = i.featureEvidence ?: AirPlayFeatureEvidence.fromLegacyFlags(i.features)
            if (evidence.malformed) securityFailure(SecurityReason.SECURITY_INPUT_MALFORMED)
            if (evidence.set == null) securityFailure(SecurityReason.SECURITY_INPUT_MISSING)
            val capabilities = AirPlaySecurityCapabilities(i.features, evidence)
            // F80 selects MFiMutualAuth before the lower-bit AuthenticationType choices in
            // 24A446. Retaining it must not silently authorize our existing HAP/SAP exchanges.
            if (capabilities.mutualMfiAuthentication == true)
                securityFailure(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED)
            // 24A446 CU support is wider than bit 38. Do not misclassify an unimplemented
            // HK-access/system/transient entry as SAP, or silently open our direct-CU path.
            if (capabilities.requiresUnsupportedCuEntry)
                securityFailure(SecurityReason.PAIRING_MECHANISM_UNSUPPORTED)
            if (capabilities.directCoreUtilsPairing != true) false
            else {
                val gate = i.pairedCapabilityGate
                // A receiver that announces F38 but withholds `pk` cannot be driven by the CU/HAP
                // exchange at all. Falling back to `/auth-setup` is a different mechanism, not a
                // weaker version of the same one, so it needs an explicit opt-in.
                if (gate == null && i.sapFallbackAfterGate) return SecurityRoute.SAP
                gate ?: securityFailure(SecurityReason.SECURITY_POLICY_UNRESOLVED)
            }
        }
        return if (paired) SecurityRoute.PAIRED else SecurityRoute.SAP
    }
    fun select(i: SecurityInputs): SecurityAction {
        when (route(i)) {
            SecurityRoute.CONTINUE_VERIFY -> return SecurityAction.CONTINUE_VERIFY
            SecurityRoute.SAP -> return SecurityAction.AUTH_SETUP
            SecurityRoute.WAIT_START_PARAMETERS -> return SecurityAction.WAIT_START_PARAMETERS
            SecurityRoute.PAIRED -> Unit
        }
        if (!i.peerHintPresent) securityFailure(SecurityReason.PAIR_IDENTIFIER_MISSING)
        return when (i.record) {
            PairRecordStatus.ACTIVE -> SecurityAction.PAIR_VERIFY
            PairRecordStatus.ABSENT -> if (i.setupProviderBound) SecurityAction.PAIR_SETUP
                else securityFailure(SecurityReason.SETUP_INPUT_UNAVAILABLE,layer="AirPlay-pair",phase="pair-setup")
            PairRecordStatus.IO_ERROR -> securityFailure(SecurityReason.PAIR_STORE_IO_ERROR)
            PairRecordStatus.CORRUPT -> securityFailure(SecurityReason.PAIR_STORE_CORRUPT)
            PairRecordStatus.PENDING_SAP -> securityFailure(SecurityReason.PAIR_QUARANTINED)
            PairRecordStatus.QUARANTINED -> securityFailure(SecurityReason.PAIR_QUARANTINED)
            PairRecordStatus.OWNER_LOST -> securityFailure(SecurityReason.LOCAL_IDENTITY_LOST)
        }
    }
}

/** Input agreement is selected internally from peer-specific evidence, never from transport. */
fun interface SetupInputProvider { fun input(binding: SecurityBinding): ByteArray? }
data class SetupInputAgreement(val peer: String, val schemaId: String, val evidenceId: String)

/** Reference metadata is neither an entry gate nor a transport allow-list. */
object InternalSecurityProfiles {
    val referenceId = "BOOTSTRAP_READY_PAIRED_SAP_FIXED_INPUT_R1"
    const val revision = 2
    const val pairingSchemaRevision = 1 // Scope clarification does not change the pairing record format.
    const val productionReady = false
    const val entryGate = false
    val activationBlockers get() = listOf(SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE,
        SecurityReason.SAP_SIGNATURE_SCHEMA_UNSUPPORTED, SecurityReason.SAP_TRUST_MATERIAL_UNAVAILABLE)
    fun fixedSetupProvider(agreement: SetupInputAgreement): SetupInputProvider {
        require(agreement.peer.isNotBlank() && agreement.evidenceId.isNotBlank())
        require(agreement.schemaId == referenceId)
        return SetupInputProvider { binding ->
            if (binding.peer != agreement.peer) null else {
                "3939".toByteArray(Charsets.US_ASCII)
            }
        }
    }
}

/** Phase-specific missing execution material, independent of bootstrap transport or routing. */
object SecurityRequirements {
    fun requireAaTrust(policy: AuthTrustPolicy? = null) = requireTrust(policy, AuthLayer.IAP2_AA)
    fun requireSapTrust(policy: AuthTrustPolicy? = null) = requireTrust(policy, AuthLayer.AIRPLAY_SAP)
    private fun requireTrust(policy: AuthTrustPolicy?, layer: AuthLayer) {
        if (policy == null || policy.anchors.isEmpty()) securityFailure(
            if (layer == AuthLayer.IAP2_AA) SecurityReason.AA_TRUST_MATERIAL_UNAVAILABLE else SecurityReason.SAP_TRUST_MATERIAL_UNAVAILABLE,
            layer = if (layer == AuthLayer.IAP2_AA) "iAP2-AA" else "AirPlay-SAP",
            phase = if (layer == AuthLayer.IAP2_AA) "certificate-verification" else "auth-setup")
        if (policy.layer != layer || policy.synthetic) securityFailure(SecurityReason.TRUST_PURPOSE_UNSUPPORTED, layer.name, "trust")
    }
    /** No supported target SAP signature contract has been supplied. */
    fun requireSapSignatureSchema(): Unit = securityFailure(SecurityReason.SAP_SIGNATURE_SCHEMA_UNSUPPORTED,
        layer = "AirPlay-SAP", phase = "auth-setup")
}
