package com.example.enrollment.spml;

import com.example.enrollment.config.EnrollmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * SOAP SPML 2.0 {@code modifyRequest} client (generic broker integration).
 */
public final class SoapSpml20Client implements SpmlClient {

    private static final Logger LOG = LoggerFactory.getLogger(SoapSpml20Client.class);

    private final EnrollmentConfig config;
    private final HttpClient httpClient;

    public SoapSpml20Client(EnrollmentConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.spmlConnectTimeoutMs()))
                .build();
    }

    @Override
    public SpmlResult upsertPushToken(String subscriberIdentifier, String repositoryXml) {
        return modify(subscriberIdentifier, repositoryXml, "modify");
    }

    @Override
    public SpmlResult clearPushToken(String subscriberIdentifier, String repositoryXml) {
        return modify(subscriberIdentifier, repositoryXml, "modify");
    }

    @Override
    public long currentSequence(String subscriberIdentifier) {
        return 0L;
    }

    private SpmlResult modify(String subscriberIdentifier, String repositoryXml, String operation) {
        if (config.spmlEndpoint() == null || config.spmlEndpoint().isBlank()) {
            return SpmlResult.failure(500, "spml_misconfigured", "endpoint not set");
        }
        String body = buildModifyEnvelope(subscriberIdentifier, repositoryXml, operation);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.spmlEndpoint()))
                    .timeout(Duration.ofMillis(config.spmlReadTimeoutMs()))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"urn:oasis:names:tc:SPML:2:0:" + operation + "Request\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapResponse(response);
        } catch (Exception ex) {
            LOG.warn("SPML {} failed subscriber={}: {}", operation, subscriberIdentifier, ex.toString());
            return SpmlResult.upstream(ex.toString());
        }
    }

    static String buildModifyEnvelope(String subscriberIdentifier, String repositoryXml, String operation) {
        String escapedXml = repositoryXml
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:spml="urn:oasis:names:tc:SPML:2:0">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <spml:%sRequest requestID="%s">
                      <spml:objectIdentifier type="urn:com:example:subscriber">
                        <spml:id>%s</spml:id>
                      </spml:objectIdentifier>
                      <spml:modification operation="replace">
                        <spml:component name="RepositoryData">
                          <spml:value>%s</spml:value>
                        </spml:component>
                      </spml:modification>
                    </spml:%sRequest>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(operation, java.util.UUID.randomUUID(), escape(subscriberIdentifier), escapedXml, operation);
    }

    private static SpmlResult mapResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status >= 200 && status < 300
                && (body.contains("result=\"success\"") || body.contains("<spml:result>success</spml:result>"))) {
            return SpmlResult.ok();
        }
        if (status == 404 || body.toLowerCase().contains("nosuchobject")) {
            return SpmlResult.notFound(truncate(body));
        }
        if (body.contains("soap:Fault") || body.contains("faultstring")) {
            return SpmlResult.upstream(truncate(body));
        }
        if (status >= 200 && status < 300) {
            return SpmlResult.ok();
        }
        return SpmlResult.failure(status, "spml_http_" + status, truncate(body));
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String truncate(String body) {
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
