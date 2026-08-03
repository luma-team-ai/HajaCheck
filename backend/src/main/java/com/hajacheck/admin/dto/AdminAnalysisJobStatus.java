package com.hajacheck.admin.dto;

import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * 관리자 AI 분석 현황 모니터링(신규) 상태 분류 — DB InspectionStatus(7단계)를 화면 4단계로 축약한다.
 * 대시보드 "최근 점검"(RecentInspectionResponse)의 5단계 라벨(CREATED/UPLOADING/ANALYZING을 전부
 * "분석중"으로 묶음)과 달리, 이 화면은 "AI 분석이 지금 진행 중인지"를 정확히 구분해야 해서
 * ANALYZING을 별도 단계로 분리한다(관리자 AI 분석 모니터링 설계 논의 — "완료" 기준은 ANALYZED부터로
 * 팀 결정, 2026-07-27).
 *
 * <p>FAILED — 회차에 속한 사진 중 하나라도 AI 분석에 실패하면 ANALYZING에서 이 상태로 전이한다
 * (InspectionStatus.java 참고). COMPLETED와 분리된 별도 버킷으로 둔다 — 이 화면의 목적 자체가
 * 관리자가 분석 실패를 놓치지 않고 보는 것이라, COMPLETED나 PENDING에 섞으면 그 목적이 무색해진다.
 */
public enum AdminAnalysisJobStatus {
    PENDING(EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING)),
    ANALYZING(EnumSet.of(InspectionStatus.ANALYZING)),
    FAILED(EnumSet.of(InspectionStatus.FAILED)),
    COMPLETED(EnumSet.of(InspectionStatus.ANALYZED, InspectionStatus.REVIEWED, InspectionStatus.REPORTED));

    private final Set<InspectionStatus> inspectionStatuses;

    AdminAnalysisJobStatus(Set<InspectionStatus> inspectionStatuses) {
        this.inspectionStatuses = inspectionStatuses;
    }

    public Set<InspectionStatus> toInspectionStatuses() {
        return inspectionStatuses;
    }

    public static AdminAnalysisJobStatus from(InspectionStatus status) {
        for (AdminAnalysisJobStatus bucket : values()) {
            if (bucket.inspectionStatuses.contains(status)) {
                return bucket;
            }
        }
        // InspectionStatus에 값이 추가되고 이 매핑이 갱신되지 않으면 여기서 즉시 드러난다
        // (조용히 세 버킷 중 하나로 잘못 분류되는 것보다 명시적 실패가 안전하다).
        throw new IllegalStateException("AdminAnalysisJobStatus에 매핑되지 않은 InspectionStatus: " + status);
    }
}
