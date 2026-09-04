package tn.steg.backend.organization.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.organization.application.dto.DepartmentRequest;
import tn.steg.backend.organization.application.dto.DepartmentResponse;
import tn.steg.backend.organization.application.dto.EmployeeRequest;
import tn.steg.backend.organization.application.dto.EmployeeResponse;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.model.Employee;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;

import java.util.List;
import java.util.UUID;

/**
 * Application service for the Organization module.
 * Manages departments and employees; restricted to ADMIN and HR staff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // Department operations
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments() {
        return departmentRepository.findAll().stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DepartmentResponse getDepartment(UUID id) {
        return DepartmentResponse.from(findDepartmentOrThrow(id));
    }

    @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest request) {
        if (departmentRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessRuleException("DEPARTMENT_CODE_EXISTS",
                    "A department with code '" + request.code() + "' already exists.");
        }

        Department parent = null;
        if (request.parentDepartmentId() != null) {
            parent = findDepartmentOrThrow(request.parentDepartmentId());
        }

        Department department = new Department(request.code(), request.name(), request.description());
        department.setParentDepartment(parent);
        department = departmentRepository.save(department);

        log.info("Department created: code={}", department.getCode());
        return DepartmentResponse.from(department);
    }

    @Transactional
    public DepartmentResponse updateDepartment(UUID id, DepartmentRequest request) {
        Department department = findDepartmentOrThrow(id);

        // Check code uniqueness only if it changed
        if (!department.getCode().equals(request.code())
                && departmentRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessRuleException("DEPARTMENT_CODE_EXISTS",
                    "A department with code '" + request.code() + "' already exists.");
        }

        Department parent = null;
        if (request.parentDepartmentId() != null) {
            if (request.parentDepartmentId().equals(id)) {
                throw new BusinessRuleException("DEPARTMENT_SELF_PARENT",
                        "A department cannot be its own parent.");
            }
            parent = findDepartmentOrThrow(request.parentDepartmentId());
        }

        department.setCode(request.code());
        department.setName(request.name());
        department.setDescription(request.description());
        department.setParentDepartment(parent);
        department = departmentRepository.save(department);

        log.info("Department updated: id={}", id);
        return DepartmentResponse.from(department);
    }

    @Transactional
    public void deactivateDepartment(UUID id) {
        Department department = findDepartmentOrThrow(id);
        department.setActive(false);
        departmentRepository.save(department);
        log.info("Department deactivated: id={}", id);
    }

    // -------------------------------------------------------------------------
    // Employee operations
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmployeeResponse> listEmployees() {
        return employeeRepository.findAll().stream()
                .map(EmployeeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getEmployee(UUID id) {
        return EmployeeResponse.from(findEmployeeOrThrow(id));
    }

    @Transactional
    public EmployeeResponse createEmployee(EmployeeRequest request) {
        if (employeeRepository.findByEmployeeNumber(request.employeeNumber()).isPresent()) {
            throw new BusinessRuleException("EMPLOYEE_NUMBER_EXISTS",
                    "An employee with number '" + request.employeeNumber() + "' already exists.");
        }

        Department department = findDepartmentOrThrow(request.departmentId());

        Employee employee = new Employee(
                request.employeeNumber(),
                request.firstName(),
                request.lastName(),
                department
        );
        employee.setPhoneNumber(request.phoneNumber());
        employee.setPosition(request.position());
        employee.setHireDate(request.hireDate());

        if (request.userId() != null) {
            User user = userRepository.findById(request.userId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.userId()));
            employee.setUser(user);
        }

        employee = employeeRepository.save(employee);
        log.info("Employee created: number={}", employee.getEmployeeNumber());
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse updateEmployee(UUID id, EmployeeRequest request) {
        Employee employee = findEmployeeOrThrow(id);

        if (!employee.getEmployeeNumber().equals(request.employeeNumber())
                && employeeRepository.findByEmployeeNumber(request.employeeNumber()).isPresent()) {
            throw new BusinessRuleException("EMPLOYEE_NUMBER_EXISTS",
                    "An employee with number '" + request.employeeNumber() + "' already exists.");
        }

        Department department = findDepartmentOrThrow(request.departmentId());
        employee.setEmployeeNumber(request.employeeNumber());
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setPhoneNumber(request.phoneNumber());
        employee.setPosition(request.position());
        employee.setHireDate(request.hireDate());
        employee.setDepartment(department);

        if (request.userId() != null) {
            User user = userRepository.findById(request.userId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.userId()));
            employee.setUser(user);
        }

        employee = employeeRepository.save(employee);
        log.info("Employee updated: id={}", id);
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public void deactivateEmployee(UUID id) {
        Employee employee = findEmployeeOrThrow(id);
        employee.setActive(false);
        employeeRepository.save(employee);
        log.info("Employee deactivated: id={}", id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Department findDepartmentOrThrow(UUID id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + id));
    }

    private Employee findEmployeeOrThrow(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }
}
