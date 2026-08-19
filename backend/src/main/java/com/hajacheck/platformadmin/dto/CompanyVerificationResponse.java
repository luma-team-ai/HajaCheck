package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;

/**
 * 회사 검증 상태 진단·조치 결과 응답(#1367) — 플랫폼 관리자가 "이 회사가 왜 막혔는지"를 판단하고,
 * 무효화/복구 직후 결과를 확인하는 데 쓴다.
 *
 * <p><b>왜 이 필드들인가</b>:
 * <ul>
 *   <li>{@code verificationStatus} — 회사 스코프 개방 여부를 실제로 결정하는 인가 플래그.
 *       {@code FAILED} 면 오너를 포함한 전 구성원의 점검 생성·담당자 배정이 막혀 있다.</li>
 *   <li>{@code ntsOutcome} — 그 상태가 <b>왜</b> 그렇게 됐는지(국세청 판정 · 소급 스탬프 · 관리자 조치).
 *       값 공간은 enum 이 아니라 자유 문자열이라 그대로 노출한다({@code Company} 클래스 javadoc).</li>
 *   <li>{@code ntsVerified} — 사용자 대면 "사업자 인증 완료" 배지의 실제 값. 인가 플래그와 어긋나 보일 수
 *       있어(자동승인 #1324) 둘을 나란히 보여준다.</li>
 *   <li>{@code hasBusinessStartDate} — <b>복구 가능 여부</b>의 선행 조건. false 면 복구해도 재검증 배치가
 *       영원히 잡지 못하므로 복구가 거부된다(#1329).</li>
 *   <li>{@code activeMemberCount} — 이 조치가 몇 명의 작업을 멈추는지(영향 범위). 개인정보를 담지 않는
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
        long activeMemberCount) {

    public static CompanyVerificationResponse from(Company company, long activeMemberCount) {
        return new CompanyVerificationResponse(
                company.getId(),
                company.getStatus(),
                company.getVerificationStatus(),
                company.ntsOutcome().orElse(null),
                company.ntsCheckedAt().orElse(null),
                company.isNtsVerified(),
                company.getBusinessStartDate() != null,
                activeMemberCount);
    }
}
