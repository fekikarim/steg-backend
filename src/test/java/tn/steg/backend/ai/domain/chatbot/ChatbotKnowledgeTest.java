package tn.steg.backend.ai.domain.chatbot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S10b — knowledge parsing and keyword lookup (pure unit test).
 */
@DisplayName("S10b — ChatbotKnowledge")
class ChatbotKnowledgeTest {

    private static final String DOC = """
            # Title (ignored, no ## prefix on first line matters not)
            ## Roles
            Admin and Supervisor rules.
            ## Approval flow
            Approve needs no reason; deny needs a reason.
            """;

    @Test
    @DisplayName("splits sections on ## headers")
    void splitsSections() {
        ChatbotKnowledge knowledge = ChatbotKnowledge.parse(DOC);
        assertThat(knowledge.sections()).extracting(ChatbotKnowledge.Section::title)
                .containsExactly("Roles", "Approval flow");
    }

    @Test
    @DisplayName("lookup scores by keyword hits, accent-insensitive")
    void lookupScores() {
        ChatbotKnowledge knowledge = ChatbotKnowledge.parse(DOC);
        List<ChatbotKnowledge.Section> found = knowledge.lookup("approve reason deny motif", 2);
        assertThat(found).extracting(ChatbotKnowledge.Section::title)
                .contains("Approval flow");
    }

    @Test
    @DisplayName("empty document parses to no sections")
    void emptyDocument() {
        assertThat(ChatbotKnowledge.parse("").sections()).isEmpty();
        assertThat(ChatbotKnowledge.parse(null).sections()).isEmpty();
    }
}
