package com.inpartnerhub.diligence.admin.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/**
 * Decides which URLs outbound webhooks may call (PRD ADM-07, Bab 10.1). Targets must use HTTPS and every address the
 * host resolves to must be public, so an administrator cannot point a webhook at Vault, Keycloak, the actuator port
 * or the cloud metadata endpoint. The check runs when a subscription is saved and again before each delivery, which
 * narrows DNS-rebinding; production egress should additionally go through an allow-listing proxy.
 */
final class WebhookTargetPolicy {

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowInsecure;
    private final Resolver resolver;

    WebhookTargetPolicy(boolean allowInsecure, Resolver resolver) {
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    static WebhookTargetPolicy of(WebhookProperties properties) {
        return new WebhookTargetPolicy(properties.allowInsecureTargets(), InetAddress::getAllByName);
    }

    URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidRequestException("Target URL cannot be empty");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("Invalid target URL format");
        }
        var scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new InvalidRequestException("Target URL must use HTTP or HTTPS protocol");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidRequestException("Target URL must contain a valid host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidRequestException("Target URL must not contain credentials");
        }
        if (allowInsecure) {
            return uri;
        }
        if (!scheme.equalsIgnoreCase("https")) {
            throw new InvalidRequestException("Target URL must use HTTPS");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new InvalidRequestException("Target host cannot be resolved");
        }
        for (var address : addresses) {
            if (!isPublic(address)) {
                throw new InvalidRequestException("Target URL must resolve to a public internet address");
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        var b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // unique local fc00::/7
            }
            // NAT64 64:ff9b::/96 embeds an IPv4 address in the last four bytes
            if (first == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return isPublicIpv4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff);
            }
        }
        return true;
    }

    private static boolean isPublicIpv4(int a, int b, int c) {
        return !(a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && (b & 0xc0) == 64)   // carrier-grade NAT 100.64.0.0/10
                || (a == 169 && b == 254)           // link-local, cloud metadata
                || (a == 172 && (b & 0xf0) == 16)   // 172.16.0.0/12
                || (a == 192 && b == 168)
                || (a == 192 && b == 0 && c == 0)   // IETF protocol assignments
                || (a == 198 && (b == 18 || b == 19))); // benchmarking
    }
}
