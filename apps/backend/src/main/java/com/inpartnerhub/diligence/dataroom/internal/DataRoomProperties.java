package com.inpartnerhub.diligence.dataroom.internal;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param watermarkZone  time zone of the timestamp stamped on viewed and downloaded documents */
@ConfigurationProperties("diligence.dataroom")
record DataRoomProperties(ZoneId watermarkZone) {

    DataRoomProperties {
        watermarkZone = watermarkZone == null ? ZoneId.of("Asia/Jakarta") : watermarkZone;
    }
}
