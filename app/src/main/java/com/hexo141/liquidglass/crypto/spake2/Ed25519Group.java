// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package com.hexo141.liquidglass.crypto.spake2;

import java.math.BigInteger;

/**
 * Minimal, self-contained edwards25519 group arithmetic used by the SPAKE2 implementation.
 *
 * <p>Points are kept in extended homogeneous coordinates {@code (X : Y : Z : T)} with
 * {@code x = X/Z}, {@code y = Y/Z} and {@code XY = ZT}. The unified "add-2008-hwcd-3"
 * formula for {@code a = -1} is used for both addition and doubling: it is complete on
 * edwards25519 (because {@code d} is a non-square), so no special cases are required.
 *
 * <p>The arithmetic is intentionally simple rather than constant time. It is only used for a
 * one-shot ephemeral key exchange during ADB pairing, where the scalars never leave the device
 * and are never reused.
 */
final class Ed25519Group {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger ZERO = BigInteger.ZERO;
    static final BigInteger ONE = BigInteger.ONE;
    static final BigInteger TWO = BigInteger.TWO;
    static final BigInteger EIGHT = BigInteger.valueOf(8);

    /** Order of the prime order subgroup: {@code l = 2^252 + 27742317777372353535851937790883648493}. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    /** {@code d = -121665/121666 mod p}. */
    static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    /** {@code sqrt(-1) mod p}. */
    static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(ONE).shiftRight(2), P);

    /** Compressed encoding of the standard base point {@code B}. */
    private static final String BASE_ENCODING =
            "5866666666666666666666666666666666666666666666666666666666666666";
    /** Compressed encoding of the SPAKE2 mask point {@code M} (client/Alice role). */
    private static final String M_ENCODING =
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e";
    /** Compressed encoding of the SPAKE2 mask point {@code N} (server/Bob role). */
    private static final String N_ENCODING =
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778";

    static final Point BASE = decode(hexToBytes(BASE_ENCODING));
    static final Point M = decode(hexToBytes(M_ENCODING));
    static final Point N = decode(hexToBytes(N_ENCODING));

    static {
        if (BASE == null || M == null || N == null) {
            throw new IllegalStateException("Invalid hard-coded edwards25519 constants");
        }
    }

    private Ed25519Group() {
    }

    /** A point in extended homogeneous coordinates. */
    static final class Point {
        final BigInteger X;
        final BigInteger Y;
        final BigInteger Z;
        final BigInteger T;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.X = x;
            this.Y = y;
            this.Z = z;
            this.T = t;
        }
    }

    static Point identity() {
        return new Point(ZERO, ONE, ONE, ZERO);
    }

    static Point create(BigInteger x, BigInteger y) {
        return new Point(x.mod(P), y.mod(P), ONE, x.multiply(y).mod(P));
    }

    static Point negate(Point p) {
        return new Point(p.X.negate().mod(P), p.Y, p.Z, p.T.negate().mod(P));
    }

    /** Unified addition, also valid when {@code p == q}. */
    static Point add(Point p, Point q) {
        BigInteger a = p.Y.subtract(p.X).multiply(q.Y.subtract(q.X)).mod(P);
        BigInteger b = p.Y.add(p.X).multiply(q.Y.add(q.X)).mod(P);
        BigInteger c = p.T.multiply(D2).multiply(q.T).mod(P);
        BigInteger d = p.Z.multiply(TWO).multiply(q.Z).mod(P);
        BigInteger e = b.subtract(a);
        BigInteger f = d.subtract(c);
        BigInteger g = d.add(c);
        BigInteger h = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    static Point doublePoint(Point p) {
        return add(p, p);
    }

    /**
     * {@code k * p}, using a left-to-right double-and-add ladder.
     *
     * @param k non-negative scalar
     */
    static Point multiply(BigInteger k, Point p) {
        if (k.signum() < 0) {
            throw new IllegalArgumentException("Negative scalar");
        }
        Point acc = identity();
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            acc = doublePoint(acc);
            if (k.testBit(i)) {
                acc = add(acc, p);
            }
        }
        return acc;
    }

    /** Affine {@code x} coordinate. */
    static BigInteger affineX(Point p) {
        return p.X.multiply(p.Z.modInverse(P)).mod(P);
    }

    /** Affine {@code y} coordinate. */
    static BigInteger affineY(Point p) {
        return p.Y.multiply(p.Z.modInverse(P)).mod(P);
    }

    /** RFC 8032 point encoding: little endian {@code y} with the low bit of {@code x} in bit 255. */
    static byte[] encode(Point p) {
        BigInteger zi = p.Z.modInverse(P);
        BigInteger x = p.X.multiply(zi).mod(P);
        BigInteger y = p.Y.multiply(zi).mod(P);
        byte[] out = toLittleEndian32(y);
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /**
     * RFC 8032 point decoding.
     *
     * @return the decoded point, or {@code null} when the encoding is not a curve point
     */
    static Point decode(byte[] encoded) {
        if (encoded == null || encoded.length != 32) {
            return null;
        }
        int sign = (encoded[31] >> 7) & 1;
        byte[] yBytes = encoded.clone();
        yBytes[31] &= 0x7f;
        BigInteger y = fromLittleEndian(yBytes).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(ONE).mod(P);
        BigInteger v = D.multiply(y2).add(ONE).mod(P);

        // x = sqrt(u/v)
        BigInteger vInv = v.modInverse(P);
        BigInteger x2 = u.multiply(vInv).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).mod(P).equals(x2)) {
            x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).mod(P).equals(x2)) {
                return null;
            }
        }
        if (x.signum() == 0 && sign == 1) {
            return null;
        }
        if ((x.testBit(0) ? 1 : 0) != sign) {
            x = P.subtract(x).mod(P);
        }
        return create(x, y);
    }

    /** @return whether the point satisfies {@code -x^2 + y^2 = 1 + d*x^2*y^2} */
    static boolean isOnCurve(Point p) {
        BigInteger x = affineX(p);
        BigInteger y = affineY(p);
        BigInteger left = x.multiply(x).negate().add(y.multiply(y)).mod(P);
        BigInteger right = ONE.add(D.multiply(x).multiply(x).multiply(y).multiply(y)).mod(P);
        return left.equals(right);
    }

    static BigInteger fromLittleEndian(byte[] in) {
        byte[] be = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            be[i] = in[in.length - 1 - i];
        }
        return new BigInteger(1, be);
    }

    static byte[] toLittleEndian32(BigInteger value) {
        byte[] out = new byte[32];
        BigInteger v = value;
        for (int i = 0; i < 32; i++) {
            out[i] = v.byteValue();
            v = v.shiftRight(8);
        }
        return out;
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
