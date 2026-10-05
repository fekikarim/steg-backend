package tn.steg.backend.reporting.domain.model;

import java.util.UUID;

/**
 * Read-model projection for supervisor workload (S11): distinct live
 * candidates per supervising user, resolved from ACTIVE assignments
 * (user-backed). Filled by a database-side GROUP BY; never backed by an entity.
 */
public interface WorkloadRow {

    UUID getSupervisorUserId();

    String getEmail();

    long getCandidateCount();
}
