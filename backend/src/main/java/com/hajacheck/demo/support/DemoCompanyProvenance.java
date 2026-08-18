package com.hajacheck.demo.support;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.demo.service.DemoSeedService;
import com.hajacheck.global.util.JsonValidator;

/**
 * 데모 시드 회사 provenance 판정(#1626 P1-2, #1648 공용화) — {@code DemoResetService}(리셋 대상 특정)와
 * {@code PendingBusinessReverifyScheduler}(국세청 재검증 배치 스킵)가 <b>같은 판정 로직</b>을 공유한다.
 * 이중 조건(BRN 일치 AND provenance 표식)은 시더({@link DemoSeedService#seedAll})만 기록하는 값이라
 * 사람 설정 오류(예: {@code app.demo.login-id} 오설정)로 흔들리지 않는다.
 *
 * <p>⚠️ provenance 표식은 <b>JSON 파싱으로 판정</b>한다(#1626 P1-A) — substring 매칭 금지.
 * {@code businessRegistrationOcrRaw} 는 {@code @JdbcTypeCode(JSON)} String 이라 Postgres 가 jsonb 를
 * canonical text 로 저장하며 콜론 뒤 공백을 넣는다({@code {"source": "DEMO_SEED"}}). 별도 트랜잭션
 * 재조회(스케줄러·리셋 배치)는 공백 포함 텍스트가 오므로 공백 없는 substring 매칭은 항상 false 가 되어
 * 가드가 무력화된다({@link DemoSeedService#DEMO_SEED_PROVENANCE_SOURCE} javadoc 참고).
 */
public final class DemoCompanyProvenance {

    private DemoCompanyProvenance() {
    }

    /** BRN + provenance 표식이 모두 시드 상수와 일치해야 "진짜 데모 회사"로 인정한다(이중 판정). */
    public static boolean isDemoSeeded(Company company) {
        return isDemoBrn(company) && hasDemoMarker(company);
    }

    /** 사업자등록번호가 데모 시드 상수({@link DemoSeedService#DEMO_BUSINESS_NUMBER})와 일치하는지. */
    public static boolean isDemoBrn(Company company) {
        return DemoSeedService.DEMO_BUSINESS_NUMBER.equals(company.getBusinessRegistrationNumber());
    }

    /** ocr_raw 에 데모 시드 provenance 표식({@code source=DEMO_SEED})이 있는지(JSON 파싱 판정). */
    public static boolean hasDemoMarker(Company company) {
        return JsonValidator.readTextField(
                        company.getBusinessRegistrationOcrRaw(), DemoSeedService.DEMO_SEED_PROVENANCE_FIELD)
                .filter(DemoSeedService.DEMO_SEED_PROVENANCE_SOURCE::equals)
                .isPresent();
    }
}
