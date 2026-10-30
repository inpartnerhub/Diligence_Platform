package com.inpartnerhub.diligence.search.internal;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
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
        var transport = ApacheHttpClient5TransportBuilder.builder(host);
        if (properties.hasCredentials()) {
            var credentials = new BasicCredentialsProvider();
            credentials.setCredentials(new AuthScope(host),
                    new UsernamePasswordCredentials(properties.username(), properties.password().toCharArray()));
            transport.setHttpClientConfigCallback(client -> client.setDefaultCredentialsProvider(credentials));
        }
        return new OpenSearchClient(transport.build());
    }
}
