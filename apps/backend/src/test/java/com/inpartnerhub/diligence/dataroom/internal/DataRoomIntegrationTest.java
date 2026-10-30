package com.inpartnerhub.diligence.dataroom.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.inpartnerhub.diligence.TestContainersConfiguration;
import com.inpartnerhub.diligence.dataroom.DocumentStorage;
import com.inpartnerhub.diligence.ingestion.ContentScanner;
import com.inpartnerhub.diligence.ingestion.ContentTypeDetector;
import com.inpartnerhub.diligence.ingestion.PreviewRenderer;
import com.inpartnerhub.diligence.ingestion.TextExtractor;
import com.inpartnerhub.diligence.search.SearchIndex;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ResourceNotFoundException;

/**
 * Sprint 5–6 end to end against real PostgreSQL and RabbitMQ: template folders, target grants enforced by row-level
 * security (V7), upload with folder paths, and the outbox → RabbitMQ → ingestion worker → scan status round trip.
 * Object storage, ClamAV, Tika, Gotenberg and OpenSearch are replaced by test doubles.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "spring.cache.type=none",
        "diligence.identity.admin-client-secret=test-only",
        "diligence.ingestion.retry-backoff=10ms"
})
@Import({ TestContainersConfiguration.class, com.inpartnerhub.diligence.project.internal.ProjectTestApi.class })
class DataRoomIntegrationTest {

    @Autowired
    com.inpartnerhub.diligence.project.internal.ProjectTestApi projects;

    @Autowired
    FolderService folders;

    @Autowired
    DocumentService documents;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    DocumentStorage storage;

    @MockitoBean
    ContentScanner scanner;

    @MockitoBean
    ContentTypeDetector detector;

    @MockitoBean
    TextExtractor extractor;

    @MockitoBean
    PreviewRenderer renderer;

    @MockitoBean
    SearchIndex searchIndex;

    @Autowired
    DocumentViewService views;

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final UUID clientOrg = UUID.randomUUID();
    private final UUID targetOrg = UUID.randomUUID();
    private final UUID pm = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), ((InputStream) inv.getArgument(1)).readAllBytes());
            return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString());
        when(storage.get(anyString())).thenAnswer(inv -> new ByteArrayInputStream(objects.get(inv.getArgument(0))));
        when(scanner.scan(any())).thenAnswer(inv -> {
            var content = new String(((InputStream) inv.getArgument(0)).readAllBytes(), StandardCharsets.US_ASCII);
            return content.contains("EICAR") ? new ContentScanner.ScanResult(false, "Eicar-Test-Signature")
                    : new ContentScanner.ScanResult(true, null);
        });
        when(detector.detect(any(), anyString())).thenAnswer(inv -> ((String) inv.getArgument(1)).endsWith(".exe")
                ? "application/x-msdownload" : "application/pdf");

        when(extractor.extract(any(), anyString(), anyInt())).thenReturn("isi perjanjian");

        jdbc.update("insert into adm.organization (id, name, type) values (?, ?, 'CLIENT'), (?, ?, 'TARGET')",
                clientOrg, "C " + clientOrg, targetOrg, "T " + targetOrg);
        jdbc.update("insert into adm.user_account (id, organization_id, email, display_name, global_role, status) "
                + "values (?, null, ?, 'PM', 'PROJECT_MANAGER', 'ACTIVE'), (?, ?, ?, 'Target', 'TARGET', 'ACTIVE')",
                pm, pm + "@x.test", target, targetOrg, target + "@x.test");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void targetUploadsIntoGrantedFolderAndPipelineScansThroughRabbitMq() {
        signIn(pm, Role.PROJECT_MANAGER);
        var projectId = projects.createWithFolders(pm.toString().substring(0, 8), clientOrg, targetOrg,
                List.of("01 Inbox", "02 Internal", "02 Internal/Memo"));
        projects.addMember(projectId, target, "TARGET");
        var tree = folders.tree(projectId);
        assertThat(tree).extracting(Folder::getName).containsExactlyInAnyOrder("01 Inbox", "02 Internal", "Memo");
        var inbox = byName(tree, "01 Inbox");
        var internal = byName(tree, "02 Internal");
        folders.replaceGrants(projectId, inbox.getId(), List.of(new FolderService.GrantCommand(
                FolderAccess.PrincipalType.ROLE, null, FolderAccess.Permission.UPLOAD)));

        // Target side: only the granted folder is visible; uploads with a relative path create subfolders.
        signIn(target, Role.TARGET);
        assertThat(folders.tree(projectId)).extracting(Folder::getName).containsExactly("01 Inbox");
        assertThatThrownBy(() -> documents.list(projectId, internal.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        var clean = upload(projectId, inbox.getId(), "Kontrak/2025/Perjanjian.pdf", "contract text");
        var infected = upload(projectId, inbox.getId(), null, "X5O!P%@AP EICAR test");
        var executable = upload(projectId, inbox.getId(), "tool.exe", "MZ binary");
        assertThat(folders.tree(projectId)).extracting(Folder::getName)
                .containsExactlyInAnyOrder("01 Inbox", "Kontrak", "2025");

        // The worker consumes DocumentUploaded from RabbitMQ and records the outcome.
        signIn(pm, Role.PROJECT_MANAGER);
        assertThat(awaitStatus(() -> status(clean))).isEqualTo("CLEAN");
        assertThat(awaitStatus(() -> status(infected))).isEqualTo("INFECTED");
        assertThat(awaitStatus(() -> status(executable))).isEqualTo("REJECTED");

        // Clean documents reach the search index (after commit, via the event publication registry).
        verify(searchIndex, timeout(10_000)).index(argThat(d -> d.documentId().equals(clean)
                && d.content().equals("isi perjanjian") && d.projectId().equals(projectId)));
        verify(searchIndex, timeout(10_000)).remove(infected);

        // The target may view but not download (folder policy denies by default).
        signIn(target, Role.TARGET);
        var viewable = views.open(projectId, clean, null);
        assertThat(viewable.downloadAllowed()).isFalse();
        assertThat(viewable.version().getPreviewStatus()).isEqualTo(PreviewState.FAILED); // not a real PDF

        // Same name again: new version; identical content: recognised as unchanged.
        signIn(target, Role.TARGET);
        var again = documents.upload(projectId, inbox.getId(), command("Kontrak/2025/Perjanjian.pdf", "contract text"),
                stream("contract text"));
        assertThat(again.unchanged()).isTrue();
        var v2 = documents.upload(projectId, inbox.getId(), command("Kontrak/2025/Perjanjian.pdf", "amended"),
                stream("amended"));
        assertThat(v2.version().getVersionNumber()).isEqualTo(2);
        assertThat(documents.versions(projectId, clean)).hasSize(2);
    }

    private UUID upload(UUID projectId, UUID folderId, String relativePath, String content) {
        var name = relativePath == null ? "malware-sample.pdf" : relativePath;
        return documents.upload(projectId, folderId, command(name, content), stream(content)).document().getId();
    }

    private static DocumentService.UploadCommand command(String relativePath, String content) {
        var name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return new DocumentService.UploadCommand(name, relativePath, "application/pdf",
                content.getBytes(StandardCharsets.UTF_8).length);
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String status(UUID documentId) {
        return jdbc.queryForObject("select scan_status from dr.document where id = ?", String.class, documentId);
    }

    private static String awaitStatus(Supplier<String> status) {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        var current = status.get();
        while ("PENDING".equals(current) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            current = status.get();
        }
        return current;
    }

    private static Folder byName(List<Folder> folders, String name) {
        return folders.stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void signIn(UUID userId, Role role) {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority(role.authority())), userId.toString()));
    }
}
