package tn.steg.backend.community.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.annotation.RateLimited;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.community.application.CommunityService;
import tn.steg.backend.community.application.dto.CommunityCommentResponse;
import tn.steg.backend.community.application.dto.CommunityPostResponse;
import tn.steg.backend.community.application.dto.CommunityReportResponse;
import tn.steg.backend.community.application.dto.CreateCommentRequest;
import tn.steg.backend.community.application.dto.CreatePostRequest;
import tn.steg.backend.community.application.dto.CreateReportRequest;
import tn.steg.backend.community.application.dto.MuteStudentRequest;
import tn.steg.backend.community.application.dto.ResolveReportRequest;
import tn.steg.backend.community.domain.model.CommunityReportStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST contract for the student community feed (T08 / ST-COM-01/02, D7).
 *
 * <p>One community for students with an active internship; staff
 * (ADMIN/SUPERVISOR) read and moderate. Every endpoint enforces its rule
 * server-side ({@code @authz.isCommunityReader} /
 * {@code @authz.canParticipateInCommunity} at the gate, standing
 * re-validated inside the service).
 *
 * <p>Every post/comment mutation is also broadcast to
 * {@code /topic/community} as a lightweight envelope
 * ({@code kind/postId/at} — never body content), so socket subscribers can
 * invalidate and refetch over REST. Broadcasts are best-effort: a broker
 * failure never fails the REST call itself (the persisted state remains
 * authoritative).
 */
@Slf4j
@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
@Tag(name = "Community", description = "Student community feed: posts, comments, reports, moderation (T08)")
public class CommunityController {

    private final CommunityService communityService;
    private final SimpMessagingTemplate messagingTemplate;

    // ------------------------------------------------------------------
    // Feed reads
    // ------------------------------------------------------------------

