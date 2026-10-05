package tn.steg.backend.certificate.domain.repository;

import tn.steg.backend.certificate.domain.model.Certificate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CertificateRepository {
    Optional<Certificate> findById(UUID id);
    Optional<Certificate> findByReference(String reference);
    List<Certificate> findByInternshipId(UUID internshipId);
    Certificate save(Certificate certificate);

    /** Atomic reference counter (sequence-backed, race-free). */
    long nextReferenceSequence();

    /**
     * S8 workspace query (§5.7): one paged query with explicit joins and the
     * filters mirrored in the count query.
     */
    org.springframework.data.domain.Page<Certificate> searchCertificates(
            tn.steg.backend.certificate.domain.model.CertificateStatus status,
            String pattern,
            org.springframework.data.domain.Pageable pageable);
}
