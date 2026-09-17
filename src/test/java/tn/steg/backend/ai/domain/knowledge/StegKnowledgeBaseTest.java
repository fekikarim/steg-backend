package tn.steg.backend.ai.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("STEG controlled knowledge base")
class StegKnowledgeBaseTest {

    private final StegKnowledgeBase kb = new StegKnowledgeBase();

    @Test
    @DisplayName("retrieves internship-type sheet for a PFE vs Observation question")
    void retrievesRelevantSheet() {
        var found = kb.retrieve("Quelle est la différence entre Observation et PFE ?");
        assertThat(found).anyMatch(e -> e.id().equals("internship-types"));
    }

    @Test
    @DisplayName("out-of-scope questions match nothing so the model declines")
    void outOfScopeMatchesNothing() {
        var found = kb.retrieve("Quel est le score du match d'hier soir ?");
        assertThat(found).isEmpty();
        assertThat(StegKnowledgeBase.renderContext(found)).contains("décliner");
    }

    @Test
    @DisplayName("normalization is accent-insensitive")
    void accentInsensitive() {
        var found = kb.retrieve("DEMANDE DE STAGE documents requis");
        assertThat(found).anyMatch(e -> e.id().equals("required-documents"));
    }
}
