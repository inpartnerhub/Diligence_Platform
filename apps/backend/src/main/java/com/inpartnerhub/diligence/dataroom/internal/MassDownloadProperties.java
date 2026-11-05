package com.inpartnerhub.diligence.dataroom.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls against mass document exfiltration (DOC-05, issue #62).
 *
 * @param stepUpFiles    bulk downloads with at least this many documents need a recent sign-in
 * @param stepUpMaxAge   how recent that sign-in (password + MFA) must be
 * @param alertDocuments documents one user may download within {@code alertWindow} before it is flagged
 * @param alertWindow    detection window
 */
@ConfigurationProperties("diligence.dataroom.mass-download")
record MassDownloadProperties(Integer stepUpFiles, Duration stepUpMaxAge, Integer alertDocuments,
        Duration alertWindow) {

    MassDownloadProperties {
        stepUpFiles = stepUpFiles == null || stepUpFiles < 1 ? 50 : stepUpFiles;
        stepUpMaxAge = stepUpMaxAge == null || stepUpMaxAge.isNegative() ? Duration.ofMinutes(10) : stepUpMaxAge;
        alertDocuments = alertDocuments == null || alertDocuments < 1 ? 200 : alertDocuments;
        alertWindow = alertWindow == null || alertWindow.isZero() || alertWindow.isNegative()
                ? Duration.ofMinutes(15) : alertWindow;
    }
}
