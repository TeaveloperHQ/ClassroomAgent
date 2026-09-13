package com.teaveloper.classroomagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Cryptographic identity for this agent.
 *
 * ECDSA P-256 keypair kept in AndroidKeyStore — private key is
 * non-exportable, so a compromised APK image cannot forge another agent's
 * signature. Public key is X.509-encoded and base64url-encoded for
 * advertisement over mDNS TXT records.
 *
 * Used by peer-consensus gossip: usage events sent to peers are signed with
 * sign(); receiving peers verify with the sender's mDNS-advertised pubkey.
 */
object PeerIdentity {

    private const val KEY_ALIAS = "classroomagent_p2p_signing_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val SIG_ALGO = "SHA256withECDSA"
    private val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private var privateKey: PrivateKey? = null
    private var publicKey: PublicKey? = null

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE_PROVIDER
            )
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
            android.util.Log.d("PeerIdentity", "새 키페어 생성됨")
        }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        privateKey = entry.privateKey
        publicKey = entry.certificate.publicKey
    }

    /** X.509-encoded public key as URL-safe base64 (no padding). Fits in an mDNS TXT record. */
    fun publicKeyB64(): String {
        val pk = publicKey ?: error("PeerIdentity.init() 먼저 호출 필요")
        return Base64.encodeToString(pk.encoded, B64_FLAGS)
    }

    fun sign(data: ByteArray): ByteArray {
        val sig = Signature.getInstance(SIG_ALGO)
        sig.initSign(privateKey ?: error("PeerIdentity.init() 먼저 호출 필요"))
        sig.update(data)
        return sig.sign()
    }

    fun signB64(data: ByteArray): String =
        Base64.encodeToString(sign(data), B64_FLAGS)

    /**
     * Verify a signature made by another agent with the given base64-encoded public key.
     * Returns false on any decode / verify failure — never throws.
     */
    fun verify(publicKeyB64: String, data: ByteArray, sigB64: String): Boolean = try {
        val keyBytes = Base64.decode(publicKeyB64, B64_FLAGS)
        val sigBytes = Base64.decode(sigB64, B64_FLAGS)
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val sig = Signature.getInstance(SIG_ALGO)
        sig.initVerify(pubKey)
        sig.update(data)
        sig.verify(sigBytes)
    } catch (e: Exception) {
        android.util.Log.w("PeerIdentity", "verify 실패: ${e.message}")
        false
    }
}
