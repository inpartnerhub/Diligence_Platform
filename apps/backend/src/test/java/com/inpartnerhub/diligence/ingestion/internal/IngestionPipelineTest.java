package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.dataroom.ScanResults.Outcome;
import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.ScanException;

class IngestionPipelineTest {

    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final ContentScanner scanner = mock(ContentScanner.class);
    private final ContentTypeDetector detector = mock(ContentTypeDetector.class);
    private final ScanResults results = mock(ScanResults.class);
    private final IngestionProperties properties = new IngestionProperties(URI.create("http://tika"), null, "localhost",
            3310, null, Duration.ZERO);
    private final IngestionPipeline pipeline = new IngestionPipeline(storage, scanner, detector, results, properties);
    private final DocumentUploaded event = new DocumentUploaded(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "key", "Akta.pdf", "application/pdf", 3);

    @Test
    void cleanAllowedFilesBecomeClean() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1, 2, 3 }));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), eq("Akta.pdf"))).thenReturn("application/pdf");

        pipeline.onDocumentUploaded(event);

        verify(results).record(event.versionId(), Outcome.CLEAN, "application/pdf", null);
    }

    @Test
    void malwareIsReportedWithItsSignature() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(false, "Eicar-Signature"));

        pipeline.onDocumentUploaded(event);

        verify(results).record(event.versionId(), Outcome.INFECTED, null, "Eicar-Signature");
    }

    @Test
    void disallowedTypesAreRejected() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), any())).thenReturn("application/x-msdownload");

        pipeline.onDocumentUploaded(event);

        verify(results).record(eq(event.versionId()), eq(Outcome.REJECTED), eq("application/x-msdownload"),
                startsWith("File type"));
    }

    @Test
    void transientOutagesAreRetriedThenFailed() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenThrow(new ScanException("ClamAV unavailable", true, null));

        pipeline.onDocumentUploaded(event);

        verify(scanner, times(IngestionPipeline.MAX_ATTEMPTS)).scan(any());
        verify(results).record(event.versionId(), Outcome.FAILED, null, "ClamAV unavailable");
    }

    @Test
    void permanentRefusalsFailImmediately() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenThrow(new ScanException("size limit exceeded", false, null));

        pipeline.onDocumentUploaded(event);

        verify(scanner, times(1)).scan(any());
        verify(results).record(event.versionId(), Outcome.FAILED, null, "size limit exceeded");
    }

    @Test
    void allowListMatchesExactTypesAndPrefixes() {
        assertThat(properties.isAllowed("application/pdf")).isTrue();
        assertThat(properties.isAllowed("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isTrue();
        assertThat(properties.isAllowed("text/plain; charset=UTF-8")).isTrue();
        assertThat(properties.isAllowed("application/x-sh")).isFalse();
        assertThat(properties.isAllowed(null)).isFalse();
        var custom = new IngestionProperties(null, null, null, 0, List.of("image/*"), null);
        assertThat(custom.isAllowed("image/webp")).isTrue();
        assertThat(custom.retryBackoff()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void clamAvRepliesAreParsed() {
        assertThat(ClamAvScanner.parse("stream: OK").clean()).isTrue();
        var infected = ClamAvScanner.parse("stream: Win.Test.EICAR_HDB-1 FOUND");
        assertThat(infected.clean()).isFalse();
        assertThat(infected.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThatThrownBy(() -> ClamAvScanner.parse("INSTREAM size limit exceeded. ERROR"))
                .isInstanceOf(ScanException.class)
                .matches(ex -> !((ScanException) ex).isTransientFailure());
    }

    @Test
    void clamAvClientSpeaksInstream() throws Exception {
        try (var server = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    var in = socket.getInputStream();
                    var command = new String(in.readNBytes(10), StandardCharsets.US_ASCII);
                    var length = ByteBuffer.wrap(in.readNBytes(4)).getInt();
                    var data = new String(in.readNBytes(length), StandardCharsets.US_ASCII);
                    var terminator = ByteBuffer.wrap(in.readNBytes(4)).getInt();
                    OutputStream out = socket.getOutputStream();
                    out.write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return command + "|" + data + "|" + terminator;
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            var scanner = new ClamAvScanner(new IngestionProperties(null, null, "127.0.0.1", server.getLocalPort(),
                    null, null));

            var result = scanner.scan(new ByteArrayInputStream("EICAR".getBytes(StandardCharsets.US_ASCII)));

            assertThat(result.clean()).isTrue();
            assertThat(received.get()).isEqualTo("zINSTREAM\0|EICAR|0");
        }
    }

    @Test
    void unreachableClamAvIsTransient() {
        var scanner = new ClamAvScanner(new IngestionProperties(null, null, "127.0.0.1", 1, null, null));
        assertThatThrownBy(() -> scanner.scan(InputStream.nullInputStream()))
                .isInstanceOf(ScanException.class)
                .matches(ex -> ((ScanException) ex).isTransientFailure());
    }
}
