package com.hajacheck.counsel.service;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.counsel.dto.CounselAttachmentResponse;
import com.hajacheck.counsel.entity.ChatMessage;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.repository.ChatMessageRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.counsel.support.CounselAttachmentPolicy;
import com.hajacheck.core.media.config.MediaUploadProperties;
import com.hajacheck.core.media.support.ImageSignatureValidator;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 상담 채팅 이미지 첨부 업로드/서빙(FR-7, #20/HAJA-33). Media 도메인({@code inspectionId} 강결합)을
 * 재사용하지 않고, {@link FileStorageService}(저장 추상화)와 {@link ImageSignatureValidator}(매직바이트 검증)
 * 유틸만 재사용한다. 업로드 응답의 저장키는 프론트가 WS SEND 바디에 실어 보내고, 그때
 * {@code CounselChatService}가 {@code chat_messages}에 직접 스냅샷한다(Media 테이블에 행을 만들지 않음).
 *
 * <p>업로드/서빙 모두 티켓 당사자(사용자 본인/담당 상담원)만 허용한다 — 비당사자·미존재는 열거 방지 통일 응답.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CounselAttachmentService {

    private final CounselTicketRepository ticketRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final FileStorageService fileStorage;
    private final MediaUploadProperties mediaUploadProperties;

    /**
     * 이미지 1건 업로드 — 당사자 검증 → 매직바이트 검증(선언 Content-Type 불신) → 저장 → 저장키 반환.
     * 저장키는 원본 URL 이 아니라 {@code FileStorageService} storageKey 로, 서빙은 별도 인가 엔드포인트로만 한다.
     */
    public CounselAttachmentResponse upload(Long ticketId, Long requesterId, MultipartFile file) {
        loadParticipantTicket(ticketId, requesterId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_REQUIRED);
        }
        ImageSignatureValidator.validate(file);
        StoredFile stored = fileStorage.store(file, CounselAttachmentPolicy.CATEGORY,
                mediaUploadProperties.getAllowedContentTypes(), mediaUploadProperties.getMaxSizeBytes());
        return new CounselAttachmentResponse(
                stored.storageKey(), CounselAttachmentPolicy.mimeTypeOf(stored.storageKey()));
    }

    /**
     * 첨부 서빙 — 당사자 검증 → 메시지가 이 티켓 세션 소속인지 확인 → 저장키로 바이트 읽기. 원본을 정적 URL 로
     * 노출하지 않고 매 요청 인가한다(개인 대화 첨부 — 컨트롤러가 {@code noStore().cachePrivate()} 적용).
     */
    public AttachmentFile read(Long ticketId, Long messageId, Long requesterId) {
        CounselTicket ticket = loadParticipantTicket(ticketId, requesterId);
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        // 메시지가 이 티켓의 세션 소속이 아니면(다른 티켓 메시지 id 대입) 열거 방지 통일 응답.
        if (ticket.getSessionId() == null || !Objects.equals(message.getSessionId(), ticket.getSessionId())) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
        }
        if (message.getAttachmentKey() == null) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        byte[] content = fileStorage.read(message.getAttachmentKey());
        String mimeType = message.getAttachmentMimeType() != null
                ? message.getAttachmentMimeType()
                : CounselAttachmentPolicy.mimeTypeOf(message.getAttachmentKey());
        return new AttachmentFile(content, mimeType);
    }

    private CounselTicket loadParticipantTicket(Long ticketId, Long requesterId) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        boolean participant = Objects.equals(ticket.getUserId(), requesterId)
                || Objects.equals(ticket.getCounselorId(), requesterId);
        if (!participant) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
        }
        return ticket;
    }

    /** 서빙 바이트 + MIME. */
    public record AttachmentFile(byte[] content, String mimeType) {
    }
}
