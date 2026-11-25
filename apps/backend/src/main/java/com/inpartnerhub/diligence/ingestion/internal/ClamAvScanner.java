package com.inpartnerhub.diligence.ingestion.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ScanException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * clamd client using the INSTREAM command over TCP (no files touch the scanner's disk). Fail-closed by default
 * ({@code CLAMAV_MODE=required}) when {@code CLAMAV_HOST} is unset; set {@code CLAMAV_MODE=external} (or
 * {@code disabled}) only when malware scanning is enforced externally (e.g. S3 malware protection).
 */
@Component
class ClamAvScanner implements ContentScanner, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final int CHUNK = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private final IngestionProperties properties;
    private final String effectiveMode;
    private final boolean active;

    ClamAvScanner(IngestionProperties properties) {
        this(properties, null);
    }

    @Autowired
    ClamAvScanner(IngestionProperties properties, ObjectProvider<MeterRegistry> meters) {
        this.properties = properties;
        var configuredHost = properties.clamavHost() != null && !properties.clamavHost().isBlank();
        var mode = properties.clamavMode();
        if ("external".equals(mode) || "disabled".equals(mode)) {
            this.effectiveMode = mode;
            this.active = false;
            log.warn("CLAMAV_MODE={} — in-process ClamAV scanning is bypassed; ensure external/S3 malware scanning is active",
                    mode);
        } else if (configuredHost) {
            this.effectiveMode = "clamav";
            this.active = true;
        } else {
            this.effectiveMode = "misconfigured";
            this.active = false;
            log.error("CLAMAV_HOST is not set while CLAMAV_MODE={} — uploads will fail closed until CLAMAV_HOST is configured or CLAMAV_MODE=external is explicitly set",
                    mode);
        }
        var registry = meters == null ? null : meters.getIfAvailable();
        if (registry != null) {
            double gaugeValue = this.active ? 1.0 : 0.0;
            Gauge.builder("diligence_ingestion_av_enabled", () -> gaugeValue)
                    .description("Whether in-process ClamAV scanning is active (1) or bypassed/misconfigured (0)")
                    .register(registry);
        }
    }

    @Override
    public ScanResult scan(InputStream content) {
        if ("misconfigured".equals(effectiveMode)) {
            throw new ScanException(
                    "Antivirus scanner is required (CLAMAV_MODE=" + properties.clamavMode()
                            + ") but CLAMAV_HOST is not set",
                    false, null);
        }
        if (!active) {
            return new ScanResult(true, null);
        }
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

    @Override
    public Health health() {
        if ("misconfigured".equals(effectiveMode)) {
            return Health.down()
                    .withDetail("mode", effectiveMode)
                    .withDetail("inProcessScan", false)
                    .withDetail("reason", "CLAMAV_HOST not configured")
                    .build();
        }
        return Health.up()
                .withDetail("mode", effectiveMode)
                .withDetail("inProcessScan", active)
                .build();
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
