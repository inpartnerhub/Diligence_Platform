package com.inpartnerhub.diligence.ingestion;

import java.io.InputStream;

/** Port for malware scanning (ClamAV clamd over TCP). */
public interface ContentScanner {

    ScanResult scan(InputStream content);

    record ScanResult(boolean clean, String signature) {
    }
}
