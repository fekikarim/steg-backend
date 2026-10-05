package tn.steg.backend.application.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.application.application.dto.ApplicationQueueResponse;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipType;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Staff application queue (AGENTS.md §5.2 and §6.2).
 *
 * <p>Pagination, search, filters and sort are executed server-side; the client
 * only renders one page. Scope is resolved here, never trusted from the client:
 * an Admin sees every application, a Supervisor sees the applications of the
 * candidates he supervises (out-of-scope rows simply do not exist for him).
 */
@Service
@RequiredArgsConstructor
public class ApplicationQueueService {

    /** Sort keys the API accepts; anything else falls back to createdAt DESC. */
    private static final Set<String> SORTABLE = Set.of(
            "reference",
            "status",
            "submissionDate",
            "calculatedType",
            "createdAt",
            "updatedAt",
            "candidate.firstName",
            "candidate.lastName",
            "candidate.email",
            "candidate.university.name");

    private static final int MAX_PAGE_SIZE = 100;

    private final InternshipApplicationRepository applicationRepository;
    private final SupervisionScopeService supervisionScopeService;

    @Transactional(readOnly = true)
    public Page<ApplicationQueueResponse> search(
            UserPrincipal actor,
            ApplicationStatus status,
            InternshipType type,
            String universityName,
            LocalDate submissionFrom,
            LocalDate submissionTo,
            String search,
            Pageable pageable) {

        boolean scoped = !supervisionScopeService.hasGlobalAccess(actor);
        List<UUID> candidateIds = List.of();
        if (scoped) {
            candidateIds = supervisionScopeService.assignedInternships(actor).stream()
                    .map(Internship::getCandidate)
                    .filter(Objects::nonNull)
                    .map(Candidate::getId)
                    .distinct()
                    .toList();
            if (candidateIds.isEmpty()) {
                return Page.empty(sanitize(pageable));
            }
        }

        String pattern = search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        // Stored lower-case: the query compares LOWER(candidate.university.name)
        // against it, and passing a lower-cased non-null value also keeps the
        // parameter typed as text in Postgres (a NULL inside LOWER() is not).
        String university = universityName == null || universityName.isBlank()
                ? null
                : universityName.trim().toLowerCase(Locale.ROOT);

        return applicationRepository.searchStaffApplications(
                        scoped,
                        candidateIds,
                        status,
                        type,
                        university,
                        submissionFrom,
                        submissionTo,
                        pattern,
                        sanitize(pageable))
                .map(ApplicationQueueResponse::from);
    }

    /** Clamps the page size and drops sort properties the API does not expose. */
    private static Pageable sanitize(Pageable pageable) {
        Pageable source = pageable != null
                ? pageable
                : PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        int size = Math.min(Math.max(source.getPageSize(), 1), MAX_PAGE_SIZE);
        Sort safeSort = Sort.by(source.getSort().stream()
                .filter(order -> SORTABLE.contains(order.getProperty()))
                .toList());
        if (safeSort.isUnsorted()) {
            safeSort = Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return PageRequest.of(source.getPageNumber(), size, safeSort);
    }
}
