package com.example.webhook.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class WebhookHttpClientConfig {
    @Bean
    RestClientCustomizer webhookRestClientTimeouts(
            @Value("${webhook.http.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${webhook.http.read-timeout-ms:5000}") long readTimeoutMs) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Math.toIntExact(connectTimeoutMs));
            requestFactory.setReadTimeout(Math.toIntExact(readTimeoutMs));
            builder.requestFactory(requestFactory);
        };
    }
}
