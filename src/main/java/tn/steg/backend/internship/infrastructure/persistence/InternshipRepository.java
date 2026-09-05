package tn.steg.backend.internship.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.Internship;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tn.steg.backend.internship.domain.model.InternshipStatus;

@Repository
public interface InternshipRepository extends JpaRepository<Internship, UUID>, tn.steg.backend.internship.domain.repository.InternshipRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Internship i where i.id = :id")
    Optional<Internship> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);

    @Query("SELECT COUNT(i) FROM Internship i WHERE i.reference LIKE :prefix%")
    long countByReferencePrefix(@Param("prefix") String prefix);

    @Query("select i from Internship i where i.candidate.id = :candidateId")
    List<Internship> findByCandidateId(@Param("candidateId") UUID candidateId);

    @Query("select i from Internship i where i.candidate.user.id = :userId and i.status = :status")
    List<Internship> findByCandidateUserIdAndStatus(@Param("userId") UUID userId, @Param("status") InternshipStatus status);
}
