package tn.steg.backend.organization.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.steg.backend.organization.application.OrganizationService;
import tn.steg.backend.organization.application.dto.DepartmentRequest;
import tn.steg.backend.organization.application.dto.DepartmentResponse;
import tn.steg.backend.organization.application.dto.EmployeeRequest;
import tn.steg.backend.organization.application.dto.EmployeeResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Organization", description = "Department and employee management — ADMIN / HR only")
public class OrganizationController {

    private final OrganizationService organizationService;

    // -------------------------------------------------------------------------
    // Departments
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @GetMapping("/departments")
    @Operation(summary = "List all departments")
    public ResponseEntity<List<DepartmentResponse>> listDepartments() {
        return ResponseEntity.ok(organizationService.listDepartments());
    }

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @GetMapping("/departments/{id}")
    @Operation(summary = "Get a department by ID")
    public ResponseEntity<DepartmentResponse> getDepartment(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getDepartment(id));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PostMapping("/departments")
    @Operation(summary = "Create a new department")
    public ResponseEntity<DepartmentResponse> createDepartment(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizationService.createDepartment(request));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @PutMapping("/departments/{id}")
    @Operation(summary = "Update a department")
    public ResponseEntity<DepartmentResponse> updateDepartment(
            @PathVariable UUID id,
            @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(organizationService.updateDepartment(id, request));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @DeleteMapping("/departments/{id}")
    @Operation(summary = "Deactivate a department (soft-delete)")
    public ResponseEntity<Void> deactivateDepartment(@PathVariable UUID id) {
        organizationService.deactivateDepartment(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Employees
    // -------------------------------------------------------------------------

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @GetMapping("/employees")
    @Operation(summary = "List all employees")
    public ResponseEntity<List<EmployeeResponse>> listEmployees() {
        return ResponseEntity.ok(organizationService.listEmployees());
    }

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @GetMapping("/employees/{id}")
    @Operation(summary = "Get an employee by ID")
    public ResponseEntity<EmployeeResponse> getEmployee(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getEmployee(id));
    }

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @PostMapping("/employees")
    @Operation(summary = "Create a new employee")
    public ResponseEntity<EmployeeResponse> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(organizationService.createEmployee(request));
    }

    @PreAuthorize("@authz.hasAnyRole('ADMIN', 'HR')")
    @PutMapping("/employees/{id}")
    @Operation(summary = "Update an employee")
    public ResponseEntity<EmployeeResponse> updateEmployee(
            @PathVariable UUID id,
            @Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.ok(organizationService.updateEmployee(id, request));
    }

    @PreAuthorize("@authz.hasRole('ADMIN')")
    @DeleteMapping("/employees/{id}")
    @Operation(summary = "Deactivate an employee (soft-delete)")
    public ResponseEntity<Void> deactivateEmployee(@PathVariable UUID id) {
        organizationService.deactivateEmployee(id);
        return ResponseEntity.noContent().build();
    }
}
