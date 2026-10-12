package com.partnermesh.enrollment.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "partnermesh.provisioning")
public record ProvisioningProperties(URI baseUrl, Duration connectTimeout, Duration requestTimeout) {
}
