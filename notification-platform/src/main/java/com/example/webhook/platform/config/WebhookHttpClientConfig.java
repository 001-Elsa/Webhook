package com.example.webhook.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import com.example.webhook.platform.service.WebhookUrlValidator;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import java.net.UnknownHostException;
import java.net.InetAddress;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;

@Configuration
public class WebhookHttpClientConfig {
    @Bean
    RestClientCustomizer webhookRestClientTimeouts(
            @Value("${webhook.http.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${webhook.http.read-timeout-ms:5000}") long readTimeoutMs,
            WebhookUrlValidator urlValidator) {
        return builder -> {
            var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(new DnsResolver() {
                        @Override
                        public InetAddress[] resolve(String host) throws UnknownHostException {
                            try {
                                return urlValidator.resolveAndValidate(host);
                            } catch (IllegalArgumentException ex) {
                                UnknownHostException failure = new UnknownHostException("Blocked webhook host: " + host);
                                failure.initCause(ex);
                                throw failure;
                            }
                        }
                        @Override
                        public String resolveCanonicalHostname(String host) throws UnknownHostException {
                            resolve(host);
                            return host;
                        }
                    })
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                            .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs)).build())
                    .build();
            var client = HttpClients.custom().setConnectionManager(connectionManager).disableRedirectHandling()
                    .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                            .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs)).build())
                    .build();
            HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(client);
            builder.requestFactory(requestFactory);
        };
    }
}
