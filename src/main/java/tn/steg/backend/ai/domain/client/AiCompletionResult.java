package tn.steg.backend.ai.domain.client;

/**
 * Result returned by an AI completion call.
 *
 * @param content generated text
 * @param modelName model used
 * @param provider provider name
 * @param success whether call succeeded
 * @param errorMessage error message if failed
 */
public record AiCompletionResult(
        String content,
        String modelName,
        String provider,
        boolean success,
        String errorMessage
) {
    public static AiCompletionResult success(String content, String modelName, String provider) {
        return new AiCompletionResult(content, modelName, provider, true, null);
    }

    public static AiCompletionResult failure(String errorMessage, String modelName, String provider) {
        return new AiCompletionResult(null, modelName, provider, false, errorMessage);
    }
}
