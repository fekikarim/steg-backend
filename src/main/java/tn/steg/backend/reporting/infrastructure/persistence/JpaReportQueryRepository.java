package tn.steg.backend.reporting.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.reporting.domain.model.GroupCount;
import tn.steg.backend.reporting.domain.model.PaymentTotalRow;
import tn.steg.backend.reporting.domain.repository.ReportQueryRepository;

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
}