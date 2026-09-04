package tn.steg.backend.organization.domain.repository;

import tn.steg.backend.organization.domain.model.Department;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository {
    List<Department> findAll();
    Optional<Department> findById(UUID id);
    Optional<Department> findByCode(String code);
    Department save(Department department);
}
