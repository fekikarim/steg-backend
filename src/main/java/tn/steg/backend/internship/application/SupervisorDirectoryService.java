package tn.steg.backend.internship.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.application.dto.SupervisorOption;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SupervisorDirectoryService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SupervisorOption> listSelectableSupervisors() {
        Map<java.util.UUID, SupervisorOption> options = new LinkedHashMap<>();
        addRole(options, "ADMIN");
        addRole(options, "SUPERVISOR");
        return options.values().stream()
                .sorted(Comparator.comparing(SupervisorOption::email))
                .toList();
    }

    private void addRole(Map<java.util.UUID, SupervisorOption> options, String role) {
        for (User user : userRepository.findDistinctByAssignedRoles_Code(role)) {
            if (Boolean.TRUE.equals(user.getEnabled())) {
                options.putIfAbsent(user.getId(), SupervisorOption.from(user, role));
            }
        }
    }
}