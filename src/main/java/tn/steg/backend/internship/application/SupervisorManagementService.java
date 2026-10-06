package tn.steg.backend.internship.application;

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
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ConflictException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.iam.application.dto.ResetAccountPasswordResponse;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.dto.AssignCandidateRequest;
import tn.steg.backend.internship.application.dto.CreateSupervisorRequest;
import tn.steg.backend.internship.application.dto.ReassignSupervisorRequest;
import tn.steg.backend.internship.application.dto.SupervisorResponse;
import tn.steg.backend.internship.application.dto.UpdateSupervisorRequest;
import tn.steg.backend.internship.domain.model.AssignmentStatus;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipAssignment;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipAssignmentRepository;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.notification.application.NotificationService;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.notification.domain.model.NotificationPriority;
import tn.steg.backend.organization.domain.model.Department;
import tn.steg.backend.organization.domain.repository.DepartmentRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
public class SupervisorManagementService {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+";

    /** Sort keys the supervisor list accepts; anything else falls back to email ASC. */
    private static final Set<String> SORTABLE = Set.of("email", "status", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final InternshipRepository internshipRepository;
    private final InternshipAssignmentRepository assignmentRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final tn.steg.backend.iam.domain.repository.RefreshTokenRepository refreshTokenRepository;
    /**
     * T07/BR-38: the PRIVATE Intern↔Supervisor thread must follow the
     * assignment on every path that (re)binds a supervisor. Best-effort like
     * {@code InternshipService.assign()}: a messaging failure never rolls
     * back the assignment itself. No cycle: MessagingService depends on
     * repositories only, never on this service.
     */
    private final MessagingService messagingService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public Page<SupervisorResponse> searchSupervisors(String search, UserStatus status, Pageable pageable) {
        String pattern = search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.findSupervisorUsers(pattern, status, sanitize(pageable))
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

    @Transactional(readOnly = true)
    public SupervisorResponse getSupervisor(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + id));
        return toResponse(user);
    }

