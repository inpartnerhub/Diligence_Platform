package com.inpartnerhub.diligence.request.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.inpartnerhub.diligence.shared.web.ApiPaths;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/requests")
@Tag(name = "Request list")
class RequestTransferController {

    static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;

    private final RequestTransferService transfers;

    RequestTransferController(RequestTransferService transfers) {
        this.transfers = transfers;
    }

    @GetMapping("/export")
    @Operation(summary = "Download the request list as Excel (xlsx) or PDF; logged in the audit trail — REQ-05")
    ResponseEntity<byte[]> export(@PathVariable UUID projectId,
            @RequestParam(defaultValue = "xlsx") String format) {
        var parsed = switch (format.toLowerCase(Locale.ROOT)) {
            case "xlsx" -> RequestTransferService.Format.XLSX;
            case "pdf" -> RequestTransferService.Format.PDF;
            default -> throw new InvalidRequestException("Format must be xlsx or pdf");
        };
        return file(transfers.export(projectId, parsed));
    }

    @GetMapping("/import-template")
    @Operation(summary = "Empty Excel sheet for importing items, with the project's workstreams — REQ-01")
    ResponseEntity<byte[]> template(@PathVariable UUID projectId) {
        return file(transfers.template(projectId));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import items from Excel. dryRun=true only validates; otherwise all rows are stored or, "
            + "if any row is invalid, none — REQ-01")
    RequestTransferService.ImportResult importList(@PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
        if (file.isEmpty()) {
            throw new InvalidRequestException("The file is empty");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new InvalidRequestException("Import files are limited to 5 MB");
        }
        try (var content = file.getInputStream()) {
            return transfers.importList(projectId, content, dryRun);
        } catch (IOException ex) {
            throw new InvalidRequestException("The upload could not be read");
        }
    }

    private static ResponseEntity<byte[]> file(RequestTransferService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
