// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package com.hexo141.liquidglass.adb;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

final class SslUtils {
    private static final String TAG = "SslUtils";

    private static final String TLS_VERSION = "TLSv1.3";

    /** Provider shipped with this app (conscrypt-android). */
    private static final String BUNDLED_PROVIDER = "org.conscrypt.OpenSSLProvider";

    /** Context for the ADB transport: the platform stack is the historical default here. */
    private static SSLContext sslContext;

    /** Context for pairing: must be able to export keying material, see {@link #getPairingSslContext}. */
    private static SSLContext pairingSslContext;

    private SslUtils() {
    }

    /**
     * Context used for the TLS upgrade of the ADB transport itself (no keying material needed).
     * Pairing must use {@link #getPairingSslContext} instead, because only that context can export
     * the session keys.
     */
    @NonNull
    public static SSLContext getSslContext(KeyPair keyPair) throws NoSuchAlgorithmException, KeyManagementException {
        if (sslContext == null) {
            sslContext = create(keyPair, false);
        }
        return sslContext;
    }

    /**
     * Context used for pairing.
     *
     * <p>Pairing needs {@code exportKeyingMaterial("adb-label\0")} from the finished TLS session.
     * The platform Conscrypt method is a hidden API and is denied to apps since Android 10
     * ("Accessing hidden method ... blocked, reflection, denied"), so the exporter has to come from
     * the Conscrypt copy bundled in this app. The socket is created from that same provider, which
     * makes it an {@code org.conscrypt} socket: the exporter can then be called directly, without
     * reflection and without touching any platform internals.
     */
    @NonNull
    public static SSLContext getPairingSslContext(KeyPair keyPair)
            throws NoSuchAlgorithmException, KeyManagementException {
        if (pairingSslContext == null) {
            pairingSslContext = create(keyPair, true);
        }
        return pairingSslContext;
    }

    @NonNull
    private static SSLContext create(KeyPair keyPair, boolean preferBundled)
            throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = preferBundled ? createBundled() : createPlatform();
        if (context == null) {
            context = preferBundled ? createPlatform() : createBundled();
        }
        if (context == null) {
            throw new NoSuchAlgorithmException(
                    "TLSv1.3 isn't supported on your platform. Use custom Conscrypt library instead.");
        }

        Log.i(TAG, "using " + describe(context) + " for TLSv1.3");

        context.init(new KeyManager[]{getKeyManager(keyPair)},
                new X509TrustManager[]{getAllAcceptingTrustManager()},
                new SecureRandom());
        return context;
    }

    @Nullable
    private static SSLContext createBundled() {
        try {
            Class<?> providerClass = Class.forName(BUNDLED_PROVIDER);
            Provider provider = (Provider) providerClass.getDeclaredConstructor().newInstance();
            return SSLContext.getInstance(TLS_VERSION, provider);
        } catch (Throwable e) {
            // Native libraries missing for this ABI, 16KB page mismatch, ...
            Log.w(TAG, "bundled Conscrypt is unusable", e);
            return null;
        }
    }

    @Nullable
    private static SSLContext createPlatform() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            return SSLContext.getInstance(TLS_VERSION);
        } catch (Throwable e) {
            Log.w(TAG, "platform TLSv1.3 is unavailable", e);
            return null;
        }
    }

    /** {@code true} when the context comes from the Conscrypt copy bundled in this app. */
    static boolean isBundled(@NonNull SSLContext context) {
        return context.getProvider().getClass().getName().startsWith("org.conscrypt.");
    }

    @NonNull
    private static String describe(@NonNull SSLContext context) {
        return (isBundled(context) ? "bundled" : "platform") + " TLSv1.3 provider ("
                + context.getProvider().getClass().getName() + ")";
    }

    @NonNull
    private static KeyManager getKeyManager(KeyPair keyPair) {
        return new X509ExtendedKeyManager() {
            private final String mAlias = "key";

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                for (String keyType : keyTypes) {
                    if (keyType.equals("RSA")) return mAlias;
                }
                return null;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                if (this.mAlias.equals(alias)) {
                    return new X509Certificate[]{(X509Certificate) keyPair.getCertificate()};
                }
                return null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                if (this.mAlias.equals(alias)) {
                    return keyPair.getPrivateKey();
                }
                return null;
            }
        };
    }

    @SuppressLint("TrustAllX509TrustManager") // Accept all certificates
    @NonNull
    private static X509TrustManager getAllAcceptingTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
