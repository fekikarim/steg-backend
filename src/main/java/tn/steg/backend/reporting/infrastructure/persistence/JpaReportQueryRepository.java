package tn.steg.backend.reporting.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.reporting.domain.model.GroupCount;
import tn.steg.backend.reporting.domain.model.PaymentTotalRow;
import tn.steg.backend.reporting.domain.model.WorkloadRow;
import tn.steg.backend.reporting.domain.repository.ReportQueryRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only JPA adapter for the Back Office reporting dashboards. All queries
 * aggregate in the database (GROUP BY + COUNT/SUM); none load raw aggregates
 * into the persistence context and none may mutate. Rewritten as projections,
 * so every call is a single SQL statement (no N+1).
 */
@Repository
public interface JpaReportQueryRepository extends JpaRepository<InternshipApplication, UUID>, ReportQueryRepository {

    @Query("select s.status as groupName, count(s) as count " +
            "from InternshipApplication s group by s.status")
    List<GroupCount> countApplicationsByStatus();

    @Query("select i.type as groupName, count(i) as count " +
            "from Internship i group by i.type")
    List<GroupCount> countInternshipsByType();

    @Query("select i.status as groupName, count(i) as count " +
            "from Internship i group by i.status")
    List<GroupCount> countInternshipsByStatus();

    @Query("select d.code as groupName, count(distinct i.id) as count " +
            "from Internship i " +
            "join InternshipAssignment a on a.internship = i " +
            "join a.destination d " +
            "group by d.code")
    List<GroupCount> countInternshipsByDepartment();

    @Query("select fc.status as groupName, count(fc) as count " +
            "from FinanceCase fc group by fc.status")
    List<GroupCount> countFinanceCasesByStatus();

    @Query("select year(pr.paymentDate) as year, month(pr.paymentDate) as month, " +
            "d.code as departmentCode, sum(pr.amount) as totalAmount, count(pr) as receiptCount " +
            "from PaymentReceipt pr " +
            "join pr.financeCase fc " +
            "join fc.internship i " +
            "left join InternshipAssignment a on a.internship = i " +
            "left join a.destination d " +
            "where pr.paymentDate is not null " +
            "group by year(pr.paymentDate), month(pr.paymentDate), d.code " +
            "order by year(pr.paymentDate) desc, month(pr.paymentDate) desc, d.code asc")
    List<PaymentTotalRow> paymentTotals();

    @Query("select year(pr.paymentDate) as year, month(pr.paymentDate) as month, " +
            "d.code as departmentCode, sum(pr.amount) as totalAmount, count(pr) as receiptCount " +
            "from PaymentReceipt pr " +
            "join pr.financeCase fc " +
            "join fc.internship i " +
            "join InternshipAssignment a on a.internship = i " +
            "join a.destination d " +
            "where pr.paymentDate is not null and a.destination.id = :departmentId " +
            "group by year(pr.paymentDate), month(pr.paymentDate), d.code " +
            "order by year(pr.paymentDate) desc, month(pr.paymentDate) desc, d.code asc")
    List<PaymentTotalRow> paymentTotalsForDepartment(@Param("departmentId") UUID departmentId);

    @Query("select t.status as groupName, count(t) as count " +
            "from Task t group by t.status")
    List<GroupCount> countTasksByStatus();

    @Query("select t.status as groupName, count(t) as count " +
            "from Task t " +
            "where t.internship.candidate.id in :ids and t.internship.candidate.deletedAt is null " +
            "group by t.status")
    List<GroupCount> countTasksByStatusForCandidates(@Param("ids") List<UUID> ids);

    @Query("select count(t) from Task t " +
            "where t.internship.candidate.id in :ids and t.internship.candidate.deletedAt is null " +
            "and t.dueDate is not null and t.dueDate < :today " +
            "and t.status not in (tn.steg.backend.companion.domain.model.TaskStatus.APPROVED, " +
            "tn.steg.backend.companion.domain.model.TaskStatus.DENIED, " +
            "tn.steg.backend.companion.domain.model.TaskStatus.CANCELLED)")
    long countOverdueTasksForCandidates(@Param("ids") List<UUID> ids, @Param("today") LocalDate today);

    @Query("select count(t) from Task t " +
            "where t.dueDate is not null and t.dueDate < :today " +
            "and t.status not in (tn.steg.backend.companion.domain.model.TaskStatus.APPROVED, " +
            "tn.steg.backend.companion.domain.model.TaskStatus.DENIED, " +
            "tn.steg.backend.companion.domain.model.TaskStatus.CANCELLED)")
    long countOverdueTasksGlobal(@Param("today") LocalDate today);

    @Query("select i.status as groupName, count(i) as count " +
            "from Internship i " +
            "where i.candidate.id in :ids and i.candidate.deletedAt is null " +
            "group by i.status")
    List<GroupCount> countInternshipsByStatusForCandidates(@Param("ids") List<UUID> ids);

    @Query("select count(c) from Candidate c " +
            "where c.deletedAt is null and c.createdAt >= :since")
    long countLiveCandidatesCreatedSince(@Param("since") Instant since);

    @Query("select count(c) from Candidate c where c.deletedAt is null")
    long countLiveCandidates();

    @Query("select count(pr) from PaymentReceipt pr")
    long countPaymentReceipts();

    @Query("select count(c) from Certificate c " +
            "where c.status <> tn.steg.backend.certificate.domain.model.CertificateStatus.REVOKED")
    long countIssuedCertificates();

    @Query("select a.supervisorUser.id as supervisorUserId, a.supervisorUser.email as email, " +
            "count(distinct i.candidate.id) as candidateCount " +
            "from InternshipAssignment a " +
            "join a.internship i " +
            "join i.candidate c " +
            "where a.status = tn.steg.backend.internship.domain.model.AssignmentStatus.ACTIVE " +
            "and a.supervisorUser is not null and c.deletedAt is null " +
            "group by a.supervisorUser.id, a.supervisorUser.email " +
            "order by candidateCount desc, email asc")
    List<WorkloadRow> workloadBySupervisor();

    @Query("select count(a) from InternshipApplication a " +
            "where a.createdAt >= :fromInclusive and a.createdAt < :toExclusive")
    long countApplicationsCreatedBetween(@Param("fromInclusive") Instant fromInclusive,
                                          @Param("toExclusive") Instant toExclusive);

    @Query("select count(i) from Internship i " +
            "where i.createdAt >= :fromInclusive and i.createdAt < :toExclusive")
    long countInternshipsCreatedBetween(@Param("fromInclusive") Instant fromInclusive,
                                        @Param("toExclusive") Instant toExclusive);
}