package tn.steg.backend.companion.application.dto;

/**
 * T09/B6 request of the text path (AI-3, ST-JRN-05): the student describes his
 * internship in free text, bounded like an uploaded specification, feeding the
 * exact same scope/schema/retry/audit pipeline as the tasks path — the text is
 * DATA, never instructions (BR-49). Length validation stays server-side
 * ({@code JournalDocumentService}) so the mocked payload cannot bypass it.
 */
public record JournalGenerationRequest(String text) {
}
