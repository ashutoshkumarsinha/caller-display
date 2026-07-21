package com.example.sip.security;

/**
 * Fetches a new access token (OAuth client credentials, Vault lease, APNS JWT, etc.).
 */
@FunctionalInterface
public interface TokenRefreshClient {

    /**
     * @return fresh token, or {@code null} / blank value if refresh failed
     */
    AccessToken refresh();
}
