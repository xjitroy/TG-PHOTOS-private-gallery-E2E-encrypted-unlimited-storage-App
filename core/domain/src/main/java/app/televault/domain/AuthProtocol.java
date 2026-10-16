package app.televault.domain;

/** Pure state/command policy shared by the production adapter and offline tests. */
public final class AuthProtocol {
    private AuthProtocol() {}

    public enum State {
        STARTING, PARAMETERS, PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE,
        OTHER_DEVICE, REGISTRATION, PREMIUM_REQUIRED, READY, LOGGING_OUT, CLOSING,
        CLOSED, UNSUPPORTED, SETUP_REQUIRED
    }

    public enum Action { PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE, REGISTER, RESEND_CODE, LOGOUT }

    public static State map(String type) {
        return switch (type) {
            case "authorizationStateWaitTdlibParameters" -> State.PARAMETERS;
            case "authorizationStateWaitPhoneNumber" -> State.PHONE;
            case "authorizationStateWaitCode" -> State.CODE;
            case "authorizationStateWaitPassword" -> State.PASSWORD;
            case "authorizationStateWaitEmailAddress" -> State.EMAIL;
            case "authorizationStateWaitEmailCode" -> State.EMAIL_CODE;
            case "authorizationStateWaitOtherDeviceConfirmation" -> State.OTHER_DEVICE;
            case "authorizationStateWaitRegistration" -> State.REGISTRATION;
            case "authorizationStateWaitPremiumPurchase" -> State.PREMIUM_REQUIRED;
            case "authorizationStateReady" -> State.READY;
            case "authorizationStateLoggingOut" -> State.LOGGING_OUT;
            case "authorizationStateClosing" -> State.CLOSING;
            case "authorizationStateClosed" -> State.CLOSED;
            default -> State.UNSUPPORTED;
        };
    }

    public static boolean permits(State state, Action action) {
        return switch (action) {
            case PHONE -> state == State.PHONE || state == State.CODE || state == State.PASSWORD || state == State.EMAIL || state == State.EMAIL_CODE || state == State.REGISTRATION || state == State.PREMIUM_REQUIRED;
            case CODE, RESEND_CODE -> state == State.CODE;
            case PASSWORD -> state == State.PASSWORD;
            case EMAIL -> state == State.EMAIL;
            case EMAIL_CODE -> state == State.EMAIL_CODE;
            case REGISTER -> state == State.REGISTRATION;
            case LOGOUT -> state == State.READY;
        };
    }

    public static String method(Action action) {
        return switch (action) {
            case PHONE -> "setAuthenticationPhoneNumber";
            case CODE -> "checkAuthenticationCode";
            case RESEND_CODE -> "resendAuthenticationCode";
            case PASSWORD -> "checkAuthenticationPassword";
            case EMAIL -> "setAuthenticationEmailAddress";
            case EMAIL_CODE -> "checkAuthenticationEmailCode";
            case REGISTER -> "registerUser";
            case LOGOUT -> "logOut";
        };
    }

    public enum CodeDelivery { NONE, TELEGRAM, SMS, CALL, FLASH_CALL, MISSED_CALL, SMS_WORD, SMS_PHRASE, FRAGMENT, UNSUPPORTED }
    public static CodeDelivery delivery(String type) {
        return switch(type) {
            case "" -> CodeDelivery.NONE;
            case "authenticationCodeTypeTelegramMessage" -> CodeDelivery.TELEGRAM;
            case "authenticationCodeTypeSms", "authenticationCodeTypeFirebaseAndroid", "authenticationCodeTypeFirebaseIos" -> CodeDelivery.SMS;
            case "authenticationCodeTypeCall" -> CodeDelivery.CALL;
            case "authenticationCodeTypeFlashCall" -> CodeDelivery.FLASH_CALL;
            case "authenticationCodeTypeMissedCall" -> CodeDelivery.MISSED_CALL;
            case "authenticationCodeTypeSmsWord" -> CodeDelivery.SMS_WORD;
            case "authenticationCodeTypeSmsPhrase" -> CodeDelivery.SMS_PHRASE;
            case "authenticationCodeTypeFragment" -> CodeDelivery.FRAGMENT;
            default -> CodeDelivery.UNSUPPORTED;
        };
    }
    public static boolean canResend(CodeDelivery next, long now, long availableAt, long floodUntil, boolean busy) {
        return !busy && next != CodeDelivery.NONE && next != CodeDelivery.UNSUPPORTED && now >= availableAt && now >= floodUntil;
    }

    public enum Failure { INVALID_INPUT, FLOOD_WAIT, NETWORK, REQUEST_FAILED, INTERNAL, TIMEOUT }
    public record SafeError(Failure kind, long retryAfterSeconds) {}

    /** TDLib 406 messages must not be processed; no raw error string ever reaches UI/logs. */
    public static SafeError sanitize(int code, String message) {
        if (code == 406) return new SafeError(Failure.REQUEST_FAILED, 0);
        if (code == 429) {
            var match = java.util.regex.Pattern.compile("(?:FLOOD_WAIT_|retry after )(\\d+)").matcher(message);
            long seconds = 60;
            if (match.find()) {
                try { seconds = Math.max(1, Long.parseLong(match.group(1))); }
                catch (NumberFormatException ignored) { seconds = 60; }
            }
            return new SafeError(Failure.FLOOD_WAIT, seconds);
        }
        if (code == 400) return new SafeError(Failure.INVALID_INPUT, 0);
        if (code >= 500) return new SafeError(Failure.NETWORK, 0);
        return new SafeError(Failure.REQUEST_FAILED, 0);
    }
}
