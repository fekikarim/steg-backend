package tn.steg.backend.ai.domain.chatbot;

/**
 * Outcome of one chatbot live-data tool execution.
 *
 * <p>Tools are read-only and scope-enforced: {@code data} carries only
 * whitelisted operational fields (references, names, statuses, dates,
 * counts) — never CIN, credentials, tokens, contact details or secrets.
 * Out-of-scope or missing rows yield a refusal with {@code success=false}
 * and no existence leak (same message as "not found").
 */
public record ChatbotToolResult(
        boolean success,
        String data,
        String error
) {
    public static ChatbotToolResult ok(String data) {
        return new ChatbotToolResult(true, data == null ? "" : data, null);
    }

    public static ChatbotToolResult refused(String reason) {
        return new ChatbotToolResult(false, "", reason);
    }
}
