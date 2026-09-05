package tn.steg.backend.ai.domain.client;

import java.util.List;

/**
 * Port abstraction for calling AI completion models.
 * Kept generic so any provider (Gemini, mock, etc.) can implement it.
 */
public interface AiCompletionClient {

    /**
     * Generate an advisory text completion given system instructions and prompt contents.
     *
     * @param systemInstruction system guidance framing the AI role (advisory only)
     * @param userPrompts list of user prompt segments or assembled content
     * @return completion result containing model response and token/model metadata
     */
    AiCompletionResult complete(String systemInstruction, List<String> userPrompts);

    /**
     * @return the provider identifier (e.g. "gemini")
     */
    String getProvider();

    /**
     * @return the model name used by this client
     */
    String getModel();
}
