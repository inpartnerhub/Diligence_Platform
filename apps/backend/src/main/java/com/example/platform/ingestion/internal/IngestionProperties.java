package com.example.platform.ingestion.internal;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.ingestion")
record IngestionProperties(URI tikaUrl, String clamavHost, int clamavPort) {
}
