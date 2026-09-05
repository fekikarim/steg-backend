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
    boolean existsByReference(String reference);
    long countByReferencePrefix(String prefix);
}
