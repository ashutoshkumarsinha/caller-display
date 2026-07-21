package com.example.sip.push;

import com.example.sip.model.RingingEvent;
import com.example.sip.model.PushTokenRecord;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a platform-specific push for an incoming ringing event.
 */
public interface PushClient {

    CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token);

    final class PushResult {
        private final boolean success;
        private final int statusCode;
        private final String errorCode;
        private final boolean tokenInvalid;

        public PushResult(boolean success, int statusCode, String errorCode, boolean tokenInvalid) {
            this.success = success;
            this.statusCode = statusCode;
            this.errorCode = errorCode;
            this.tokenInvalid = tokenInvalid;
        }

        public static PushResult ok(int statusCode) {
            return new PushResult(true, statusCode, null, false);
        }

        public static PushResult failure(int statusCode, String errorCode, boolean tokenInvalid) {
            return new PushResult(false, statusCode, errorCode, tokenInvalid);
        }

        public boolean success() {
            return success;
        }

        public int statusCode() {
            return statusCode;
        }

        public String errorCode() {
            return errorCode;
        }

        public boolean tokenInvalid() {
            return tokenInvalid;
        }
    }
}
