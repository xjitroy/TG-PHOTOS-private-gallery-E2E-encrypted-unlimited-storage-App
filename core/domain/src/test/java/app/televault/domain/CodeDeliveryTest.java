package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.AuthProtocol.*;
public class CodeDeliveryTest {
    @Test public void resendHonorsBothTelegramCountdownAndFloodWait() {
        assertFalse(canResend(CodeDelivery.SMS, 5000, 6000, 0, false));
        assertFalse(canResend(CodeDelivery.CALL, 7000, 6000, 8000, false));
        assertFalse(canResend(CodeDelivery.CALL, 8000, 6000, 8000, true));
        assertTrue(canResend(CodeDelivery.SMS, 8000, 6000, 8000, false));
        assertFalse(canResend(CodeDelivery.NONE, 8000, 0, 0, false));
        assertFalse(canResend(CodeDelivery.UNSUPPORTED, 8000, 0, 0, false));
        assertEquals(CodeDelivery.UNSUPPORTED, delivery("authenticationCodeTypeFuture"));
        assertEquals(CodeDelivery.SMS, delivery("authenticationCodeTypeSms"));
        assertEquals(CodeDelivery.CALL, delivery("authenticationCodeTypeCall"));
        assertTrue(permits(State.CODE, Action.RESEND_CODE));
        assertFalse(permits(State.READY, Action.RESEND_CODE));
        assertEquals("resendAuthenticationCode", method(Action.RESEND_CODE));
    }
}
