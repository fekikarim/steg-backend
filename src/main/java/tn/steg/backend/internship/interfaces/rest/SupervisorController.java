package tn.steg.backend.internship.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.internship.application.SupervisorDirectoryService;
import tn.steg.backend.internship.application.SupervisorManagementService;
import tn.steg.backend.internship.application.dto.AssignCandidateRequest;
import tn.steg.backend.internship.application.dto.CreateSupervisorRequest;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.application.dto.SupervisorOption;
import tn.steg.backend.internship.application.dto.SupervisorResponse;
import tn.steg.backend.internship.application.dto.UpdateSupervisorRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/supervisors")
@RequiredArgsConstructor
@Tag(name = "Supervisors", description = "Admin supervisor directory and management")
public class SupervisorController {

    private final SupervisorDirectoryService supervisorDirectoryService;
    private final SupervisorManagementService supervisorManagementService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List Admin and Supervisor users selectable for assignment")
    public ResponseEntity<List<SupervisorOption>> listSupervisors() {
        return ResponseEntity.ok(supervisorDirectoryService.listSelectableSupervisors());
    }

    @GetMapping("/manage")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Paged supervisor management list (AGENTS.md §5.6)",
               description = "Server-side pagination, email search, status filter and sort. "
                       + "The literal /manage path wins over /{id}: see SupervisorRouteTableTest.")
    public ResponseEntity<Page<SupervisorResponse>> listSupervisorsForManagement(
            @RequestParam(required = false, name = "q") String search,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 20, sort = "email", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(supervisorManagementService.searchSupervisors(search, status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get supervisor detail by ID")
    public ResponseEntity<SupervisorResponse> getSupervisor(@PathVariable UUID id) {
        return ResponseEntity.ok(supervisorManagementService.getSupervisor(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a supervisor account with a temporary strong password and mustChangePassword=true")
    public ResponseEntity<ResetAccountPasswordResponse> createSupervisor(
            @Valid @RequestBody CreateSupervisorRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        ResetAccountPasswordResponse response = supervisorManagementService.createSupervisor(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update supervisor status or enabled state")
    public ResponseEntity<SupervisorResponse> updateSupervisor(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSupervisorRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(supervisorManagementService.updateSupervisor(id, request, actor));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete supervisor account (blocked if active candidate assignments exist)")
    public ResponseEntity<Void> deleteSupervisor(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        supervisorManagementService.deleteSupervisor(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/assign-candidate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign an approved candidate/internship to a supervisor")
    public ResponseEntity<Void> assignCandidate(
            @PathVariable UUID id,
            @Valid @RequestBody AssignCandidateRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        supervisorManagementService.assignCandidate(id, request, actor);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/reassign/{internshipId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reassign an internship from current supervisor to a new supervisor")
    public ResponseEntity<Void> reassignSupervisor(
            @PathVariable UUID id,
            @PathVariable UUID internshipId,
            @Valid @RequestBody ReassignSupervisorRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        supervisorManagementService.reassignSupervisor(internshipId, request, actor);
        return ResponseEntity.ok().build();
    }
}