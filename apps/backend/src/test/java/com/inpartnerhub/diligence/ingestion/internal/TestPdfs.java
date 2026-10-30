package com.inpartnerhub.diligence.ingestion.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Small generated PDFs for pipeline and watermark tests. */
public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] pages(int count) throws IOException {
        return pages(count, PDRectangle.A4);
    }

    public static byte[] pages(int count, PDRectangle size) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (var i = 1; i <= count; i++) {
                var page = new PDPage(size);
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, size.getHeight() - 72);
                    content.showText("Halaman " + i);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
