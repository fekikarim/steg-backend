package tn.steg.backend.reporting.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.reporting.application.DashboardSummaryService;
import tn.steg.backend.reporting.application.ReportQueryService;
import tn.steg.backend.reporting.application.dto.AdminDashboardSummaryDto;
import tn.steg.backend.reporting.application.dto.SupervisorDashboardSummaryDto;
import tn.steg.backend.reporting.application.dto.GroupCountDto;
import tn.steg.backend.reporting.application.dto.PaymentTotalRowDto;

import java.util.List;
import java.util.UUID;

/**
 * Read-only Back Office analytics/export dashboards (Phase A13). Endpoints
 * only run database-side aggregates and never mutate authoritative state.
 * Authorization is per report family: all dashboards for ADMIN.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Reports", description = "Read-only Back Office reporting dashboards (aggregate queries only)")
@SecurityRequirement(name = "BearerAuth")
public class ReportController {

    private final ReportQueryService reportQueryService;
    private final DashboardSummaryService dashboardSummaryService;

    @GetMapping("/applications-by-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Internship applications grouped by status (ADMIN)",
            description = "Read-only count of internship applications per application status.")
    @ApiResponse(responseCode = "200", description = "Grouped application counts",
            content = @Content(schema = @Schema(implementation = GroupCountDto.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Requires ADMIN role")
    public ResponseEntity<List<GroupCountDto>> applicationsByStatus() {
        return ResponseEntity.ok(reportQueryService.applicationsByStatus());
    }

    @GetMapping("/internships-by-type")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Internships grouped by internship type (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Grouped internship type counts")
    public ResponseEntity<List<GroupCountDto>> internshipsByType() {
        return ResponseEntity.ok(reportQueryService.internshipsByType());
    }

    @GetMapping("/internships-by-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Internships grouped by lifecycle status (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Grouped internship status counts")
    public ResponseEntity<List<GroupCountDto>> internshipsByStatus() {
        return ResponseEntity.ok(reportQueryService.internshipsByStatus());
    }

    @GetMapping("/internships-by-department")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assigned internships grouped by destination department (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Grouped department counts (distinct internships)")
    public ResponseEntity<List<GroupCountDto>> internshipsByDepartment() {
        return ResponseEntity.ok(reportQueryService.internshipsByDepartment());
    }

    @GetMapping("/finance-cases-by-status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Finance cases grouped by status (ADMIN)")
    @ApiResponse(responseCode = "200", description = "Grouped finance case counts")
    public ResponseEntity<List<GroupCountDto>> financeCasesByStatus() {
        return ResponseEntity.ok(reportQueryService.financeCasesByStatus());
    }

    @GetMapping("/payment-totals")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Paid amounts rolled up by period and department (ADMIN)",
            description = "Totals paid by calendar year/month and destination department. "
                    + "Pass departmentId to scope the rollup to a single department.")
    @ApiResponse(responseCode = "200", description = "Payment rollup rows",
            content = @Content(schema = @Schema(implementation = PaymentTotalRowDto.class)))
    @ApiResponse(responseCode = "404", description = "Unknown departmentId")
    public ResponseEntity<List<PaymentTotalRowDto>> paymentTotals(
            @RequestParam(required = false)
            @Parameter(description = "Restrict the rollup to this destination department") UUID departmentId) {
        return ResponseEntity.ok(reportQueryService.paymentTotals(departmentId));
    }

    @GetMapping("/admin-summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin dashboard summary, global scope (ADMIN)",
            description = "S11: one scoped backend call serving every Admin tile — pending "
                    + "applications, new candidates, tasks awaiting approval, reports "
                    + "awaiting validation, receipts/certificates issued, supervisor "
                    + "workload and monthly trends. Read-only aggregates, never mutated.")
    @ApiResponse(responseCode = "200", description = "Global dashboard summary")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Requires ADMIN role")
    public ResponseEntity<AdminDashboardSummaryDto> adminSummary() {
        return ResponseEntity.ok(dashboardSummaryService.adminSummary());
    }

    @GetMapping("/supervisor-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "Supervisor dashboard summary, own scope (ADMIN global)",
            description = "S11: one scoped backend call serving every Supervisor tile — "
                    + "own candidates, tasks awaiting approval, overdue tasks, "
                    + "candidates by status and recent notifications. An Admin caller "
                    + "sees global numbers. Read-only aggregates, never mutated.")
    @ApiResponse(responseCode = "200", description = "Scoped dashboard summary")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Requires staff role")
    public ResponseEntity<SupervisorDashboardSummaryDto> supervisorSummary(
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(dashboardSummaryService.supervisorSummary(actor));
    }
}