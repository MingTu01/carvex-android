package io.github.iamr0s.carvex.carplay.core
import java.io.ByteArrayInputStream
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.math.BigInteger
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Signature integrity only. Does not assert an Apple root trust chain. */
object AccessoryAuth {
    data class ParsedAuthenticationCertificate(val certificate:X509Certificate,val schema:ChallengeSchema)
    /** Local support policy, selected before parsing an untrusted response. The experimental
     * RSA contract is the captured RSA-1024/PKCS7/SHA1-digest subset, not a rule for all RSA keys. */
    enum class CertificatePolicy { STRICT_P256, EXPERIMENTAL_CAPTURED_RSA1024_SHA1_OR_P256 }
    /** Parses the supported AA container and algorithm only; no root trust is asserted here.
     * MFiSAP uses leaf() separately and does not inherit this iAP2 AA container schema. */
    fun parseAuthenticationCertificate(bytes:ByteArray,log:(String)->Unit={}):ParsedAuthenticationCertificate =
        parseAuthenticationCertificate(bytes,CertificatePolicy.STRICT_P256,log)

    fun parseAuthenticationCertificate(bytes:ByteArray,policy:CertificatePolicy,
                                       log:(String)->Unit={}):ParsedAuthenticationCertificate {
        require(bytes.size in 1..16384)
        val cert=leaf(bytes,log)
        val key=cert.publicKey
        val selected=if(policy==CertificatePolicy.EXPERIMENTAL_CAPTURED_RSA1024_SHA1_OR_P256&&
            key is RSAPublicKey&&key.modulus.bitLength()==1024)ChallengeSchema.RSA_SHA1_20 else schema(cert)
        // Both currently supported AA contracts use canonical DER PKCS7 signedData. A raw
        // certificate or its signature OID never selects the challenge/signature algorithm.
        val container=ASN1Primitive.fromByteArray(bytes)
        require(container.getEncoded(ASN1Encoding.DER).contentEquals(bytes))
        require(container is ASN1Sequence&&container.size()==2&&container.getObjectAt(0)==PKCSObjectIdentifiers.signedData)
        require(ContentInfo.getInstance(container).contentType==PKCSObjectIdentifiers.signedData)
        return ParsedAuthenticationCertificate(cert,selected)
    }
    fun leaf(bytes:ByteArray,log:(String)->Unit={}):X509Certificate {
        require(bytes.size in 1..16384)
        val certificates=CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        // Multiple certificates do not establish mutual authentication or root trust.
        if(certificates.size!=1)log("Certificate container count="+certificates.size+"; chain trust not evaluated")
        return certificates.singleOrNull{it.basicConstraints<0}
            ?:error("AUTH_SCHEMA_UNSUPPORTED: no unique experimental leaf")
    }
    /** Explicit authentication schema; an untrusted packet length must not choose the algorithm.
     * RSA/SHA-256 is available for a profile with affirmative generation evidence. */
    enum class ChallengeSchema(val challengeBytes:Int) {
        RSA_SHA1_20(20), RSA_SHA256_32(32), AA_P256_SHA256_DIGEST_V1(32),
        AA_P256_SHA256_DIGEST_V2(20), AA_P256_SHA256_MESSAGE_X962(32);

        companion object {
            /** Source compatibility only: this is the explicit P-256/raw-rs/prehashed schema. */
            val ECDSA_SHA256_32=AA_P256_SHA256_DIGEST_V1
        }

        fun accepts(key:PublicKey):Boolean=when(this){
            AA_P256_SHA256_DIGEST_V1,AA_P256_SHA256_DIGEST_V2,
            AA_P256_SHA256_MESSAGE_X962->key is ECPublicKey&&isP256(key)
            RSA_SHA1_20,RSA_SHA256_32->key is RSAPublicKey&&key.modulus.bitLength() in 1024..4096
        }
    }

