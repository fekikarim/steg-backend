package tn.steg.backend.document.domain.repository;

import tn.steg.backend.document.domain.model.FileAsset;

import java.util.Optional;
import java.util.UUID;

public interface FileAssetRepository {
    Optional<FileAsset> findById(UUID id);
    FileAsset save(FileAsset fileAsset);
}
