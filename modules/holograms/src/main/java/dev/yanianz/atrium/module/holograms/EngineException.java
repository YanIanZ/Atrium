package dev.yanianz.atrium.module.holograms;

/** The selected external engine failed; the caller falls back to the native engine. */
final class EngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    EngineException(String message) {
        super(message);
    }

    EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
