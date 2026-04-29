
package com.qswitch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DukptUtilTest {

    private static final String BDK = "0123456789ABCDEFFEDCBA9876543210";
    private static final String KSN = "FFFF9876543210E00001";

    @Test
    void shouldDeriveStableWorkingKey() {
        String key = DukptUtil.deriveWorkingKey(BDK, KSN);

        assertEquals(32, key.length());
        assertTrue(key.matches("[0-9a-f]{32}"));

        // Regression test (internal consistency, NOT standard validation)
        assertEquals("042666b49184cfa368de9628d0397bc9", key);
    }

    @Test
    void shouldMatchKnownIpekVector() {
        String ipek = DukptUtil.deriveIpekHex(BDK, KSN);

        // 🔥 Official known DUKPT IPEK vector
        assertEquals("6ac292faa1315b4d858ab3a3d7d5933a", ipek);
    }

    @Test
    void shouldChangeWithCounter() {
        String key1 = DukptUtil.deriveWorkingKey(BDK, KSN);
        String key2 = DukptUtil.deriveWorkingKey(BDK, "FFFF9876543210E00002");

        assertNotEquals(key1, key2);
    }

    @Test
    void shouldApplyPinVariantCorrectly() {
        String base = DukptUtil.deriveWorkingKey(BDK, KSN);
        String pin = DukptUtil.derivePinKey(BDK, KSN);

        byte[] baseBytes = hexToBytes(base);
        byte[] pinBytes = hexToBytes(pin);

        byte[] expected = xor(baseBytes,
                hexToBytes("00000000000000FF00000000000000FF"));

        assertArrayEquals(expected, pinBytes);
    }

    @Test
    void shouldApplyMacVariantCorrectly() {
        String base = DukptUtil.deriveWorkingKey(BDK, KSN);
        String mac = DukptUtil.deriveMacKey(BDK, KSN);

        byte[] expected = xor(
                hexToBytes(base),
                hexToBytes("000000000000FF00000000000000FF00")
        );

        assertArrayEquals(expected, hexToBytes(mac));
    }

    @Test
    void shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> DukptUtil.deriveWorkingKey("BAD", KSN));

        assertThrows(IllegalArgumentException.class,
                () -> DukptUtil.deriveWorkingKey(BDK, "BAD"));
    }

    // =========================
    // Helpers (for validation)
    // =========================

    private byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte)
                    ((Character.digit(hex.charAt(i), 16) << 4)
                            + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}