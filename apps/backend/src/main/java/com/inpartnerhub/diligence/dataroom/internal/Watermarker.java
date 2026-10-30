package com.inpartnerhub.diligence.dataroom.internal;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/**
 * Dynamic watermark (PRD DOC-04): every viewed page and every downloaded PDF carries who looked at it, from where and
 * when, so a leaked screenshot or file can be traced. Applied per request; nothing watermarked is stored.
 */
final class Watermarker {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Viewer identity stamped on the content. */
    record Stamp(String name, String email, String ipAddress, Instant at, ZoneId zone) {

        String identity() {
            return email == null || email.isBlank() || email.equals(name) ? name : name + " · " + email;
        }

        String origin() {
            return (ipAddress == null ? "" : ipAddress + " · ") + TIME.format(at.atZone(zone));
        }
    }

    private Watermarker() {
    }

    /** Tiles the stamp diagonally across a page image and adds a footer line. */
    static byte[] stampPng(byte[] png, Stamp stamp) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("Unreadable page image");
        }
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var width = image.getWidth();
            var height = image.getHeight();
            var size = Math.max(14, width / 45);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
            g.setColor(new Color(90, 90, 90));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f));
            var original = g.getTransform();
            g.rotate(-Math.PI / 6, width / 2.0, height / 2.0);
            var lineHeight = size * 7;
            var line = stamp.identity() + "   " + stamp.origin();
            var lineWidth = g.getFontMetrics().stringWidth(line) + size * 4;
            for (var y = -height; y < height * 2; y += lineHeight) {
                var offset = (y / lineHeight) % 2 == 0 ? 0 : lineWidth / 2;
                for (var x = -width - offset; x < width * 2; x += lineWidth) {
                    g.drawString(line, x, y);
                }
            }
            g.setTransform(original);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, size * 2 / 3)));
            g.drawString(line, size, height - size / 2);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream(png.length + 64 * 1024);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * Stamps every page of a PDF and writes the result. When printing is not allowed the file is encrypted with a
     * random owner password and the print permission removed (honoured by standard PDF readers).
     */
    static void stampPdf(Path pdf, Stamp stamp, boolean printAllowed, OutputStream out) throws IOException {
        try (var document = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            if (document.isEncrypted()) {
                document.setAllSecurityToBeRemoved(true);
            }
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var line = latin1(stamp.identity() + "   " + stamp.origin());
            var tile = new PDExtendedGraphicsState();
            tile.setNonStrokingAlphaConstant(0.16f);
            var footer = new PDExtendedGraphicsState();
            footer.setNonStrokingAlphaConstant(0.55f);
            for (var page : document.getPages()) {
                var box = page.getCropBox();
                var size = Math.max(9f, box.getWidth() / 45f);
                var lineWidth = font.getStringWidth(line) / 1000f * size + size * 4;
                var lineHeight = size * 7;
                try (var content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                    content.setNonStrokingColor(0.35f, 0.35f, 0.35f);
                    content.setGraphicsStateParameters(tile);
                    content.beginText();
                    content.setFont(font, size);
                    var transform = new AffineTransform();
                    transform.translate(box.getLowerLeftX() + box.getWidth() / 2, box.getLowerLeftY()
                            + box.getHeight() / 2);
                    transform.rotate(Math.PI / 6);
                    var row = 0;
                    for (var y = -box.getHeight(); y < box.getHeight(); y += lineHeight, row++) {
                        var offset = row % 2 == 0 ? 0 : lineWidth / 2;
                        for (var x = -box.getWidth() - offset; x < box.getWidth(); x += lineWidth) {
                            var position = new AffineTransform(transform);
                            position.translate(x, y);
                            content.setTextMatrix(new Matrix(position));
                            content.showText(line);
                        }
                    }
                    content.endText();
                    content.setGraphicsStateParameters(footer);
                    content.beginText();
                    content.setFont(font, Math.max(7f, size * 2 / 3));
                    content.newLineAtOffset(box.getLowerLeftX() + size, box.getLowerLeftY() + size / 2);
                    content.showText(line);
                    content.endText();
                }
            }
            if (!printAllowed) {
                var permissions = new AccessPermission();
                permissions.setCanPrint(false);
                permissions.setCanPrintFaithful(false);
                var seed = new byte[16];
                RANDOM.nextBytes(seed);
                var ownerPassword = HexFormat.of().formatHex(seed);
                var policy = new StandardProtectionPolicy(ownerPassword, "", permissions);
                policy.setEncryptionKeyLength(256);
                document.protect(policy);
            }
            document.save(out);
        }
    }

    /** The standard PDF fonts only cover Latin-1; other characters (e.g. CJK names) become '?'. */
    static String latin1(String text) {
        var result = new StringBuilder(text.length());
        for (var c : text.toCharArray()) {
            result.append(c == '·' || (c >= 0x20 && c <= 0x7e) || (c >= 0xa0 && c <= 0xff) ? c : '?');
        }
        return result.toString();
    }
}
