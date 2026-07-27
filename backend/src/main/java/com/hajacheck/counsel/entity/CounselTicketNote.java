package com.hajacheck.counsel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 상담원 전용 비공개 메모(고객에게 노출되지 않음, #1021/HAJA-503). 티켓당 1개(unique) — 담당 상담원이
 * 조회·작성한다. 연관관계는 {@link CounselTicket}과 마찬가지로 FK 값 컬럼({@code ticketId})만 보유하고
 * 별도 {@code @OneToOne} 매핑은 두지 않는다(조회·인가 검증은 서비스에서 티켓을 통해 이미 이루어진다).
 */
@Entity
@Getter
@Table(name = "counsel_ticket_notes")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CounselTicketNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, unique = true)
    private Long ticketId;

    @Column(name = "counselor_id", nullable = false)
    private Long counselorId;

    @Column(columnDefinition = "text")
    private String content;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private CounselTicketNote(Long ticketId, Long counselorId, String content) {
        this.ticketId = ticketId;
        this.counselorId = counselorId;
        this.content = content;
    }

    /** 신규 메모 생성(최초 저장). */
    public static CounselTicketNote create(Long ticketId, Long counselorId, String content) {
        return CounselTicketNote.builder()
                .ticketId(ticketId)
                .counselorId(counselorId)
                .content(content)
                .build();
    }

    /** 메모 내용 갱신 — 담당 상담원이 바뀐 시점(재배정)이라면 최신 요청자로 갱신한다. */
    public void updateContent(Long counselorId, String content) {
        this.counselorId = counselorId;
        this.content = content;
    }
}
