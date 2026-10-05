package tn.steg.backend.iam.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.CreateInternAccountRequest;
import tn.steg.backend.iam.application.dto.InternAccountResponse;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.application.dto.UpdateInternAccountRequest;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.notification.application.port.out.EmailSender;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternAccountManagementService {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CandidateRepository candidateRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final AuditService auditService;
    private final tn.steg.backend.iam.domain.repository.RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Sort keys the paged account list accepts; anything else falls back to email ASC. */
    private static final Set<String> SORTABLE = Set.of("email", "status", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Server-side paged 'STEG intern' account list (AGENTS.md §5.4 + §5 list
     * rule): role/status filters, email-or-full-name search, whitelisted sort
     * and a clamped page size. One query returns the page; the row enrichment
     * (candidate + internship) follows the caller's page only.
     */
    @Transactional(readOnly = true)
    public Page<InternAccountResponse> searchManagedAccounts(
            String role, UserStatus status, String search, Pageable pageable) {
        String roleFilter = normalizeRole(role);
        String pattern = search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.findManagedAccounts(roleFilter, status, pattern, sanitize(pageable))
                .map(this::toResponse);
    }

    /** Clamps the page size and drops sort properties the API does not expose. */
    private static Pageable sanitize(Pageable pageable) {
        Pageable source = pageable != null
                ? pageable
                : PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "email"));
        int size = Math.min(Math.max(source.getPageSize(), 1), MAX_PAGE_SIZE);
        Sort safeSort = Sort.by(source.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        if (safeSort.isUnsorted()) {
            safeSort = Sort.by(Sort.Direction.ASC, "email");
        }
        return PageRequest.of(source.getPageNumber(), size, safeSort);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String code = role.trim().toUpperCase(Locale.ROOT);
        if (!"INTERN".equals(code) && !"SUPERVISOR".equals(code)) {
            throw new BusinessRuleException("INVALID_ROLE",
                    "Only INTERN or SUPERVISOR roles are permitted for mobile account management.");
        }
        return code;
    }

    @Transactional(readOnly = true)
    public List<InternAccountResponse> listAccounts(String roleFilter, UserStatus statusFilter, String search) {
        List<User> allUsers = userRepository.findAll();

        return allUsers.stream()
                .filter(u -> hasRelevantRole(u, roleFilter))
                .filter(u -> statusFilter == null || u.getStatus() == statusFilter)
                .map(this::toResponse)
                .filter(resp -> matchesSearch(resp, search))
                .sorted(Comparator.comparing(InternAccountResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InternAccountResponse getAccount(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        return toResponse(user);
    }

    @Transactional
    public ResetAccountPasswordResponse createAccount(CreateInternAccountRequest request, UserPrincipal actor) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("USER_ALREADY_EXISTS",
                    "A user account with email " + request.email() + " already exists.");
        }

        String roleCode = request.role().toUpperCase();
        if (!"INTERN".equals(roleCode) && !"SUPERVISOR".equals(roleCode)) {
            throw new BusinessRuleException("INVALID_ROLE",
                    "Only INTERN or SUPERVISOR roles are permitted for mobile account management.");
        }

        Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleCode));

        String temporaryPassword = generatePassword();
        User user = new User(request.email(), passwordEncoder.encode(temporaryPassword), UserStatus.ACTIVE);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.getAssignedRoles().add(role);

        user = userRepository.save(user);

        if (request.candidateId() != null) {
            Candidate candidate = candidateRepository.findById(request.candidateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + request.candidateId()));
            candidate.setUser(user);
            candidateRepository.save(candidate);
        }

        boolean emailSent = false;
        try {
            emailSender.send(request.email(),
                    "Vos identifiants STEG",
                    "Votre compte STEG (" + roleCode + ") a été créé.\n"
                            + "Email: " + request.email() + "\n"
                            + "Mot de passe temporaire: " + temporaryPassword + "\n"
                            + "Veuillez changer ce mot de passe dès votre première connexion.",
                    "account-credentials-" + user.getId());
            emailSent = true;
        } catch (Exception ex) {
            log.warn("Failed to send credentials email to {}: {}", request.email(), ex.getMessage());
        }

        auditService.log("ACCOUNT_CREATED", "User", user.getId(),
                null,
                Map.of("email", user.getEmail(), "role", roleCode, "mustChangePassword", true),
                actor != null ? actor.getId() : null, null);

        return new ResetAccountPasswordResponse(user.getEmail(), temporaryPassword, emailSent);
    }

    @Transactional
    public InternAccountResponse updateAccount(UUID id, UpdateInternAccountRequest request, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        UserStatus oldStatus = user.getStatus();
        Boolean oldEnabled = user.getEnabled();

        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        user = userRepository.save(user);

        auditService.log("ACCOUNT_UPDATED", "User", user.getId(),
                Map.of("status", oldStatus.name(), "enabled", oldEnabled),
                Map.of("status", user.getStatus().name(), "enabled", user.getEnabled()),
                actor != null ? actor.getId() : null, null);

        return toResponse(user);
    }

    @Transactional
    public void deleteAccount(UUID id, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        // Block deletion if supervisor has active candidate assignments
        boolean isSupervisor = user.getAssignedRoles().stream()
                .anyMatch(r -> "SUPERVISOR".equalsIgnoreCase(r.getCode()));
        if (isSupervisor) {
            var activeAssignments = assignmentRepository.findBySupervisorUserIdAndStatus(id, AssignmentStatus.ACTIVE);
            if (!activeAssignments.isEmpty()) {
                throw new ConflictException("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS",
                        "Cannot delete supervisor with active candidate assignments. Reassign candidates first.");
            }
        }

        // Unlink candidate if linked
        candidateRepository.findByUserId(id).ifPresent(candidate -> {
            candidate.setUser(null);
            candidateRepository.save(candidate);
        });

        // Session revocation: every refresh token of the deleted account dies
        // with it; the JWT filter refuses the (still-unexpired) access token.
        refreshTokenRepository.revokeAllForUser(id);

        userRepository.delete(user);

        auditService.log("ACCOUNT_DELETED", "User", id,
                Map.of("email", user.getEmail()), null,
                actor != null ? actor.getId() : null, null);
    }

    @Transactional
    public ResetAccountPasswordResponse resetPassword(UUID id, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        String temporaryPassword = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        // Credential version (V53): tokens issued before the reset are
        // refused by the JWT filter — the old access token stops working NOW,
        // not at expiry.
        user.setCredentialsUpdatedAt(java.time.Instant.now());
        user = userRepository.save(user);
        // Session revocation: the reset must end every existing session —
        // old refresh tokens are revoked and the old access token stops
        // working at once (the JWT filter re-checks the account state).
        refreshTokenRepository.revokeAllForUser(user.getId());

        boolean emailSent = false;
        try {
            emailSender.send(user.getEmail(),
                    "Réinitialisation de votre mot de passe STEG",
                    "Votre mot de passe a été réinitialisé par un administrateur.\n"
                            + "Nouveau mot de passe temporaire: " + temporaryPassword + "\n"
                            + "Veuillez changer ce mot de passe dès votre première connexion.",
                    "account-reset-" + user.getId());
            emailSent = true;
        } catch (Exception ex) {
            log.warn("Failed to send reset password email to userId={}: {}", user.getId(), ex.getMessage());
        }

        auditService.log("ACCOUNT_PASSWORD_RESET", "User", user.getId(),
                null,
                Map.of("email", user.getEmail(), "mustChangePassword", true),
                actor != null ? actor.getId() : null, null);

        return new ResetAccountPasswordResponse(user.getEmail(), temporaryPassword, emailSent);
    }

    @Transactional
    public InternAccountResponse updateStatus(UUID id, UserStatus status, Boolean enabled, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        UserStatus oldStatus = user.getStatus();
        Boolean oldEnabled = user.getEnabled();

        if (status != null) {
            user.setStatus(status);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }

        user = userRepository.save(user);

        // Session revocation: the moment an account loses its login
        // capability, every refresh token is revoked and the old access
        // token stops working (the JWT filter re-checks the account state).
        boolean loginCapable = Boolean.TRUE.equals(user.getEnabled()) && user.getStatus() == UserStatus.ACTIVE;
        if (!loginCapable) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        auditService.log("ACCOUNT_STATUS_UPDATED", "User", user.getId(),
                Map.of("status", oldStatus.name(), "enabled", oldEnabled),
                Map.of("status", user.getStatus().name(), "enabled", user.getEnabled()),
                actor != null ? actor.getId() : null, null);

        return toResponse(user);
    }

    private boolean hasRelevantRole(User user, String roleFilter) {
        if (roleFilter != null && !roleFilter.isBlank()) {
            return user.getAssignedRoles().stream()
                    .anyMatch(r -> r.getCode().equalsIgnoreCase(roleFilter.trim()));
        }
        return user.getAssignedRoles().stream()
                .anyMatch(r -> "INTERN".equalsIgnoreCase(r.getCode()) || "SUPERVISOR".equalsIgnoreCase(r.getCode()));
    }

    private boolean matchesSearch(InternAccountResponse resp, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String q = search.trim().toLowerCase();
        return (resp.email() != null && resp.email().toLowerCase().contains(q))
                || (resp.fullName() != null && resp.fullName().toLowerCase().contains(q));
    }

    private InternAccountResponse toResponse(User user) {
        Optional<Candidate> candidate = candidateRepository.findByUserId(user.getId());
        String fullName = candidate
                .map(c -> (c.getFirstName() + " " + c.getLastName()).strip())
                .orElse(null);
        UUID candidateId = candidate.map(Candidate::getId).orElse(null);

        UUID internshipId = null;
        if (candidateId != null) {
            List<Internship> internships = internshipRepository.findByCandidateId(candidateId);
            if (!internships.isEmpty()) {
                internshipId = internships.get(0).getId();
            }
        }

        return InternAccountResponse.from(user, fullName, candidateId, internshipId);
    }

    private String generatePassword() {
        StringBuilder password = new StringBuilder(20);
        password.append(randomCharacter(UPPER));
        password.append(randomCharacter(LOWER));
        password.append(randomCharacter(DIGITS));
        password.append(randomCharacter(SYMBOLS));
        String alphabet = UPPER + LOWER + DIGITS + SYMBOLS;
        while (password.length() < 20) {
            password.append(randomCharacter(alphabet));
        }
        for (int i = password.length() - 1; i > 0; i--) {
            int swapIndex = secureRandom.nextInt(i + 1);
            char c = password.charAt(i);
            password.setCharAt(i, password.charAt(swapIndex));
            password.setCharAt(swapIndex, c);
        }
        return password.toString();
    }

    private char randomCharacter(String alphabet) {
        return alphabet.charAt(secureRandom.nextInt(alphabet.length()));
    }
}
