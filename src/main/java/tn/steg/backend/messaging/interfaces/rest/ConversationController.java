package tn.steg.backend.messaging.interfaces.rest;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.messaging.application.MessagingService;
import tn.steg.backend.messaging.application.dto.AddMemberRequest;
import tn.steg.backend.messaging.application.dto.ConversationResponse;
import tn.steg.backend.messaging.application.dto.CreateGroupRequest;
import tn.steg.backend.messaging.application.dto.MarkDeliveredRequest;
import tn.steg.backend.messaging.application.dto.MarkReadRequest;
import tn.steg.backend.messaging.application.dto.MessageResponse;
import tn.steg.backend.messaging.application.dto.SendMessageRequest;
import tn.steg.backend.messaging.application.dto.UnreadCountResponse;

import java.util.List;
import java.util.UUID;

/**
 * REST fallback/history adapter for messaging (Phase A9).
 * Real-time delivery uses STOMP (see {@code MessagingStompController});
 * every endpoint here enforces the same active-membership rule server-side.
 *
 * <p>Every mutation (send, edit, delete, delivered ack, read ack) is also
 * broadcast to {@code /topic/conversations/{id}} so WebSocket subscribers
 * stay in sync regardless of which transport originated the change.
 * Broadcasts are best-effort: a broker failure never fails the REST call
 * itself (the persisted state remains authoritative).
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
@Tag(name = "Messaging", description = "Conversations, messages, delivery/read receipts, attachments (REST fallback to the STOMP real-time channel)")
public class ConversationController {

    private final MessagingService messagingService;
    private final SimpMessagingTemplate messagingTemplate;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List my conversations (active membership only)")
    public ResponseEntity<List<ConversationResponse>> listMine(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.listMyConversations(actor));
    }

    @PostMapping("/group")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create a GROUP conversation among current interns (staff/moderator managed)")
    public ResponseEntity<ConversationResponse> createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messagingService.createGroupConversation(request.title(), request.memberUserIds(), actor));
    }

    @GetMapping("/{conversationId}")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Get a conversation I belong to")
    public ResponseEntity<ConversationResponse> getConversation(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.getConversation(conversationId, actor));
    }

    @PostMapping("/{conversationId}/members")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Add a member to a GROUP conversation (moderator/staff only)")
    public ResponseEntity<ConversationResponse> addMember(
            @PathVariable UUID conversationId,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.addMember(conversationId, request.userId(), actor));
    }

    @PostMapping("/{conversationId}/leave")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Leave a conversation (soft-leave, history preserved)")
    public ResponseEntity<Void> leave(@PathVariable UUID conversationId,
                                      @AuthenticationPrincipal UserPrincipal actor) {
        messagingService.leaveConversation(conversationId, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversationId}/messages")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Paginated history ordered by sequenceNumber (cursor-based; fetching acks delivery)")
    public ResponseEntity<Page<MessageResponse>> history(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long cursor,
            @PageableDefault(size = 30, sort = "sequenceNumber", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.getHistory(conversationId, cursor, pageable, actor));
    }

    @PostMapping("/{conversationId}/messages")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Send a message (REST fallback; real-time clients use STOMP)")
    public ResponseEntity<MessageResponse> send(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        MessageResponse saved = messagingService.sendMessage(conversationId, request.content(), actor);
        broadcast(conversationId, saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping(value = "/{conversationId}/messages/with-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Send a message with a file attachment (PDF/JPEG/PNG, max 10 MB, malware hook)")
    public ResponseEntity<MessageResponse> sendWithAttachment(
            @PathVariable UUID conversationId,
            @RequestParam("content") String content,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserPrincipal actor) {
        MessageResponse saved = messagingService.sendMessageWithAttachment(conversationId, content, file, actor);
        broadcast(conversationId, saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/messages/{messageId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Edit my message (sender only; membership re-checked in service)")
    public ResponseEntity<MessageResponse> edit(
            @PathVariable UUID messageId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        MessageResponse updated = messagingService.editMessage(messageId, request.content(), actor);
        broadcast(updated.conversationId(), updated);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/messages/{messageId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Soft-delete a message (content redacted, history kept)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID messageId,
            @AuthenticationPrincipal UserPrincipal actor) {
        MessageResponse redacted = messagingService.deleteMessage(messageId, actor);
        broadcast(redacted.conversationId(), redacted);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/delivered")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Acknowledge delivery up to a sequenceNumber (SENT → DELIVERED)")
    public ResponseEntity<List<MessageResponse>> markDelivered(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MarkDeliveredRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        List<MessageResponse> changed =
                messagingService.markDelivered(conversationId, request.upToSequenceNumber(), actor);
        broadcastAll(conversationId, changed);
        return ResponseEntity.ok(changed);
    }

    @PostMapping("/{conversationId}/read")
    @PreAuthorize("@authz.isOwnConversation(#conversationId)")
    @Operation(summary = "Mark read up to a sequenceNumber (sequence watermark; advances delivery too)")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID conversationId,
            @Valid @RequestBody MarkReadRequest request,
            @AuthenticationPrincipal UserPrincipal actor) {
        List<MessageResponse> changed =
                messagingService.markRead(conversationId, request.upToSequenceNumber(), actor);
        broadcastAll(conversationId, changed);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unread/counts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Unread-count aggregation per conversation for the current user (sequence-based, own messages excluded)")
    public ResponseEntity<List<UnreadCountResponse>> unreadCounts(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.getUnreadCounts(actor));
    }

    @GetMapping("/messages/{messageId}/attachments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List attachments of a message (members only; hidden once deleted)")
    public ResponseEntity<List<MessageResponse.AttachmentResponse>> listAttachments(
            @PathVariable UUID messageId,
            @AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(messagingService.listAttachments(messageId, actor));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Download a message attachment (members only, audited, streamed)")
    public ResponseEntity<InputStreamResource> downloadAttachment(
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal UserPrincipal actor,
            HttpServletRequest request) {
        MessagingService.AttachmentDownload download =
                messagingService.downloadAttachment(attachmentId, actor, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .body(new InputStreamResource(download.inputStream()));
    }

    private void broadcast(UUID conversationId, MessageResponse message) {
        try {
            messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, message);
        } catch (Exception e) {
            // Best-effort: persistence is authoritative; a broker hiccup must
            // never fail the REST call. Clients converge via history fetch.
            log.warn("WS broadcast failed for conv {}: {}", conversationId, e.getMessage());
        }
    }

    private void broadcastAll(UUID conversationId, List<MessageResponse> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MessageResponse message : messages) {
            broadcast(conversationId, message);
        }
    }
}
