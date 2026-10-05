package tn.steg.backend.internship.application;

import org.junit.jupiter.api.Test;
import tn.steg.backend.iam.domain.model.Role;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.model.UserStatus;
import tn.steg.backend.iam.domain.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupervisorDirectoryServiceTest {

    @Test
    void listsEnabledAdminAndSupervisorUsersWithoutDuplicates() {
        User admin = user("admin@example.test", "ADMIN");
        User supervisor = user("supervisor@example.test", "SUPERVISOR");
        User disabled = user("disabled@example.test", "SUPERVISOR");
        disabled.setEnabled(false);

        UserRepository repository = mock(UserRepository.class);
        when(repository.findDistinctByAssignedRoles_Code("ADMIN")).thenReturn(List.of(admin));
        when(repository.findDistinctByAssignedRoles_Code("SUPERVISOR"))
                .thenReturn(List.of(supervisor, disabled, admin));

        List<tn.steg.backend.internship.application.dto.SupervisorOption> options =
                new SupervisorDirectoryService(repository).listSelectableSupervisors();

        assertEquals(2, options.size());
        assertTrue(options.stream().anyMatch(option -> option.email().equals("admin@example.test")));
        assertTrue(options.stream().anyMatch(option -> option.email().equals("supervisor@example.test")));
    }

    private User user(String email, String roleCode) {
        User user = new User(email, "hash", UserStatus.ACTIVE);
        user.setId(UUID.randomUUID());
        user.getAssignedRoles().add(new Role(roleCode, roleCode, roleCode));
        return user;
    }
}