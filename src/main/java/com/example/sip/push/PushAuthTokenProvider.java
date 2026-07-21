package com.example.sip.push;

/**
 * Supplies Authorization Bearer tokens for APNS/FCM and supports one-shot refresh on 401/403.
 */
public interface PushAuthTokenProvider {

    /** Current bearer token (without {@code Bearer } prefix). May be blank. */
    String currentToken();

    /**
     * Attempt to refresh credentials.
     *
     * @return true if a new token is available for retry
     */
    boolean refresh();

    /** Number of refresh attempts (for tests / metrics). */
    default int refreshCount() {
        return 0;
    }
}
