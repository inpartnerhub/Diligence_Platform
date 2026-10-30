package com.inpartnerhub.diligence.dataroom.internal;

/** Lifecycle of uploaded content (PRD Bab 8.3). Only CLEAN content is ever served. */
enum ScanStatus {
    PENDING, CLEAN, INFECTED, REJECTED, FAILED
}
