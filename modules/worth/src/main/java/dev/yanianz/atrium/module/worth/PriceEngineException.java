package dev.yanianz.atrium.module.worth;

/** An external price engine could not be started or failed a lookup; the caller falls back to atrium. */
final class PriceEngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PriceEngineException(String message) {
        super(message);
    }

    PriceEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
