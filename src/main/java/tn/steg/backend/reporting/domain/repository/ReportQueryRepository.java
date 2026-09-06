package tn.steg.backend.reporting.domain.repository;

import tn.steg.backend.reporting.domain.model.GroupCount;
import tn.steg.backend.reporting.domain.model.PaymentTotalRow;

import java.util.List;
import java.util.UUID;

/**
 * Read-only reporting port. Every method performs a database-side aggregate —
 * no raw entity loading, no N+1 — and none of them may mutate state. See the
 * JPA adapter for the concrete queries.
 */
public interface ReportQueryRepository {

    List<GroupCount> countApplicationsByStatus();

    List<GroupCount> countInternshipsByType();

    List<GroupCount> countInternshipsByStatus();

    List<GroupCount> countInternshipsByDepartment();

    List<GroupCount> countFinanceCasesByStatus();

    List<PaymentTotalRow> paymentTotals();

    List<PaymentTotalRow> paymentTotalsForDepartment(UUID departmentId);
}