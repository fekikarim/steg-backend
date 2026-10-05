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

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT nextval('certificate_reference_seq')", nativeQuery = true)
    long nextReferenceSequence();

    @org.springframework.data.jpa.repository.Query(value = "SELECT DISTINCT c FROM Certificate c "
            + "LEFT JOIN FETCH c.internship i LEFT JOIN FETCH i.candidate cand "
            + "LEFT JOIN FETCH cand.university LEFT JOIN FETCH i.supervisorUser "
            + "WHERE (:status IS NULL OR c.status = :status) "
            + "AND (:pattern IS NULL OR LOWER(cand.firstName) LIKE :pattern "
            + "OR LOWER(cand.lastName) LIKE :pattern OR LOWER(cand.email) LIKE :pattern "
            + "OR LOWER(c.reference) LIKE :pattern OR LOWER(i.reference) LIKE :pattern)",
            countQuery = "SELECT COUNT(DISTINCT c) FROM Certificate c "
                    + "LEFT JOIN c.internship i LEFT JOIN i.candidate cand "
                    + "WHERE (:status IS NULL OR c.status = :status) "
                    + "AND (:pattern IS NULL OR LOWER(cand.firstName) LIKE :pattern "
                    + "OR LOWER(cand.lastName) LIKE :pattern OR LOWER(cand.email) LIKE :pattern "
                    + "OR LOWER(c.reference) LIKE :pattern OR LOWER(i.reference) LIKE :pattern)")
    org.springframework.data.domain.Page<Certificate> searchCertificates(
            @org.springframework.data.repository.query.Param("status")
            tn.steg.backend.certificate.domain.model.CertificateStatus status,
            @org.springframework.data.repository.query.Param("pattern") String pattern,
            org.springframework.data.domain.Pageable pageable);
}
