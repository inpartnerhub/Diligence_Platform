package com.inpartnerhub.diligence.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.DocumentUploaded;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.dataroom.ScanResults;
import com.inpartnerhub.diligence.dataroom.ScanResults.PreviewStatus;
import com.inpartnerhub.diligence.dataroom.ScanResults.Rendition;
import com.inpartnerhub.diligence.dataroom.ScanResults.Outcome;
import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.ingestion.ScanException;
import com.inpartnerhub.diligence.ingestion.TextExtractor;

class IngestionPipelineTest {

    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final ContentScanner scanner = mock(ContentScanner.class);
    private final ContentTypeDetector detector = mock(ContentTypeDetector.class);
    private final TextExtractor extractor = mock(TextExtractor.class);
    private final PreviewRenderer renderer = mock(PreviewRenderer.class);
    private final ScanResults results = mock(ScanResults.class);
    private final IngestionProperties properties = new IngestionProperties(URI.create("http://tika"), null, "localhost",
            3310, null, Duration.ZERO);
    private final IngestionPipeline pipeline = new IngestionPipeline(storage, scanner, detector, extractor,
            renderer, results, properties);
    private final DocumentUploaded event = new DocumentUploaded(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "key", "Akta.pdf", "application/pdf", 3);

    @Test
    void cleanPdfsAreIndexedAndPreRenderedPageByPage() throws Exception {
        var pdf = TestPdfs.pages(3);
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(pdf));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), eq("Akta.pdf"))).thenReturn("application/pdf");
        when(extractor.extract(any(), eq("application/pdf"), anyInt())).thenReturn("Akta pendirian PT Contoh");

        pipeline.onDocumentUploaded(event);

        verify(results).recordClean(event.versionId(), "application/pdf",
                new Rendition(PreviewStatus.READY, 3, false, true));
        verify(storage).put(eq(RenditionKeys.text(event.projectId(), event.versionId())), any(), anyLong(),
                startsWith("text/plain"));
        for (var page = 1; page <= 3; page++) {
            verify(storage).put(eq(RenditionKeys.page(event.projectId(), event.versionId(), page)), any(),
                    anyLong(), eq("image/png"));
        }
        // The original is the PDF: no second copy is stored.
        verify(storage, never()).put(eq(RenditionKeys.pdf(event.projectId(), event.versionId())), any(), anyLong(),
                any());
    }

    @Test
    void officeFilesAreConvertedAndTheRenditionIsKeptForDownloads() throws Exception {
        var xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), any())).thenReturn(xlsx);
        when(extractor.extract(any(), any(), anyInt())).thenReturn("");
        when(renderer.supports(xlsx)).thenReturn(true);
        var pdf = TestPdfs.pages(1);
        doAnswer(inv -> Files.write(inv.getArgument(3), pdf)).when(renderer)
                .renderPdf(any(), eq("Akta.pdf"), eq(xlsx), any(Path.class));

        pipeline.onDocumentUploaded(event);

        verify(results).recordClean(event.versionId(), xlsx, new Rendition(PreviewStatus.READY, 1, true, false));
        verify(storage).put(eq(RenditionKeys.pdf(event.projectId(), event.versionId())), any(), eq((long) pdf.length),
                eq("application/pdf"));
    }

    @Test
    void imagesBecomeSinglePagePreviews() throws Exception {
        var image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(png.toByteArray()));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), any())).thenReturn("image/png");
        when(extractor.extract(any(), any(), anyInt())).thenReturn("hasil OCR");

        pipeline.onDocumentUploaded(event);

        verify(results).recordClean(event.versionId(), "image/png", new Rendition(PreviewStatus.READY, 1, true, true));
    }

    @Test
    void emailsHaveTextButNoPreviewAndBrokenFilesDegradeToNoPreview() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream("not a pdf".getBytes()));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(extractor.extract(any(), any(), anyInt())).thenReturn("From: a@b.test");
        when(detector.detect(any(), any())).thenReturn("message/rfc822");

        pipeline.onDocumentUploaded(event);
        verify(results).recordClean(event.versionId(), "message/rfc822",
                new Rendition(PreviewStatus.UNAVAILABLE, 0, false, true));

        when(detector.detect(any(), any())).thenReturn("application/pdf");
        pipeline.onDocumentUploaded(event);
        verify(results).recordClean(event.versionId(), "application/pdf",
                new Rendition(PreviewStatus.FAILED, 0, false, true));
    }

    @Test
    void enrichmentOutagesAreRetriedThenDegradeInsteadOfFailingACleanDocument() {
        when(storage.get("key")).thenAnswer(inv -> new ByteArrayInputStream(new byte[] { 1 }));
        when(scanner.scan(any())).thenReturn(new ContentScanner.ScanResult(true, null));
        when(detector.detect(any(), any())).thenReturn("text/plain");
        when(extractor.extract(any(), any(), anyInt())).thenThrow(new ScanException("Tika unavailable", true, null));
        when(renderer.supports("text/plain")).thenReturn(true);
        doThrow(new ScanException("Gotenberg unavailable", true, null)).when(renderer)
                .renderPdf(any(), any(), any(), any());

        pipeline.onDocumentUploaded(event);

        verify(extractor, times(IngestionPipeline.MAX_ATTEMPTS)).extract(any(), any(), anyInt());
        verify(results).recordClean(event.versionId(), "text/plain",
                new Rendition(PreviewStatus.FAILED, 0, false, false));
        verify(results, never()).record(any(), eq(Outcome.FAILED), any(), any());
    }

    @Test
    void conversionNamesFollowTheDetectedType() {
        var docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertThat(GotenbergPreviewRenderer.convertibleName("Kontrak.docx", docx)).isEqualTo("Kontrak.docx");
        assertThat(GotenbergPreviewRenderer.convertibleName("Kontrak.pdf", docx)).isEqualTo("Kontrak.docx");
        assertThat(GotenbergPreviewRenderer.convertibleName("README", "text/plain; charset=UTF-8"))
                .isEqualTo("README.txt");
        assertThat(GotenbergPreviewRenderer.convertibleName(null, "text/csv")).isEqualTo("document.csv");
    }

    @Test
    void extractedTextIsCappedAndTrimmed() throws Exception {
        var body = new ByteArrayInputStream("  abcdefghij  ".getBytes(StandardCharsets.UTF_8));
        assertThat(TikaTextExtractor.read(body, 6)).isEqualTo("abcd");
        assertThat(TikaTextExtractor.read(new ByteArrayInputStream(new byte[0]), 10)).isEmpty();
    }

    @Test
    void oversizedPagesAreScaledDownAndPageCountIsCapped() throws Exception {
        var file = Files.createTempFile("pages", ".pdf");
        try {
            Files.write(file, TestPdfs.pages(4, new PDRectangle(5000, 3000)));
            var sizes = new ArrayList<int[]>();
            var rendered = PageRasterizer.render(file, 2, 120, (number, png) -> {
                var image = ImageIO.read(new ByteArrayInputStream(png));
                sizes.add(new int[] { image.getWidth(), image.getHeight() });
            });
            assertThat(rendered).isEqualTo(2);
            assertThat(sizes).hasSize(2).allSatisfy(size -> assertThat(size[0]).isLessThanOrEqualTo(2400));
        } finally {
            Files.deleteIfExists(file);
        }
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
