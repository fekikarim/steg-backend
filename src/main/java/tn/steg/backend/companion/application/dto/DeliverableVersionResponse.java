package tn.steg.backend.companion.application.dto;

import java.time.Instant;
import java.util.UUID;

public record DeliverableVersionResponse(
        UUID id,
        UUID deliverableId,
        UUID fileAssetId,
        String fileName,
        String mimeType,
        Long size,
        UUID uploadedById,
        String uploadedByEmail,
        Integer versionNumber,
        String changeSummary,
        Instant uploadedAt
) {}
