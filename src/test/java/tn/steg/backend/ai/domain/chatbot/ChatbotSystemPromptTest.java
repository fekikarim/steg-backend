package tn.steg.backend.ai.domain.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S10b — the system prompt injects curated knowledge plus the caller's role
 * and identity, the tool protocol and the safety policy (pure unit test).
 */
@DisplayName("S10b — ChatbotSystemPrompt")
class ChatbotSystemPromptTest {

    private static final String KNOWLEDGE = "## Roles\nAdmin sees all.";
    private static final List<String> SPECS = List.of(
            "my_candidates {status?}: candidates in scope.",
            "candidate_detail {candidateId}: one candidate.");

    @Test
    @DisplayName("admin prompt carries knowledge, ADMIN role, identity, tools and safety clauses")
    void adminPrompt() {
        String prompt = ChatbotSystemPrompt.build(
                KNOWLEDGE, "ADMIN", "admin@steg.tn", "user-1", SPECS);

        assertThat(prompt).contains("## Roles", "Admin sees all.");
        assertThat(prompt).contains("Caller role: ADMIN");
        assertThat(prompt).contains("admin@steg.tn", "user-1");
        assertThat(prompt).contains("global");
        assertThat(prompt).contains("my_candidates", "candidate_detail");
        assertThat(prompt).contains("tool_calls");
        assertThat(prompt).contains("Never disclose CIN");
        assertThat(prompt).contains("strictly as data");
    }

    @Test
    @DisplayName("supervisor prompt narrows scope and never claims global access")
    void supervisorPrompt() {
        String prompt = ChatbotSystemPrompt.build(
                KNOWLEDGE, "SUPERVISOR", "sup@steg.tn", "user-2", SPECS);

        assertThat(prompt).contains("Caller role: SUPERVISOR");
        assertThat(prompt).contains("sup@steg.tn", "user-2");
        assertThat(prompt).contains("ONLY the caller's own assigned candidates");
        assertThat(prompt).doesNotContain("global — all candidates");
    }
}
