package com.inpartnerhub.diligence.ingestion.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("diligence.ingestion")
record IngestionProperties(URI tikaUrl, URI gotenbergUrl, String clamavHost, int clamavPort) {
}
