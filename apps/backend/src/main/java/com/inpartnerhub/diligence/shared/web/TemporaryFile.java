package com.inpartnerhub.diligence.shared.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * A generated download (export) written to a temporary file instead of the heap (#129), streamed to the client and
 * deleted once sent. Large exports would otherwise exist several times in memory at once (workbook, byte array,
 * response copy).
 */
public record TemporaryFile(String fileName, String contentType, Path path) {

    /** A new, empty temporary file; the caller writes it and deletes it on failure. */
    public static Path create(String prefix, String suffix) {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (IOException ex) {
            throw new UncheckedIOException("No temporary file for the export", ex);
        }
    }

    /** Deletes a temporary file, ignoring one that is already gone. */
    public static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the operating system cleans the temporary directory
        }
    }

    public long size() {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** An attachment response that streams the file and deletes it afterwards (also when the client aborts). */
    public ResponseEntity<StreamingResponseBody> response() {
        long size;
        try {
            size = size();
        } catch (UncheckedIOException ex) {
            delete(path);
            throw ex;
        }
        StreamingResponseBody body = out -> {
            try {
                Files.copy(path, out);
            } finally {
                delete(path);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(size)
                .body(body);
    }
}
