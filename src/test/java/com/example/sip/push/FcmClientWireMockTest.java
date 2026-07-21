package com.example.sip.push;

import com.example.sip.config.GatewayConfig;
import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.2, T2.4, T2.6, T2.7 — FCM WireMock contracts.
 */
class FcmClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private GatewayConfig config;
    private ObjectMapper mapper;
    private HttpClient httpClient;
    private RingingEvent event;
    private PushTokenRecord token;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        event = RingingEventFixtures.ringingE164();
        token = new PushTokenRecord("fcm-device", PushPlatform.FCM, 2);
        config = GatewayConfig.builder()
                .fcmUrl(wireMock.baseUrl() + "/v1/projects/demo/messages:send")
                .fcmBearer("fcm-old")
                .pushHttpTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Test
    void successIncludesPlatformInBody() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"projects/demo/messages/1\"}")));

        FcmClient client = new FcmClient(config, httpClient, mapper);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertTrue(result.success());
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/projects/demo/messages:send"))
                .withHeader("Authorization", equalTo("Bearer fcm-old"))
                .withRequestBody(containing("\"platform\":\"FCM\""))
                .withRequestBody(containing("\"alertMessage\":\"Receiving call from +12125559843\"")));
    }

    @Test
    void unregisteredMarksTokenInvalid() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody("{\"error\":{\"status\":\"UNREGISTERED\",\"message\":\"Requested entity was not found.\"}}")));

        FcmClient client = new FcmClient(config, httpClient, mapper);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.tokenInvalid());
    }

    @Test
    void unauthorizedRefreshesWithoutInvalidatingToken() throws Exception {
        BearerTokenProvider auth = new BearerTokenProvider("fcm-old", () -> "fcm-new");

        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .withHeader("Authorization", equalTo("Bearer fcm-old"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"UNAUTHENTICATED\"}")));
        wireMock.stubFor(post(urlEqualTo("/v1/projects/demo/messages:send"))
                .withHeader("Authorization", equalTo("Bearer fcm-new"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        FcmClient client = new FcmClient(config, httpClient, mapper, auth);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertFalse(result.tokenInvalid());
        assertEquals(1, auth.refreshCount());
    }
}
