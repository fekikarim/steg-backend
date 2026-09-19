package tn.steg.backend.candidate.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.candidate.application.dto.IdentifierValidationRequest;
import tn.steg.backend.candidate.application.dto.IdentifierValidationResponse;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.candidate.domain.service.NationalIdHasher;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.common.domain.model.UserPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdentifierValidationService {

    private final CandidateRepository candidateRepository;
    private final InternshipApplicationRepository applicationRepository;

    @Transactional(readOnly = true)
    public IdentifierValidationResponse validate(IdentifierValidationRequest request) {
        List<String> duplicates = new ArrayList<>();

        // Resolve current user if authenticated — to avoid flagging own identifiers as duplicate
        UUID currentUserId = resolveCurrentUserId();

        // Normalize inputs
        String email = request.email() != null ? request.email().trim().toLowerCase() : null;
        String nationalId = request.nationalId() != null ? request.nationalId().trim() : null;
        String phone = request.phone() != null ? request.phone().trim() : null;
        String normalizedPhone = phone != null && !phone.isEmpty() ? normalizePhone(phone) : null;

        // Check CIN
        if (nationalId != null && !nationalId.isEmpty()) {
            try {
                String hash = NationalIdHasher.sha256Hex(nationalId);
                Optional<Candidate> candidateOpt = candidateRepository.findByNationalIdHash(hash);
                if (candidateOpt.isPresent()) {
                    Candidate c = candidateOpt.get();
                    if (!isOwnCandidate(c, currentUserId) && hasExistingApplication(c)) {
                        duplicates.add("nationalId");
                        log.info("Identifier validation: nationalId hash {} already has application (candidateId={})", hash.substring(0, 8) + "...", c.getId());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to hash nationalId for validation: {}", e.getMessage());
            }
        }

        // Check email (case-insensitive) — may have multiple candidates with same email (not unique at candidate level)
        if (email != null && !email.isEmpty()) {
            List<Candidate> candidates = candidateRepository.findByEmailIgnoreCase(email);
            if (candidates.isEmpty()) {
                candidateRepository.findByEmail(email).ifPresent(candidates::add);
            }
            for (Candidate c : candidates) {
                if (!isOwnCandidate(c, currentUserId) && hasExistingApplication(c)) {
                    duplicates.add("email");
                    log.info("Identifier validation: email {} already has application (candidateId={})", maskEmail(email), c.getId());
                    break;
                }
            }
        }

        // Check phone (normalized) — phone is not unique, may have multiple matches
        if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
            List<Candidate> candidates = new ArrayList<>();
            candidates.addAll(candidateRepository.findByPhone(phone));
            if (!normalizedPhone.equals(phone)) {
                candidates.addAll(candidateRepository.findByPhone(normalizedPhone));
            }
            // Fallback scan for formatting differences (e.g. "+216 71 123 456" vs "+21671123456")
            if (candidates.isEmpty()) {
                findCandidateByNormalizedPhone(normalizedPhone).ifPresent(candidates::add);
            } else {
                // Also check normalized scan to catch additional formatting variants not covered by exact match
                findCandidateByNormalizedPhone(normalizedPhone).ifPresent(c -> {
                    if (candidates.stream().noneMatch(existing -> existing.getId().equals(c.getId()))) {
                        candidates.add(c);
                    }
                });
            }
            for (Candidate c : candidates) {
                if (!isOwnCandidate(c, currentUserId) && hasExistingApplication(c)) {
                    duplicates.add("phone");
                    log.info("Identifier validation: phone {} already has application (candidateId={})", maskPhone(phone), c.getId());
                    break;
                }
            }
        }

        boolean valid = duplicates.isEmpty();
        String message;
        if (valid) {
            message = "All identifiers are available.";
        } else {
            // Generic message — do not reveal which candidate, just that an application exists
            // Frontend will show a professional dialog with guidance
            if (duplicates.size() == 1) {
                String field = duplicates.get(0);
                String fieldLabel = switch (field) {
                    case "email" -> "email address";
                    case "nationalId" -> "ID card number (CIN)";
                    case "phone" -> "phone number";
                    default -> field;
                };
                message = "An application already exists for the provided " + fieldLabel + ". Please use your existing account or tracking reference instead of submitting a new application.";
            } else {
                message = "An application already exists for the provided identifiers (" + String.join(", ", duplicates) + "). Please use your existing account or tracking reference.";
            }
        }

        return new IdentifierValidationResponse(valid, duplicates, message);
    }

    private boolean hasExistingApplication(Candidate candidate) {
        try {
            return applicationRepository.existsByCandidateId(candidate.getId());
        } catch (Exception e) {
            log.warn("Failed to check existing application for candidate {}: {}", candidate.getId(), e.getMessage());
            return false;
        }
    }

    private boolean isOwnCandidate(Candidate candidate, UUID currentUserId) {
        if (currentUserId == null) return false;
        try {
            return candidate.getUser() != null && currentUserId.equals(candidate.getUser().getId());
        } catch (Exception e) {
            return false;
        }
    }

    private UUID resolveCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) return null;
            Object principal = auth.getPrincipal();
            if (principal instanceof UserPrincipal up) {
                return up.getId();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Optional<Candidate> findCandidateByNormalizedPhone(String normalizedInput) {
        try {
            List<Candidate> all = candidateRepository.findAll();
            for (Candidate c : all) {
                String storedPhone = c.getPhone();
                if (storedPhone == null || storedPhone.isBlank()) continue;
                String normalizedStored = normalizePhone(storedPhone);
                if (normalizedStored.equals(normalizedInput)) {
                    return Optional.of(c);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan candidates for phone validation: {}", e.getMessage());
        }
        return Optional.empty();
    }

    static String normalizePhone(String raw) {
        if (raw == null) return "";
        // Remove spaces, dashes, dots, parentheses
        String compact = raw.replaceAll("[\\s.\\-()]", "");
        // Handle +216 prefix variations: ensure consistent format
        // Keep as is after removing formatting; comparison will be on compact form
        return compact.toLowerCase();
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String domain = parts[1];
        String maskedLocal = local.length() <= 2 ? "***" : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return maskedLocal + "@" + domain;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        return "***" + phone.substring(phone.length() - 4);
    }
}
