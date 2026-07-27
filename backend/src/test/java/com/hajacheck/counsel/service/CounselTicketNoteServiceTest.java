package com.hajacheck.counsel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.counsel.dto.CounselTicketNoteResponse;
import com.hajacheck.counsel.entity.CounselTicket;
import com.hajacheck.counsel.entity.CounselTicketNote;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.repository.CounselTicketNoteRepository;
import com.hajacheck.counsel.repository.CounselTicketRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CounselTicketNoteService 단위테스트(#1021/HAJA-503) — 담당 상담원 본인만 조회/저장 가능(IDOR 방지) +
 * upsert(신규 생성/기존 갱신) 동작.
 */
@ExtendWith(MockitoExtension.class)
class CounselTicketNoteServiceTest {

    private static final Long TICKET_ID = 50L;
    private static final Long COUNSELOR_ID = 9L;
    private static final Long OTHER_COUNSELOR_ID = 10L;
    private static final Long USER_ID = 1L;

    @Mock
    private CounselTicketNoteRepository noteRepository;
    @Mock
    private CounselTicketRepository ticketRepository;

    private CounselTicketNoteService service;

    @BeforeEach
    void setUp() {
        service = new CounselTicketNoteService(noteRepository, ticketRepository);
    }

    private CounselTicket assignedTicket() {
        CounselTicket ticket = CounselTicket.request(USER_ID, CounselType.ANALYSIS_RESULT, 1,
                "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ReflectionTestUtils.setField(ticket, "counselorId", COUNSELOR_ID);
        return ticket;
    }

    // ── getNote ──

    @Test
    void 조회_담당상담원본인_메모없으면_빈응답() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));
        when(noteRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());

        CounselTicketNoteResponse response = service.getNote(TICKET_ID, COUNSELOR_ID);

        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.content()).isNull();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void 조회_담당상담원본인_기존메모반환() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));
        CounselTicketNote note = CounselTicketNote.create(TICKET_ID, COUNSELOR_ID, "고객 재문의 예정");
        ReflectionTestUtils.setField(note, "updatedAt", LocalDateTime.of(2026, 7, 27, 10, 0));
        when(noteRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.of(note));

        CounselTicketNoteResponse response = service.getNote(TICKET_ID, COUNSELOR_ID);

        assertThat(response.content()).isEqualTo("고객 재문의 예정");
        assertThat(response.counselorId()).isEqualTo(COUNSELOR_ID);
    }

    @Test
    void 조회_담당아닌상담원_403_TICKET_FORBIDDEN() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));

        assertThatThrownBy(() -> service.getNote(TICKET_ID, OTHER_COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        verify(noteRepository, never()).findByTicketId(any());
    }

    @Test
    void 조회_미배정티켓_403_TICKET_FORBIDDEN() {
        CounselTicket ticket = CounselTicket.request(USER_ID, CounselType.ANALYSIS_RESULT, 1,
                "INSPECTION_REPORT", "AI 분석 결과 등급 문의");
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.getNote(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
    }

    @Test
    void 조회_티켓없음_404_TICKET_NOT_FOUND() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNote(TICKET_ID, COUNSELOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_NOT_FOUND);
    }

    // ── saveNote: upsert ──

    @Test
    void 저장_기존메모없음_신규생성() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));
        when(noteRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());
        when(noteRepository.save(any(CounselTicketNote.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketNoteResponse response = service.saveNote(TICKET_ID, COUNSELOR_ID, "신규 메모");

        assertThat(response.content()).isEqualTo("신규 메모");
        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
    }

    @Test
    void 저장_기존메모있음_내용갱신() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));
        CounselTicketNote existing = CounselTicketNote.create(TICKET_ID, COUNSELOR_ID, "이전 메모");
        when(noteRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.of(existing));
        when(noteRepository.save(any(CounselTicketNote.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketNoteResponse response = service.saveNote(TICKET_ID, COUNSELOR_ID, "갱신된 메모");

        assertThat(response.content()).isEqualTo("갱신된 메모");
        verify(noteRepository, never()).save(org.mockito.ArgumentMatchers.argThat(
                n -> n != existing));
    }

    @Test
    void 저장_담당아닌상담원_403_TICKET_FORBIDDEN() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));

        assertThatThrownBy(() -> service.saveNote(TICKET_ID, OTHER_COUNSELOR_ID, "몰래 수정"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNSEL_TICKET_FORBIDDEN);
        verify(noteRepository, never()).save(any());
    }

    @Test
    void 저장_빈내용도허용() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(assignedTicket()));
        when(noteRepository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());
        when(noteRepository.save(any(CounselTicketNote.class))).thenAnswer(inv -> inv.getArgument(0));

        CounselTicketNoteResponse response = service.saveNote(TICKET_ID, COUNSELOR_ID, null);

        assertThat(response.content()).isNull();
    }
}
