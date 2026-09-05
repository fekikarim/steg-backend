package tn.steg.backend.messaging.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.steg.backend.audit.application.AuditService;
import tn.steg.backend.candidate.domain.repository.CandidateRepository;
import tn.steg.backend.common.domain.exception.BusinessRuleException;
import tn.steg.backend.common.domain.exception.ResourceNotFoundException;
import tn.steg.backend.common.domain.model.UserPrincipal;
import tn.steg.backend.document.domain.model.DocumentType;
import tn.steg.backend.document.domain.model.FileAsset;
import tn.steg.backend.document.domain.repository.FileAssetRepository;
import tn.steg.backend.document.domain.service.DocumentValidationService;
import tn.steg.backend.document.domain.service.FileStorageService;
import tn.steg.backend.document.domain.service.MalwareScanner;
import tn.steg.backend.iam.domain.model.User;
import tn.steg.backend.iam.domain.repository.UserRepository;
import tn.steg.backend.internship.domain.model.Internship;
import tn.steg.backend.internship.domain.model.InternshipStatus;
import tn.steg.backend.internship.domain.repository.InternshipRepository;
import tn.steg.backend.organization.domain.repository.EmployeeRepository;
import tn.steg.backend.messaging.application.dto.ConversationResponse;
import tn.steg.backend.messaging.application.dto.MessageResponse;
import tn.steg.backend.messaging.application.dto.UnreadCountResponse;
import tn.steg.backend.messaging.domain.model.Conversation;
import tn.steg.backend.messaging.domain.model.ConversationMember;
import tn.steg.backend.messaging.domain.model.ConversationMemberRole;
import tn.steg.backend.messaging.domain.model.ConversationType;
import tn.steg.backend.messaging.domain.model.Message;
import tn.steg.backend.messaging.domain.model.MessageAttachment;
import tn.steg.backend.messaging.domain.model.MessageStatus;
import tn.steg.backend.messaging.domain.repository.ConversationMemberRepository;
import tn.steg.backend.messaging.domain.repository.ConversationRepository;
import tn.steg.backend.messaging.domain.repository.MessageAttachmentRepository;
import tn.steg.backend.messaging.domain.repository.MessageRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application service for the Messaging module (A9).
 *
 * <p>Backend-authoritative on every read/write:
 * each operation verifies the caller is an <em>active</em>
 * ({@code leftAt IS NULL}) member of the target conversation.
 * Conversation ids from clients are never trusted alone.
 *
 * <p>Ordering uses a monotonic {@code sequenceNumber} per conversation,
 * allocated under a pessimistic lock on the parent conversation row
 * ({@code SELECT ... FOR UPDATE}) plus a defensive DB unique constraint
 * {@code uq_messages_conv_seq} (V19).
 *
 * <p>Message lifecycle (single {@code status} column):
 * <pre>
 *   SENT      — persisted by the server, not yet delivered to any recipient.
 *   DELIVERED — observed by at least one non-sender active member
 *               (explicit delivery ack or history fetch).
 *   READ      — every non-sender active member has a read watermark
 *               ({@code lastReadSequenceNumber}) at or beyond this message.
 *   EDITED    — content edited by the sender (terminal display state;
 *               delivery/read evidence is preserved in member watermarks).
 *   DELETED   — soft-deleted (content redacted, row + attachments kept
 *               for audit; attachment bytes are retained, only hidden).
 * </pre>
 * Unread counts are sequence-based: messages with
 * {@code sequenceNumber &gt; member.lastReadSequenceNumber} sent by someone
 * else. {@code lastReadAt} is a wall-clock audit marker only.
 *
 * <p>Messages are never hard-deleted: delete sets {@code deletedAt} +
 * status {@code DELETED} and redacts content on read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagingService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final InternshipRepository internshipRepository;
    private final CandidateRepository candidateRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final FileAssetRepository fileAssetRepository;
    private final FileStorageService fileStorageService;
    private final DocumentValidationService documentValidationService;
    private final MalwareScanner malwareScanner;
    private final AuditService auditService;

    /**
     * GROUP access policy once an internship ends ({@code retain} default |
     * {@code revoke}). PRIVATE threads are unaffected in both modes.
     * TODO — STEG VALIDATION REQUIRED: confirm the final value with STEG policy.
     */
    @Value("${steg.messaging.groups.completed-intern-access:retain}")
    private String completedInternAccess;

    // -------------------------------------------------------------------------
    // Private threads (Intern <-> ACTIVE supervisor)
    // -------------------------------------------------------------------------

    /**
     * Creates or reuses the PRIVATE thread for an internship and reconciles
     * membership to exactly the current intern + current supervisor.
     * Called from {@code InternshipService.assign()} when an assignment becomes ACTIVE.
     */
    @Transactional
    public Conversation ensurePrivateThread(Internship internship, User internUser, User supervisorUser) {
        if (internship == null || internUser == null || supervisorUser == null) {
            throw new BusinessRuleException("INVALID_THREAD_PARTICIPANTS",
                    "Private thread requires an internship, an intern user and a supervisor user.");
        }

        Conversation conversation = conversationRepository.findPrivateByInternshipId(internship.getId())
                .orElseGet(() -> {
                    Conversation created = new Conversation(
                            ConversationType.PRIVATE,
                            "Internship " + internship.getReference() + " — private thread",
                            internship);
                    Conversation saved = conversationRepository.save(created);
                    auditService.log("CONVERSATION_CREATED_PRIVATE", "Conversation", saved.getId(),
                            null, null, supervisorUser.getId(), null);
                    log.info("Private conversation created: id={} internship={}", saved.getId(), internship.getId());
                    return saved;
                });

        ensureActiveMember(conversation, internUser, ConversationMemberRole.MEMBER);
        ensureActiveMember(conversation, supervisorUser, ConversationMemberRole.MODERATOR);

        // Enforce exactly-2 invariant: soft-remove any other active member
        // (e.g. a previous supervisor after reassignment) so stale supervisors
        // cannot read new messages, while history is preserved.
        Set<UUID> expected = Set.of(internUser.getId(), supervisorUser.getId());
        for (ConversationMember active : memberRepository.findActiveByConversationId(conversation.getId())) {
            if (!expected.contains(active.getUser().getId())) {
                active.setLeftAt(Instant.now());
                memberRepository.save(active);
                auditService.log("CONVERSATION_MEMBER_REMOVED", "Conversation", conversation.getId(),
                        null, null, supervisorUser.getId(), null);
                log.info("Stale member {} removed from private conversation {}", active.getUser().getId(), conversation.getId());
            }
        }

        return conversation;
    }

    private void ensureActiveMember(Conversation conversation, User user, ConversationMemberRole roleIfNew) {
        var existing = memberRepository.findByConversationIdAndUserId(conversation.getId(), user.getId());
        if (existing.isPresent()) {
            ConversationMember member = existing.get();
            if (member.getLeftAt() != null) {
                member.setLeftAt(null);
                member.setJoinedAt(Instant.now());
                // Watermarks are intentionally retained across rejoin so messages
                // sent while away still count as unread (no history loss).
                memberRepository.save(member);
                auditService.log("CONVERSATION_MEMBER_REJOINED", "Conversation", conversation.getId(),
                        null, null, user.getId(), null);
            }
        } else {
            ConversationMember member = new ConversationMember(conversation, user, roleIfNew);
            memberRepository.save(member);
            auditService.log("CONVERSATION_MEMBER_ADDED", "Conversation", conversation.getId(),
                    null, null, user.getId(), null);
        }
    }

    // -------------------------------------------------------------------------
    // Group conversations (current interns)
    // -------------------------------------------------------------------------

    @Transactional
    public ConversationResponse createGroupConversation(String title, List<UUID> memberUserIds, UserPrincipal actor) {
        if (title == null || title.isBlank()) {
            throw new BusinessRuleException("INVALID_GROUP_TITLE", "Group title must not be blank.");
        }

        User creator = findUserOrThrow(actor.getId());
        if (isRevokePolicy() && !hasGroupStanding(actor.getId())) {
            throw new BusinessRuleException("NOT_CURRENT_INTERN",
                    "GROUP conversations are scoped to users with a currently ACTIVE internship.");
        }

        Conversation conversation = new Conversation(ConversationType.GROUP, title.strip(), null);
        conversation = conversationRepository.save(conversation);
        memberRepository.save(new ConversationMember(conversation, creator, ConversationMemberRole.MODERATOR));

        Set<UUID> requested = memberUserIds != null ? new HashSet<>(memberUserIds) : new HashSet<>();
        requested.remove(creator.getId());
        for (UUID memberId : requested) {
            User member = findUserOrThrow(memberId);
            assertIsCurrentIntern(memberId);
            memberRepository.save(new ConversationMember(conversation, member, ConversationMemberRole.MEMBER));
        }

        auditService.log("CONVERSATION_CREATED_GROUP", "Conversation", conversation.getId(),
                null, null, actor.getId(), null);
        log.info("Group conversation created: id={} title={} actor={}", conversation.getId(), title, actor.getId());
        return toConversationResponse(conversation, actor.getId());
    }

    @Transactional
    public ConversationResponse addMember(UUID conversationId, UUID userId, UserPrincipal actor) {
        Conversation conversation = findConversationOrThrow(conversationId);
        assertGroupOnly(conversation);
        assertCanManageMembers(conversation, actor);

        if (memberRepository.findByConversationIdAndUserId(conversationId, userId).isPresent()) {
            ConversationMember existing = memberRepository.findByConversationIdAndUserId(conversationId, userId).orElseThrow();
            if (existing.getLeftAt() == null) {
                throw new BusinessRuleException("ALREADY_MEMBER", "User is already an active member of this conversation.");
            }
            // Rejoin: watermarks retained so messages sent while away stay unread.
            if (isRevokePolicy() && !hasGroupStanding(existing.getUser().getId())) {
                throw new BusinessRuleException("NOT_CURRENT_INTERN",
                        "GROUP conversations are scoped to users with a currently ACTIVE internship.");
            }
            existing.setLeftAt(null);
            existing.setJoinedAt(Instant.now());
            memberRepository.save(existing);
            auditService.log("CONVERSATION_MEMBER_REJOINED", "Conversation", conversationId, null, null, actor.getId(), null);
        } else {
            assertIsCurrentIntern(userId);
            User user = findUserOrThrow(userId);
            memberRepository.save(new ConversationMember(conversation, user, ConversationMemberRole.MEMBER));
            auditService.log("CONVERSATION_MEMBER_ADDED", "Conversation", conversationId, null, null, actor.getId(), null);
        }

        return toConversationResponse(conversation, actor.getId());
    }

    @Transactional
    public void leaveConversation(UUID conversationId, UserPrincipal actor) {
        ConversationMember membership = memberRepository
                .findActiveByConversationIdAndUserId(conversationId, actor.getId())
                .orElseThrow(() -> new AccessDeniedException("You are not an active member of this conversation."));
        membership.setLeftAt(Instant.now());
        memberRepository.save(membership);
        auditService.log("CONVERSATION_MEMBER_LEFT", "Conversation", conversationId, null, null, actor.getId(), null);
        log.info("Member {} left conversation {}", actor.getId(), conversationId);
    }

    // -------------------------------------------------------------------------
    // Reads (all membership-guarded)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ConversationResponse> listMyConversations(UserPrincipal actor) {
        List<ConversationMember> memberships = memberRepository.findActiveByUserId(actor.getId());
        boolean revoke = isRevokePolicy() && !hasGroupStanding(actor.getId());
        List<ConversationResponse> result = new ArrayList<>();
        for (ConversationMember membership : memberships) {
            Conversation conversation = conversationRepository.findById(membership.getConversation().getId())
                    .orElse(null);
            if (conversation == null) {
                continue;
            }
            if (revoke && conversation.getType() == ConversationType.GROUP) {
                continue;
            }
            result.add(toConversationResponse(conversation, actor.getId()));
        }
        return result;
    }

    /**
     * Visibility probe used by the STOMP SUBSCRIBE guard: the caller may
     * subscribe to {@code /topic/conversations/{id}} only when actively
     * entitled (same rule as reads, including the revoke-mode GROUP policy).
     * Returns false for unknown ids without distinguishing them.
     */
    @Transactional(readOnly = true)
    public boolean isConversationVisible(UUID conversationId, UUID userId) {
        var conversation = conversationRepository.findById(conversationId);
        if (conversation.isEmpty()) {
            return false;
        }
        if (memberRepository.findActiveByConversationIdAndUserId(conversationId, userId).isEmpty()) {
            return false;
        }
        return conversation.get().getType() != ConversationType.GROUP
                || !isRevokePolicy()
                || hasGroupStanding(userId);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID conversationId, UserPrincipal actor) {
        assertActiveMembership(conversationId, actor.getId());
        Conversation conversation = findConversationOrThrow(conversationId);
        return toConversationResponse(conversation, actor.getId());
    }

    /**
     * Paginated history ordered by {@code sequenceNumber}.
     * Fetching acts as an implicit delivery acknowledgement for the caller:
     * the caller's delivery watermark advances to the highest sequence in the
     * returned page and eligible {@code SENT} messages become {@code DELIVERED}.
     * Explicit acks remain available via {@code markDelivered} for clients that
     * receive messages over WebSocket without fetching history.
     */
    @Transactional
    public Page<MessageResponse> getHistory(UUID conversationId, Long cursorExclusive, Pageable pageable, UserPrincipal actor) {
        assertActiveMembership(conversationId, actor.getId());
        findConversationOrThrow(conversationId);

        Page<Message> page;
        if (cursorExclusive != null) {
            page = messageRepository.findByConversationIdAndSequenceNumberLessThanEqual(
                    conversationId, cursorExclusive, pageable);
        } else {
            page = messageRepository.findByConversationId(conversationId, pageable);
        }

        // Implicit delivery ack (not audited per message to avoid audit spam on
        // every history scroll; explicit markDelivered calls are audited).
        if (!page.getContent().isEmpty()) {
            long maxInPage = page.getContent().stream()
                    .mapToLong(Message::getSequenceNumber)
                    .max().orElse(0L);
            advanceDeliveredWatermark(conversationId, maxInPage, actor.getId());
        }

        final Page<Message> result = page;
        return result.map(m -> MessageResponse.from(m, toAttachmentResponses(m.getId())));
    }

    @Transactional(readOnly = true)
    public List<UnreadCountResponse> getUnreadCounts(UserPrincipal actor) {
        List<ConversationMember> memberships = memberRepository.findActiveByUserId(actor.getId());
        boolean revoke = isRevokePolicy() && !hasGroupStanding(actor.getId());
        List<UnreadCountResponse> result = new ArrayList<>();
        for (ConversationMember membership : memberships) {
            UUID conversationId = membership.getConversation().getId();
            if (revoke) {
                var conversation = conversationRepository.findById(conversationId).orElse(null);
                if (conversation != null && conversation.getType() == ConversationType.GROUP) {
                    continue;
                }
            }
            Long lastSeq = messageRepository.findMaxSequenceNumber(conversationId).orElse(0L);
            long unread = computeUnread(conversationId, membership, actor.getId());
            result.add(new UnreadCountResponse(conversationId, unread, lastSeq));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Writes (all membership-guarded, ordered, audited)
    // -------------------------------------------------------------------------

    @Transactional
    public MessageResponse sendMessage(UUID conversationId, String content, UserPrincipal actor) {
        if (content == null || content.isBlank()) {
            throw new BusinessRuleException("EMPTY_MESSAGE", "Message content must not be blank.");
        }
        if (content.length() > 4000) {
            throw new BusinessRuleException("MESSAGE_TOO_LONG", "Message content must not exceed 4000 characters.");
        }

        assertActiveMembership(conversationId, actor.getId());

        // Serialize sequence allocation on the parent conversation row.
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));
        Long nextSeq = messageRepository.findMaxSequenceNumber(conversationId).map(m -> m + 1).orElse(1L);

        User sender = findUserOrThrow(actor.getId());
        Message message = new Message(conversation, sender, content.strip(), nextSeq);
        message = messageRepository.save(message);

        auditService.log("MESSAGE_SENT", "Message", message.getId(), null, null, actor.getId(), null);
        log.debug("Message sent: id={} conv={} seq={} sender={}", message.getId(), conversationId, nextSeq, actor.getId());
        return MessageResponse.from(message, List.of());
    }

    @Transactional
    public MessageResponse sendMessageWithAttachment(
            UUID conversationId, String content, MultipartFile file, UserPrincipal actor) {
        // Same transaction: BusinessRuleException (runtime) on attachment
        // validation/storage failure rolls back the message row as well,
        // so no orphan SENT message is left behind.
        MessageResponse sent = sendMessage(conversationId, content, actor);
        if (file == null || file.isEmpty()) {
            return sent;
        }

        Message message = messageRepository.findById(sent.id())
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + sent.id()));
        FileAsset asset = storeChatAttachment(file, actor);
        attachmentRepository.save(new MessageAttachment(message, asset));

        auditService.log("MESSAGE_ATTACHMENT_ADDED", "Message", message.getId(), null, null, actor.getId(), null);
        return MessageResponse.from(message, toAttachmentResponses(message.getId()));
    }

    @Transactional
    public MessageResponse editMessage(UUID messageId, String newContent, UserPrincipal actor) {
        if (newContent == null || newContent.isBlank()) {
            throw new BusinessRuleException("EMPTY_MESSAGE", "Message content must not be blank.");
        }
        if (newContent.length() > 4000) {
            throw new BusinessRuleException("MESSAGE_TOO_LONG", "Message content must not exceed 4000 characters.");
        }

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        assertActiveMembership(message.getConversation().getId(), actor.getId());

        if (!message.getSender().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the sender may edit this message.");
        }
        if (message.getDeletedAt() != null) {
            throw new BusinessRuleException("MESSAGE_DELETED", "A deleted message cannot be edited.");
        }

        message.setContent(newContent.strip());
        message.setEditedAt(Instant.now());
        if (message.getStatus() == MessageStatus.SENT || message.getStatus() == MessageStatus.DELIVERED
                || message.getStatus() == MessageStatus.READ) {
            // EDITED is a terminal display state; delivery/read evidence is
            // preserved in member watermarks (lastDelivered/ReadSequenceNumber).
            message.setStatus(MessageStatus.EDITED);
        }
        message = messageRepository.save(message);

        auditService.log("MESSAGE_EDITED", "Message", messageId, null, null, actor.getId(), null);
        return MessageResponse.from(message, toAttachmentResponses(message.getId()));
    }

    @Transactional
    public MessageResponse deleteMessage(UUID messageId, UserPrincipal actor) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        assertActiveMembership(message.getConversation().getId(), actor.getId());

        boolean isSender = message.getSender().getId().equals(actor.getId());
        boolean isStaff = actor.hasRole("ADMIN") || actor.hasRole("HR");
        if (!isSender && !isStaff) {
            throw new AccessDeniedException("Only the sender or staff may delete this message.");
        }
        if (message.getDeletedAt() != null) {
            return MessageResponse.from(message, List.of());
        }

        message.setDeletedAt(Instant.now());
        message.setStatus(MessageStatus.DELETED);
        message = messageRepository.save(message);

        auditService.log("MESSAGE_DELETED", "Message", messageId, null, null, actor.getId(), null);
        log.info("Message soft-deleted: id={} actor={}", messageId, actor.getId());
        return MessageResponse.from(message, List.of());
    }

    /**
     * Explicit delivery acknowledgement up to a sequence number.
     * Transitions eligible {@code SENT} messages (sent by someone else) to
     * {@code DELIVERED}. Monotonic: rewinding a watermark is rejected.
     *
     * @return messages whose status changed (for WebSocket broadcast).
     */
    @Transactional
    public List<MessageResponse> markDelivered(UUID conversationId, Long upToSequenceNumber, UserPrincipal actor) {
        if (upToSequenceNumber == null || upToSequenceNumber <= 0) {
            throw new BusinessRuleException("INVALID_SEQUENCE", "upToSequenceNumber must be positive.");
        }
        ConversationMember membership = memberRepository
                .findActiveByConversationIdAndUserId(conversationId, actor.getId())
                .orElseThrow(() -> new AccessDeniedException("You are not an active member of this conversation."));

        Long maxSeq = messageRepository.findMaxSequenceNumber(conversationId).orElse(0L);
        if (upToSequenceNumber > maxSeq) {
            throw new BusinessRuleException("SEQUENCE_OUT_OF_RANGE",
                    "upToSequenceNumber exceeds the latest message sequence (" + maxSeq + ").");
        }
        Long current = membership.getLastDeliveredSequenceNumber();
        if (current != null && upToSequenceNumber < current) {
            throw new BusinessRuleException("SEQUENCE_REWIND",
                    "Cannot move the delivery watermark backwards (current: " + current + ").");
        }

        membership.setLastDeliveredSequenceNumber(upToSequenceNumber);
        memberRepository.save(membership);

        List<Message> candidates = messageRepository
                .findByConversationIdAndSequenceNumberLessThanEqualOrderBySequenceNumberAsc(conversationId, upToSequenceNumber);
        List<MessageResponse> changed = new ArrayList<>();
        for (Message message : candidates) {
            if (message.getStatus() == MessageStatus.SENT
                    && !message.getSender().getId().equals(actor.getId())) {
                message.setStatus(MessageStatus.DELIVERED);
                messageRepository.save(message);
                changed.add(MessageResponse.from(message, toAttachmentResponses(message.getId())));
            }
        }

        auditService.log("MESSAGE_DELIVERED", "Conversation", conversationId, null, null, actor.getId(), null);
        return changed;
    }

    /**
     * Read acknowledgement up to a sequence number.
     * Advances both watermarks (reading implies delivery) and promotes
     * messages to {@code READ} once every non-sender active member has read
     * at or beyond them.
     *
     * @return messages whose status changed (for WebSocket broadcast).
     */
    @Transactional
    public List<MessageResponse> markRead(UUID conversationId, Long upToSequenceNumber, UserPrincipal actor) {
        if (upToSequenceNumber == null || upToSequenceNumber <= 0) {
            throw new BusinessRuleException("INVALID_SEQUENCE", "upToSequenceNumber must be positive.");
        }
        ConversationMember membership = memberRepository
                .findActiveByConversationIdAndUserId(conversationId, actor.getId())
                .orElseThrow(() -> new AccessDeniedException("You are not an active member of this conversation."));

        Long maxSeq = messageRepository.findMaxSequenceNumber(conversationId).orElse(0L);
        if (upToSequenceNumber > maxSeq) {
            throw new BusinessRuleException("SEQUENCE_OUT_OF_RANGE",
                    "upToSequenceNumber exceeds the latest message sequence (" + maxSeq + ").");
        }
        Long current = membership.getLastReadSequenceNumber();
        if (current != null && upToSequenceNumber < current) {
            throw new BusinessRuleException("SEQUENCE_REWIND",
                    "Cannot move the read watermark backwards (current: " + current + ").");
        }

        membership.setLastReadAt(Instant.now());
        membership.setLastReadSequenceNumber(upToSequenceNumber);
        Long delivered = membership.getLastDeliveredSequenceNumber();
        if (delivered == null || delivered < upToSequenceNumber) {
            membership.setLastDeliveredSequenceNumber(upToSequenceNumber);
        }
        memberRepository.save(membership);

        // Messages this reader now covers become at least DELIVERED; those read
        // by every non-sender active member become READ. Only genuine status
        // transitions are reported for broadcast (no duplicate noise).
        List<Message> candidates = messageRepository
                .findByConversationIdAndSequenceNumberLessThanEqualOrderBySequenceNumberAsc(conversationId, upToSequenceNumber);
        List<ConversationMember> activeMembers = memberRepository.findActiveByConversationId(conversationId);
        List<MessageResponse> changed = new ArrayList<>();
        for (Message message : candidates) {
            if (message.getStatus() == MessageStatus.EDITED || message.getStatus() == MessageStatus.DELETED) {
                continue;
            }
            MessageStatus before = message.getStatus();
            if (message.getStatus() == MessageStatus.SENT
                    && !message.getSender().getId().equals(actor.getId())) {
                message.setStatus(MessageStatus.DELIVERED);
            }
            if ((message.getStatus() == MessageStatus.DELIVERED || message.getStatus() == MessageStatus.SENT)
                    && isReadByAllRecipients(message, activeMembers)) {
                message.setStatus(MessageStatus.READ);
            }
            if (message.getStatus() != before) {
                messageRepository.save(message);
                changed.add(MessageResponse.from(message, toAttachmentResponses(message.getId())));
            }
        }

        auditService.log("MESSAGE_READ", "Conversation", conversationId, null, null, actor.getId(), null);
        return changed;
    }

    // -------------------------------------------------------------------------
    // Attachments (membership-guarded, audited)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MessageResponse.AttachmentResponse> listAttachments(UUID messageId, UserPrincipal actor) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found: " + messageId));
        assertActiveMembership(message.getConversation().getId(), actor.getId());
        if (message.getDeletedAt() != null) {
            return List.of();
        }
        return toAttachmentResponses(messageId);
    }

    public record AttachmentDownload(InputStream inputStream, String fileName, String mimeType, long size) {}

    @Transactional
    public AttachmentDownload downloadAttachment(UUID attachmentId, UserPrincipal actor, String ipAddress) {
        MessageAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + attachmentId));
        Message message = attachment.getMessage();
        assertActiveMembership(message.getConversation().getId(), actor.getId());
        if (message.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Attachment not found: " + attachmentId);
        }

        FileAsset asset = attachment.getFile();
        auditService.log("MESSAGE_ATTACHMENT_DOWNLOADED", "MessageAttachment", attachmentId,
                null, null, actor.getId(), ipAddress);
        InputStream stream = fileStorageService.getInputStream(asset.getStorageKey());
        return new AttachmentDownload(stream, asset.getOriginalFileName(), asset.getMimeType(), asset.getSize());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Conversation findConversationOrThrow(UUID id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + id));
    }

    private User findUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private void assertActiveMembership(UUID conversationId, UUID userId) {
        // Existence is intentionally hidden: unknown ids and non-member ids
        // both surface as 403/404 without leaking which conversations exist.
        var conversation = conversationRepository.findById(conversationId);
        boolean isMember = conversation.isPresent()
                && memberRepository.findActiveByConversationIdAndUserId(conversationId, userId).isPresent();
        if (conversation.isEmpty() || !isMember) {
            throw new AccessDeniedException("You are not an active member of this conversation.");
        }
        // Completed-intern GROUP policy (revoke mode only; PRIVATE unaffected).
        if (isRevokePolicy()
                && conversation.get().getType() == ConversationType.GROUP
                && !hasGroupStanding(userId)) {
            throw new AccessDeniedException("You are not an active member of this conversation.");
        }
    }

    private boolean isRevokePolicy() {
        return "revoke".equalsIgnoreCase(completedInternAccess);
    }

    /**
     * Current GROUP standing: an ACTIVE internship, an employee record, or a
     * staff platform role. Used only when the revoke policy is enabled.
     */
    private boolean hasGroupStanding(UUID userId) {
        if (!internshipRepository.findByCandidateUserIdAndStatus(userId, InternshipStatus.ACTIVE).isEmpty()) {
            return true;
        }
        if (employeeRepository.findByUserId(userId).isPresent()) {
            return true;
        }
        return userRepository.findById(userId)
                .map(user -> user.getAssignedRoles().stream()
                        .map(role -> role.getCode() == null ? ""
                                : role.getCode().toUpperCase().replaceFirst("^ROLE_", ""))
                        .anyMatch(code -> code.equals("ADMIN") || code.equals("HR") || code.equals("DIRECTOR")
                                || code.equals("SUPERVISOR") || code.equals("FINANCE")))
                .orElse(false);
    }

    private void assertGroupOnly(Conversation conversation) {
        if (conversation.getType() != ConversationType.GROUP) {
            throw new BusinessRuleException("NOT_GROUP_CONVERSATION",
                    "Membership can only be managed on GROUP conversations.");
        }
    }

    private void assertCanManageMembers(Conversation conversation, UserPrincipal actor) {
        if (actor.hasRole("ADMIN") || actor.hasRole("HR") || actor.hasRole("DIRECTOR")) {
            return;
        }
        var membership = memberRepository.findActiveByConversationIdAndUserId(conversation.getId(), actor.getId())
                .orElseThrow(() -> new AccessDeniedException("You are not an active member of this conversation."));
        if (membership.getRole() != ConversationMemberRole.MODERATOR
                && membership.getRole() != ConversationMemberRole.OWNER) {
            throw new AccessDeniedException("Only a group moderator/owner or staff may manage members.");
        }
    }

    private void assertIsCurrentIntern(UUID userId) {
        List<Internship> active = internshipRepository.findByCandidateUserIdAndStatus(userId, InternshipStatus.ACTIVE);
        if (active.isEmpty()) {
            throw new BusinessRuleException("NOT_CURRENT_INTERN",
                    "GROUP conversations are scoped to users with a currently ACTIVE internship.");
        }
    }

    /**
     * Sequence-based unread count: messages after the member's read watermark
     * ({@code lastReadSequenceNumber}, NULL = 0) sent by someone else.
     */
    private long computeUnread(UUID conversationId, ConversationMember membership, UUID viewerId) {
        Long watermark = membership.getLastReadSequenceNumber();
        long after = watermark != null ? watermark : 0L;
        return messageRepository.countUnread(conversationId, after, viewerId);
    }

    private boolean isReadByAllRecipients(Message message, List<ConversationMember> activeMembers) {
        for (ConversationMember member : activeMembers) {
            if (member.getUser().getId().equals(message.getSender().getId())) {
                continue;
            }
            Long watermark = member.getLastReadSequenceNumber();
            if (watermark == null || watermark < message.getSequenceNumber()) {
                return false;
            }
        }
        return true;
    }

    private void advanceDeliveredWatermark(UUID conversationId, long upToSeq, UUID userId) {
        var membershipOpt = memberRepository.findActiveByConversationIdAndUserId(conversationId, userId);
        if (membershipOpt.isEmpty()) {
            return;
        }
        ConversationMember membership = membershipOpt.get();
        Long current = membership.getLastDeliveredSequenceNumber();
        if (current != null && current >= upToSeq) {
            // Still promote already-fetched SENT messages (first fetch wins).
        } else {
            membership.setLastDeliveredSequenceNumber(upToSeq);
            memberRepository.save(membership);
        }
        List<Message> candidates = messageRepository
                .findByConversationIdAndSequenceNumberLessThanEqualOrderBySequenceNumberAsc(conversationId, upToSeq);
        for (Message message : candidates) {
            if (message.getStatus() == MessageStatus.SENT
                    && !message.getSender().getId().equals(userId)) {
                message.setStatus(MessageStatus.DELIVERED);
                messageRepository.save(message);
            }
        }
    }

    private ConversationResponse toConversationResponse(Conversation conversation, UUID viewerId) {
        List<ConversationResponse.MemberResponse> members = memberRepository
                .findActiveByConversationId(conversation.getId()).stream()
                .map(m -> new ConversationResponse.MemberResponse(
                        m.getUser().getId(), m.getRole().name(), m.getJoinedAt(), m.getLastReadAt(),
                        m.getLastReadSequenceNumber(), m.getLastDeliveredSequenceNumber()))
                .toList();
        Long lastSeq = messageRepository.findMaxSequenceNumber(conversation.getId()).orElse(0L);
        Long unread = 0L;
        var viewerMembership = memberRepository.findActiveByConversationIdAndUserId(conversation.getId(), viewerId);
        if (viewerMembership.isPresent()) {
            unread = computeUnread(conversation.getId(), viewerMembership.get(), viewerId);
        }
        return ConversationResponse.from(conversation, members, lastSeq, unread);
    }

    private List<MessageResponse.AttachmentResponse> toAttachmentResponses(UUID messageId) {
        return attachmentRepository.findByMessageId(messageId).stream()
                .map(a -> new MessageResponse.AttachmentResponse(
                        a.getId(),
                        a.getFile().getId(),
                        a.getFile().getOriginalFileName(),
                        a.getFile().getMimeType(),
                        a.getFile().getSize()))
                .toList();
    }

    /**
     * Stores a chat attachment through the same validation pipeline as Phase A6
     * (size caps, Tika MIME inspection, spoof detection, malware hook).
     * Chat attachments reuse {@code DocumentType.OTHER} limits: PDF/JPEG/PNG
     * only, 10 MB default. Anything else (executables, scripts, archives,
     * oversized files) is rejected before any bytes reach storage.
     */
    private FileAsset storeChatAttachment(MultipartFile file, UserPrincipal actor) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("FILE_READ_ERROR", "Could not read attachment content: " + e.getMessage());
        }

        DocumentValidationService.ValidationResult validation =
                documentValidationService.validate(bytes, file.getOriginalFilename(), file.getContentType(), DocumentType.OTHER);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            if (!malwareScanner.isClean(is, file.getOriginalFilename())) {
                throw new BusinessRuleException("MALWARE_DETECTED", "Malware or suspicious content detected in attachment.");
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
}
