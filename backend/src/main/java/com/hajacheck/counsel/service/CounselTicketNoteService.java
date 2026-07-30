package com.hajacheck.counsel.service;

import com.hajacheck.counsel.dto.CounselTicketNoteResponse;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketNote;
import com.hajacheck.counsel.repository.CounselTicketNoteRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담원 전용 비공개 메모(고객에게 노출되지 않음, 티켓당 1개, #1021/HAJA-503).
 *
 * <p>{@link CounselTicketService}가 이미 커서(대기열/배정/종료/이력/대화 등) 신규 서비스로 분리한다.
 * 조회/저장 모두 <b>담당 상담원 본인만</b> 허용한다(티켓의 {@code counselorId}와 요청자 불일치 시
 * {@code COUNSEL_TICKET_FORBIDDEN} 403 — cross-counselor IDOR 방지). 아직 아무에게도 배정되지 않은
 * 티켓(counselorId=null)은 그 누구도 담당자가 아니므로 동일하게 403 처리한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CounselTicketNoteService {

    private final CounselTicketNoteRepository noteRepository;
    private final CounselTicketRepository ticketRepository;

    /** 메모 조회 — 담당 상담원 본인만. 메모가 아직 없으면 빈 응답(content=null)을 반환한다. */
    public CounselTicketNoteResponse getNote(Long ticketId, Long requesterId) {
        CounselTicket ticket = loadOwnedTicket(ticketId, requesterId);
        return noteRepository.findByTicketId(ticket.getId())
                .map(CounselTicketNoteResponse::from)
                .orElseGet(() -> CounselTicketNoteResponse.empty(ticket.getId()));
    }

    /** 메모 저장 — upsert(없으면 생성, 있으면 갱신). 담당 상담원 본인만. */
    @Transactional
    public CounselTicketNoteResponse saveNote(Long ticketId, Long requesterId, String content) {
        CounselTicket ticket = loadOwnedTicket(ticketId, requesterId);
        CounselTicketNote note = noteRepository.findByTicketId(ticket.getId())
                .map(existing -> {
                    existing.updateContent(requesterId, content);
                    return existing;
                })
                .orElseGet(() -> CounselTicketNote.create(ticket.getId(), requesterId, content));
        CounselTicketNote saved = noteRepository.save(note);
        return CounselTicketNoteResponse.from(saved);
    }

    /**
     * 티켓 소유(담당 상담원 본인) 검증 후 로드. 미존재는 {@code COUNSEL_TICKET_NOT_FOUND}, 존재하지만
     * 담당 상담원이 아니거나(다른 상담원 담당/미배정) 요청자와 불일치하면 {@code COUNSEL_TICKET_FORBIDDEN}.
     */
    private CounselTicket loadOwnedTicket(Long ticketId, Long requesterId) {
        CounselTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUNSEL_TICKET_NOT_FOUND));
        if (!requesterId.equals(ticket.getCounselorId())) {
            throw new BusinessException(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        }
        return ticket;
    }
}
