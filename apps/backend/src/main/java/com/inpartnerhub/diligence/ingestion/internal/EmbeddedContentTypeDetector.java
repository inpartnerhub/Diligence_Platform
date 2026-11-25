package com.inpartnerhub.diligence.ingestion.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipInputStream;

import com.inpartnerhub.diligence.ingestion.ScanException;

/**
 * Lightweight content-based MIME type detector used when an external Apache Tika Server is not configured
 * ({@code TIKA_URL} is empty). Uses magic bytes and ZIP/OOXML container inspection so spoofed file extensions (e.g.
 * executables renamed to {@code .pdf}) still fail the ingestion allowlist.
 */
final class EmbeddedContentTypeDetector {

    private static final int SAMPLE_BYTES = 8192;
    private static final int MAX_ZIP_ENTRIES = 512;
    private static final int MAX_MIMETYPE_ENTRY_BYTES = 128;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_MAGIC = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    private static final byte[] JPEG_MAGIC = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] TIFF_LE_MAGIC = new byte[] { 0x49, 0x49, 0x2A, 0x00 };
    private static final byte[] TIFF_BE_MAGIC = new byte[] { 0x4D, 0x4D, 0x00, 0x2A };
    private static final byte[] RTF_MAGIC = "{\\rtf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = new byte[] { 0x50, 0x4B, 0x03, 0x04 };
    private static final byte[] OLE2_MAGIC = new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };

    private EmbeddedContentTypeDetector() {
    }

    static String detect(InputStream content, String fileName) {
        try {
            var buffered = content instanceof BufferedInputStream bis ? bis
                    : new BufferedInputStream(content, SAMPLE_BYTES);
            buffered.mark(SAMPLE_BYTES + 1);
            var header = buffered.readNBytes(SAMPLE_BYTES);
            buffered.reset();

            if (header.length == 0) {
                return "application/octet-stream";
            }
            if (startsWith(header, PDF_MAGIC)) {
                return "application/pdf";
            }
            if (startsWith(header, PNG_MAGIC)) {
                return "image/png";
            }
            if (startsWith(header, JPEG_MAGIC)) {
                return "image/jpeg";
            }
            if (startsWith(header, TIFF_LE_MAGIC) || startsWith(header, TIFF_BE_MAGIC)) {
                return "image/tiff";
            }
            if (startsWith(header, RTF_MAGIC)) {
                return "application/rtf";
            }
            if (startsWith(header, ZIP_MAGIC)) {
                return detectZipContainer(buffered);
            }
            if (startsWith(header, OLE2_MAGIC)) {
                return detectOle2(fileName);
            }
            if (looksLikeUtf8Text(header)) {
                return detectTextExtension(fileName);
            }
            return "application/octet-stream";
        } catch (IOException ex) {
            throw new ScanException("Embedded content detection failed: " + ex.getMessage(), false, ex);
        }
    }

    private static String detectZipContainer(InputStream stream) {
        try (var zip = new ZipInputStream(stream, StandardCharsets.UTF_8)) {
            var hasWord = false;
            var hasExcel = false;
            var hasPowerPoint = false;
            for (var i = 0; i < MAX_ZIP_ENTRIES; i++) {
                var entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }
                var name = entry.getName();
                if ("mimetype".equals(name)) {
                    var mime = readSmallEntry(zip);
                    if (mime.startsWith("application/vnd.oasis.opendocument.")) {
                        return mime;
                    }
                } else if (name.startsWith("word/")) {
                    hasWord = true;
                } else if (name.startsWith("xl/")) {
                    hasExcel = true;
                } else if (name.startsWith("ppt/")) {
                    hasPowerPoint = true;
                }
                if (hasWord) {
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                }
                if (hasExcel) {
                    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                }
                if (hasPowerPoint) {
                    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                }
            }
            return "application/zip";
        } catch (IOException | IllegalArgumentException ex) {
            return "application/octet-stream";
        }
    }

    private static String readSmallEntry(ZipInputStream zip) throws IOException {
        var out = new ByteArrayOutputStream(MAX_MIMETYPE_ENTRY_BYTES);
        var buf = new byte[MAX_MIMETYPE_ENTRY_BYTES];
        int read;
        while (out.size() < MAX_MIMETYPE_ENTRY_BYTES
                && (read = zip.read(buf, 0, Math.min(buf.length, MAX_MIMETYPE_ENTRY_BYTES - out.size()))) != -1) {
            out.write(buf, 0, read);
        }
        return out.toString(StandardCharsets.US_ASCII).strip();
    }

    private static String detectOle2(String fileName) {
        return switch (extensionOf(fileName)) {
            case "doc" -> "application/msword";
            case "xls" -> "application/vnd.ms-excel";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "msg" -> "application/vnd.ms-outlook";
            default -> "application/x-ole-storage";
        };
    }

    private static String detectTextExtension(String fileName) {
        return switch (extensionOf(fileName)) {
            case "csv" -> "text/csv";
            case "eml" -> "message/rfc822";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        var dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length()
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (var i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeUtf8Text(byte[] sample) {
        for (var b : sample) {
            if (b == 0) {
                return false;
            }
        }
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var decoded = decoder.decode(ByteBuffer.wrap(sample));
            for (var i = 0; i < decoded.length(); i++) {
                var c = decoded.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                    return false;
                }
            }
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }
}
