package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;

/**
 * 회사 검증 상태 진단·조치 결과 응답(#1367) — 플랫폼 관리자가 "이 회사가 왜 막혔는지"를 판단하고,
 * 무효화/복구/강제개방 직후 결과를 확인하는 데 쓴다.
 *
 * <p><b>왜 이 필드들인가</b>:
 * <ul>
 *   <li>{@code verificationStatus} — 회사 스코프 개방 여부를 실제로 결정하는 인가 플래그.
 *       {@code FAILED} 면 오너를 포함한 전 구성원의 점검 생성·담당자 배정이 막혀 있다.</li>
 *   <li>{@code ntsOutcome} — 그 상태가 <b>왜</b> 그렇게 됐는지(국세청 판정 · 소급 스탬프 · 관리자 조치).
 *       값 공간은 enum 이 아니라 자유 문자열이라 그대로 노출한다({@code Company} 클래스 javadoc).</li>
 *   <li>{@code ntsVerified} — 사용자 대면 "사업자 인증 완료" 배지의 실제 값. 인가 플래그와 어긋나 보일 수
 *       있어(자동승인 #1324 · 관리자 override) 둘을 나란히 보여준다.</li>
 *   <li>{@code hasBusinessStartDate} — <b>복구 가능 여부</b>의 선행 조건. false 면 PENDING 복귀 경로에서
 *       재검증 배치가 영원히 잡지 못하므로 복구가 거부된다(#1329).</li>
 *   <li>{@code ntsLastAttemptAt} · {@code ntsLastAlertOutcome} · {@code ntsLastAlertAt} — 재검증 배치의
 *       마지막 처리 시도와 <b>자동 강등하지 않은 경보</b>(MISMATCH/SUSPENDED) 기록. 경보만 남기는 정책은
 *       사람 판단으로 통제를 옮긴 것이므로, 그 신호가 관리자 화면까지 도달해야 정책이 성립한다.
 *       이 값들이 없으면 {@code ntsOutcome} 이 옛 값 그대로라 "어제 MISMATCH 를 받았다"를 알 수 없다.</li>
 *   <li>{@code reverifiableByBatch} — <b>자동 재차단이 걸리는가</b>. false 면 강제개방(override)해도
 *       국세청이 나중에 미등록·폐업을 확정했을 때 재검증 배치가 <b>자동으로 다시 차단하지 못한다</b>
 *       (개업일자 없음 · 반려 · 데모 시드 — 배치가 회사를 조회하지 못하거나 국세청 호출 전에 스킵한다).
 *       조치 시점 경고 로그만으로는 사후에 확인할 수 없어(특히 데모 시드 여부는 응답에서 유추조차 불가)
 *       진단 응답에 노출한다. 판정은 복구 가드와 <b>같은 함수</b>를 쓴다 — 조건이 갈라지면 "가드는 막는데
 *       응답은 가능하다고 표시"하는 모순이 생긴다.</li>
 *   <li>{@code effectiveMemberCount} — 이 조치가 실제로 멈추는(또는 멈춘) 구성원 수. 단순 활성 사용자
 *       수가 아니라 <b>스코프 판정과 같은 조건</b>의 유효 구성원 수다
 *       ({@code CompanyMembershipRepository#countEffectiveApprovedMembers} javadoc). 개인정보를 담지 않는
 *       집계값이다.</li>
 * </ul>
 *
 * <p>사업자등록번호·대표자명 등 개인정보성 식별자는 담지 않는다 — 진단에 필요하지 않다.
 */
public record CompanyVerificationResponse(
        Long companyId,
        CompanyStatus status,
        BusinessVerificationStatus verificationStatus,
        String ntsOutcome,
        String ntsCheckedAt,
        boolean ntsVerified,
        boolean hasBusinessStartDate,
        String ntsLastAttemptAt,
        String ntsLastAlertOutcome,
        String ntsLastAlertAt,
        boolean reverifiableByBatch,
        long effectiveMemberCount) {

    public static CompanyVerificationResponse from(
            Company company, long effectiveMemberCount, boolean reverifiableByBatch) {
        return new CompanyVerificationResponse(
                company.getId(),
                company.getStatus(),
                company.getVerificationStatus(),
                company.ntsOutcome().orElse(null),
                company.ntsCheckedAt().orElse(null),
                company.isNtsVerified(),
                company.getBusinessStartDate() != null,
                company.ntsLastAttemptAt().orElse(null),
                company.ntsLastAlertOutcome().orElse(null),
                company.ntsLastAlertAt().orElse(null),
                reverifiableByBatch,
                effectiveMemberCount);
    }
}
