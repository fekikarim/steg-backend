package tn.steg.backend.ai.domain.chatbot;

import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.Map;
import java.util.Set;

/**
 * Port for executing chatbot live-data tools under the CALLER's permissions.
 *
 * <p>Implementations live OUTSIDE {@code ai.*} (AiIsolation forbids the AI
 * layer from calling application services): the runner resolves scope through
 * {@code SupervisionScopeService} and reads through domain repository ports.
 * The AI layer only sees tool names, arg schemas and capped text results —
 * never SQL, never the database.
 */
public interface ChatbotToolRunner {

    /** Names of the available read-only tools (stable contract for prompts). */
    Set<String> toolNames();

    /** JSON-schema-ish arg documentation per tool, injected into the prompt. */
    String toolSpec(String name);

    /**
     * Executes one tool call. Unknown tools and invalid args yield a refusal
     * result (fed back to the model), never an exception and never a side
     * effect — tools are strictly read-only.
     */
    ChatbotToolResult run(UserPrincipal actor, String name, Map<String, String> args);
}
