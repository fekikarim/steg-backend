package tn.steg.backend.iam.domain.repository;

import tn.steg.backend.iam.domain.model.Role;

import java.util.Optional;

/**
 * Domain port interface for Role persistence operations.
 */
public interface RoleRepository {
    Optional<Role> findByCode(String code);
}
