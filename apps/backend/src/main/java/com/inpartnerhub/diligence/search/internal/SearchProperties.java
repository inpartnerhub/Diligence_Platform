package com.inpartnerhub.diligence.search.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("diligence.search")
record SearchProperties(URI url, String indexPrefix) {
}
