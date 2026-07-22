package com.example.enrollment.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PUT /v1/push-tokens/{msisdn} request body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PushTokenRequest {

    private String platform;
    private String deviceToken;
    private String appId;
    private Long sequenceHint;

    public String platform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String deviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public String appId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Long sequenceHint() {
        return sequenceHint;
    }

    public void setSequenceHint(Long sequenceHint) {
        this.sequenceHint = sequenceHint;
    }
}
