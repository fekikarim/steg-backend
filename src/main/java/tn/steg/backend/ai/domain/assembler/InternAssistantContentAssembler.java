package tn.steg.backend.ai.domain.assembler;

import java.util.UUID;

/**
 * Assembles intern/supervisor-scoped assistant context (mobile intern assistant).
 * Unlike the candidate assembler, context is the participant's actual internship
 * (type, period, journal/task counts) plus the controlled knowledge base.
 */
public interface InternAssistantContentAssembler {
    AssembledAiContent assemble(UUID userId, String userQuestion);
}
