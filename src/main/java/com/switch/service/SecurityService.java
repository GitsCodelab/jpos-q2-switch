package com.qswitch.service;

import com.qswitch.util.DukptUtil;
import com.qswitch.util.MacUtil;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;

import java.nio.charset.StandardCharsets;

public class SecurityService {
    private static final String BASE_DERIVATION_KEY_ENV = "BASE_DERIVATION_KEY";
    private static final String DECLINE_SECURITY_ERROR = "96";

    public ValidationResult validateRequestSecurity(ISOMsg request) {
        if (!hasAnySecurityField(request)) {
            return ValidationResult.valid();
        }
        if (!request.hasField(52) || !request.hasField(62) || !request.hasField(64)) {
            return ValidationResult.invalid(DECLINE_SECURITY_ERROR, "Missing one or more security fields (52/62/64)");
        }

        String pinBlockHex = readFieldAsHex(request, 52);
        String ksn = safeString(request, 62);
        if (!pinBlockHex.matches("(?i)[0-9a-f]{16}")) {
            return ValidationResult.invalid(DECLINE_SECURITY_ERROR, "Invalid PIN block format");
        }
        if (ksn.length() < 10) {
            return ValidationResult.invalid(DECLINE_SECURITY_ERROR, "Invalid KSN length");
        }

        String providedMacHex = readFieldAsHex(request, 64);
        String expectedMacHex = generateRequestMacHex(request);
        if (!providedMacHex.equalsIgnoreCase(expectedMacHex)) {
            return ValidationResult.invalid(DECLINE_SECURITY_ERROR, "MAC mismatch");
        }
        return ValidationResult.valid();
    }

    public boolean hasAnySecurityField(ISOMsg msg) {
        return msg.hasField(52) || msg.hasField(62) || msg.hasField(64);
    }

    public byte[] generateResponseMac(ISOMsg request, ISOMsg response) {
        String pinBlockHex = readFieldAsHex(request, 52);
        String ksn = safeString(request, 62);
        String workingKey = DukptUtil.deriveWorkingKey(readBaseDerivationKey(), ksn);
        String payload = safeMti(response)
            + "|" + safeString(response, 11)
            + "|" + safeString(response, 37)
            + "|" + safeString(response, 39)
            + "|" + pinBlockHex
            + "|" + ksn;
        String macHex = MacUtil.hmacSha256Hex(payload, workingKey).substring(0, 16);
        return hexToBytes(macHex);
    }

    public String generateRequestMacHex(ISOMsg request) {
        String pinBlockHex = readFieldAsHex(request, 52);
        String ksn = safeString(request, 62);
        String workingKey = DukptUtil.deriveWorkingKey(readBaseDerivationKey(), ksn);
        String payload = safeMti(request)
            + "|" + safeString(request, 11)
            + "|" + safeString(request, 37)
            + "|" + safeString(request, 4)
            + "|" + pinBlockHex
            + "|" + ksn;
        return MacUtil.hmacSha256Hex(payload, workingKey).substring(0, 16);
    }

    private String readBaseDerivationKey() {
        String value = System.getenv(BASE_DERIVATION_KEY_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + BASE_DERIVATION_KEY_ENV);
        }
        return value;
    }

    private String safeString(ISOMsg msg, int field) {
        return msg.hasField(field) ? safeString(msg.getString(field)) : "";
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String safeMti(ISOMsg msg) {
        try {
            return safeString(msg.getMTI());
        } catch (ISOException e) {
            return "";
        }
    }

    private String readFieldAsHex(ISOMsg msg, int field) {
        String text = safeString(msg.getString(field));
        if (text.matches("(?i)[0-9a-f]+")) {
            return text;
        }
        try {
            byte[] data = msg.getBytes(field);
            return bytesToHex(data);
        } catch (Exception ignored) {
            return bytesToHex(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final String responseCode;
        private final String reason;

        private ValidationResult(boolean valid, String responseCode, String reason) {
            this.valid = valid;
            this.responseCode = responseCode;
            this.reason = reason;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, "00", "OK");
        }

        public static ValidationResult invalid(String responseCode, String reason) {
            return new ValidationResult(false, responseCode, reason);
        }

        public boolean isValid() {
            return valid;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getReason() {
            return reason;
        }
    }
}