    @Transactional
    public ResetAccountPasswordResponse createSupervisor(CreateSupervisorRequest request, UserPrincipal actor) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessRuleException("USER_ALREADY_EXISTS",
                    "A user account with email " + request.email() + " already exists.");
        }

        Role role = roleRepository.findByCode("SUPERVISOR")
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: SUPERVISOR"));

        String temporaryPassword = generatePassword();
        User user = new User(request.email(), passwordEncoder.encode(temporaryPassword), UserStatus.ACTIVE);
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.getAssignedRoles().add(role);

        user = userRepository.save(user);

        boolean emailSent = false;
        try {
            emailSender.send(request.email(),
                    "Vos identifiants STEG Superviseur",
                    "Votre compte STEG Superviseur a été créé.\n"
                            + "Email: " + request.email() + "\n"
                            + "Mot de passe temporaire: " + temporaryPassword + "\n"
                            + "Veuillez changer ce mot de passe dès votre première connexion.",
                    "supervisor-credentials-" + user.getId());
            emailSent = true;
        } catch (Exception ex) {
            log.warn("Failed to send credentials email to supervisor {}: {}", request.email(), ex.getMessage());
        }

        auditService.log("SUPERVISOR_CREATED", "User", user.getId(),
                null,
                Map.of("email", user.getEmail(), "role", "SUPERVISOR", "mustChangePassword", true),
                actor != null ? actor.getId() : null, null);

        return new ResetAccountPasswordResponse(user.getEmail(), temporaryPassword, emailSent);
    }

    @Transactional
    public SupervisorResponse updateSupervisor(UUID id, UpdateSupervisorRequest request, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + id));

        UserStatus oldStatus = user.getStatus();
        Boolean oldEnabled = user.getEnabled();

        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }

        user = userRepository.save(user);

        // Session revocation (Task 3): a supervisor who loses login capability
        // loses every session at once.
        boolean loginCapable = Boolean.TRUE.equals(user.getEnabled()) && user.getStatus() == UserStatus.ACTIVE;
        if (!loginCapable) {
            refreshTokenRepository.revokeAllForUser(user.getId());
        }

        auditService.log("SUPERVISOR_UPDATED", "User", user.getId(),
                Map.of("status", oldStatus.name(), "enabled", oldEnabled),
                Map.of("status", user.getStatus().name(), "enabled", user.getEnabled()),
                actor != null ? actor.getId() : null, null);

        return toResponse(user);
    }

    @Transactional
    public void deleteSupervisor(UUID id, UserPrincipal actor) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + id));

        var activeAssignments = assignmentRepository.findBySupervisorUserIdAndStatus(id, AssignmentStatus.ACTIVE);
        if (!activeAssignments.isEmpty()) {
            throw new ConflictException("SUPERVISOR_HAS_ACTIVE_ASSIGNMENTS",
                    "Cannot delete supervisor with active candidate assignments. Reassign candidates first.");
        }

        // Session revocation: the deleted supervisor's refresh tokens die
        // with the account; the JWT filter refuses the old access token.
        refreshTokenRepository.revokeAllForUser(id);

        userRepository.delete(user);

        auditService.log("SUPERVISOR_DELETED", "User", id,
                Map.of("email", user.getEmail()), null,
                actor != null ? actor.getId() : null, null);
    }

    @Transactional
    public void assignCandidate(UUID supervisorUserId, AssignCandidateRequest request, UserPrincipal actor) {
        User supervisor = userRepository.findById(supervisorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Supervisor not found: " + supervisorUserId));

        boolean validRole = supervisor.getAssignedRoles().stream()
                .anyMatch(r -> "SUPERVISOR".equalsIgnoreCase(r.getCode()) || "ADMIN".equalsIgnoreCase(r.getCode()));
        if (!validRole) {
            throw new BusinessRuleException("INVALID_SUPERVISOR_ROLE", "Selected user is not an Admin or Supervisor.");
        }

        Internship internship = internshipRepository.findById(request.internshipId())
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + request.internshipId()));

        // §5.6: only an APPROVED candidate may be assigned (manual internships
        // without a linked application stay assignable), and never from a
        // cancelled/archived internship.
        if (internship.getApplication() != null
                && internship.getApplication().getStatus() != ApplicationStatus.APPROVED) {
            throw new ConflictException("CANDIDATE_NOT_APPROVED",
                    "Only an approved candidate can be assigned to a supervisor "
                            + "(application is " + internship.getApplication().getStatus() + ").");
        }
        if (internship.getStatus() == InternshipStatus.CANCELLED
                || internship.getStatus() == InternshipStatus.ARCHIVED) {
            throw new ConflictException("INTERNSHIP_NOT_ACTIVE",
                    "This internship is " + internship.getStatus() + " and cannot be assigned.");
        }

        Department department = null;
        if (request.departmentId() != null) {
            department = departmentRepository.findById(request.departmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + request.departmentId()));
        }

        // Close any existing active assignment
        Optional<InternshipAssignment> existingActive =
                assignmentRepository.findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);
        if (existingActive.isPresent()) {
            InternshipAssignment oldAssignment = existingActive.get();
            oldAssignment.setStatus(AssignmentStatus.REASSIGNED);
            oldAssignment.setEndedAt(Instant.now());
            // Flush the closure BEFORE inserting the new ACTIVE row: Hibernate
            // orders INSERTs before UPDATEs on flush, and the database keeps a
            // partial unique index of one ACTIVE assignment per internship.
            assignmentRepository.saveAndFlush(oldAssignment);
            if (department == null) {
                department = oldAssignment.getDestination();
            }
        }

        if (department == null) {
            department = departmentRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("No department available for assignment."));
        }

        User actorUser = actor != null ? userRepository.findById(actor.getId()).orElse(null) : null;

        InternshipAssignment assignment = new InternshipAssignment();
        assignment.setInternship(internship);
        assignment.setDestination(department);
        assignment.setSupervisorUser(supervisor);
        assignment.setAssignedByUser(actorUser != null ? actorUser : supervisor);
        assignment.setAssignedAt(LocalDate.now());
        assignment.setStartDate(internship.getStartDate());
        assignment.setEndDate(internship.getEndDate());
        assignment.setStatus(AssignmentStatus.ACTIVE);
        assignment.setAssignmentReason("Assigned via supervisor management");

        assignmentRepository.save(assignment);

        internship.setSupervisorUser(supervisor);
        internshipRepository.save(internship);

        rebindPrivateThreadBestEffort(internship, supervisor);

        auditService.log("CANDIDATE_ASSIGNED", "Internship", internship.getId(),
                null,
                Map.of("supervisorUserId", supervisor.getId(), "assignmentId", assignment.getId() != null ? assignment.getId() : UUID.randomUUID()),
                actor != null ? actor.getId() : null, null);

        notificationService.dispatch("Nouveau stagiaire assigné",
                "Un stagiaire vous a été assigné pour le stage " + internship.getReference(),
                NotificationPriority.NORMAL, "Internship", internship.getId(),
                List.of(supervisor.getId()), actor != null ? actor.getId() : null);
    }

    @Transactional
    public void reassignSupervisor(UUID internshipId, ReassignSupervisorRequest request, UserPrincipal actor) {
        Internship internship = internshipRepository.findById(internshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Internship not found: " + internshipId));

        User oldSupervisor = internship.getSupervisorUser();

        User newSupervisor = userRepository.findById(request.newSupervisorUserId())
                .orElseThrow(() -> new ResourceNotFoundException("New supervisor not found: " + request.newSupervisorUserId()));

        boolean validRole = newSupervisor.getAssignedRoles().stream()
                .anyMatch(r -> "SUPERVISOR".equalsIgnoreCase(r.getCode()) || "ADMIN".equalsIgnoreCase(r.getCode()));
        if (!validRole) {
            throw new BusinessRuleException("INVALID_SUPERVISOR_ROLE", "Selected new user is not an Admin or Supervisor.");
        }

        Department destination = null;
        Optional<InternshipAssignment> existingActive =
                assignmentRepository.findByInternshipIdAndStatus(internship.getId(), AssignmentStatus.ACTIVE);
        if (existingActive.isPresent()) {
            InternshipAssignment oldAssignment = existingActive.get();
            oldAssignment.setStatus(AssignmentStatus.REASSIGNED);
            oldAssignment.setEndedAt(Instant.now());
            // See assignCandidate: the closure must hit the database before the
            // new ACTIVE assignment is inserted (one ACTIVE per internship).
            assignmentRepository.saveAndFlush(oldAssignment);
            destination = oldAssignment.getDestination();
        }

        if (destination == null) {
            destination = departmentRepository.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("No department available for assignment."));
        }

        User actorUser = actor != null ? userRepository.findById(actor.getId()).orElse(null) : null;

        InternshipAssignment newAssignment = new InternshipAssignment();
        newAssignment.setInternship(internship);
        newAssignment.setDestination(destination);
        newAssignment.setSupervisorUser(newSupervisor);
        newAssignment.setAssignedByUser(actorUser != null ? actorUser : newSupervisor);
        newAssignment.setAssignedAt(LocalDate.now());
        newAssignment.setStartDate(internship.getStartDate());
        newAssignment.setEndDate(internship.getEndDate());
        newAssignment.setStatus(AssignmentStatus.ACTIVE);
        newAssignment.setAssignmentReason(request.reason() != null ? request.reason() : "Reassigned via supervisor management");

        assignmentRepository.save(newAssignment);

        internship.setSupervisorUser(newSupervisor);
        internshipRepository.save(internship);

        rebindPrivateThreadBestEffort(internship, newSupervisor);

        auditService.log("SUPERVISOR_REASSIGNED", "Internship", internship.getId(),
                Map.of("oldSupervisorUserId", oldSupervisor != null ? oldSupervisor.getId() : "NONE"),
                Map.of("newSupervisorUserId", newSupervisor.getId(), "reason", request.reason() != null ? request.reason() : ""),
                actor != null ? actor.getId() : null, null);

        List<UUID> recipientIds = new ArrayList<>();
        if (oldSupervisor != null) {
            recipientIds.add(oldSupervisor.getId());
        }
        recipientIds.add(newSupervisor.getId());

        notificationService.dispatch("Réaffectation de superviseur",
                "Le stage " + internship.getReference() + " a été réaffecté au superviseur " + newSupervisor.getEmail(),
                NotificationPriority.NORMAL, "Internship", internship.getId(),
                recipientIds, actor != null ? actor.getId() : null);
    }

    /**
     * T07/BR-38: rebinds the PRIVATE Intern↔Supervisor thread to exactly the
     * current intern + current supervisor (new supervisor gains access and
     * history, stale supervisors lose access). Best-effort: a messaging
     * failure is logged and never rolls back the assignment itself — same
     * posture as {@code InternshipService.assign()}.
     */
    private void rebindPrivateThreadBestEffort(Internship internship, User supervisor) {
        try {
            if (internship.getCandidate() != null
                    && internship.getCandidate().getUser() != null
                    && supervisor != null) {
                messagingService.ensurePrivateThread(
                        internship,
                        internship.getCandidate().getUser(),
                        supervisor);
            } else {
                log.warn("Skipping private thread rebind for internship {}: missing intern/supervisor user link",
                        internship.getReference());
            }
        } catch (Exception e) {
            log.error("Failed to rebind private thread for internship {}: {}",
                    internship.getReference(), e.getMessage());
        }
    }

    private SupervisorResponse toResponse(User user) {
        int activeCount = assignmentRepository
                .findBySupervisorUserIdAndStatus(user.getId(), AssignmentStatus.ACTIVE)
                .size();
        String role = user.getAssignedRoles().stream()
                .map(Role::getCode)
                .filter(r -> "SUPERVISOR".equalsIgnoreCase(r) || "ADMIN".equalsIgnoreCase(r))
                .findFirst()
                .orElse("SUPERVISOR");
        return SupervisorResponse.from(user, role, activeCount);
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
