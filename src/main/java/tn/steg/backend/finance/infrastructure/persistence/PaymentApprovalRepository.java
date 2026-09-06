package tn.steg.backend.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.finance.domain.model.PaymentApproval;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentApprovalRepository extends JpaRepository<PaymentApproval, UUID>,
        tn.steg.backend.finance.domain.repository.PaymentApprovalRepository {
    List<PaymentApproval> findByFinanceCaseIdOrderByDecisionSequenceAsc(UUID financeCaseId);
    long countByFinanceCaseId(UUID financeCaseId);

    /**
     * A14 N+1 fix backing the domain port method: approval histories for many
     * cases with the deciding employee fetched eagerly (rendered per approval).
     */
    @Query("SELECT DISTINCT a FROM PaymentApproval a " +
           "LEFT JOIN FETCH a.decidedBy " +
           "WHERE a.financeCase.id IN :financeCaseIds ORDER BY a.decisionSequence ASC")
    List<PaymentApproval> findByFinanceCaseIdInWithDecider(@Param("financeCaseIds") Collection<UUID> financeCaseIds);
}
