package com.hexo141.liquidglass.adb

import android.content.Context
import android.os.Build
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * ADB connection manager bound to the same RSA key that was handed to adbd while pairing.
 *
 * Because adbd already stores that public key, [connectTls] authenticates without any pairing
 * code: it discovers `_adb-tls-connect._tcp` over mDNS and performs the STLS handshake.
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyManager = AdbKeyManager(context)

    init {
        setApi(Build.VERSION.SDK_INT)
        // Make a rejected key surface as AdbAuthenticationFailedException instead of silently
        // retrying, so the caller knows the pairing has to be redone.
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = keyManager.privateKey

    override fun getCertificate(): Certificate = keyManager.certificate

    override fun getDeviceName(): String = AdbPairingService.DEVICE_NAME

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        /** Returns the shared manager, creating it on first use. */
        fun get(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }

        /** Whether the shared manager currently holds a live connection (never creates one). */
        fun isConnected(): Boolean = instance?.isConnected() == true
    }
}
