package com.partnermesh.enrollment.config;

import com.partnermesh.enrollment.outbox.BackoffPolicy;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient provisioningHttpClient(ProvisioningProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Bean
    BackoffPolicy backoffPolicy(OutboxProperties properties) {
        return new BackoffPolicy(properties.backoffBase(), properties.backoffMax(), properties.backoffMultiplier());
    }
}
