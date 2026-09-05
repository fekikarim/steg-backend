package tn.steg.backend.ai.domain.assembler;

import java.util.UUID;

public interface AiContentAssembler<T> {
    AssembledAiContent assemble(UUID targetEntityId, T context);
}
