package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(companies) 대조 + jsonb 연산자(->>)를 쓰는 네이티브 쿼리 검증을 위해 임베디드 교체를 끄고
// Testcontainers PostgreSQL 사용(FacilityRepositoryTest 패턴).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class CompanyRepositoryTest extends PostgresTestSupport {

    private static final LocalDate START_DATE = LocalDate.of(2020, 1, 1);
    private static final String OCR_MANUAL = "{\"source\":\"MANUAL_INPUT\"}";

    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedCompany(BusinessVerificationStatus verificationStatus, LocalDate businessStartDate) {
        return seedCompany(verificationStatus, businessStartDate, OCR_MANUAL, false);
    }

    /**
     * @param ocrRaw    provenance(ntsOutcome)를 담는 감사용 jsonb 원본
     * @param rejected  true면 회사를 REJECTED(반려)로 만든다
     */
    private Long seedCompany(BusinessVerificationStatus verificationStatus, LocalDate businessStartDate,
                             String ocrRaw, boolean rejected) {
        int seq = sequence.incrementAndGet();
        User owner = User.createCompanyOwner(
                "owner" + seq + "@haja.test", "대표자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        Company company = Company.createPendingReview(
                owner.getId(),
                "회사" + seq,
                "BRN-%06d".formatted(seq),
                "대표자",
                "서울",
                null,
                "https://example.com/brn",
                ocrRaw,
                businessStartDate);
        if (verificationStatus == BusinessVerificationStatus.VERIFIED) {
            company.markBusinessVerified();
        } else if (verificationStatus == BusinessVerificationStatus.FAILED) {
            company.markBusinessVerificationFailed();
        }
        if (rejected) {
            company.reject(owner.getId(), "테스트 반려");
        }
        em.persist(company);
        em.flush();
        return company.getId();
    }

    private List<Long> reverifyTargetIds() {
        em.clear();
        return companyRepository.findNtsReverifyTargets(PageRequest.of(0, 20))
                .stream().map(Company::getId).toList();
    }

    // ── 재검증 대상 조회(#888 + #1324 P1: provenance 기준 확장) ────────────────────────────────

    @Test
    void PENDING이면서_개업일자가있는회사는_재검증대상이다() {
        Long targetId = seedCompany(BusinessVerificationStatus.PENDING, START_DATE);
        // 개업일자 없는 레거시 backfill 회사 — PENDING이어도 국세청 재조회 파라미터가 없어 제외.
        seedCompany(BusinessVerificationStatus.PENDING, null);

        assertThat(reverifyTargetIds()).containsExactly(targetId);
    }

    @Test
    void VERIFIED여도_provenance가SKIPPED면_재검증대상이다() {
        // #1324 P1 의 핵심 시나리오 — 국세청 장애(SKIPPED, fail-open) 구간에 가입해 자동승인으로
        // verification_status 만 VERIFIED 가 된 회사. verification_status 기준이면 영구 이탈한다.
        Long targetId = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}", false);

        assertThat(reverifyTargetIds()).containsExactly(targetId);
    }

    @Test
    void VERIFIED여도_provenance가UNKNOWN_BACKFILL이거나_키가없으면_재검증대상이다() {
        // V38 소급 승인분(검증한 적 없음).
        Long backfilled = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"UNKNOWN_BACKFILL\"}", false);
        // ntsOutcome 키 자체가 없는 행(직렬화 실패 fallback·V38 이전 데이터).
        Long noKey = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE, OCR_MANUAL, false);
        // jsonb 컬럼이 null 인 행.
        Long nullColumn = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE, null, false);

        assertThat(reverifyTargetIds()).containsExactly(backfilled, noKey, nullColumn);
    }

    @Test
    void provenance가VERIFIED_또는LEGACY_VERIFIED면_재검증대상에서제외된다() {
        // 국세청이 실제로 확인해 준 것 — 다시 조회할 이유가 없다(쿼터 낭비 + 무한 루프 방지).
        seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"VERIFIED\",\"ntsCheckedAt\":\"2026-07-31T00:00:00Z\"}", false);
        seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"LEGACY_VERIFIED\"}", false);

        assertThat(reverifyTargetIds()).isEmpty();
    }

    @Test
    void 재검증성공으로_provenance가VERIFIED가되면_다음회차대상에서빠진다() {
        // 루프 종료 보장 — markBusinessVerifiedByNts 가 provenance 를 갱신하지 않으면 매 회차 재선정된다.
        Long id = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}", false);
        assertThat(reverifyTargetIds()).containsExactly(id);

        Company target = companyRepository.findById(id).orElseThrow();
        target.markBusinessVerifiedByNts();
        em.flush();

        assertThat(reverifyTargetIds()).isEmpty();
    }

    @Test
    void FAILED회사와_REJECTED회사는_재검증대상이아니다() {
        // 이미 확정 불량.
        seedCompany(BusinessVerificationStatus.FAILED, START_DATE);
        // 명시적으로 반려된 회사 — provenance 가 증명 불가여도 국세청 쿼터를 쓰며 재조회하지 않는다.
        seedCompany(BusinessVerificationStatus.PENDING, START_DATE, OCR_MANUAL, true);
        seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"SKIPPED\"}", true);

        assertThat(reverifyTargetIds()).isEmpty();
    }

    // ── 대기열 라운드로빈 정렬(#1367 P1-C) ──────────────────────────────────────────────────

    /**
     * MISMATCH/SUSPENDED 를 자동 강등하지 않기로 하면서(#1367) 그 회사들이 <b>집합을 떠나지 않는 영구
     * 거주자</b>가 됐다. 옛 정렬({@code id asc}) + {@code LIMIT n} 은 id 가 작은 앞 n 건만 반복 처리하므로,
     * 영구 거주자가 상한을 채우면 id 가 큰 신규 가입 회사가 <b>영원히 재검증되지 않는다</b>(이 배치는
     * FAILED 를 찍는 유일한 런타임 경로라 미등록·폐업 업체가 무경보로 방치된다).
     */
    @Test
    void 시도스탬프가_오래된회사부터_먼저조회된다() {
        // id 는 작지만 최근에 처리한 회사(= 매 회차 앞자리를 점유하던 영구 거주자).
        Long recentlyAttempted = seedCompany(BusinessVerificationStatus.PENDING, START_DATE,
                "{\"ntsLastAttemptAt\":\"2026-08-20T05:30:00Z\"}", false);
        // id 는 크지만 오래 전에 처리한 회사.
        Long staleAttempted = seedCompany(BusinessVerificationStatus.PENDING, START_DATE,
                "{\"ntsLastAttemptAt\":\"2026-01-01T05:30:00Z\"}", false);
        // id 가 가장 크지만 한 번도 처리되지 않은 회사(신규 가입) — 가장 먼저 나와야 한다.
        Long neverAttempted = seedCompany(BusinessVerificationStatus.PENDING, START_DATE);

        assertThat(reverifyTargetIds())
                .containsExactly(neverAttempted, staleAttempted, recentlyAttempted);
    }

    @Test
    void 상한이작아도_미시도회사가_밀려나지않는다() {
        // 영구 거주자가 상한(=1)을 채우던 회귀 시나리오 — 정렬이 id asc 면 신규 가입 회사가 영영 안 잡힌다.
        seedCompany(BusinessVerificationStatus.PENDING, START_DATE,
                "{\"ntsLastAttemptAt\":\"2026-08-20T05:30:00Z\"}", false);
        Long neverAttempted = seedCompany(BusinessVerificationStatus.PENDING, START_DATE);
        em.clear();

        List<Long> firstPage = companyRepository.findNtsReverifyTargets(PageRequest.of(0, 1))
                .stream().map(Company::getId).toList();

        assertThat(firstPage).containsExactly(neverAttempted);
    }

    @Test
    void 스탬프는_대상집합을_바꾸지않는다() {
        // ntsLastAttemptAt 은 정렬 축일 뿐이다 — where 절은 ntsOutcome 만 본다.
        Long target = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"SKIPPED\"}", false);
        Long excluded = seedCompany(BusinessVerificationStatus.VERIFIED, START_DATE,
                "{\"ntsOutcome\":\"VERIFIED\"}", false);
        assertThat(reverifyTargetIds()).containsExactly(target);

        companyRepository.findById(target).orElseThrow().stampNtsReverifyAttempt();
        companyRepository.findById(excluded).orElseThrow().stampNtsReverifyAttempt();
        em.flush();

        // 스탬프 후에도 대상 집합은 그대로다(제외된 회사가 들어오지도, 대상이 빠지지도 않는다).
        assertThat(reverifyTargetIds()).containsExactly(target);
    }

    @Test
    void 관리자override회사는_재검증대상에_계속남는다() {
        // #1367 P1-A — override 의 안전장치. 화이트리스트 밖 라벨이라 두 번째 갈래에 계속 잡혀,
        // 국세청이 나중에 확정 불량을 주면 배치가 자동으로 다시 차단할 수 있다.
        Long overridden = seedCompany(BusinessVerificationStatus.PENDING, START_DATE,
                "{\"ntsOutcome\":\"MISMATCH_ALERTED\"}", false);
        Company company = companyRepository.findById(overridden).orElseThrow();
        company.overrideBusinessVerificationByAdmin("실물 확인 완료", 55L);
        em.flush();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(reverifyTargetIds()).contains(overridden);
    }

    @Test
    void Pageable로_회차당최대처리건수를제한한다() {
        seedCompany(BusinessVerificationStatus.PENDING, START_DATE);
        seedCompany(BusinessVerificationStatus.PENDING, START_DATE);
        seedCompany(BusinessVerificationStatus.PENDING, START_DATE);
        em.clear();

        List<Company> result = companyRepository.findNtsReverifyTargets(PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
    }
}
