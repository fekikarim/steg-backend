package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Query payload for Candidate Virtual Assistant")
public record CandidateAssistantQueryRequest(
        @NotBlank(message = "Query question cannot be blank")
        @Size(max = 2000, message = "Question is too long (maximum 2000 characters)")
        String question
) {
}
