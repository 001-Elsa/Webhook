package com.example.webhook.platform.service;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Component
public class WebhookUrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validate(String rawUrl) {
        URI uri = parse(rawUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Webhook URL must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL must not contain user info");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL host is required");
        }
        if ("localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Webhook URL must not target localhost");
        }
        for (InetAddress address : resolve(host)) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Webhook URL resolves to a private or local address");
            }
        }
    }

    private URI parse(String rawUrl) {
        try {
            return URI.create(rawUrl).normalize();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Webhook URL is invalid", ex);
        }
    }

    private InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Webhook URL host cannot be resolved", ex);
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isIpv6UniqueLocal(address);
    }

    private boolean isIpv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
