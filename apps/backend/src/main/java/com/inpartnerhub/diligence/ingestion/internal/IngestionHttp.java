package com.inpartnerhub.diligence.ingestion.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP clients for the pipeline's services: short connect timeout, generous read timeout (request bodies are streamed). */
final class IngestionHttp {

    private IngestionHttp() {
    }

    static RestClient client(RestClient.Builder builder, URI baseUrl, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }
}
