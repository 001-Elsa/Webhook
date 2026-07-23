package com.example.webhook.platform.service;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class WebhookUrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private final Set<String> allowedPrivateHosts;

    public WebhookUrlValidator() {
        this("");
    }

    @Autowired
    public WebhookUrlValidator(@Value("${webhook.security.allowed-private-hosts:}") String allowedPrivateHosts) {
        this.allowedPrivateHosts = Arrays.stream(allowedPrivateHosts.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

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
        resolveAndValidate(host);
    }

    private URI parse(String rawUrl) {
        try {
            return URI.create(rawUrl).normalize();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Webhook URL is invalid", ex);
        }
    }

    public InetAddress[] resolveAndValidate(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (!allowedPrivateHosts.contains(host.toLowerCase(Locale.ROOT))) {
                for (InetAddress address : addresses) {
                    if (isBlockedAddress(address)) {
                        throw new IllegalArgumentException("Webhook URL resolves to a private or local address");
                    }
                }
            }
            return addresses;
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException illegalArgumentException) throw illegalArgumentException;
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
