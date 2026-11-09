package com.inpartnerhub.diligence.admin.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Delivers webhooks to the addresses the target policy checked (#87). {@link java.net.http.HttpClient} resolves the
 * host again when it connects, which leaves a DNS-rebinding window after the check; here the policy is the client's
 * DNS resolver, so the check and the connection use the same answer. TLS still verifies the certificate against the
 * host name. Redirects, retries, cookies, system proxies and connection reuse are off.
 */
final class PinnedWebhookTransport implements WebhookTransport, AutoCloseable {

    private final CloseableHttpClient client;

    PinnedWebhookTransport(WebhookTargetPolicy policy) {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PolicyDnsResolver(policy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .setRedirectsEnabled(false)
                        .build())
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
    }

    @Override
    public int post(URI target, Map<String, String> headers, String jsonBody) throws IOException {
        var request = new HttpPost(target);
        headers.forEach(request::setHeader);
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        // The body is not consumed: closing the response drops the connection, which is never reused.
        try (var response = client.executeOpen(null, request, null)) {
            return response.getCode();
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private record PolicyDnsResolver(WebhookTargetPolicy policy) implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return policy.resolveForDelivery(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
