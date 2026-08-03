package com.hajacheck.mypage.dto;

import com.hajacheck.core.inspection.entity.InspectionStatus;

/**
 * 마이페이지 "내 점검 이력" 화면 표시 상태(#844) — 실 {@link InspectionStatus}(6종)를 화면 3종으로
 * 백엔드에서 매핑한다(handoff §2-2 계약, 프론트에 상태 매핑 로직을 두지 않기 위함).
 */
public enum MyInspectionDisplayStatus {
    REVIEW_DONE,
    REVIEW_PENDING,
    ANALYZING;

    public static MyInspectionDisplayStatus from(InspectionStatus status) {
        return switch (status) {
            case REVIEWED, REPORTED -> REVIEW_DONE;
            case ANALYZED -> REVIEW_PENDING;
            case CREATED, UPLOADING, ANALYZING, FAILED -> ANALYZING;
        };
    }
}
