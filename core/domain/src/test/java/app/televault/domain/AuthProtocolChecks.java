package app.televault.domain;

import java.util.Map;
import static app.televault.domain.AuthProtocol.*;

/** Dependency-free assertions against the production domain policy. No real Telegram calls. */
public final class AuthProtocolChecks {
    private static int checks;
    private static void check(boolean condition, String context) {
        checks++;
        if (!condition) throw new AssertionError(context);
    }
    public static void main(String[] args) {
        var states = Map.ofEntries(
            Map.entry("WaitTdlibParameters", State.PARAMETERS), Map.entry("WaitPhoneNumber", State.PHONE),
            Map.entry("WaitCode", State.CODE), Map.entry("WaitPassword", State.PASSWORD),
            Map.entry("WaitEmailAddress", State.EMAIL), Map.entry("WaitEmailCode", State.EMAIL_CODE),
            Map.entry("WaitOtherDeviceConfirmation", State.OTHER_DEVICE), Map.entry("WaitRegistration", State.REGISTRATION),
            Map.entry("WaitPremiumPurchase", State.PREMIUM_REQUIRED), Map.entry("Ready", State.READY),
            Map.entry("LoggingOut", State.LOGGING_OUT), Map.entry("Closing", State.CLOSING), Map.entry("Closed", State.CLOSED));
        for (var pair : states.entrySet()) check(map("authorizationState" + pair.getKey()) == pair.getValue(), "Auth mapping " + pair.getKey());
        check(map("authorizationStateFuture") == State.UNSUPPORTED, "Future auth must fail closed");
        var allowed = Map.of(Action.PHONE, State.PHONE, Action.CODE, State.CODE, Action.RESEND_CODE, State.CODE, Action.PASSWORD, State.PASSWORD,
            Action.EMAIL, State.EMAIL, Action.EMAIL_CODE, State.EMAIL_CODE, Action.REGISTER, State.REGISTRATION, Action.LOGOUT, State.READY);
        for (State state : State.values()) for (Action action : Action.values())
            check(permits(state, action) == (action == Action.PHONE ? java.util.Set.of(State.PHONE, State.CODE, State.PASSWORD, State.EMAIL, State.EMAIL_CODE, State.REGISTRATION, State.PREMIUM_REQUIRED).contains(state) : allowed.get(action) == state), "Command gating " + state + "/" + action);
        check(sanitize(406, "FLOOD_WAIT_123 secret").kind() == Failure.REQUEST_FAILED, "406 opaque error");
        check(sanitize(406, "FLOOD_WAIT_123 secret").retryAfterSeconds() == 0, "406 string not processed");
        check(sanitize(429, "FLOOD_WAIT_120").retryAfterSeconds() == 120, "Server flood wait");
        check(sanitize(429, "Too Many Requests: retry after 72").retryAfterSeconds() == 72, "Retry-after format");
        check(sanitize(429, "FLOOD_WAIT_999999999999999999999999999999").retryAfterSeconds() == 60, "Overflow safely handled");
        check(sanitize(429, "FLOOD_WAIT_0").retryAfterSeconds() == 1, "No immediate retry loop");
        check(sanitize(500, "private session details").kind() == Failure.NETWORK, "Network error mapping");
        check(sanitize(400, "user password").kind() == Failure.INVALID_INPUT, "Invalid input mapping");
        check(!sanitize(401, "private password").toString().contains("private password"), "Secrets not echoed");
        // Fake test-only login sequence: server events alone decide Ready, never a command's Ok.
        State current = map("authorizationStateWaitPhoneNumber");
        check(permits(current, Action.PHONE), "Phone submit");
        check(current != State.READY, "Submitting phone does not authorize");
        current = map("authorizationStateWaitCode");
        check(!permits(current, Action.PASSWORD), "Reject premature 2FA");
        current = map("authorizationStateWaitPassword");
        check(permits(current, Action.PASSWORD), "Password submit");
        current = map("authorizationStateReady");
        check(permits(current, Action.LOGOUT), "Authorized logout");
        current = map("authorizationStateLoggingOut");
        check(!permits(current, Action.LOGOUT), "No repeated logout");
        current = map("authorizationStateClosed");
        check(!permits(current, Action.CODE), "Closed rejects secrets");
        System.out.println("PASS: " + checks + " production AuthProtocol assertions; simulated states only");
    }
}
