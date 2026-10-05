package tn.steg.backend.iam.interfaces.rest;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.InternAccountManagementService;
import tn.steg.backend.iam.application.dto.CreateInternAccountRequest;
import tn.steg.backend.iam.application.dto.InternAccountResponse;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.application.dto.UpdateInternAccountRequest;
import tn.steg.backend.iam.domain.model.UserStatus;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/intern-accounts")
@RequiredArgsConstructor
@Tag(name = "Intern Accounts", description = "Admin mobile account management for interns and supervisors")
public class InternAccountController {

    private final InternAccountManagementService internAccountManagementService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List mobile accounts (students and supervisors) with filtering")
    public ResponseEntity<List<InternAccountResponse>> listAccounts(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(internAccountManagementService.listAccounts(role, status, search));
    }

    /**
     * Paged 'STEG intern' account list (AGENTS.md §5.4 + §5 list rule):
     * server-side pagination, role/status filters and email-or-name search.
     * Declared before {@code /{id}} so the literal segment always wins.
     */
    @GetMapping("/manage")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Paged mobile account management list (students and supervisors)")
    public ResponseEntity<Page<InternAccountResponse>> listManagedAccounts(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false, name = "q") String search,
            @PageableDefault(size = 20, sort = "email", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(
                internAccountManagementService.searchManagedAccounts(role, status, search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get mobile account detail by user ID")
    public ResponseEntity<InternAccountResponse> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(internAccountManagementService.getAccount(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new intern or supervisor mobile account with random temporary password")
    public ResponseEntity<ResetAccountPasswordResponse> createAccount(
            @Valid @RequestBody CreateInternAccountRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        ResetAccountPasswordResponse response = internAccountManagementService.createAccount(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store")
                .body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update mobile account status or enable state")
    public ResponseEntity<InternAccountResponse> updateAccount(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInternAccountRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(internAccountManagementService.updateAccount(id, request, actor));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a mobile account (blocks if supervisor has active candidate assignments)")
    public ResponseEntity<Void> deleteAccount(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        internAccountManagementService.deleteAccount(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reset account password to a strong random temporary password")
    public ResponseEntity<ResetAccountPasswordResponse> resetPassword(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(internAccountManagementService.resetPassword(id, actor));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Activate or deactivate a mobile account")
    public ResponseEntity<InternAccountResponse> updateStatus(
            @PathVariable UUID id,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean enabled,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(internAccountManagementService.updateStatus(id, status, enabled, actor));
    }
}
