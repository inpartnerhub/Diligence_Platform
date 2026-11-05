package com.inpartnerhub.diligence.dataroom.internal;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param watermarkZone          time zone of the timestamp stamped on viewed and downloaded documents
 * @param bulkDownloadMaxFiles   most documents one bulk ZIP download may contain (DOC-05)
 * @param bulkDownloadMaxSize    largest total size of the documents in one bulk ZIP download (DOC-05)
 */
@ConfigurationProperties("diligence.dataroom")
record DataRoomProperties(ZoneId watermarkZone, Integer bulkDownloadMaxFiles, DataSize bulkDownloadMaxSize) {

    DataRoomProperties {
        watermarkZone = watermarkZone == null ? ZoneId.of("Asia/Jakarta") : watermarkZone;
        bulkDownloadMaxFiles = bulkDownloadMaxFiles == null || bulkDownloadMaxFiles < 1 ? 500 : bulkDownloadMaxFiles;
        bulkDownloadMaxSize = bulkDownloadMaxSize == null || bulkDownloadMaxSize.toBytes() < 1
                ? DataSize.ofGigabytes(2) : bulkDownloadMaxSize;
    }
}
