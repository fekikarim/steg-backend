package tn.steg.backend.organization.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.organization.domain.model.Department;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID>, tn.steg.backend.organization.domain.repository.DepartmentRepository {
    Optional<Department> findByCode(String code);
}
