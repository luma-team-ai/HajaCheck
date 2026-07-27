package com.hajacheck.admin.dto;

import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * 관리자 AI 분석 현황 모니터링(신규) 상태 분류 — DB InspectionStatus(6단계)를 화면 3단계로 축약한다.
 * 대시보드 "최근 점검"(RecentInspectionResponse)의 4단계 라벨(CREATED/UPLOADING/ANALYZING을 전부
 * "분석중"으로 묶음)과 달리, 이 화면은 "AI 분석이 지금 진행 중인지"를 정확히 구분해야 해서
 * ANALYZING을 별도 단계로 분리한다(관리자 AI 분석 모니터링 설계 논의 — "완료" 기준은 ANALYZED부터로
 * 팀 결정, 2026-07-27).
 *
 * <p>⚠️ 실패(FAILED) 상태는 없다 — InspectionStatus 자체에 FAILED가 없고, 분석 실패 시
 * ANALYZING → statusBeforeAnalysis(CREATED/UPLOADING)로 롤백된다(InspectionStatus.java 참고).
 * 그래서 이 화면에서 PENDING은 "아직 시작 안 함"과 "직전 시도가 실패해 대기로 돌아감"을 구분하지
 * 못한다 — 필요해지면 AnalysisProgressStore의 stage=failed를 별도로 조회해야 한다(1차 스코프 제외).
 */
public enum AdminAnalysisJobStatus {
    PENDING(EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING)),
    ANALYZING(EnumSet.of(InspectionStatus.ANALYZING)),
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
