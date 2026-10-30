package com.example.platform.search.internal;

import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenSearchConfiguration {

    @Bean
    OpenSearchClient openSearchClient(SearchProperties properties) {
        var url = properties.url();
        var host = new HttpHost(url.getScheme(), url.getHost(), url.getPort());
        return new OpenSearchClient(ApacheHttpClient5TransportBuilder.builder(host).build());
    }
}
