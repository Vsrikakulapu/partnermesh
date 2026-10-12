package com.partnermesh.enrollment.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.partnermesh.enrollment.config.ProvisioningProperties;
import com.partnermesh.enrollment.domain.Enrollment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls the provisioning service. Every call carries the enrollment id as {@code Idempotency-Key}, so
 * a retry after a lost response (the downstream did the work but the reply never arrived) returns the
 * existing provision instead of creating a second one.
 */
@Component
public class ProvisioningClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final ProvisioningProperties properties;

    public ProvisioningClient(HttpClient provisioningHttpClient, ObjectMapper json, ProvisioningProperties properties) {
        this.http = provisioningHttpClient;
        this.json = json;
        this.properties = properties;
    }

    public ProvisioningResult provision(Enrollment enrollment) {
        HttpResponse<String> response;
        try {
            String body = json.writeValueAsString(Map.of(
                    "enrollmentId", enrollment.id().toString(),
                    "partnerId", enrollment.partnerId(),
                    "programCode", enrollment.programCode()));
            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", enrollment.id().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ProvisioningResult.RetryableFailure("interrupted");
        } catch (IOException io) {
            return new ProvisioningResult.RetryableFailure(io.getClass().getSimpleName() + ": " + io.getMessage());
        }
        return classify(response.statusCode(), response.body());
    }

    ProvisioningResult classify(int status, String body) {
        if (status == 200 || status == 201) {
            try {
                JsonNode node = json.readTree(body);
                String provisionId = node.path("provisionId").asText("");
                if (!provisionId.isBlank()) {
                    return new ProvisioningResult.Success(provisionId);
                }
            } catch (IOException ignored) {
                // fall through: a 2xx without a usable body is treated as transient
            }
            return new ProvisioningResult.RetryableFailure("HTTP " + status + " without provisionId");
        }
        String reason = "HTTP " + status + ": " + abbreviate(body);
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return new ProvisioningResult.RetryableFailure(reason);
        }
        return new ProvisioningResult.PermanentFailure(reason);
    }

    private URI endpoint() {
        return properties.baseUrl().resolve("/api/v1/provisions");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
