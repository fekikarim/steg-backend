package tn.steg.backend.document.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.document.domain.model.FileAsset;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileAssetRepository extends JpaRepository<FileAsset, UUID> {
    Optional<FileAsset> findByChecksum(String checksum);
}
