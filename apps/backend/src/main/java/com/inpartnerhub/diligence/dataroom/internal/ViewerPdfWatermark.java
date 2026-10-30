package com.inpartnerhub.diligence.dataroom.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.dataroom.PdfWatermark;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.shared.audit.RequestMetadata;

@Component
class ViewerPdfWatermark implements PdfWatermark {

    private final CurrentUserProvider currentUser;
    private final DataRoomProperties properties;

    ViewerPdfWatermark(CurrentUserProvider currentUser, DataRoomProperties properties) {
        this.currentUser = currentUser;
        this.properties = properties;
    }

    @Override
    public void stampForCurrentUser(Path pdf, boolean printAllowed, OutputStream out) throws IOException {
        var user = currentUser.requireCurrentUser();
        var name = user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
        var stamp = new Watermarker.Stamp(name, user.email(), RequestMetadata.capture().ipAddress(), Instant.now(),
                properties.watermarkZone());
        Watermarker.stampPdf(pdf, stamp, printAllowed, out);
    }
}
