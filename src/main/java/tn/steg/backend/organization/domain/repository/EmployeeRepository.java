package tn.steg.backend.organization.domain.repository;

import tn.steg.backend.organization.domain.model.Employee;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository {
    List<Employee> findAll();
    Optional<Employee> findById(UUID id);
    Optional<Employee> findByEmployeeNumber(String employeeNumber);
    Employee save(Employee employee);
}
