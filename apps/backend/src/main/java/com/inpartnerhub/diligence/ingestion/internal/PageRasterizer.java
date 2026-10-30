package com.inpartnerhub.diligence.ingestion.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Pre-renders PDF pages to PNG once per version, so a preview only has to stamp the viewer's watermark on stored
 * images (PRD: preview ≤ 3 s) and the PDF itself never reaches a browser that may not download it.
 */
final class PageRasterizer {

    /** Upper bound for the longer side of a page image, so oversized pages (plans, posters) stay small. */
    static final int MAX_PIXELS = 2400;

    @FunctionalInterface
    interface PageSink {
        void accept(int pageNumber, byte[] png) throws IOException;
    }

    private PageRasterizer() {
    }

    /** Renders at most {@code maxPages} pages and returns how many were rendered. */
    static int render(Path pdf, int maxPages, int dpi, PageSink sink) throws IOException {
        try (var document = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            var pages = Math.min(document.getNumberOfPages(), maxPages);
            for (var index = 0; index < pages; index++) {
                var box = document.getPage(index).getCropBox();
                var longest = Math.max(box.getWidth(), box.getHeight());
                var scale = Math.min(dpi / 72f, MAX_PIXELS / Math.max(longest, 1f));
                sink.accept(index + 1, png(renderer.renderImage(index, scale, ImageType.RGB)));
            }
            return pages;
        }
    }

    /** Builds a PDF with one page per image frame (multi-page TIFF scans), each page sized to its image. */
    static void imagesToPdf(Path image, Path target) throws IOException {
        var frames = frames(image);
        if (frames.isEmpty()) {
            throw new IOException("Unreadable image");
        }
        try (var document = new PDDocument()) {
            for (var frame : frames) {
                var page = new PDPage(new PDRectangle(frame.getWidth(), frame.getHeight()));
                document.addPage(page);
                var picture = LosslessFactory.createFromImage(document, frame);
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawImage(picture, 0, 0, frame.getWidth(), frame.getHeight());
                }
            }
            document.save(target.toFile());
        }
    }

    private static List<BufferedImage> frames(Path image) throws IOException {
        var result = new ArrayList<BufferedImage>();
        try (var input = ImageIO.createImageInputStream(image.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return result;
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                var count = reader.getNumImages(true);
                for (var i = 0; i < count; i++) {
                    result.add(reader.read(i));
                }
            } finally {
                reader.dispose();
            }
        }
        return result;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream(256 * 1024);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
