package com.inpartnerhub.diligence.ingestion.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

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

    /**
     * Largest frame accepted, in pixels (#138). Dimensions are read from the image header before anything is decoded:
     * a small "decompression bomb" can declare a size whose decoded pixels would exhaust the heap. 250 MP is far above
     * a scan (A0 at 600 dpi is about 560 MP; an A4 page at 600 dpi about 35 MP) yet bounds the decoding work.
     */
    static final long MAX_FRAME_PIXELS = 250_000_000L;

    /** Frames whose longer side exceeds this are subsampled while being read, never decoded at full size. */
    static final int MAX_DECODED_SIDE = 2 * MAX_PIXELS;

    /**
     * Builds a PDF with one page per image frame (multi-page TIFF scans), each page sized to its (possibly subsampled)
     * image. Frames are decoded and added one at a time, at most {@code maxFrames}; the document's streams are kept
     * in a temporary file. Throws {@link IOException} (no preview) for unreadable images and frames above
     * {@link #MAX_FRAME_PIXELS}.
     */
    static void imagesToPdf(Path image, Path target, int maxFrames) throws IOException {
        try (var input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                throw new IOException("Unreadable image");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unreadable image");
            }
            var reader = readers.next();
            try (var document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
                reader.setInput(input);
                var count = Math.min(reader.getNumImages(true), Math.max(maxFrames, 1));
                if (count < 1) {
                    throw new IOException("Unreadable image");
                }
                for (var i = 0; i < count; i++) {
                    var frame = readFrame(reader, i);
                    var page = new PDPage(new PDRectangle(frame.getWidth(), frame.getHeight()));
                    document.addPage(page);
                    var picture = LosslessFactory.createFromImage(document, frame);
                    try (var content = new PDPageContentStream(document, page)) {
                        content.drawImage(picture, 0, 0, frame.getWidth(), frame.getHeight());
                    }
                }
                document.save(target.toFile());
            } finally {
                reader.dispose();
            }
        }
    }

    /** Checks the declared size, then decodes one frame, subsampled when it is larger than any preview needs. */
    static BufferedImage readFrame(ImageReader reader, int index) throws IOException {
        long width = reader.getWidth(index);
        long height = reader.getHeight(index);
        if (width < 1 || height < 1 || width * height > MAX_FRAME_PIXELS) {
            throw new IOException("Image frame %d is %d × %d pixels, above the preview limit".formatted(index + 1,
                    width, height));
        }
        var param = reader.getDefaultReadParam();
        var factor = (int) Math.ceil(Math.max(width, height) / (double) MAX_DECODED_SIDE);
        if (factor > 1) {
            param.setSourceSubsampling(factor, factor, 0, 0);
        }
        return reader.read(index, param);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream(256 * 1024);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
