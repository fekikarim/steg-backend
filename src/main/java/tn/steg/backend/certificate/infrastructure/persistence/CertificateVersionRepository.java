package tn.steg.backend.certificate.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.certificate.domain.model.CertificateVersion;

import java.util.List;
import java.util.UUID;

@Repository
public interface CertificateVersionRepository extends JpaRepository<CertificateVersion, UUID>,
        tn.steg.backend.certificate.domain.repository.CertificateVersionRepository {
    List<CertificateVersion> findByCertificateIdOrderByVersionNumberAsc(UUID certificateId);
}
