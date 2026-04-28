package com.qswitch.listener;

import com.qswitch.dao.EventDAO;
import com.qswitch.dao.TransactionDAO;
import com.qswitch.service.SecurityService;
import com.qswitch.service.TransactionService;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOResponseListener;
import org.jpos.iso.ISOSource;
import org.jpos.iso.MUX;
import org.jpos.util.NameRegistrar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchListenerTest {

    @Test
    void shouldSendSecurityDeclineWhenValidationFails() throws Exception {
        SwitchListener listener = new SwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.invalid("96", "MAC mismatch"), false, null)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "123456", "654321123456", "000000000100");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        assertEquals("96", source.lastSent.getString(39));
        assertEquals("123456", source.lastSent.getString(11));
        assertEquals("654321123456", source.lastSent.getString(37));
    }

    @Test
    void shouldSendLocalApprovalWhenSecurityValidAndMuxMissing() throws Exception {
        SwitchListener listener = new SwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), false, null)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "111111", "999999999999", "000000000500");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        assertEquals("00", source.lastSent.getString(39));
        assertEquals("111111", source.lastSent.getString(11));
        assertEquals("999999999999", source.lastSent.getString(37));
    }

    @Test
    void shouldSend96WhenAmountIsInvalid() throws Exception {
        SwitchListener listener = new SwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), false, null)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "222222", "888888888888", "BAD-AMOUNT");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        assertEquals("96", source.lastSent.getString(39));
        assertEquals("000000", source.lastSent.getString(11));
        assertEquals("000000000000", source.lastSent.getString(37));
    }

    @Test
    void shouldAttachMacWhenSecurityFieldsExist() throws Exception {
        byte[] expectedMac = new byte[] {0x01, 0x23, 0x45, 0x67, 0x11, 0x22, 0x33, 0x44};
        SwitchListener listener = new SwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), true, expectedMac)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "333333", "777777777777", "000000000100");
        request.set(52, "1234567890ABCDEF");
        request.set(62, "FFFF9876543210E00001");
        request.set(64, "AAAAAAAAAAAAAAAA");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("00", source.lastSent.getString(39));
        assertArrayEquals(expectedMac, source.lastSent.getBytes(64));
    }

    // =================== MUX TESTS ===================

    @Test
    void shouldForwardMuxResponseWhenMuxReturnsValidResponse() throws Exception {
        ISOMsg muxResp = new ISOMsg();
        muxResp.setMTI("0210");
        muxResp.set(39, "00");
        muxResp.set(11, "444444");
        muxResp.set(37, "666666666666");

        SwitchListener listener = new MuxInjectableSwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), false, null),
            new StubMux(muxResp, false)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "444444", "666666666666", "000000000200");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        assertEquals("00", source.lastSent.getString(39));
    }

    @Test
    void shouldSend91WhenMuxReturnsNullTimeout() throws Exception {
        SwitchListener listener = new MuxInjectableSwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), false, null),
            new StubMux(null, false)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "555555", "555555555555", "000000000300");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        assertEquals("91", source.lastSent.getString(39));
    }

    @Test
    void shouldFallbackToLocalWhenMuxThrowsException() throws Exception {
        SwitchListener listener = new MuxInjectableSwitchListener(
            new TransactionService(new TransactionDAO(), new EventDAO()),
            new StubSecurityService(SecurityService.ValidationResult.valid(), false, null),
            new StubMux(null, true)
        );

        CapturingSource source = new CapturingSource();
        ISOMsg request = buildRequest("0200", "666666", "111111111111", "000000000400");

        boolean processed = listener.process(source, request);

        assertTrue(processed);
        assertNotNull(source.lastSent);
        assertEquals("0210", source.lastSent.getMTI());
        // Fell back to local: positive amount → approved
        assertEquals("00", source.lastSent.getString(39));
    }

    private static ISOMsg buildRequest(String mti, String stan, String rrn, String amount) throws ISOException {
        ISOMsg request = new ISOMsg();
        request.setMTI(mti);
        request.set(11, stan);
        request.set(37, rrn);
        request.set(4, amount);
        return request;
    }

    private static final class MuxInjectableSwitchListener extends SwitchListener {
        private final MUX mux;

        private MuxInjectableSwitchListener(TransactionService ts, SecurityService ss, MUX mux) {
            super(ts, ss);
            this.mux = mux;
        }

        @Override
        protected MUX lookupMux() throws NameRegistrar.NotFoundException {
            if (mux == null) {
                throw new NameRegistrar.NotFoundException("mux.acquirer-mux");
            }
            return mux;
        }
    }

    private static final class StubMux implements MUX {
        private final ISOMsg fixedResponse;
        private final boolean throwOnRequest;

        private StubMux(ISOMsg fixedResponse, boolean throwOnRequest) {
            this.fixedResponse = fixedResponse;
            this.throwOnRequest = throwOnRequest;
        }

        @Override
        public ISOMsg request(ISOMsg request, long timeout) throws ISOException {
            if (throwOnRequest) {
                throw new ISOException("StubMux simulated error");
            }
            return fixedResponse;
        }

        @Override
        public void request(ISOMsg request, long timeout, ISOResponseListener listener, Object handBack) {
            // not used in tests
        }

        @Override
        public void send(ISOMsg msg) {
            // not used in tests
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    private static final class CapturingSource implements ISOSource {
        private ISOMsg lastSent;

        @Override
        public void send(ISOMsg m) {
            this.lastSent = m;
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }

    private static final class StubSecurityService extends SecurityService {
        private final ValidationResult validationResult;
        private final boolean hasSecurityFields;
        private final byte[] responseMac;

        private StubSecurityService(ValidationResult validationResult, boolean hasSecurityFields, byte[] responseMac) {
            this.validationResult = validationResult;
            this.hasSecurityFields = hasSecurityFields;
            this.responseMac = responseMac;
        }

        @Override
        public ValidationResult validateRequestSecurity(ISOMsg request) {
            return validationResult;
        }

        @Override
        public boolean hasAnySecurityField(ISOMsg msg) {
            return hasSecurityFields;
        }

        @Override
        public byte[] generateResponseMac(ISOMsg request, ISOMsg response) {
            return responseMac;
        }
    }
}
