package tn.steg.backend.ai.domain.chatbot;

/**
 * Port exposing the curated assistant knowledge.
 *
 * <p>Implemented in {@code ai.infrastructure} (classpath resource
 * {@code assistant-knowledge.md}); consumed from {@code ai.application} and
 * from the scoped tool runner outside {@code ai.*} — always through this
 * domain port, never through infrastructure types.
 */
public interface ChatbotKnowledgeSource {

    /** Parsed curated knowledge (full text + sections). Never null. */
    ChatbotKnowledge knowledge();
}
