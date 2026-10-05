package tn.steg.backend.ai.infrastructure.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.steg.backend.ai.domain.chatbot.ChatbotKnowledge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S10b — the curated knowledge file ships on the classpath and covers the
 * AGENTS.md essentials (roles, workflows, modules, rules, STEG context).
 */
@DisplayName("S10b — AssistantKnowledgeLoader")
class AssistantKnowledgeLoaderTest {

    @Test
    @DisplayName("knowledge file loads with all required sections")
    void knowledgeLoads() {
        AssistantKnowledgeLoader loader = new AssistantKnowledgeLoader();
        ChatbotKnowledge knowledge = loader.knowledge();

        assertThat(knowledge.fullText()).isNotBlank();
        assertThat(knowledge.sections()).extracting(ChatbotKnowledge.Section::title)
                .contains("Roles", "Capabilities matrix", "Application workflow",
                        "Credentials rule", "Tasks", "Internship validation",
                        "Certificates", "Notifications", "Audit",
                        "AI features and degraded modes", "Security rules");
    }

    @Test
    @DisplayName("knowledge carries the approval, credentials and scope rules")
    void knowledgeCoversKeyRules() {
        String text = new AssistantKnowledgeLoader().knowledge().fullText();
        assertThat(text).contains("ADMIN", "SUPERVISOR");
        assertThat(text).contains("409");
        assertThat(text).contains("404");
        assertThat(text).contains("Africa/Tunis");
    }

    @Test
    @DisplayName("knowledge contains no secret material")
    void knowledgeHasNoSecrets() {
        String text = new AssistantKnowledgeLoader().knowledge().fullText();
        assertThat(text).doesNotContain("GEMINI_API_KEY", "RESEND_API_KEY",
                "X-Service-Token", "re_", "sk-", "Bearer ");
    }
}
