package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Company;

/**
 * 가입 상태 조회 응답(승인 대기 화면 새로고침).
 * status ∈ PENDING_REVIEW|APPROVED|REJECTED. rejectionReason 은 REJECTED 일 때만 존재.
 *
 * <p>#1324(가입 즉시 자동승인) 이후 <b>신규 가입은 항상 APPROVED</b> 다. 나머지 두 라벨은 현재
 * <b>기존 데이터에서만</b> 나온다:
 * <ul>
 *   <li>{@code PENDING_REVIEW} — V38 소급 승인 대상에서 제외된 회사(국세청 확정 불량 = 
 *       {@code verification_status=FAILED}). 신규 가입은 이 상태로 저장되지 않는다.</li>
 *   <li>{@code REJECTED} — V38 이전에 반려된 회사. <b>새로 생길 수 없다</b>: {@code Company#reject}
 *       는 {@code requirePendingReview} 가드를 갖는데 신규 가입은 곧바로 APPROVED 가 되고, APPROVED 를
 *       다시 PENDING_REVIEW 로 되돌리는 경로가 없다. 관리자 반려 화면이 배선되더라도 그 가드를 함께
 *       손보지 않으면 이 라벨은 계속 도달 불가다.</li>
 * </ul>
 * 두 라벨을 계약에서 지우지는 않는다(응답 스키마 축소는 프론트 파괴적 변경 + 기존 행이 실재한다).
 */
public record SignupStatusResponse(
        String status,
        String companyName,
        String rejectionReason
) {
    public static SignupStatusResponse from(Company company) {
        return new SignupStatusResponse(
                company.getStatus().name(),
                company.getName(),
                company.getRejectionReason()
        );
    }
}
