package com.example.platform.search.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.search")
record SearchProperties(URI url, String indexPrefix) {
}
