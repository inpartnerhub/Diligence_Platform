package com.inpartnerhub.diligence.ingestion.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ScanException;

/** clamd client using the INSTREAM command over TCP (no files touch the scanner's disk). */
@Component
class ClamAvScanner implements ContentScanner {

    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private final IngestionProperties properties;

    ClamAvScanner(IngestionProperties properties) {
        this.properties = properties;
    }

    @Override
    public ScanResult scan(InputStream content) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.clamavHost(), properties.clamavPort()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            var out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            var buffer = new byte[CHUNK];
            int read;
            while ((read = content.read(buffer)) > 0) {
                out.write(ByteBuffer.allocate(4).putInt(read).array());
                out.write(buffer, 0, read);
            }
            out.write(new byte[4]); // zero-length chunk ends the stream
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException ex) {
            throw new ScanException("ClamAV unavailable: " + ex.getMessage(), true, ex);
        }
    }

    /** "stream: OK" → clean; "stream: &lt;signature&gt; FOUND" → infected; anything with ERROR is a refusal. */
    static ScanResult parse(String reply) {
        var text = reply.strip();
        if (text.endsWith("OK")) {
            return new ScanResult(true, null);
        }
        if (text.endsWith("FOUND")) {
            var signature = text.substring(text.indexOf(':') + 1, text.length() - "FOUND".length()).strip();
            return new ScanResult(false, signature);
        }
        throw new ScanException("ClamAV refused the file: " + text, false, null);
    }

    private static String readReply(InputStream in) throws IOException {
        var reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) { // NUL-terminated (z-prefixed command)
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
