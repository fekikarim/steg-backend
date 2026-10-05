package tn.steg.backend.ai.infrastructure.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tn.steg.backend.ai.domain.chatbot.ChatbotKnowledge;
import tn.steg.backend.ai.domain.chatbot.ChatbotKnowledgeSource;

import java.nio.charset.StandardCharsets;

/**
 * Loads the curated chatbot knowledge ({@code assistant-knowledge.md},
 * built from {@code todo/AGENTS.md}) from the classpath.
 *
 * <p>The file ships inside the jar (src/main/resources), so every deployment
 * carries the same reviewed knowledge. A blank file fails fast at startup —
 * silently running the chatbot without knowledge would be worse than not
 * starting it; callers degrade per-turn on AI failures, never on knowledge.
 */
@Slf4j
@Component
public class AssistantKnowledgeLoader implements ChatbotKnowledgeSource {

    private static final String RESOURCE = "assistant-knowledge.md";

    private final ChatbotKnowledge knowledge;

    public AssistantKnowledgeLoader() {
        this.knowledge = ChatbotKnowledge.parse(readResource());
        log.info("Assistant knowledge loaded: {} chars, {} sections",
                knowledge.fullText().length(), knowledge.sections().size());
        if (knowledge.fullText().isBlank() || knowledge.sections().isEmpty()) {
            throw new IllegalStateException(
                    "assistant-knowledge.md is empty: the chatbot must not run without curated knowledge");
        }
    }

    @Override
    public ChatbotKnowledge knowledge() {
        return knowledge;
    }

    static String readResource() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE);
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Cannot read assistant knowledge resource '" + RESOURCE + "'", ex);
        }
    }
}
