package tn.steg.backend.ai.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Back-office chatbot query (Admin/Supervisor scope)")
public record ChatbotQueryRequest(
        @NotBlank(message = "Query message cannot be blank")
        @Size(max = 2000, message = "Message is too long (maximum 2000 characters)")
        String message
) {
}
