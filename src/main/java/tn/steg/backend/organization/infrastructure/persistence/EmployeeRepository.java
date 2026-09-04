package tn.steg.backend.organization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.organization.domain.model.Employee;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID>, tn.steg.backend.organization.domain.repository.EmployeeRepository {
    Optional<Employee> findByEmployeeNumber(String employeeNumber);
}
