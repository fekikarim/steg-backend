package tn.steg.backend.finance.application.dto;

/**
 * Decision payload for approve/reject. A comment is optional on approval but
 * mandatory on rejection (enforced in the service, with a dedicated error).
 */
public record PaymentDecisionRequest(
        String comment
) {
}