    private val p256:ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply{init(ECGenParameterSpec("secp256r1"))}
            .getParameterSpec(ECParameterSpec::class.java)
    }
    private fun isP256(key:ECPublicKey):Boolean=key.params.let{
        it.curve==p256.curve&&it.generator==p256.generator&&it.order==p256.order&&it.cofactor==p256.cofactor
    }
    /** This representation belongs only to AA_P256_SHA256_DIGEST_V1, not generic AA/06. */
    fun matchesP256Serial(serialNumber:BigInteger,identifier:ByteArray):Boolean {
        if(serialNumber.signum()<=0||identifier.size!=32||identifier.any{
                val c=it.toInt() and 255;c !in 0x30..0x39&&c !in 0x41..0x46})return false
        val magnitude=serialNumber.toByteArray().dropWhile{it==0.toByte()}
        if(magnitude.size!=16)return false
        val expected=serialNumber.toString(16).uppercase(java.util.Locale.ROOT).padStart(32,'0')
            .toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected,identifier)
    }

    fun schema(cert:X509Certificate):ChallengeSchema=schema(cert.publicKey)
    /** 24A446 SecCertificateGetiAuthVersion -> MFAACertificateManager proof branch. */
    internal fun schema(cert:X509Certificate,authVersion:Int):ChallengeSchema {
        val key=cert.publicKey
        require(key is ECPublicKey&&isP256(key)){"BAA authentication certificate must use P-256"}
        return when(authVersion){
            2->ChallengeSchema.AA_P256_SHA256_DIGEST_V2
            3->ChallengeSchema.AA_P256_SHA256_DIGEST_V1
            0,4,5->ChallengeSchema.AA_P256_SHA256_MESSAGE_X962
            else->throw IllegalArgumentException("unsupported iAP authentication version "+authVersion)
        }
    }
    internal fun schema(key:PublicKey):ChallengeSchema=when{
        ChallengeSchema.AA_P256_SHA256_DIGEST_V1.accepts(key)->ChallengeSchema.AA_P256_SHA256_DIGEST_V1
        else->throw IllegalArgumentException("unsupported iAP authentication public key")
    }

    fun challenge(cert:X509Certificate,schema:ChallengeSchema=schema(cert)):ByteArray {
        require(schema.accepts(cert.publicKey)){"iAP authentication schema does not match public key"}
        return Crypto.random(schema.challengeBytes)
    }

    fun verifyChallenge(cert:X509Certificate,challenge:ByteArray,signature:ByteArray,
                        schema:ChallengeSchema=schema(cert)):Boolean=
        verifyChallenge(cert.publicKey,challenge,signature,schema)

    internal fun verifyChallenge(key:PublicKey,challenge:ByteArray,signature:ByteArray,schema:ChallengeSchema):Boolean {
        if(!schema.accepts(key)||challenge.size!=schema.challengeBytes)return false
        return when(schema){
            ChallengeSchema.AA_P256_SHA256_DIGEST_V1,ChallengeSchema.AA_P256_SHA256_DIGEST_V2->{
                if(signature.size!=64)return false
                try{Signature.getInstance("NONEwithECDSA").run{initVerify(key);update(challenge);verify(der(signature))}}
                catch(t:GeneralSecurityException){false}
            }
            ChallengeSchema.AA_P256_SHA256_MESSAGE_X962->
                try{Signature.getInstance("SHA256withECDSA").run{initVerify(key);update(challenge);verify(signature)}}
                catch(t:GeneralSecurityException){false}
            ChallengeSchema.RSA_SHA1_20,ChallengeSchema.RSA_SHA256_32->{
                val rsa=key as? RSAPublicKey?:return false
                if(signature.size!=(rsa.modulus.bitLength()+7)/8)return false
                val prefix=if(challenge.size==20)hex("3021300906052b0e03021a05000414")
                    else hex("3031300d060960864801650304020105000420")
                try{Signature.getInstance("NONEwithRSA").run{initVerify(key);update(prefix+challenge);verify(signature)}}
                catch(t:GeneralSecurityException){false}
            }
        }
    }
    private fun hex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun der(raw:ByteArray):ByteArray {
        fun number(b:ByteArray):ByteArray {
            val trim=b.dropWhile{it==0.toByte()}.toByteArray().let{if(it.isEmpty())byteArrayOf(0)else it}
            val v=if(trim[0]<0)byteArrayOf(0)+trim else trim
            return byteArrayOf(2,v.size.toByte())+v
        }
        val body=number(raw.copyOfRange(0,32))+number(raw.copyOfRange(32,64))
        return byteArrayOf(0x30,body.size.toByte())+body
    }
    data class Mfi(val key:ByteArray,val iv:ByteArray,val wrapped:ByteArray) {
        fun streamCipher(id:Long):Cipher {
            fun derive(label:String)=Crypto.hash((label+id).toByteArray(Charsets.US_ASCII),key).copyOf(16)
            return Cipher.getInstance("AES/CTR/NoPadding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(derive("AirPlayStreamKey"),"AES"),IvParameterSpec(derive("AirPlayStreamIV")))}
        }
    }
    /** Explicit local compatibility contract; complete production SAP trust remains unavailable. */
    enum class SapSignatureSchema { EXPERIMENTAL_RSA_PKCS1_SHA1_PUBLIC_KEYS_V1 }
    internal fun verifyMfiSignature(key:PublicKey,schema:SapSignatureSchema,server:ByteArray,client:ByteArray,signature:ByteArray):Boolean {
        require(server.size==32&&client.size==32)
        if(key !is RSAPublicKey||key.modulus.bitLength() !in 1024..4096)return false
        val actual=signature
        val algorithm=when(schema){SapSignatureSchema.EXPERIMENTAL_RSA_PKCS1_SHA1_PUBLIC_KEYS_V1->"SHA1withRSA"}
        return try{Signature.getInstance(algorithm).run{initVerify(key);update(server+client);verify(actual)}}
            catch(t:SignatureException){false}
    }
    internal fun sapMaterial(label:String,shared:ByteArray):ByteArray {
        require(shared.size==32)
        return MessageDigest.getInstance("SHA-1").run{update(label.toByteArray(Charsets.US_ASCII));digest(shared)}.copyOf(16)
    }
    @Deprecated("Synthetic compatibility experiment only; no supported production SAP schema")
    internal fun mfi(client:RtspClient,schema:SapSignatureSchema,log:(String)->Unit={}):Mfi {
        val secret=Crypto.random(32);val public=Crypto.xPublic(secret)
        try{
        val r=client.request("POST","/auth-setup",byteArrayOf(1)+public,"application/octet-stream")
        check(r.status in 200..299){"MFiSAP rejected "+r.status}
        val b=r.body;require(b.size>=40)
        val server=b.copyOfRange(0,32);val certSize=ByteBuffer.wrap(b,32,4).int
        require(certSize in 1..16384 && 36+certSize+4<=b.size)
        val cert=leaf(b.copyOfRange(36,36+certSize),log)
        val sigSize=ByteBuffer.wrap(b,36+certSize,4).int;val at=40+certSize
        require(sigSize in 1..4096 && at+sigSize==b.size)
        val shared=try{Crypto.shared(secret,server)}finally{secret.fill(0)}
        // docs/05: SAP signature wrapping uses SHA1, independently of video SHA512.
        fun derive(label:String)=sapMaterial(label,shared)
        val cipher=Cipher.getInstance("AES/CTR/NoPadding").apply{init(Cipher.DECRYPT_MODE,SecretKeySpec(derive("AES-KEY"),"AES"),IvParameterSpec(derive("AES-IV")))}
        shared.fill(0)
        val signature=cipher.update(b.copyOfRange(at,b.size))
        val algorithm=schema.name
        val format="RSA-PKCS1"
        log("${"MFiSAP certificateBytes="}${certSize}${" publicKey="}"+cert.publicKey.algorithm+" signatureBytes="+signature.size+"${" algorithm="}${algorithm}${" format="}${format}")
        if(!verifyMfiSignature(cert.publicKey,schema,server,public,signature))
            throw SecurityException("${"MFiSAP signature verification failed ("}${algorithm}${", certificate "}${certSize}${" bytes, signature "}${signature.size}${" bytes); signature still checked in experiment mode, verify against receiver protocol samples"}")
        log("MFiSAP signature verified; Apple root trust NOT verified")
        val key=Crypto.random(16);val iv=Crypto.random(16)
        return Mfi(key,iv,cipher.update(key))
        }finally{secret.fill(0)}
    }
}
