/*
 * Copyright (C) 2021 Muntashir Al-Islam
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.hexo141.liquidglass.crypto.spake2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.security.auth.Destroyable;

import com.hexo141.liquidglass.crypto.x25519.x25519Scalar;

/**
 * SPAKE2 (edwards25519) as implemented by BoringSSL's {@code spake25519} and used by ADB
 * pairing.
 *
 * <p>The group arithmetic lives in {@link Ed25519Group}; this class only implements the protocol
 * bookkeeping: password scalar derivation, masking/unmasking and the final transcript hash.
 */
@SuppressWarnings("unused")
public class Spake2Context implements Destroyable {
    /**
     * Maximum message size in bytes
     */
    public static final int MAX_MSG_SIZE = 32;
    /**
     * Maximum key size in bytes
     */
    public static final int MAX_KEY_SIZE = 64;

    private final byte[] mMyName;
    private final byte[] mTheirName;
    private final Spake2Role mMyRole;
    private final byte[] mPrivateKey = new byte[32];
    private final byte[] mMyMsg = new byte[32];
    private final byte[] mPasswordHash = new byte[64];

    /** {@code password_scalar} in little endian, i.e. the (possibly "hacked") SHA-512 reduction. */
    private byte[] mPasswordScalar = new byte[32];

    private State mState;
    private boolean mDisablePasswordScalarHack;
    private boolean mIsDestroyed = false;

    public Spake2Context(Spake2Role myRole,
                         final byte[] myName,
                         final byte[] theirName) {
        mMyRole = myRole;
        mMyName = new byte[myName.length];
        mTheirName = new byte[theirName.length];
        mState = State.Init;

        System.arraycopy(myName, 0, mMyName, 0, myName.length);
        System.arraycopy(theirName, 0, mTheirName, 0, theirName.length);
    }

    public void setDisablePasswordScalarHack(boolean disablePasswordScalarHack) {
        mDisablePasswordScalarHack = disablePasswordScalarHack;
    }

    public boolean isDisablePasswordScalarHack() {
        return mDisablePasswordScalarHack;
    }

    public Spake2Role getMyRole() {
        return mMyRole;
    }

    public byte[] getMyMsg() {
        return mMyMsg;
    }

    public byte[] getMyName() {
        return mMyName;
    }

    public byte[] getTheirName() {
        return mTheirName;
    }

    @Override
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    @Override
    public void destroy() {
        mIsDestroyed = true;
        Arrays.fill(mMyName, (byte) 0);
        Arrays.fill(mTheirName, (byte) 0);
        Arrays.fill(mPrivateKey, (byte) 0);
        Arrays.fill(mMyMsg, (byte) 0);
        Arrays.fill(mPasswordScalar, (byte) 0);
        Arrays.fill(mPasswordHash, (byte) 0);
    }

    /**
     * @param password Shared password.
     * @return A message of size {@link #MAX_MSG_SIZE}.
     * @throws IllegalArgumentException If SHA-512 is unavailable for some reason.
     * @throws IllegalStateException    If the message has already been generated.
     */
    public byte[] generateMessage(final byte[] password) throws IllegalArgumentException, IllegalStateException {
        byte[] privateKey = new byte[64];
        new SecureRandom().nextBytes(privateKey);
        return generateMessage(password, privateKey);
    }

