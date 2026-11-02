package com.inpartnerhub.diligence.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.inpartnerhub.diligence.audit.AuditEvents.AuditLogExported;
import com.inpartnerhub.diligence.security.internal.SecurityConfiguration;

@WebMvcTest(controllers = AuditLogController.class,
        properties = { "diligence.security.client-id=diligence-api",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test" })
@Import(SecurityConfiguration.class)
@RecordApplicationEvents
class AuditLogControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationEvents applicationEvents;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    AuditLogRepository repository;

    private static RequestPostProcessor superAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    private static RequestPostProcessor analyst() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }

    @Test
    void searchReturnsPagedAuditLogsForSuperAdmin() throws Exception {
        var entry = new AuditLogEntry(
                Instant.parse("2026-11-01T10:00:00Z"),
                UUID.randomUUID(),
                "user.invited",
                "user",
                "u-123",
                "127.0.0.1",
                "Mozilla/5.0",
                "SUCCESS",
                "{\"role\":\"ANALYST\"}");

        when(repository.findAll(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));

        mvc.perform(get("/api/v1/admin/audit-logs")
                .with(superAdmin())
                .param("action", "user.invited"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("user.invited"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCESS"));
    }

    @Test
    void searchRejectsNonSuperAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-logs").with(analyst()))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportGeneratesCsvAndPublishesAuditLogExportedEvent() throws Exception {
        var entry1 = new AuditLogEntry(
                Instant.parse("2026-11-01T12:00:00Z"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "document.downloaded",
                "document",
                "doc-456",
                "192.168.1.10",
                "TestAgent",
                "SUCCESS",
                "{\"watermarked\":true}");

        var entry2 = new AuditLogEntry(
                Instant.parse("2026-11-01T12:05:00Z"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "finding.created",
                "finding",
                "fnd-789",
                "10.0.0.1",
                "TestAgent",
                "SUCCESS",
                "=cmd|' /C calc'!A0"); // test CSV formula injection defense

        when(repository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(entry1, entry2));

        var result = mvc.perform(get("/api/v1/admin/audit-logs/export")
                .with(superAdmin())
                .param("action", "document.downloaded"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        var content = result.getResponse().getContentAsString();
        assertThat(content).startsWith("\uFEFFTimestamp (UTC),User ID,Action,Object Type,Object ID,IP Address,Outcome,Details");
        assertThat(content).contains("\"document.downloaded\",\"document\",\"doc-456\"");
        // Formula injection cell must be prepended with a single quote
        assertThat(content).contains("\"'=cmd|' /C calc'!A0\"");

        var exported = applicationEvents.stream(AuditLogExported.class).findFirst().orElseThrow();
        assertThat(exported.auditAction()).isEqualTo("audit.exported");
        assertThat(exported.auditDetails()).containsEntry("action", "document.downloaded");
    }

    @Test
    void exportRejectsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-logs/export"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/admin/audit-logs/export").with(analyst()))
                .andExpect(status().isForbidden());
    }
}

