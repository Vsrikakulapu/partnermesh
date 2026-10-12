package com.partnermesh.enrollment.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.partnermesh.enrollment.config.ProvisioningProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProvisioningClientTest {

    private final ProvisioningClient client = new ProvisioningClient(HttpClient.newHttpClient(), new ObjectMapper(),
            new ProvisioningProperties(URI.create("http://127.0.0.1:1"), Duration.ofMillis(200), Duration.ofMillis(200)));

    @Test
    void successRequiresAProvisionId() {
        assertThat(client.classify(201, "{\"provisionId\":\"prv-1\"}")).isEqualTo(new ProvisioningResult.Success("prv-1"));
        assertThat(client.classify(200, "{\"provisionId\":\"prv-1\"}")).isEqualTo(new ProvisioningResult.Success("prv-1"));
        assertThat(client.classify(200, "{}")).isInstanceOf(ProvisioningResult.RetryableFailure.class);
        assertThat(client.classify(200, "not json")).isInstanceOf(ProvisioningResult.RetryableFailure.class);
    }

    @Test
    void transientStatusesAreRetryableAndClientErrorsArePermanent() {
        for (int status : new int[] {408, 425, 429, 500, 502, 503, 504}) {
            assertThat(client.classify(status, "")).as("HTTP %d", status).isInstanceOf(ProvisioningResult.RetryableFailure.class);
        }
        for (int status : new int[] {400, 401, 403, 404, 409, 422}) {
            assertThat(client.classify(status, "")).as("HTTP %d", status).isInstanceOf(ProvisioningResult.PermanentFailure.class);
        }
    }
}
