package com.hexo141.liquidglass.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import java.util.Locale

/**
 * RSA 2048 key and self-signed certificate used for ADB pairing.
 *
 * The private key signs the TLS client handshake and the RSA public key is sent to adbd in
 * Android's ADB public key format. Both are persisted in private SharedPreferences so repeat
 * pairings reuse the same key that adbd may already trust.
 */
class AdbKeyManager(context: Context) {

    companion object {
        private const val TAG = "AdbKeyManager"
        private const val PREF_NAME = "adb_pairing_key"
        private const val KEY_PRIVATE = "private_key_pkcs8"
        private const val KEY_CERT = "certificate_der"

        /** Certificate validity end (2031-12-31), matching the usual Android self-signed cert. */
        private const val NOT_AFTER_MILLIS = 1956528000_000L
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    val privateKey: PrivateKey
    val certificate: X509Certificate

    init {
        val loaded = load()
        if (loaded != null) {
            privateKey = loaded.first
            certificate = loaded.second
        } else {
            val generated = generate()
            privateKey = generated.first
            certificate = generated.second
            save(privateKey, certificate)
        }
        Log.d(TAG, "adb key ready: ${certificate.subjectX500Principal}")
    }

    private fun load(): Pair<PrivateKey, X509Certificate>? {
        val privateBytes = preferences.getString(KEY_PRIVATE, null) ?: return null
        val certBytes = preferences.getString(KEY_CERT, null) ?: return null
        return try {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateBytes, Base64.NO_WRAP))) as PrivateKey
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.decode(certBytes, Base64.NO_WRAP))) as X509Certificate
            key to cert
        } catch (e: Throwable) {
            Log.w(TAG, "stored adb key is unusable, regenerating", e)
            null
        }
    }

    private fun save(privateKey: PrivateKey, certificate: X509Certificate) {
        preferences.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP))
            .putString(KEY_CERT, Base64.encodeToString(certificate.encoded, Base64.NO_WRAP))
            .apply()
    }

    private fun generate(): Pair<PrivateKey, X509Certificate> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val keyPair = generator.generateKeyPair()

        val name = X500Name("CN=00")
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val holder = X509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date(0),
            Date(NOT_AFTER_MILLIS),
            Locale.ROOT,
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        ).build(signer)

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

        return keyPair.private to certificate
    }
}
