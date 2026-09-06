package tn.steg.backend.audit.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.application.dto.AuditLogResponse;

import java.util.UUID;

/**
 * Back Office audit viewer (Phase A13). Read-only: this controller never
 * mutates authoritative state and is restricted to platform ADMIN accounts.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Tag(name = "Audit", description = "Read-only Back Office audit trail viewer (ADMIN only)")
@SecurityRequirement(name = "BearerAuth")
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List audit log entries (ADMIN only)",
            description = "Returns the unified audit trail, optionally filtered by action, entity or actor. "
                    + "Read only; respects the pagination/sorting contract shared by all list endpoints."
    )
    @ApiResponse(responseCode = "200", description = "Audit log page",
            content = @Content(schema = @Schema(implementation = AuditLogResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "ADMIN role required")
    public ResponseEntity<Page<AuditLogResponse>> list(
            @RequestParam(required = false) @Parameter(description = "Exact action code, e.g. APPLICATION_ACCEPTED") String action,
            @RequestParam(required = false) @Parameter(description = "Filter by entity ID") UUID entityId,
            @RequestParam(required = false) @Parameter(description = "Filter by actor (user) ID") UUID actorId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(auditService.search(action, entityId, actorId, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get a single audit entry (ADMIN only)")
    @ApiResponse(responseCode = "200", description = "The audit entry")
    @ApiResponse(responseCode = "404", description = "No audit entry with the given id")
    public ResponseEntity<AuditLogResponse> get(@PathVariable UUID id) {
        return auditService.getById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new tn.steg.backend.common.domain.exception.ResourceNotFoundException("Audit entry not found: " + id));
    }
}