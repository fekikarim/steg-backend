package tn.steg.backend.companion.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.steg.backend.companion.domain.model.DeliverableVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliverableVersionRepository extends JpaRepository<DeliverableVersion, UUID>, tn.steg.backend.companion.domain.repository.DeliverableVersionRepository {
    List<DeliverableVersion> findByDeliverableIdOrderByVersionNumberAsc(UUID deliverableId);
    Optional<DeliverableVersion> findTopByDeliverableIdOrderByVersionNumberDesc(UUID deliverableId);
    Optional<DeliverableVersion> findByDeliverableIdAndVersionNumber(UUID deliverableId, Integer versionNumber);
}
