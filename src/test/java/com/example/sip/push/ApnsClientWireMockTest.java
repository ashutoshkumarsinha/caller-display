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
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1, T2.4, T2.5, T2.7 — APNS WireMock contracts.
 */
class ApnsClientWireMockTest {

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
        token = new PushTokenRecord("device-token-1", PushPlatform.APNS, 5);
        config = GatewayConfig.builder()
                .apnsUrl(wireMock.baseUrl())
                .apnsPushType("voip")
                .apnsPriority("10")
                .apnsTopic("com.example.app.voip")
                .apnsBearer("old-token")
                .pushHttpTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Test
    void successSendsRequiredApnsHeadersAndPlatform() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ApnsClient client = new ApnsClient(config, httpClient, mapper);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertTrue(result.success());
        wireMock.verify(postRequestedFor(urlPathEqualTo("/3/device/device-token-1"))
                .withHeader("apns-push-type", equalTo("voip"))
                .withHeader("apns-priority", equalTo("10"))
                .withHeader("apns-topic", equalTo("com.example.app.voip"))
                .withHeader("apns-id", equalTo(event.eventId()))
                .withHeader("Authorization", equalTo("Bearer old-token"))
                .withRequestBody(containing("\"platform\":\"APNS\"")));
    }

    @Test
    void goneMarksTokenInvalid() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(410).withBody("{\"reason\":\"BadDeviceToken\"}")));

        ApnsClient client = new ApnsClient(config, httpClient, mapper);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(result.tokenInvalid());
        assertEquals(410, result.statusCode());
    }

    @Test
    void unauthorizedRefreshesBearerAndRetriesWithoutInvalidatingToken() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        BearerTokenProvider auth = new BearerTokenProvider("old-token", () -> {
            refreshes.incrementAndGet();
            return "new-token";
        });

        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .withHeader("Authorization", equalTo("Bearer old-token"))
                .willReturn(aResponse().withStatus(401).withBody("{\"reason\":\"ExpiredProviderToken\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .withHeader("Authorization", equalTo("Bearer new-token"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ApnsClient client = new ApnsClient(config, httpClient, mapper, auth);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals(1, refreshes.get());
        assertEquals(1, auth.refreshCount());
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/3/device/device-token-1")));
    }

    @Test
    void forbiddenDoesNotMarkTokenInvalid() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/3/device/device-token-1"))
                .willReturn(aResponse().withStatus(403).withBody("{}")));

        BearerTokenProvider auth = new BearerTokenProvider("old-token", () -> null);
        ApnsClient client = new ApnsClient(config, httpClient, mapper, auth);
        PushClient.PushResult result = client.send(event, token).get(3, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertFalse(result.tokenInvalid());
        assertEquals(403, result.statusCode());
    }
}
