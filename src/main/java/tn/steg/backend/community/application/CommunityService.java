package tn.steg.backend.community.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.audit.domain.model.AuditSource;
import tn.steg.backend.candidate.domain.model.Candidate;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.application.idempotency.IdempotencyService;
import tn.steg.backend.common.domain.event.CommunityCommentEvent;
import tn.steg.backend.common.domain.event.CommunityRemovalEvent;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.community.application.dto.CommunityCommentResponse;
import tn.steg.backend.community.application.dto.CommunityPostResponse;
import tn.steg.backend.community.application.dto.CommunityReportResponse;
import tn.steg.backend.community.domain.model.CommunityComment;
import tn.steg.backend.community.domain.model.CommunityContentStatus;
import tn.steg.backend.community.domain.model.CommunityMute;
import tn.steg.backend.community.domain.model.CommunityPost;
import tn.steg.backend.community.domain.model.CommunityReport;
import tn.steg.backend.community.domain.model.CommunityReportStatus;
import tn.steg.backend.community.domain.model.CommunityReportTarget;
import tn.steg.backend.community.domain.repository.CommunityCommentRepository;
import tn.steg.backend.community.domain.repository.CommunityMuteRepository;
import tn.steg.backend.community.domain.repository.CommunityPostRepository;
import tn.steg.backend.community.domain.repository.CommunityReportRepository;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.service.DocumentValidationService;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.MalwareScanner;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Application service for the student community feed (T08 / ST-COM-01/02, D7).
 *
 * <p>Backend-authoritative on every rule:
 * <ul>
 *   <li>Scope (BR-39): readers = staff (ADMIN/SUPERVISOR) or students with an
 *   {@code IN_PROGRESS} internship; writers (posts/comments/reports) =
 *   active-internship students only; moderation = staff only. Membership is
 *   re-validated on every call — the client never widens access.</li>
 *   <li>Feed order: newest-first keyset {@code (createdAt DESC, id DESC)} —
 *   concurrent inserts can neither duplicate nor skip rows.</li>
 *   <li>Privacy (D7): authors surface as an opaque user id plus a
 *   server-derived display name (candidate first name + last initial) —
 *   never email, CIN, university or supervisor data. Moderator identities
 *   never leave the server.</li>
 *   <li>Writes are idempotent where a duplicate would create data
 *   (post/comment create via {@code X-Idempotency-Key}); identical bodies
 *   within 10 minutes are rejected as duplicates.</li>
 * </ul>
 *
 * <p>All community endpoints are mobile-only (no back-office UI), so audit
 * rows are recorded with {@code source = MOBILE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    /** Server content limits (client pre-checks mirror them for UX only). */
    public static final int MAX_POST_LENGTH = 2000;
    public static final int MAX_COMMENT_LENGTH = 1000;
    public static final int MAX_REASON_LENGTH = 500;
    /** Duplicate-content window for identical post bodies. */
    public static final long DUPLICATE_WINDOW_SECONDS = 600L;
    /** Mute duration bounds (minutes): 5 minutes .. 30 days. */
    public static final int MIN_MUTE_MINUTES = 5;
    public static final int MAX_MUTE_MINUTES = 43200;
    public static final int FEED_DEFAULT_SIZE = 20;
    public static final int FEED_MAX_SIZE = 50;

    /**
     * Contact-data prohibition (BR-40): email addresses and phone-number-like
     * digit runs are rejected. Plain links are ALLOWED (students share
     * learning resources; emails/phones are the PII vector) — documented
     * deviation from the illustrative "email/phone/links" list.
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?:\\+?\\d[\\d\\s./-]{5,}\\d)");

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityReportRepository reportRepository;
    private final CommunityMuteRepository muteRepository;
    private final InternshipRepository internshipRepository;
    private final CandidateRepository candidateRepository;
    private final UserRepository userRepository;
    private final FileAssetRepository fileAssetRepository;
    private final FileStorageService fileStorageService;
    private final DocumentValidationService documentValidationService;
    private final MalwareScanner malwareScanner;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;

    /** Business facts for cross-cutting concerns; never a dependency on consumers (Phase A10). */
    private final ApplicationEventPublisher eventPublisher;

    // -------------------------------------------------------------------------
    // Standing (used by the STOMP topic guard + every service call)
    // -------------------------------------------------------------------------

    /**
     * Visibility probe: may this user read the community at all — staff, or
     * a student with an {@code IN_PROGRESS} internship. Unknown ids return
     * {@code false} without distinguishing them.
     */
    @Transactional(readOnly = true)
    public boolean isCommunityVisible(UUID userId) {
        if (userId == null) {
            return false;
        }
        try {
            var user = userRepository.findById(userId);
            if (user.isEmpty()) {
                return false;
            }
            if (isStaff(user.get())) {
                return true;
            }
            return hasActiveInternship(userId);
        } catch (Exception e) {
            log.debug("Community visibility check failed for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Write probe: students with an {@code IN_PROGRESS} internship only.
     * Staff read and moderate but never publish.
     */
    @Transactional(readOnly = true)
    public boolean canParticipate(UUID userId) {
        if (userId == null) {
            return false;
        }
        try {
            var user = userRepository.findById(userId);
            if (user.isEmpty() || isStaff(user.get())) {
                return false;
            }
            return hasActiveInternship(userId);
        } catch (Exception e) {
            log.debug("Community participation check failed for user={}: {}", userId, e.getMessage());
            return false;
        }
    }

    private boolean isStaff(User user) {
        return user.getAssignedRoles().stream()
                .map(role -> role.getCode() == null ? ""
                        : role.getCode().toUpperCase().replaceFirst("^ROLE_", ""))
                .anyMatch(code -> code.equals("ADMIN") || code.equals("SUPERVISOR"));
    }

    private boolean hasActiveInternship(UUID userId) {
        return !internshipRepository.findByCandidateUserIdAndStatus(userId, InternshipStatus.IN_PROGRESS).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Feed reads
    // -------------------------------------------------------------------------

    /**
     * Newest-first keyset feed over visible posts.
     *
     * @param cursorCreatedAt exclusive upper bound (with {@code cursorId});
     *                        {@code null} with {@code cursorId == null} = first page.
     */
    @Transactional(readOnly = true)
    public CommunityPostResponse.FeedPageResponse listFeed(Instant cursorCreatedAt, UUID cursorId,
                                                           int size, UserPrincipal actor) {
        assertReader(actor.getId());
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new BusinessRuleException("CURSOR_INVALID",
                    "Feed cursor needs both cursorTs and cursorId, or neither.");
        }
        int pageSize = Math.min(Math.max(size, 1), FEED_MAX_SIZE);
        Pageable window = PageRequest.of(0, pageSize + 1, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<CommunityPost> rows = cursorCreatedAt == null
                ? postRepository.findVisibleFeedFirstPage(window)
                : postRepository.findVisibleFeedPageAfter(cursorCreatedAt, cursorId, window);
        boolean hasMore = rows.size() > pageSize;
        List<CommunityPost> page = hasMore ? rows.subList(0, pageSize) : rows;
        Instant nextTs = null;
        UUID nextId = null;
        if (hasMore && !page.isEmpty()) {
            CommunityPost last = page.get(page.size() - 1);
            nextTs = last.getCreatedAt();
            nextId = last.getId();
        }
        return new CommunityPostResponse.FeedPageResponse(
                toPostResponses(page), hasMore, nextTs, nextId);
    }

    @Transactional(readOnly = true)
    public CommunityPostResponse getPost(UUID postId, UserPrincipal actor) {
        assertReader(actor.getId());
        CommunityPost post = findVisiblePostOrThrow(postId);
        return toPostResponse(post);
    }

    @Transactional(readOnly = true)
    public Page<CommunityCommentResponse> listComments(UUID postId, Pageable pageable, UserPrincipal actor) {
        assertReader(actor.getId());
        findVisiblePostOrThrow(postId);
        Page<CommunityComment> page = commentRepository.findVisibleByPostId(postId, sanitizeComments(pageable));
        Map<UUID, String> names = resolveDisplayNames(collectAuthors(page.getContent()));
        return page.map(c -> CommunityCommentResponse.from(c, names.getOrDefault(
                c.getAuthor() != null ? c.getAuthor().getId() : null, fallbackDisplayName())));
    }

    // -------------------------------------------------------------------------
    // Writes (idempotent creates, author/staff deletes)
    // -------------------------------------------------------------------------

    @Transactional
    public CommunityPostResponse createPost(String body, UserPrincipal actor) {
        // T08/BR-56: the mobile composer mints one UUID per logical post and
        // reuses it only for retries of that same post.
        return idempotencyService.execute(actor.getId(), IdempotencyService.currentKey().orElse(null),
                () -> doCreatePost(body, null, actor), CommunityPostResponse.class);
    }

    @Transactional
    public CommunityPostResponse createPostWithAttachment(String body, MultipartFile file, UserPrincipal actor) {
        // Same transaction: attachment validation/storage failure rolls back
        // the post row as well, so no orphan post is left behind.
        CommunityPostResponse created = createPost(body, actor);
        if (file == null || file.isEmpty()) {
            return created;
        }
        CommunityPost post = postRepository.findById(created.id())
                .orElseThrow(() -> new ResourceNotFoundException("Community post not found: " + created.id()));
        FileAsset asset = storeCommunityAttachment(file, actor);
        post.setAttachment(asset);
        post = postRepository.save(post);
        audit(actor, "COMMUNITY_POST_ATTACHMENT_ADDED", "CommunityPost", post.getId(), null, null);
        return toPostResponse(post);
    }

    private CommunityPostResponse doCreatePost(String body, FileAsset attachment, UserPrincipal actor) {
        assertParticipant(actor.getId());
        assertNotMuted(actor.getId());
        String clean = checkBody(body, MAX_POST_LENGTH, "POST_TOO_LONG", "Post");
        assertNoContactData(clean);
        assertNotDuplicate(actor.getId(), clean);

        User author = findUserOrThrow(actor.getId());
        CommunityPost post = new CommunityPost(author, clean, bodyHash(clean), attachment);
        post = postRepository.save(post);

        audit(actor, "COMMUNITY_POST_CREATED", "CommunityPost", post.getId(), null, null);
        log.debug("Community post created: id={} author={}", post.getId(), actor.getId());
        return toPostResponse(post);
    }

    /**
     * Delete a post. The author withdraws it ({@code DELETED}, idempotent);
     * staff remove it ({@code REMOVED}, reason mandatory, audited, author
     * notified). Anything else is denied.
     */
    @Transactional
    public CommunityPostResponse deletePost(UUID postId, String reason, UserPrincipal actor) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Community post not found: " + postId));
        if (!post.isVisible()) {
            throw new ResourceNotFoundException("Community post not found: " + postId);
        }
        boolean isAuthor = post.getAuthor().getId().equals(actor.getId());
        boolean staff = actor.hasRole("ADMIN") || actor.hasRole("SUPERVISOR");
        if (!isAuthor && !staff) {
            throw new AccessDeniedException("Only the author or staff may delete this post.");
        }
        if (isAuthor) {
            post.setStatus(CommunityContentStatus.DELETED);
            post = postRepository.save(post);
            audit(actor, "COMMUNITY_POST_DELETED", "CommunityPost", post.getId(), null, null);
            return toPostResponse(post);
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("REMOVAL_REASON_REQUIRED",
                    "Removing a community post requires a reason.");
        }
        if (reason.strip().length() > MAX_REASON_LENGTH) {
            throw new BusinessRuleException("REMOVAL_REASON_TOO_LONG",
                    "Removal reason must not exceed 500 characters.");
        }
        User moderator = findUserOrThrow(actor.getId());
        post.setStatus(CommunityContentStatus.REMOVED);
        post.setRemovedBy(moderator);
        post.setRemovedReason(reason.strip());
        post = postRepository.save(post);

        audit(actor, "COMMUNITY_POST_REMOVED", "CommunityPost", post.getId(), null,
                Map.of("reason", reason.strip()));
        eventPublisher.publishEvent(new CommunityRemovalEvent(
                post.getId(), null, post.getAuthor().getId(), reason.strip(), true, actor.getId()));
        // A removed post keeps its tombstone id for the response contract,
        // but the body never leaves the server again.
        return redactedPostResponse(post);
    }

    @Transactional
    public CommunityCommentResponse createComment(UUID postId, String body, UserPrincipal actor) {
        return idempotencyService.execute(actor.getId(), IdempotencyService.currentKey().orElse(null),
                () -> doCreateComment(postId, body, actor), CommunityCommentResponse.class);
    }

    private CommunityCommentResponse doCreateComment(UUID postId, String body, UserPrincipal actor) {
        assertParticipant(actor.getId());
        assertNotMuted(actor.getId());
        CommunityPost post = findVisiblePostOrThrow(postId);
        String clean = checkBody(body, MAX_COMMENT_LENGTH, "COMMENT_TOO_LONG", "Comment");
        assertNoContactData(clean);

        User author = findUserOrThrow(actor.getId());
        CommunityComment comment = new CommunityComment(post, author, clean);
        comment = commentRepository.save(comment);
        post.setCommentCount(post.getCommentCount() + 1);
        postRepository.save(post);

        audit(actor, "COMMUNITY_COMMENT_CREATED", "CommunityComment", comment.getId(), null, null);

        if (!post.getAuthor().getId().equals(actor.getId())) {
            eventPublisher.publishEvent(new CommunityCommentEvent(
                    post.getId(), comment.getId(), post.getAuthor().getId(),
                    displayNameOf(actor.getId()), actor.getId()));
        }
        Map<UUID, String> names = resolveDisplayNames(List.of(actor.getId()));
        return CommunityCommentResponse.from(comment, names.getOrDefault(actor.getId(), fallbackDisplayName()));
    }

    /**
     * Delete a comment (author withdraws, staff removes with mandatory
     * reason). The parent post counter is decremented in the same
     * transaction so feed and detail counts stay consistent.
     *
     * @return the parent post id (for the realtime envelope).
     */
    @Transactional
    public UUID deleteComment(UUID commentId, String reason, UserPrincipal actor) {
        CommunityComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Community comment not found: " + commentId));
        if (!comment.isVisible()) {
            throw new ResourceNotFoundException("Community comment not found: " + commentId);
        }
        boolean isAuthor = comment.getAuthor().getId().equals(actor.getId());
        boolean staff = actor.hasRole("ADMIN") || actor.hasRole("SUPERVISOR");
        if (!isAuthor && !staff) {
            throw new AccessDeniedException("Only the author or staff may delete this comment.");
        }
        CommunityPost post = comment.getPost();
        if (isAuthor) {
            comment.setStatus(CommunityContentStatus.DELETED);
            commentRepository.save(comment);
            decrementCommentCount(post);
            audit(actor, "COMMUNITY_COMMENT_DELETED", "CommunityComment", comment.getId(), null, null);
            return post.getId();
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("REMOVAL_REASON_REQUIRED",
                    "Removing a community comment requires a reason.");
        }
        if (reason.strip().length() > MAX_REASON_LENGTH) {
            throw new BusinessRuleException("REMOVAL_REASON_TOO_LONG",
                    "Removal reason must not exceed 500 characters.");
        }
        User moderator = findUserOrThrow(actor.getId());
        comment.setStatus(CommunityContentStatus.REMOVED);
        comment.setRemovedBy(moderator);
        comment.setRemovedReason(reason.strip());
        commentRepository.save(comment);
        decrementCommentCount(post);

        audit(actor, "COMMUNITY_COMMENT_REMOVED", "CommunityComment", comment.getId(), null,
                Map.of("reason", reason.strip()));
        eventPublisher.publishEvent(new CommunityRemovalEvent(
                post.getId(), comment.getId(), comment.getAuthor().getId(), reason.strip(), false, actor.getId()));
        return post.getId();
    }

    private void decrementCommentCount(CommunityPost post) {
        CommunityPost managed = postRepository.findById(post.getId()).orElse(post);
        managed.setCommentCount(Math.max(0, managed.getCommentCount() - 1));
        postRepository.save(managed);
    }

    // -------------------------------------------------------------------------
    // Reports
    // -------------------------------------------------------------------------

    @Transactional
    public CommunityReportResponse report(CommunityReportTarget targetType, UUID postId,
                                          UUID commentId, String reason, UserPrincipal actor) {
        assertParticipant(actor.getId());
        assertNotMuted(actor.getId());
        if (targetType == null) {
            throw new BusinessRuleException("REPORT_TARGET_REQUIRED", "Report target type is required.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("REPORT_REASON_REQUIRED", "Report reason must not be blank.");
        }
        if (reason.strip().length() > MAX_REASON_LENGTH) {
            throw new BusinessRuleException("REPORT_REASON_TOO_LONG",
                    "Report reason must not exceed 500 characters.");
        }
        CommunityPost post = null;
        CommunityComment comment = null;
        if (targetType == CommunityReportTarget.POST) {
            if (postId == null || commentId != null) {
                throw new BusinessRuleException("REPORT_TARGET_MISMATCH",
                        "A post report needs exactly postId.");
            }
            post = findVisiblePostOrThrow(postId);
            if (reportRepository.findOpenByReporterAndPost(actor.getId(), postId).isPresent()) {
                throw new BusinessRuleException("REPORT_ALREADY_OPEN",
                        "You already reported this post and the report is still open.");
            }
            if (post.getAuthor().getId().equals(actor.getId())) {
                throw new BusinessRuleException("REPORT_OWN_CONTENT",
                        "You cannot report your own post. Delete it instead.");
            }
        } else {
            if (commentId == null || postId != null) {
                throw new BusinessRuleException("REPORT_TARGET_MISMATCH",
                        "A comment report needs exactly commentId.");
            }
            comment = findVisibleCommentOrThrow(commentId);
            if (reportRepository.findOpenByReporterAndComment(actor.getId(), commentId).isPresent()) {
                throw new BusinessRuleException("REPORT_ALREADY_OPEN",
                        "You already reported this comment and the report is still open.");
            }
            if (comment.getAuthor().getId().equals(actor.getId())) {
                throw new BusinessRuleException("REPORT_OWN_CONTENT",
                        "You cannot report your own comment. Delete it instead.");
            }
        }
        User reporter = findUserOrThrow(actor.getId());
        CommunityReport report = new CommunityReport(targetType, post, comment, reporter, reason.strip());
        report = reportRepository.save(report);
        audit(actor, "COMMUNITY_REPORT_CREATED", "CommunityReport", report.getId(), null, null);
        return CommunityReportResponse.from(report);
    }

    @Transactional(readOnly = true)
    public Page<CommunityReportResponse> listReports(CommunityReportStatus status,
                                                     Pageable pageable, UserPrincipal actor) {
        assertStaff(actor);
        Page<CommunityReport> page = status == null
                ? reportRepository.findAllByOrderByCreatedAtDesc(sanitize(pageable))
                : reportRepository.findByStatus(status, sanitize(pageable));
        return page.map(CommunityReportResponse::from);
    }

    @Transactional
    public CommunityReportResponse resolveReport(UUID reportId, String resolution, UserPrincipal actor) {
        assertStaff(actor);
        CommunityReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Community report not found: " + reportId));
        if (!report.isOpen()) {
            return CommunityReportResponse.from(report);
        }
        if (resolution != null && resolution.strip().length() > MAX_REASON_LENGTH) {
            throw new BusinessRuleException("REPORT_RESOLUTION_TOO_LONG",
                    "Resolution must not exceed 500 characters.");
        }
        report.setStatus(CommunityReportStatus.RESOLVED);
        report.setResolution(resolution == null || resolution.isBlank() ? null : resolution.strip());
        report.setResolvedBy(findUserOrThrow(actor.getId()));
        report.setResolvedAt(Instant.now());
        report = reportRepository.save(report);
        audit(actor, "COMMUNITY_REPORT_RESOLVED", "CommunityReport", report.getId(), null,
                Map.of("resolution", report.getResolution() != null ? report.getResolution() : ""));
        return CommunityReportResponse.from(report);
    }

    // -------------------------------------------------------------------------
    // Mutes
    // -------------------------------------------------------------------------

    @Transactional
    public void muteStudent(UUID userId, int minutes, String reason, UserPrincipal actor) {
        assertStaff(actor);
        if (minutes < MIN_MUTE_MINUTES || minutes > MAX_MUTE_MINUTES) {
            throw new BusinessRuleException("MUTE_DURATION_INVALID",
                    "Mute duration must be between 5 minutes and 30 days.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("MUTE_REASON_REQUIRED", "Muting a student requires a reason.");
        }
        if (reason.strip().length() > MAX_REASON_LENGTH) {
            throw new BusinessRuleException("MUTE_REASON_TOO_LONG",
                    "Mute reason must not exceed 500 characters.");
        }
        User target = findUserOrThrow(userId);
        if (isStaff(target)) {
            throw new BusinessRuleException("CANNOT_MUTE_STAFF", "Staff accounts cannot be muted.");
        }
        Instant until = Instant.now().plusSeconds((long) minutes * 60L);
        CommunityMute mute = muteRepository.findByUserId(userId).orElse(null);
        if (mute == null) {
            mute = new CommunityMute(target, findUserOrThrow(actor.getId()), until, reason.strip());
        } else {
            mute.setMutedBy(findUserOrThrow(actor.getId()));
            mute.setMutedUntil(until);
            mute.setReason(reason.strip());
        }
        muteRepository.save(mute);
        // Re-read: a fresh row may be a merge copy (version-seeded entities),
        // so only the managed instance carries the generated id for audit.
        CommunityMute persisted = muteRepository.findByUserId(userId).orElseThrow();
        audit(actor, "COMMUNITY_STUDENT_MUTED", "CommunityMute", persisted.getId(),
                null, Map.of("userId", userId.toString(), "until", until.toString()));
    }

    @Transactional
    public void unmuteStudent(UUID userId, UserPrincipal actor) {
        assertStaff(actor);
        CommunityMute mute = muteRepository.findByUserId(userId).orElse(null);
        if (mute == null) {
            return;
        }
        muteRepository.delete(mute);
        audit(actor, "COMMUNITY_STUDENT_UNMUTED", "CommunityMute", mute.getId(),
                Map.of("userId", userId.toString()), null);
    }

    @Transactional(readOnly = true)
    public boolean isMuted(UUID userId) {
        return muteRepository.findByUserId(userId)
                .map(m -> m.isActive(Instant.now()))
                .orElse(false);
    }

    // -------------------------------------------------------------------------
    // Attachments (membership-guarded, audited)
    // -------------------------------------------------------------------------

    public record AttachmentDownload(java.io.InputStream inputStream, String fileName,
                                     String mimeType, long size) {}

    @Transactional
    public AttachmentDownload downloadAttachment(UUID attachmentId, UserPrincipal actor, String ipAddress) {
        assertReader(actor.getId());
        CommunityPost post = postRepository.findVisiblePostIdByAttachmentId(attachmentId)
                .flatMap(postRepository::findById)
                .filter(CommunityPost::isVisible)
                .filter(p -> p.getAttachment() != null && p.getAttachment().getId().equals(attachmentId))
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));
        var asset = post.getAttachment();
        audit(actor, "COMMUNITY_ATTACHMENT_DOWNLOADED", "CommunityPost", post.getId(), null, null);
        InputStream stream = fileStorageService.getInputStream(asset.getStorageKey());
        return new AttachmentDownload(stream, asset.getOriginalFileName(), asset.getMimeType(), asset.getSize());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertReader(UUID userId) {
        if (!isCommunityVisible(userId)) {
            throw new AccessDeniedException("You do not have access to the student community.");
        }
    }

    private void assertParticipant(UUID userId) {
        if (!canParticipate(userId)) {
            throw new AccessDeniedException("Only students with an active internship may write to the community.");
        }
    }

    private void assertStaff(UserPrincipal actor) {
        if (!actor.hasRole("ADMIN") && !actor.hasRole("SUPERVISOR")) {
            throw new AccessDeniedException("Community moderation is restricted to staff.");
        }
    }

    private void assertNotMuted(UUID userId) {
        CommunityMute mute = muteRepository.findByUserId(userId).orElse(null);
        if (mute != null && mute.isActive(Instant.now())) {
            throw new BusinessRuleException("STUDENT_MUTED",
                    "You are muted from the community until " + mute.getMutedUntil()
                            + ". Reason: " + mute.getReason());
        }
    }

    private CommunityPost findVisiblePostOrThrow(UUID postId) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Community post not found: " + postId));
        if (!post.isVisible()) {
            throw new ResourceNotFoundException("Community post not found: " + postId);
        }
        return post;
    }

    private CommunityComment findVisibleCommentOrThrow(UUID commentId) {
        CommunityComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Community comment not found: " + commentId));
        if (!comment.isVisible()) {
            throw new ResourceNotFoundException("Community comment not found: " + commentId);
        }
        if (!comment.getPost().isVisible()) {
            throw new ResourceNotFoundException("Community comment not found: " + commentId);
        }
        return comment;
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private String checkBody(String body, int maxLength, String tooLongCode, String label) {
        if (body == null || body.isBlank()) {
            throw new BusinessRuleException("EMPTY_BODY", label + " content must not be blank.");
        }
        if (body.length() > maxLength) {
            throw new BusinessRuleException(tooLongCode,
                    label + " content must not exceed " + maxLength + " characters.");
        }
        return body.strip();
    }

    private void assertNoContactData(String body) {
        if (EMAIL_PATTERN.matcher(body).find() || PHONE_PATTERN.matcher(body).find()) {
            throw new BusinessRuleException("CONTACT_DATA_NOT_ALLOWED",
                    "Community posts must not contain email addresses or phone numbers.");
        }
    }

    private void assertNotDuplicate(UUID authorUserId, String cleanBody) {
        String hash = bodyHash(cleanBody);
        Instant since = Instant.now().minusSeconds(DUPLICATE_WINDOW_SECONDS);
        boolean duplicate = postRepository
                .findRecentVisibleByAuthor(authorUserId, since,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .anyMatch(p -> hash.equals(p.getBodyHash()));
        if (duplicate) {
            throw new BusinessRuleException("DUPLICATE_POST",
                    "You already posted this content recently. Please wait before reposting.");
        }
    }

    static String bodyHash(String cleanBody) {
        String normalized = cleanBody.strip().replaceAll("\\s+", " ").toLowerCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Privacy-safe display name (D7 / BR-39): candidate first name + last
     * initial ("Yasmine H."). Never email, CIN, university or supervisor.
     */
    String displayNameOf(UUID userId) {
        return resolveDisplayNames(List.of(userId)).getOrDefault(userId, fallbackDisplayName());
    }

    private String fallbackDisplayName() {
        return "Stagiaire STEG";
    }

    private Map<UUID, String> resolveDisplayNames(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID userId : userIds) {
            if (userId == null || names.containsKey(userId)) {
                continue;
            }
            String name = fallbackDisplayName();
            try {
                var candidate = candidateRepository.findByUserId(userId);
                if (candidate.isPresent()) {
                    Candidate c = candidate.get();
                    String first = c.getFirstName() != null ? c.getFirstName().strip() : "";
                    String last = c.getLastName() != null ? c.getLastName().strip() : "";
                    if (!first.isEmpty()) {
                        name = last.isEmpty() ? first : first + " " + last.charAt(0) + ".";
                    }
                }
            } catch (Exception e) {
                log.debug("Display-name lookup failed for user={}: {}", userId, e.getMessage());
            }
            names.put(userId, name);
        }
        return names;
    }

    private List<UUID> collectAuthors(List<CommunityComment> comments) {
        List<UUID> ids = new ArrayList<>();
        for (CommunityComment c : comments) {
            if (c.getAuthor() != null) {
                ids.add(c.getAuthor().getId());
            }
        }
        return ids;
    }

    /**
     * Tombstone response for a just-removed post: id/status/timestamps stay
     * for the caller's reconciliation, but the body and attachment never
     * leave the server again.
     */
    private CommunityPostResponse redactedPostResponse(CommunityPost post) {
        Map<UUID, String> names = resolveDisplayNames(
                post.getAuthor() != null ? List.of(post.getAuthor().getId()) : List.of());
        return new CommunityPostResponse(
                post.getId(),
                post.getAuthor() != null ? post.getAuthor().getId() : null,
                names.getOrDefault(post.getAuthor() != null ? post.getAuthor().getId() : null,
                        fallbackDisplayName()),
                CommunityPostResponse.REDACTED_CONTENT,
                post.getStatus(),
                post.getCommentCount(),
                null,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    private CommunityPostResponse toPostResponse(CommunityPost post) {
        Map<UUID, String> names = resolveDisplayNames(
                post.getAuthor() != null ? List.of(post.getAuthor().getId()) : List.of());
        CommunityPostResponse.AttachmentResponse attachment = null;
        if (post.getAttachment() != null) {
            var asset = post.getAttachment();
            attachment = new CommunityPostResponse.AttachmentResponse(
                    asset.getId(), asset.getOriginalFileName(), asset.getMimeType(), asset.getSize());
        }
        return CommunityPostResponse.from(post,
                names.getOrDefault(post.getAuthor() != null ? post.getAuthor().getId() : null,
                        fallbackDisplayName()),
                attachment);
    }

    private List<CommunityPostResponse> toPostResponses(List<CommunityPost> posts) {
        List<UUID> authorIds = new ArrayList<>();
        for (CommunityPost post : posts) {
            if (post.getAuthor() != null) {
                authorIds.add(post.getAuthor().getId());
            }
        }
        Map<UUID, String> names = resolveDisplayNames(authorIds);
        List<CommunityPostResponse> out = new ArrayList<>(posts.size());
        for (CommunityPost post : posts) {
            CommunityPostResponse.AttachmentResponse attachment = null;
            if (post.getAttachment() != null) {
                var asset = post.getAttachment();
                attachment = new CommunityPostResponse.AttachmentResponse(
                        asset.getId(), asset.getOriginalFileName(), asset.getMimeType(), asset.getSize());
            }
            out.add(CommunityPostResponse.from(post,
                    names.getOrDefault(post.getAuthor() != null ? post.getAuthor().getId() : null,
                            fallbackDisplayName()),
                    attachment));
        }
        return out;
    }

    /**
     * Stores a community attachment through the same validation pipeline as
     * chat attachments (size caps, Tika MIME inspection, spoof detection,
     * malware hook). Reuses {@code DocumentType.OTHER} limits: PDF/JPEG/PNG
     * only, 10 MB default.
     */
    private FileAsset storeCommunityAttachment(MultipartFile file, UserPrincipal actor) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_READ_ERROR",
                    "Could not read attachment content: " + e.getMessage());
        }
        DocumentValidationService.ValidationResult validation =
                documentValidationService.validate(bytes, file.getOriginalFilename(),
                        file.getContentType(), DocumentType.OTHER);
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            if (!malwareScanner.isClean(is, file.getOriginalFilename())) {
                throw new BusinessRuleException("MALWARE_DETECTED",
                        "Malware or suspicious content detected in attachment.");
            }
        } catch (IOException e) {
            log.error("Malware scanner read failure: {}", e.getMessage());
        }
        String storageKey;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            storageKey = fileStorageService.store(is, file.getOriginalFilename(), validation.detectedMimeType());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist attachment", e);
        }
        User uploader = findUserOrThrow(actor.getId());
        FileAsset asset = new FileAsset(
                storageKey,
                file.getOriginalFilename(),
                validation.checksum(),
                validation.detectedMimeType(),
                validation.sizeBytes(),
                uploader);
        return fileAssetRepository.save(asset);
    }

    private Pageable sanitize(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, FEED_DEFAULT_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        int size = Math.min(Math.max(pageable.getPageSize(), 1), FEED_MAX_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }

    private Pageable sanitizeComments(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, FEED_DEFAULT_SIZE, Sort.by(Sort.Direction.ASC, "createdAt"));
        }
        int size = Math.min(Math.max(pageable.getPageSize(), 1), FEED_MAX_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }

    /**
     * All community endpoints are mobile-only (no back-office UI), so audit
     * rows always carry {@code source = MOBILE} with the caller's role.
     */
    private void audit(UserPrincipal actor, String action, String entityType, UUID entityId,
                       Object oldValues, Object newValues) {
        auditService.log(action, entityType, entityId, oldValues, newValues,
                actor.getId(), null, AuditService.primaryRole(actor.getRoles()), null, AuditSource.MOBILE);
    }
}
