package com.inpartnerhub.diligence.dataroom;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * The data room's viewer watermark (PRD DOC-04) for other modules' PDF downloads, e.g. issued reports: every page is
 * stamped with the current user's name, e-mail, IP address and the time; without print permission the file is
 * encrypted with the print right removed.
 */
public interface PdfWatermark {

    void stampForCurrentUser(Path pdf, boolean printAllowed, OutputStream out) throws IOException;
}
