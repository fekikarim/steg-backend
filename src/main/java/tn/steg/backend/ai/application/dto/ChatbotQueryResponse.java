package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Back-office chatbot answer (advisory text; never authoritative state)")
public record ChatbotQueryResponse(
        @Schema(description = "Final advisory answer text")
        String answer,
        @Schema(description = "True when the model was unreachable and this is the friendly fallback text")
        boolean degraded,
        @Schema(description = "Live-data tools executed for this answer, in order")
        List<String> toolsUsed
) {
}
