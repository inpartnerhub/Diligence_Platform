package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #138: image previews never decode more than a bounded number of pixels or frames. */
class PageRasterizerTest {

    @TempDir
    Path dir;

    @Test
    void anImageDeclaringAHugeSizeIsRefusedBeforeDecoding() throws Exception {
        // 40,000 × 40,000 RGB would need about 4.8 GB decoded; the file itself is a few hundred bytes.
        var bomb = dir.resolve("bomb.png");
        Files.write(bomb, pngHeaderOnly(40_000, 40_000));

        assertThatThrownBy(() -> PageRasterizer.imagesToPdf(bomb, dir.resolve("out.pdf"), 300))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("above the preview limit");
        assertThat(dir.resolve("out.pdf")).doesNotExist();
    }

    @Test
    void largeImagesAreSubsampledWhileBeingRead() throws Exception {
        var wide = dir.resolve("wide.png");
        ImageIO.write(new BufferedImage(6_000, 300, BufferedImage.TYPE_INT_RGB), "png", wide.toFile());
        var pdf = dir.resolve("wide.pdf");

        PageRasterizer.imagesToPdf(wide, pdf, 300);

        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var box = document.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isLessThanOrEqualTo(PageRasterizer.MAX_DECODED_SIDE);
            assertThat(box.getWidth()).isEqualTo(3_000f); // factor 2
        }
    }

    @Test
    void normalImagesKeepTheirSize() throws Exception {
        var scan = dir.resolve("scan.png");
        ImageIO.write(new BufferedImage(1_240, 1_754, BufferedImage.TYPE_INT_RGB), "png", scan.toFile());
        var pdf = dir.resolve("scan.pdf");

        PageRasterizer.imagesToPdf(scan, pdf, 300);

        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(1_240f);
            assertThat(document.getPage(0).getMediaBox().getHeight()).isEqualTo(1_754f);
        }
    }

    @Test
    void onlyTheFirstFramesUpToTheLimitAreConverted() throws Exception {
        var tiff = dir.resolve("scan.tiff");
        var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        try (var out = ImageIO.createImageOutputStream(tiff.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (var i = 0; i < 3; i++) {
                writer.writeToSequence(new IIOImage(new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB), null,
                        null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        var pdf = dir.resolve("scan.pdf");

        PageRasterizer.imagesToPdf(tiff, pdf, 2);

        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void unreadableFilesFailWithoutAPreview() throws Exception {
        var junk = dir.resolve("junk.png");
        Files.writeString(junk, "not an image");

        assertThatThrownBy(() -> PageRasterizer.imagesToPdf(junk, dir.resolve("junk.pdf"), 300))
                .isInstanceOf(IOException.class);
    }

    /** A valid PNG signature and header for the given size, with a tiny image data chunk. */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' });
        var header = new ByteArrayOutputStream();
        var h = new DataOutputStream(header);
        h.writeInt(width);
        h.writeInt(height);
        h.writeByte(8); // bit depth
        h.writeByte(2); // RGB
        h.writeByte(0);
        h.writeByte(0);
        h.writeByte(0);
        chunk(out, "IHDR", header.toByteArray());
        var deflater = new Deflater();
        deflater.setInput(new byte[64]);
        deflater.finish();
        var compressed = new byte[256];
        var length = deflater.deflate(compressed);
        var data = new byte[length];
        System.arraycopy(compressed, 0, data, 0, length);
        chunk(out, "IDAT", data);
        chunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
