package tn.steg.backend.workflow.application;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.notification.application.port.out.EmailSender;

import java.security.SecureRandom;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MobileAccountProvisioningService {

    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public Optional<ProvisioningResult> provisionIfRequired(Internship internship) {
        if (!requiresMobileAccount(internship) || internship.getCandidate() == null) {
            return Optional.empty();
        }

        String email = internship.getCandidate().getEmail();
        User user = internship.getCandidate().getUser();
        if (user == null) {
            user = new User(email, "", UserStatus.ACTIVE);
        }

        boolean alreadyProvisioned = user.getAssignedRoles().stream()
                .anyMatch(role -> "INTERN".equalsIgnoreCase(role.getCode()));
        if (alreadyProvisioned) {
            return Optional.of(new ProvisioningResult(email, null, true, true));
        }

        String temporaryPassword = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        Optional<Role> internRole = roleRepository.findByCode("INTERN");
        if (internRole.isPresent()) {
            addRole(user, internRole.get());
        }
        user = userRepository.save(user);
        internship.getCandidate().setUser(user);

        boolean emailSent = false;
        try {
            emailSender.send(email,
                    "Votre accès STEG intern",
                    "Votre compte STEG intern est prêt. Email: " + email
                            + "\nMot de passe temporaire: " + temporaryPassword
                            + "\nChangez ce mot de passe lors de votre première connexion.",
                    "mobile-credentials-" + user.getId());
            emailSent = true;
        } catch (RuntimeException ignored) {
            // Approval remains committed; the caller exposes a resendable failure state.
        }

        return Optional.of(new ProvisioningResult(email, temporaryPassword, emailSent, false));
    }

    public boolean requiresMobileAccount(Internship internship) {
        return internship != null
                && internship.getRequirement() == InternshipRequirement.OBLIGATOIRE
                && (internship.getType() == InternshipType.PERFECTIONNEMENT
                || internship.getType() == InternshipType.PFE);
    }

    private void addRole(User user, Role role) {
        user.getAssignedRoles().add(role);
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
        for (int index = password.length() - 1; index > 0; index--) {
            int swapIndex = secureRandom.nextInt(index + 1);
            char current = password.charAt(index);
            password.setCharAt(index, password.charAt(swapIndex));
            password.setCharAt(swapIndex, current);
        }
        return password.toString();
    }

    private char randomCharacter(String alphabet) {
        return alphabet.charAt(secureRandom.nextInt(alphabet.length()));
    }

    public record ProvisioningResult(
            String email,
            String temporaryPassword,
            boolean emailSent,
            boolean alreadyProvisioned
    ) {
    }
}