    @GetMapping("/posts")
    @PreAuthorize("@authz.isCommunityReader()")
    @Operation(summary = "Newest-first keyset feed over visible posts")
    public ResponseEntity<CommunityPostResponse.FeedPageResponse> feed(
            @RequestParam(required = false) Instant cursorTs,
            @RequestParam(required = false) UUID cursorId,
            @RequestParam(required = false, defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(communityService.listFeed(cursorTs, cursorId, size, actor));
    }

    @GetMapping("/posts/{postId}")
    @PreAuthorize("@authz.isCommunityReader()")
    @Operation(summary = "Get one visible post (404 when removed or unknown)")
    public ResponseEntity<CommunityPostResponse> getPost(
            @PathVariable UUID postId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(communityService.getPost(postId, actor));
    }

    @GetMapping("/posts/{postId}/comments")
    @PreAuthorize("@authz.isCommunityReader()")
    @Operation(summary = "Chronological visible comments of one post")
    public ResponseEntity<Page<CommunityCommentResponse>> comments(
            @PathVariable UUID postId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(communityService.listComments(postId, pageable, actor));
    }

    // ------------------------------------------------------------------
    // Writes (active-internship students only)
    // ------------------------------------------------------------------

    @PostMapping("/posts")
    @PreAuthorize("@authz.canParticipateInCommunity()")
    @RateLimited(name = "community-post-create", limit = 10, windowSeconds = 60)
    @Operation(summary = "Create a post (idempotent via X-Idempotency-Key)")
    public ResponseEntity<CommunityPostResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        CommunityPostResponse saved = communityService.createPost(request.body(), actor);
        broadcast("POST_CREATED", saved.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping(value = "/posts/with-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.canParticipateInCommunity()")
    @RateLimited(name = "community-post-create", limit = 10, windowSeconds = 60)
    @Operation(summary = "Create a post with one image/PDF attachment (max 10 MB, malware hook)")
    public ResponseEntity<CommunityPostResponse> createPostWithAttachment(
            @RequestParam("body") String body,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal actor) {
        CommunityPostResponse saved = communityService.createPostWithAttachment(body, file, actor);
        broadcast("POST_CREATED", saved.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/posts/{postId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete my post, or remove it as staff (reason mandatory for staff)")
    public ResponseEntity<CommunityPostResponse> deletePost(
            @PathVariable UUID postId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal actor) {
        CommunityPostResponse result = communityService.deletePost(postId, reason, actor);
        broadcast("POST_REMOVED", postId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/posts/{postId}/comments")
    @PreAuthorize("@authz.canParticipateInCommunity()")
    @RateLimited(name = "community-comment-create", limit = 20, windowSeconds = 60)
    @Operation(summary = "Comment on a visible post (idempotent via X-Idempotency-Key)")
    public ResponseEntity<CommunityCommentResponse> createComment(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        CommunityCommentResponse saved = communityService.createComment(postId, request.body(), actor);
        broadcast("COMMENT_ADDED", postId);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delete my comment, or remove it as staff (reason mandatory for staff)")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID commentId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UserPrincipal actor) {
        UUID postId = communityService.deleteComment(commentId, reason, actor);
        broadcast("COMMENT_REMOVED", postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reports")
    @PreAuthorize("@authz.canParticipateInCommunity()")
    @RateLimited(name = "community-report-create", limit = 10, windowSeconds = 60)
    @Operation(summary = "Report a post or a comment (one open report per reporter and target)")
    public ResponseEntity<CommunityReportResponse> report(
            @Valid @RequestBody CreateReportRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        CommunityReportResponse saved = communityService.report(
                request.targetType(), request.postId(), request.commentId(), request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/posts/attachments/{attachmentId}/download")
    @PreAuthorize("@authz.isCommunityReader()")
    @Operation(summary = "Download a post attachment (visible posts only, audited)")
    public ResponseEntity<InputStreamResource> downloadAttachment(
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal UserPrincipal actor,
            HttpServletRequest request) {
        CommunityService.AttachmentDownload download =
                communityService.downloadAttachment(attachmentId, actor, request.getRemoteAddr());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.fileName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.size())
                .body(new InputStreamResource(download.inputStream()));
    }

    // ------------------------------------------------------------------
    // Moderation (staff only)
    // ------------------------------------------------------------------

    @GetMapping("/moderation/reports")
    @PreAuthorize("@authz.hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Moderation report queue, newest first (staff only)")
    public ResponseEntity<Page<CommunityReportResponse>> reports(
            @RequestParam(required = false) CommunityReportStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(communityService.listReports(status, pageable, actor));
    }

    @PostMapping("/moderation/reports/{reportId}/resolve")
    @PreAuthorize("@authz.hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Resolve a report (terminal, audited; staff only)")
    public ResponseEntity<CommunityReportResponse> resolveReport(
            @PathVariable UUID reportId,
            @Valid @RequestBody(required = false) ResolveReportRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(communityService.resolveReport(
                reportId, request != null ? request.resolution() : null, actor));
    }

    @PostMapping("/moderation/mutes")
    @PreAuthorize("@authz.hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Mute a student from the community (staff only, reason mandatory)")
    public ResponseEntity<Void> muteStudent(
            @Valid @RequestBody MuteStudentRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        communityService.muteStudent(request.userId(), request.minutes(), request.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/moderation/mutes/{userId}")
    @PreAuthorize("@authz.hasAnyRole('ADMIN','SUPERVISOR')")
    @Operation(summary = "Lift a community mute (staff only, idempotent)")
    public ResponseEntity<Void> unmuteStudent(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal actor) {
        communityService.unmuteStudent(userId, actor);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lightweight feed envelope: subscribers invalidate and refetch over
     * REST — the payload is a trigger, never a source of truth.
     */
    private void broadcast(String kind, UUID postId) {
        try {
            Map<String, String> envelope = Map.of(
                    "kind", kind, "postId", postId.toString(), "at", Instant.now().toString());
            messagingTemplate.convertAndSend("/topic/community", (Object) envelope);
        } catch (Exception e) {
            log.warn("Community broadcast failed for post {}: {}", postId, e.getMessage());
        }
    }
}
