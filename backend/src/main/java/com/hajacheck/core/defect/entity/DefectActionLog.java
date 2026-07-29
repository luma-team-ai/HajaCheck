package com.hajacheck.core.defect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 조치 등록 제출 이력(append-only, #1193/HAJA-569) — defects.action_* flat 컬럼(V12)은 "최신 스냅샷"만
 * 유지해 조치중(IN_PROGRESS) 단계에서 시간차를 두고 여러 번 등록하는 사진/조치내용 이력을 담지 못한다.
 * 이 엔티티는 {@code DefectService#registerActionResult} 제출마다 하나씩 append되며, defects의 flat
 * 필드와 달리 절대 덮어쓰거나 삭제되지 않는다(RESOLVED 전이 후에도 과거 이력 조회 가능).
 */
@Entity
@Getter
@Table(name = "defect_action_logs", indexes = {
        @Index(name = "idx_defect_action_logs_defect_phase", columnList = "defect_id, phase")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DefectActionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "defect_id", nullable = false)
    private Long defectId;

    @Column(name = "media_id", nullable = false)
    private Long mediaId;

    // defect_status_type(defects.status)이 아니라 별도의 defect_action_log_phase_type(2라벨,
    // IN_PROGRESS/RESOLVED)를 쓴다 — V32 마이그레이션 헤더 주석 참고(V22가 defect_status_type을
    // drop/rename하는 경로와의 의존성 충돌 회피). Java 쪽은 그대로 DefectStatus를 재사용해도
    // 무방하다(NAMED_ENUM은 값 이름만 대조하고 두 타입 다 IN_PROGRESS/RESOLVED 라벨을 갖는다).
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "defect_action_log_phase_type", nullable = false)
    private DefectStatus phase;

    @Column(name = "action_content", nullable = false, columnDefinition = "text")
    private String actionContent;

    @Column(name = "action_date", nullable = false)
    private LocalDate actionDate;

    @Column(name = "action_assignee_id", nullable = false)
    private Long actionAssigneeId;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private DefectActionLog(Long defectId, Long mediaId, DefectStatus phase, String actionContent,
                             LocalDate actionDate, Long actionAssigneeId) {
        this.defectId = defectId;
        this.mediaId = mediaId;
        this.phase = phase;
        this.actionContent = actionContent;
        this.actionDate = actionDate;
        this.actionAssigneeId = actionAssigneeId;
    }

    public static DefectActionLog record(Long defectId, Long mediaId, DefectStatus phase,
                                          String actionContent, LocalDate actionDate, Long actionAssigneeId) {
        return DefectActionLog.builder()
                .defectId(defectId)
                .mediaId(mediaId)
                .phase(phase)
                .actionContent(actionContent)
                .actionDate(actionDate)
                .actionAssigneeId(actionAssigneeId)
                .build();
    }
}
