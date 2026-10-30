package com.inpartnerhub.diligence.dataroom.internal;

import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.PROJECT;
import static com.inpartnerhub.diligence.dataroom.internal.DataRoomTestData.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentDownloaded;
import com.inpartnerhub.diligence.dataroom.DataRoomEvents.DocumentViewed;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.dataroom.RenditionKeys;
import com.inpartnerhub.diligence.dataroom.ScanResults.PreviewStatus;
import com.inpartnerhub.diligence.dataroom.ScanResults.Rendition;
import com.inpartnerhub.diligence.ingestion.internal.TestPdfs;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;
import com.inpartnerhub.diligence.shared.web.ResourceConflictException;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

class DocumentViewServiceTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    private final DataRoomAccess access = mock(DataRoomAccess.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DocumentViewService service = new DocumentViewService(documents, versions, access, storage,
            currentUser, events, new DataRoomProperties(ZoneId.of("Asia/Jakarta")),
            Clock.fixed(Instant.parse("2026-11-03T02:00:00Z"), ZoneOffset.UTC));

    private final Folder folder = new Folder(PROJECT, null, "Legal", null);
    private Document document;
    private DocumentVersion version;

    @BeforeEach
    void setUp() {
        when(currentUser.requireCurrentUser()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), "target",
                "budi@target.test", "Budi Santoso", Set.of(Role.TARGET)));
        givenRole(ProjectRole.TARGET);
        givenVersion("Kontrak.docx", DOCX, new Rendition(PreviewStatus.READY, 2, true, true));
    }

    @Test
    void openingIsLoggedAndReportsWhatTheViewerMayDo() {
        var viewable = service.open(PROJECT, document.getId(), null);

        assertThat(viewable.version()).isSameAs(version);
        assertThat(viewable.downloadAllowed()).isFalse();
        assertThat(viewable.printAllowed()).isFalse();
        assertThat(viewable.pdfAvailable()).isTrue();
        assertThat(viewable.originalAllowed()).isFalse();
        verify(events).publishEvent(any(DocumentViewed.class));
    }

    @Test
    void pagesAreWatermarkedAndOnlyServedForCleanVersionsWithinRange() throws Exception {
        var blank = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        var white = blank.createGraphics();
        white.setColor(java.awt.Color.WHITE);
        white.fillRect(0, 0, 600, 800);
        white.dispose();
        var png = new ByteArrayOutputStream();
        ImageIO.write(blank, "png", png);
        when(storage.get(RenditionKeys.page(PROJECT, version.getId(), 2)))
                .thenReturn(new ByteArrayInputStream(png.toByteArray()));

        var stamped = ImageIO.read(new ByteArrayInputStream(service.page(PROJECT, document.getId(), version.getId(),
                2)));

        assertThat(stamped.getWidth()).isEqualTo(600);
        assertThat(countNonWhite(stamped)).isGreaterThan(1000);
        assertThatThrownBy(() -> service.page(PROJECT, document.getId(), version.getId(), 3))
                .isInstanceOf(ResourceNotFoundException.class);

        version.recordScan(ScanStatus.INFECTED, DOCX, "sig", Instant.now());
        assertThatThrownBy(() -> service.page(PROJECT, document.getId(), version.getId(), 1))
                .isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void downloadsFollowTheFolderPolicyExceptForManagers() {
        assertThatThrownBy(() -> service.download(PROJECT, document.getId(), null, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(events, never()).publishEvent(any(DocumentDownloaded.class));

        givenRole(ProjectRole.PROJECT_MANAGER);
        var download = service.download(PROJECT, document.getId(), null, null);
        assertThat(download.format()).isEqualTo(DocumentViewService.Format.PDF);
        assertThat(download.fileName()).isEqualTo("Kontrak.pdf");
        verify(events).publishEvent(argThat((Object e) -> e instanceof DocumentDownloaded d && d.format().equals("pdf")));
    }

    @Test
    void targetsGetTheWatermarkedPdfAndTheInternalTeamMayTakeTheOriginal() throws Exception {
        folder.changePolicy(true, false);
        assertThatThrownBy(() -> service.download(PROJECT, document.getId(), null,
                DocumentViewService.Format.ORIGINAL)).isInstanceOf(AccessDeniedException.class);

        when(storage.get(RenditionKeys.pdf(PROJECT, version.getId())))
                .thenAnswer(inv -> new ByteArrayInputStream(TestPdfs.pages(2)));
        var out = new ByteArrayOutputStream();
        service.download(PROJECT, document.getId(), null, null).writer().writeTo(out);
        try (var pdf = Loader.loadPDF(out.toByteArray())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            assertThat(pdf.isEncrypted()).isTrue(); // print not allowed
            assertThat(pdf.getCurrentAccessPermission().canPrint()).isFalse();
            assertThat(new PDFTextStripper().getText(pdf)).contains("Budi Santoso").contains("budi@target.test");
        }

        givenRole(ProjectRole.ANALYST);
        when(storage.get(version.getObjectKey())).thenReturn(new ByteArrayInputStream(new byte[] { 7, 7 }));
        var original = service.download(PROJECT, document.getId(), null, DocumentViewService.Format.ORIGINAL);
        var bytes = new ByteArrayOutputStream();
        original.writer().writeTo(bytes);
        assertThat(original.contentType()).isEqualTo(DOCX);
        assertThat(bytes.toByteArray()).containsExactly(7, 7);
    }

    @Test
    void documentsWithoutPdfFormAreDownloadedAsOriginals() {
        folder.changePolicy(true, true);
        givenVersion("Email.eml", "message/rfc822", new Rendition(PreviewStatus.UNAVAILABLE, 0, false, true));

        assertThat(service.open(PROJECT, document.getId(), null).originalAllowed()).isTrue();
        assertThat(service.download(PROJECT, document.getId(), null, null).format())
                .isEqualTo(DocumentViewService.Format.ORIGINAL);
        assertThatThrownBy(() -> service.download(PROJECT, document.getId(), null, DocumentViewService.Format.PDF))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void versionsOfOtherDocumentsAreNotFound() {
        var other = UUID.randomUUID();
        when(versions.findById(other)).thenReturn(Optional.of(new DocumentVersion(other, UUID.randomUUID(), PROJECT,
                1, "k", "x.pdf", "application/pdf", 1, "h", UUID.randomUUID())));
        assertThatThrownBy(() -> service.open(PROJECT, document.getId(), other))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(DocumentViewService.pdfName("README")).isEqualTo("README.pdf");
    }

    private void givenRole(ProjectRole role) {
        var context = context(role);
        when(access.project(PROJECT)).thenReturn(context);
        when(access.folder(context, folder.getId())).thenReturn(folder);
    }

    private void givenVersion(String name, String type, Rendition rendition) {
        document = new Document(PROJECT, folder.getId(), name, UUID.randomUUID());
        version = new DocumentVersion(UUID.randomUUID(), document.getId(), PROJECT, 1, "projects/k", name, type, 10,
                "h", UUID.randomUUID());
        version.recordScan(ScanStatus.CLEAN, type, null, Instant.now());
        version.recordRendition(rendition);
        document.useVersion(version);
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        when(versions.findById(version.getId())).thenReturn(Optional.of(version));
    }

    private static int countNonWhite(BufferedImage image) {
        var count = 0;
        for (var x = 0; x < image.getWidth(); x++) {
            for (var y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) {
                    count++;
                }
            }
        }
        return count;
    }
}
