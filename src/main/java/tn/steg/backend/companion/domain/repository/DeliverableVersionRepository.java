package tn.steg.backend.companion.domain.repository;

import tn.steg.backend.companion.domain.model.DeliverableVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliverableVersionRepository {
    Optional<DeliverableVersion> findById(UUID id);
    DeliverableVersion save(DeliverableVersion deliverableVersion);
    List<DeliverableVersion> findByDeliverableIdOrderByVersionNumberAsc(UUID deliverableId);
    Optional<DeliverableVersion> findTopByDeliverableIdOrderByVersionNumberDesc(UUID deliverableId);
    Optional<DeliverableVersion> findByDeliverableIdAndVersionNumber(UUID deliverableId, Integer versionNumber);
}
