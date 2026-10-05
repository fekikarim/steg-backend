package tn.steg.backend.internship.application.chatbot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.steg.backend.ai.domain.chatbot.ChatbotKnowledge;
import tn.steg.backend.ai.domain.chatbot.ChatbotKnowledgeSource;
import tn.steg.backend.ai.domain.chatbot.ChatbotToolResult;
import tn.steg.backend.ai.domain.chatbot.ChatbotToolRunner;
import tn.steg.backend.application.domain.model.ApplicationStatus;
import tn.steg.backend.application.domain.model.InternshipApplication;
import tn.steg.backend.application.domain.repository.InternshipApplicationRepository;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.companion.domain.model.Task;
import tn.steg.backend.companion.domain.model.TaskStatus;
import tn.steg.backend.companion.domain.repository.TaskRepository;
import tn.steg.backend.internship.application.SupervisionScopeService;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Backend execution of the back-office chatbot's live-data tools (AGENTS.md
 * §7.5, function-calling pattern).
 *
 * <p>Lives OUTSIDE {@code ai.*} on purpose (AiIsolation forbids the AI layer
 * from calling application services): every tool resolves the CALLER's scope
 * through {@link SupervisionScopeService} — Admin sees global data, a
 * Supervisor sees only his own candidates/internships/tasks — and reads
 * through domain repository ports. Tools are strictly read-only: no branch
 * here writes anything, and unknown tools/invalid args yield a refusal
 * result (fed back to the model), never an exception.
 *
 * <p>Output hygiene (input/output safety policy): results carry whitelisted
 * operational fields only (references, names, statuses, dates, counts) —
 * never CIN/nationalId, credentials, tokens, contact details or secrets.
 * Out-of-scope or missing rows answer identically ("not found"), so a
 * Supervisor cannot probe another supervisor's rows (no existence leak).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScopedChatbotToolRunner implements ChatbotToolRunner {

    private static final int MAX_ROWS = 20;
    private static final int MAX_CHARS = 2000;

    private static final Set<String> TOOLS = Set.of(
            "kb_lookup", "my_candidates", "candidate_detail", "my_tasks", "queue_counts");

    private final SupervisionScopeService supervisionScopeService;
    private final InternshipRepository internshipRepository;
    private final InternshipApplicationRepository applicationRepository;
    private final TaskRepository taskRepository;
    private final ChatbotKnowledgeSource knowledgeSource;

    @Override
    public Set<String> toolNames() {
        return TOOLS;
    }

    @Override
    public String toolSpec(String name) {
        return switch (name) {
            case "kb_lookup" -> "kb_lookup {topic}: official STEG knowledge section(s) about a topic (roles, workflows, rules). No live data.";
            case "my_candidates" -> "my_candidates {status?}: candidates in the caller's scope (name, internship reference, status, type). Admin: all (capped); Supervisor: own only.";
            case "candidate_detail" -> "candidate_detail {candidateId}: one candidate in the caller's scope with internships, application statuses and task counts. Out-of-scope answers 'not found'.";
            case "my_tasks" -> "my_tasks {status?}: tasks in the caller's scope (title, status, due date, candidate, internship reference), capped.";
            case "queue_counts" -> "queue_counts {}: application counts by status in the caller's scope plus up to 10 pending references.";
            default -> name + " {}: unknown tool (do not call).";
        };
    }

    @Override
    @Transactional(readOnly = true)
    public ChatbotToolResult run(UserPrincipal actor, String name, Map<String, String> args) {
        if (actor == null || name == null || !TOOLS.contains(name)) {
            return ChatbotToolResult.refused("unknown tool: " + name);
        }
        Map<String, String> safeArgs = args == null ? Map.of() : args;
        try {
            String data = switch (name) {
                case "kb_lookup" -> kbLookup(safeArgs.getOrDefault("topic", ""));
                case "my_candidates" -> myCandidates(actor, safeArgs.get("status"));
                case "candidate_detail" -> candidateDetail(actor, safeArgs.get("candidateId"));
                case "my_tasks" -> myTasks(actor, safeArgs.get("status"));
                case "queue_counts" -> queueCounts(actor);
                default -> throw new IllegalStateException("unknown tool: " + name);
            };
            return ChatbotToolResult.ok(data);
        } catch (Exception ex) {
            log.warn("Chatbot tool {} degraded: {}", name, ex.getClass().getSimpleName());
            return ChatbotToolResult.refused("tool temporarily unavailable");
        }
    }

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    private String kbLookup(String topic) {
        if (topic == null || topic.isBlank()) {
            StringBuilder titles = new StringBuilder("Available knowledge sections:\n");
            for (ChatbotKnowledge.Section s : knowledgeSource.knowledge().sections()) {
                titles.append("- ").append(s.title()).append('\n');
            }
            return cap(titles.toString());
        }
        List<ChatbotKnowledge.Section> found =
                knowledgeSource.knowledge().lookup(topic, 2);
        if (found.isEmpty()) {
            return "No official knowledge section matches this topic.";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatbotKnowledge.Section s : found) {
            sb.append("[").append(s.title()).append("]\n").append(s.body()).append("\n\n");
        }
        return cap(sb.toString());
    }

    private String myCandidates(UserPrincipal actor, String statusFilter) {
        List<Candidate> candidates = supervisionScopeService.hasGlobalAccess(actor)
                ? candidatesOf(internshipRepository.findAll())
                : supervisionScopeService.supervisedCandidates(actor);
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (Candidate c : candidates) {
            for (Internship i : internshipsOf(c)) {
                if (statusFilter != null && !statusFilter.isBlank()
                        && (i.getStatus() == null
                        || !i.getStatus().name().equalsIgnoreCase(statusFilter.strip()))) {
                    continue;
                }
                total++;
                if (lines.size() < MAX_ROWS) {
                    lines.add(candidateLine(c, i));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Candidates in scope: ").append(total).append('\n');
        lines.forEach(l -> sb.append("- ").append(l).append('\n'));
        if (total > lines.size()) {
            sb.append("(showing first ").append(lines.size()).append(")");
        }
        return cap(sb.toString());
    }

    private String candidateDetail(UserPrincipal actor, String candidateIdRaw) {
        UUID candidateId;
        try {
            candidateId = UUID.fromString(candidateIdRaw == null ? "" : candidateIdRaw.strip());
        } catch (IllegalArgumentException ex) {
            return "not found";
        }
        // Scope check FIRST: out-of-scope answers exactly like missing (no leak).
        if (!supervisionScopeService.supervisesCandidate(actor, candidateId)) {
            return "not found";
        }
        List<Internship> internships = internshipRepository.findByCandidateId(candidateId);
        if (internships.isEmpty()) {
            return "Candidate " + candidateId + ": no internship on record.";
        }
        Candidate c = internships.get(0).getCandidate();
        StringBuilder sb = new StringBuilder();
        sb.append("Candidate: ").append(displayName(c)).append('\n');
        for (Internship i : internships) {
            sb.append("- Internship ").append(i.getReference())
                    .append(" | status=").append(i.getStatus())
                    .append(" | type=").append(i.getType())
                    .append(" | period=").append(i.getStartDate()).append("..").append(i.getEndDate())
                    .append(" | supervisor=").append(supervisorLabel(i)).append('\n');
            List<Task> tasks = taskRepository.findByInternshipId(i.getId());
            Map<String, Long> byStatus = new TreeMap<>();
            for (Task t : tasks) {
                byStatus.merge(String.valueOf(t.getStatus()), 1L, Long::sum);
            }
            sb.append("  tasks: ").append(tasks.size()).append(" total ").append(byStatus).append('\n');
            List<InternshipApplication> apps = applicationRepository.findByCandidateId(c.getId());
            for (InternshipApplication a : apps) {
                sb.append("  application ").append(a.getReference())
                        .append(" | status=").append(a.getStatus()).append('\n');
            }
        }
        return cap(sb.toString());
    }

    private String myTasks(UserPrincipal actor, String statusFilter) {
        final TaskStatus status;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                status = TaskStatus.valueOf(statusFilter.strip().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return "Invalid status filter. Known: TODO, IN_PROGRESS, COMPLETED, APPROVED, DENIED, CANCELLED.";
            }
        } else {
            status = null;
        }
        List<Internship> internships = supervisionScopeService.hasGlobalAccess(actor)
                ? internshipRepository.findAll()
                : supervisionScopeService.assignedInternships(actor);
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (Internship i : internships) {
            List<Task> tasks = status == null
                    ? taskRepository.findByInternshipId(i.getId())
                    : taskRepository.findByInternshipId(i.getId()).stream()
                    .filter(t -> t.getStatus() == status).toList();
            for (Task t : tasks) {
                total++;
                if (lines.size() < MAX_ROWS) {
                    lines.add(t.getTitle() + " | status=" + t.getStatus()
                            + " | due=" + t.getDueDate()
                            + " | candidate=" + (i.getCandidate() != null
                            ? displayName(i.getCandidate()) : "?")
                            + " | internship=" + i.getReference());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Tasks in scope: ").append(total).append('\n');
        lines.forEach(l -> sb.append("- ").append(l).append('\n'));
        if (total > lines.size()) {
            sb.append("(showing first ").append(lines.size()).append(")");
        }
        return cap(sb.toString());
    }

    private String queueCounts(UserPrincipal actor) {
        List<InternshipApplication> apps;
        if (supervisionScopeService.hasGlobalAccess(actor)) {
            apps = applicationRepository.findAll();
        } else {
            List<UUID> ids = supervisionScopeService.supervisedCandidateIds(actor);
            apps = ids.isEmpty() ? List.of() : applicationRepository.findByCandidateIdIn(ids);
        }
        Map<String, Long> counts = new TreeMap<>();
        List<String> pending = new ArrayList<>();
        for (InternshipApplication a : apps) {
            counts.merge(String.valueOf(a.getStatus()), 1L, Long::sum);
            if (a.getStatus() == ApplicationStatus.SUBMITTED && pending.size() < 10) {
                pending.add(a.getReference());
            }
        }
        StringBuilder sb = new StringBuilder("Applications in scope by status: ").append(counts).append('\n');
        if (!pending.isEmpty()) {
            sb.append("Pending review: ").append(String.join(", ", pending));
        }
        return cap(sb.toString());
    }

    // ------------------------------------------------------------------
    // Helpers (whitelisted fields only — never CIN, credentials, tokens,
    // contact details or secrets)
    // ------------------------------------------------------------------

    private List<Candidate> candidatesOf(List<Internship> internships) {
        Map<UUID, Candidate> byId = new LinkedHashMap<>();
        for (Internship i : internships) {
            if (i.getCandidate() != null && i.getCandidate().getId() != null) {
                byId.putIfAbsent(i.getCandidate().getId(), i.getCandidate());
            }
        }
        return new ArrayList<>(byId.values());
    }

    private List<Internship> internshipsOf(Candidate candidate) {
        if (candidate == null || candidate.getId() == null) {
            return List.of();
        }
        return internshipRepository.findByCandidateId(candidate.getId());
    }

    private String candidateLine(Candidate c, Internship i) {
        return displayName(c) + " | internship=" + i.getReference()
                + " | status=" + i.getStatus() + " | type=" + i.getType();
    }

    private static String displayName(Candidate c) {
        if (c == null) {
            return "?";
        }
        String name = ((c.getFirstName() == null ? "" : c.getFirstName()) + " "
                + (c.getLastName() == null ? "" : c.getLastName())).strip();
        return name.isEmpty() ? "candidate " + c.getId() : name;
    }

    private static String supervisorLabel(Internship i) {
        if (i.getSupervisorUser() != null && i.getSupervisorUser().getEmail() != null) {
            return i.getSupervisorUser().getEmail();
        }
        return "?";
    }

    private static String cap(String text) {
        if (text != null && text.length() > MAX_CHARS) {
            return text.substring(0, MAX_CHARS) + "\n(truncated)";
        }
        return text == null ? "" : text;
    }
}