    // Package private method for testing purposes
    byte[] generateMessage(final byte[] password, byte[] privateKey) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) {
            throw new IllegalStateException("The context was destroyed.");
        }
        if (mState != State.Init) {
            throw new IllegalStateException("Invalid state: " + mState);
        }

        x25519Scalar.reduce(privateKey);
        // Multiply by the cofactor (eight) so that we'll clear it when operating on
        // the peer's point later in the protocol.
        leftShift3(privateKey);
        System.arraycopy(privateKey, 0, mPrivateKey, 0, mPrivateKey.length);

        final BigInteger x = Ed25519Group.fromLittleEndian(mPrivateKey);
        final Ed25519Group.Point P = Ed25519Group.multiply(x, Ed25519Group.BASE);

        byte[] passwordTmp = getHash("SHA-512", password);  // 64 byte
        System.arraycopy(passwordTmp, 0, mPasswordHash, 0, mPasswordHash.length);
        x25519Scalar.reduce(passwordTmp);

        /**
         * Due to a copy-paste error in BoringSSL, the call to leftShift3() was omitted after
         * reducing the password hash, which meant that the password scalar was not a multiple of
         * eight and thus three bits of the password hash would leak. BoringSSL fixes this
         * unilaterally by adding multiples of the prime order to the password scalar: since the
         * prime order is odd, adding it flips the LSB, adding twice flips the next bit and so on
         * for the bottom three bits.
         *
         * <p>The peer applies exactly the same transformation, so the extra small order points
         * cancel out when the shared secret is computed.
         */
        BigInteger passwordScalar = Ed25519Group.fromLittleEndian(Arrays.copyOf(passwordTmp, 32));
        if (!mDisablePasswordScalarHack) {
            if (passwordScalar.testBit(0)) {
                passwordScalar = passwordScalar.add(Ed25519Group.L);
            }
            if (passwordScalar.testBit(1)) {
                passwordScalar = passwordScalar.add(Ed25519Group.L.shiftLeft(1));
            }
            if (passwordScalar.testBit(2)) {
                passwordScalar = passwordScalar.add(Ed25519Group.L.shiftLeft(2));
            }
        }
        mPasswordScalar = Ed25519Group.toLittleEndian32(passwordScalar);

        // mask = h(password) * <M or N>.
        final Ed25519Group.Point mask = Ed25519Group.multiply(passwordScalar,
                mMyRole == Spake2Role.Alice ? Ed25519Group.M : Ed25519Group.N);

        // P* = P + mask.
        final Ed25519Group.Point pStar = Ed25519Group.add(P, mask);

        System.arraycopy(Ed25519Group.encode(pStar), 0, mMyMsg, 0, mMyMsg.length);
        mState = State.MsgGenerated;
        return mMyMsg.clone();
    }

    /**
     * @param theirMsg Message generated/received from the other end.
     * @return Key of size {@link #MAX_KEY_SIZE}.
     * @throws IllegalArgumentException If the message is invalid or SHA-512 is unavailable for some reason.
     * @throws IllegalStateException    If the key has already been generated.
     */
    public byte[] processMessage(final byte[] theirMsg) throws IllegalArgumentException, IllegalStateException {
        if (mIsDestroyed) {
            throw new IllegalStateException("The context was destroyed.");
        }
        if (mState != State.MsgGenerated) {
            throw new IllegalStateException("Invalid state: " + mState);
        }
        if (theirMsg.length != 32) {
            throw new IllegalArgumentException("Peer's message is not 32 bytes");
        }

        final Ed25519Group.Point qStar = Ed25519Group.decode(theirMsg);
        if (qStar == null) {
            throw new IllegalArgumentException("Point received from peer was not on the curve.");
        }

        // Unmask peer's value with the *opposite* mask point.
        final BigInteger passwordScalar = Ed25519Group.fromLittleEndian(mPasswordScalar);
        final Ed25519Group.Point peersMask = Ed25519Group.multiply(passwordScalar,
                mMyRole == Spake2Role.Alice ? Ed25519Group.N : Ed25519Group.M);

        final Ed25519Group.Point qExt = Ed25519Group.add(qStar, Ed25519Group.negate(peersMask));

        final BigInteger x = Ed25519Group.fromLittleEndian(mPrivateKey);
        final byte[] dhShared = Ed25519Group.encode(Ed25519Group.multiply(x, qExt));

        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("SHA-512 algorithm is not supported.");
        }
        if (mMyRole == Spake2Role.Alice) {
            updateWithLengthPrefix(sha, mMyName, mMyName.length);
            updateWithLengthPrefix(sha, mTheirName, mTheirName.length);
            updateWithLengthPrefix(sha, mMyMsg, mMyMsg.length);
            updateWithLengthPrefix(sha, theirMsg, 32);
        } else { // Bob
            updateWithLengthPrefix(sha, mTheirName, mTheirName.length);
            updateWithLengthPrefix(sha, mMyName, mMyName.length);
            updateWithLengthPrefix(sha, theirMsg, 32);
            updateWithLengthPrefix(sha, mMyMsg, mMyMsg.length);
        }
        updateWithLengthPrefix(sha, dhShared, dhShared.length);
        updateWithLengthPrefix(sha, mPasswordHash, mPasswordHash.length);

        byte[] key = sha.digest();
        this.mState = State.KeyGenerated;

        return key.clone();
    }

    /**
     * Multiplies n with 8 by shifting it 3 times to the left
     *
     * @param n 32 bytes value
     */
    static void leftShift3(byte[] n) {
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int next_carry = (byte) ((n[i] & 0xFF) >>> 5);
            n[i] = (byte) ((n[i] << 3) | carry);
            carry = next_carry;
        }
    }

    private static void updateWithLengthPrefix(MessageDigest sha, final byte[] data, int len) {
        byte[] len_le = new byte[8];
        long l = len;
        int i;

        for (i = 0; i < 8; i++) {
            len_le[i] = (byte) (l & 0xFF);
            l = (l >>> 8) & 0xFFFF_FFFFL;
        }

        sha.update(len_le);
        sha.update(data);
    }

    // Package private for testing
    static byte[] getHash(String algo, byte[] bytes) throws IllegalArgumentException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Invalid hashing algorithm " + algo);
        }
        md.reset();
        return md.digest(bytes);
    }

    private enum State {
        Init,
        MsgGenerated,
        KeyGenerated,
    }
}
