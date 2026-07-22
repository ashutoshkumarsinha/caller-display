package com.example.enrollment.spml;

import com.example.enrollment.config.EnrollmentConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoapSpml20ClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void postsModifyRequestWithRepositoryXml() {
        wireMock.stubFor(post(urlEqualTo("/spml"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml")
                        .withBody("""
                                <soapenv:Envelope>
                                  <soapenv:Body>
                                    <spml:modifyResponse result="success"/>
                                  </soapenv:Body>
                                </soapenv:Envelope>
                                """)));

        EnrollmentConfig config = EnrollmentConfig.builder()
                .spmlEndpoint(wireMock.baseUrl() + "/spml")
                .spmlConnectTimeoutMs(2000)
                .spmlReadTimeoutMs(5000)
                .build();
        SoapSpml20Client client = new SoapSpml20Client(config);
        String xml = SpmlPayloadFactory.upsertToken(
                "PushNotificationAppV1", 1, "tok", com.example.enrollment.model.PushPlatform.APNS);

        SpmlResult result = client.upsertPushToken("tel:+14155552671", xml);

        assertTrue(result.success());
        wireMock.verify(postRequestedFor(urlEqualTo("/spml"))
                .withRequestBody(containing("modifyRequest"))
                .withRequestBody(containing("tel:+14155552671"))
                .withRequestBody(containing("PushNotificationAppV1")));
    }
}
