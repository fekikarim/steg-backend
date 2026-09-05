package tn.steg.backend.certificate.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.certificate.domain.model.Certificate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, UUID>,
        tn.steg.backend.certificate.domain.repository.CertificateRepository {
    Optional<Certificate> findByReference(String reference);
    List<Certificate> findByInternshipId(UUID internshipId);
    boolean existsByReference(String reference);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(c) FROM Certificate c WHERE c.reference LIKE :prefix%")
    long countByReferencePrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);
}
