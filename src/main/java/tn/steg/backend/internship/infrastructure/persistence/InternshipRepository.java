package tn.steg.backend.internship.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.steg.backend.internship.domain.model.Internship;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InternshipRepository extends JpaRepository<Internship, UUID>, tn.steg.backend.internship.domain.repository.InternshipRepository {
    Optional<Internship> findByReference(String reference);
    boolean existsByReference(String reference);

    @Query("SELECT COUNT(i) FROM Internship i WHERE i.reference LIKE :prefix%")
    long countByReferencePrefix(@Param("prefix") String prefix);
}
