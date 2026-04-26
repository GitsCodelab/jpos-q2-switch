package com.qswitch.service;

import org.jpos.iso.ISOMsg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityServiceTest {
    private final SecurityService securityService = new SecurityService();

    @Test
    void shouldValidateRequestWithValidMacAndSecurityFields() throws Exception {
        ISOMsg request = secureRequest("0200", "123456", "250011223344", "000000001000", "1122334455667788", "FFFF9876543210E00001");

        SecurityService.ValidationResult result = securityService.validateRequestSecurity(request);

        assertTrue(result.isValid());
        assertEquals("00", result.getResponseCode());
    }

    @Test
    void shouldRejectTamperedMessageWhenAmountChangesAfterMac() throws Exception {
        ISOMsg request = secureRequest("0200", "123456", "250011223344", "000000001000", "1122334455667788", "FFFF9876543210E00001");
        request.set(4, "000000009999");

        SecurityService.ValidationResult result = securityService.validateRequestSecurity(request);

        assertFalse(result.isValid());
        assertEquals("96", result.getResponseCode());
        assertEquals("MAC mismatch", result.getReason());
    }

    @Test
    void shouldRejectWhenPinBlockOrKsnIsMalformed() throws Exception {
        ISOMsg request = new ISOMsg();
        request.setMTI("0200");
        request.set(4, "000000001000");
        request.set(11, "123456");
        request.set(37, "250011223344");
        request.set(52, "BADPIN");
        request.set(62, "SHORT");
        request.set(64, "0011223344556677");

        SecurityService.ValidationResult result = securityService.validateRequestSecurity(request);

        assertFalse(result.isValid());
        assertEquals("96", result.getResponseCode());
    }

    @Test
    void shouldGenerateResponseMacDeterministically() throws Exception {
        ISOMsg request = secureRequest("0200", "123456", "250011223344", "000000001000", "1122334455667788", "FFFF9876543210E00001");
        ISOMsg response = new ISOMsg();
        response.setMTI("0210");
        response.set(11, "123456");
        response.set(37, "250011223344");
        response.set(39, "00");

        byte[] macA = securityService.generateResponseMac(request, response);
        byte[] macB = securityService.generateResponseMac(request, response);

        assertArrayEquals(macA, macB);
        assertEquals(8, macA.length);
    }

    private ISOMsg secureRequest(String mti, String stan, String rrn, String amount, String pinBlockHex, String ksn) throws Exception {
        ISOMsg request = new ISOMsg();
        request.setMTI(mti);
        request.set(4, amount);
        request.set(11, stan);
        request.set(37, rrn);
        request.set(52, pinBlockHex);
        request.set(62, ksn);
        request.set(64, securityService.generateRequestMacHex(request));
        return request;
    }
}
