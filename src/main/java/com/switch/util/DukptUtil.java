package com.qswitch.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;

public final class DukptUtil {

    private static final byte[] KEY_MASK = hexToBytes("C0C0C0C000000000C0C0C0C000000000");
    private static final byte[] KSN_MASK = hexToBytes("FFFFFFFFFFFFFFE00000");

    // Variants
    private static final byte[] PIN_VARIANT = hexToBytes("00000000000000FF00000000000000FF");
    private static final byte[] MAC_VARIANT = hexToBytes("000000000000FF00000000000000FF00");

    private DukptUtil() {}

    public static String deriveWorkingKey(String bdkHex, String ksnHex) {
        byte[] bdk = parseHex(bdkHex, "BDK");
        byte[] ksn = parseHex(ksnHex, "KSN");

        byte[] ipek = deriveIpek(bdk, ksn);
        byte[] sessionKey = deriveSessionKey(ipek, ksn);

        return bytesToHex(sessionKey);
    }

    // 🔥 NEW: expose IPEK for validation
    public static String deriveIpekHex(String bdkHex, String ksnHex) {
        byte[] bdk = parseHex(bdkHex, "BDK");
        byte[] ksn = parseHex(ksnHex, "KSN");
        return bytesToHex(deriveIpek(bdk, ksn));
    }

    // 🔥 NEW: variants
    public static String derivePinKey(String bdkHex, String ksnHex) {
        byte[] base = hexToBytes(deriveWorkingKey(bdkHex, ksnHex));
        return bytesToHex(xor(base, PIN_VARIANT));
    }

    public static String deriveMacKey(String bdkHex, String ksnHex) {
        byte[] base = hexToBytes(deriveWorkingKey(bdkHex, ksnHex));
        return bytesToHex(xor(base, MAC_VARIANT));
    }

    private static byte[] deriveIpek(byte[] bdk, byte[] ksn) {
        byte[] ksnReg = ksn.clone();
        applyMaskInPlace(ksnReg, KSN_MASK);

        byte[] ksn8 = new byte[8];
        System.arraycopy(ksnReg, 0, ksn8, 0, 8);

        byte[] left = tdesEncrypt(bdk, ksn8);
        byte[] right = tdesEncrypt(xor(bdk, KEY_MASK), ksn8);

        return concat(left, right);
    }

    private static byte[] deriveSessionKey(byte[] ipek, byte[] ksn) {
        byte[] key = ipek.clone();
        byte[] ksnReg = ksn.clone();
        applyMaskInPlace(ksnReg, KSN_MASK);

        int counter =
            ((ksn[7] & 0x1F) << 16) |
            ((ksn[8] & 0xFF) << 8) |
            (ksn[9] & 0xFF);

        for (int shift = 0x100000; shift > 0; shift >>= 1) {
            if ((counter & shift) != 0) {
                ksnReg[7] = (byte) ((ksnReg[7] & 0xE0) | ((shift >> 16) & 0x1F));
                ksnReg[8] = (byte) ((shift >> 8) & 0xFF);
                ksnReg[9] = (byte) (shift & 0xFF);

                key = nonReversibleKeyGen(key, ksnReg);
            }
        }
        return key;
    }

    private static byte[] nonReversibleKeyGen(byte[] key, byte[] ksnReg) {
        byte[] data = new byte[8];
        System.arraycopy(ksnReg, 2, data, 0, 8);

        byte[] right = processHalf(key, data);
        byte[] left = processHalf(xor(key, KEY_MASK), data);

        return concat(left, right);
    }

    private static byte[] processHalf(byte[] key, byte[] data) {
        byte[] keyLeft = new byte[8];
        byte[] keyRight = new byte[8];

        System.arraycopy(key, 0, keyLeft, 0, 8);
        System.arraycopy(key, 8, keyRight, 0, 8);

        byte[] xored = xor(data, keyRight);
        byte[] encrypted = desEncrypt(keyLeft, xored);

        return xor(encrypted, keyRight);
    }

    private static byte[] desEncrypt(byte[] key8, byte[] data8) {
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key8, "DES"));
            return cipher.doFinal(data8);
        } catch (Exception e) {
            throw new IllegalStateException("DES error", e);
        }
    }

    private static byte[] tdesEncrypt(byte[] key16, byte[] data8) {
        try {
            byte[] key24 = new byte[24];
            System.arraycopy(key16, 0, key24, 0, 16);
            System.arraycopy(key16, 0, key24, 16, 8);

            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key24, "DESede"));
            return cipher.doFinal(data8);
        } catch (Exception e) {
            throw new IllegalStateException("TDES error", e);
        }
    }

    private static byte[] parseHex(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " blank");
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if ((v.length() % 2) != 0 || !v.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException(label + " invalid hex");
        }
        return hexToBytes(v);
    }

    private static void applyMaskInPlace(byte[] target, byte[] mask) {
        for (int i = 0; i < target.length; i++) {
            target[i] &= mask[i];
        }
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte)
                ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}