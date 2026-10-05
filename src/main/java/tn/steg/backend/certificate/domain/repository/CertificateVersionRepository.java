package tn.steg.backend.certificate.domain.repository;

import tn.steg.backend.certificate.domain.model.CertificateVersion;

import java.util.List;
import java.util.UUID;

/** Domain port for the append-only certificate version history (S8). */
public interface CertificateVersionRepository {
    CertificateVersion save(CertificateVersion version);

    List<CertificateVersion> findByCertificateIdOrderByVersionNumberAsc(UUID certificateId);
}
