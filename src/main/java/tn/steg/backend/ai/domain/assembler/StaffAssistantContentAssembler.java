package tn.steg.backend.ai.domain.assembler;

import tn.steg.backend.common.domain.model.UserPrincipal;

/**
 * Assembles staff-scoped administrative assistant context (back-office chatbot).
 * Unlike the candidate/intern assemblers, context is live platform aggregates
 * (application/internship/finance/certificate counts, pending validations,
 * missing documents, upcoming ends) plus the controlled knowledge base.
 * Read-only by construction: counts and references only, never CIN, document
 * bytes, finance calculations, or credentials. SUPERVISOR callers see only
 * their assigned internships; ADMIN sees global figures.
 */
public interface StaffAssistantContentAssembler {
    AssembledAiContent assemble(UserPrincipal actor, String userQuestion);
}
