package tn.steg.backend.workflow.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.RoleRepository;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipRequirement;
import tn.steg.backend.internship.domain.model.InternshipType;
import tn.steg.backend.notification.application.port.out.EmailSender;
import tn.steg.backend.candidate.domain.model.University;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobileAccountProvisioningServiceTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private PasswordEncoder passwordEncoder;
    private EmailSender emailSender;
    private MobileAccountProvisioningService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailSender = mock(EmailSender.class);
        service = new MobileAccountProvisioningService(
                userRepository, roleRepository, passwordEncoder, emailSender);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Role intern = new Role("INTERN", "Intern", "Mobile intern");
        when(roleRepository.findByCode("INTERN")).thenReturn(Optional.of(intern));
    }

    @Test
    void provisionsObligatoryPfeWithStrongOneTimePassword() {
        Internship internship = internship(InternshipType.PFE, InternshipRequirement.OBLIGATOIRE);

        MobileAccountProvisioningService.ProvisioningResult result =
                service.provisionIfRequired(internship).orElseThrow();

        assertEquals("student@example.test", result.email());
        assertNotNull(result.temporaryPassword());
        assertEquals(20, result.temporaryPassword().length());
        assertFalse(result.alreadyProvisioned());
        assertTrue(result.emailSent());
        assertTrue(internship.getCandidate().getUser().getMustChangePassword());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void doesNotProvisionOptionalPerfectionnement() {
        Internship internship = internship(InternshipType.PERFECTIONNEMENT, InternshipRequirement.OPTIONAL);

        assertTrue(service.provisionIfRequired(internship).isEmpty());
    }

    @Test
    void emailFailureDoesNotAbortProvisioning() {
        doThrow(new RuntimeException("mail unavailable"))
                .when(emailSender).send(any(), any(), any(), any());
        Internship internship = internship(InternshipType.PERFECTIONNEMENT, InternshipRequirement.OBLIGATOIRE);

        MobileAccountProvisioningService.ProvisioningResult result =
                service.provisionIfRequired(internship).orElseThrow();

        assertFalse(result.emailSent());
        verify(userRepository).save(any(User.class));
    }

    private Internship internship(InternshipType type, InternshipRequirement requirement) {
        University university = mock(University.class);
        Candidate candidate = new Candidate("Student", "Example", "student@example.test", "hash", university);
        return new Internship("INT-1", candidate, LocalDate.now(), LocalDate.now().plusMonths(1), type, requirement);
    }
}