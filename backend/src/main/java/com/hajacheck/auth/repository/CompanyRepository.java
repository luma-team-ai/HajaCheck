package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    // 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox(#576). 심사 승인된 기업만 배정
    // 가능하게 한다 — 승인 대기/반려 기업에 사용자를 배선하면 그 회사 데이터 자체가 아직 유효하지 않다.
    List<Company> findByStatusOrderByNameAsc(CompanyStatus status);

    /**
     * 사업자 진위확인 자동 재검증 배치(#888) 대상 조회 — 판정 근거는 인가 플래그
     * ({@code verification_status})가 아니라 <b>provenance</b>({@code ocr_raw.ntsOutcome})다(#1324 P1).
     *
     * <p><b>왜 PENDING 만으로는 안 되는가</b>: #1324 자동승인이 가입 즉시 {@code verification_status} 를
     * 전건 VERIFIED 로 만든다. 그래서 국세청 장애 구간(SKIPPED, fail-open)에 가입한 회사는 예전 조건
     * ({@code verification_status = 'PENDING'})으로는 <b>재검증 집합에서 영구 이탈</b>한다 — 국세청이
     * 복구돼도 다시 검사받지 않고, FAILED 를 찍는 유일한 런타임 경로(이 배치)가 도달 불가가 된다.
     * 즉 인가 게이트를 여는 변경과 함께 그걸 사후에 되돌리는 통제가 사라진다.
     *
     * <p><b>대상</b>({@code business_start_date IS NOT NULL} — 개업일자가 없으면 국세청 재조회 파라미터가
     * 없어 호출해도 영원히 실패하므로 제외):
     * <ul>
     *   <li>{@code verification_status = 'PENDING'} — 기존 대상(#888).</li>
     *   <li>{@code verification_status = 'VERIFIED'} 인데 provenance 로 <b>증명할 수 없는</b> 것 —
     *       {@code SKIPPED}(장애·키 미설정) · {@code UNKNOWN_BACKFILL}(V38 소급분) · <b>키 부재</b>.</li>
     * </ul>
     *
     * <p><b>제외</b>:
     * <ul>
     *   <li>{@code ntsOutcome IN ('VERIFIED','LEGACY_VERIFIED')} — 국세청이 실제로 확인해 준 것.
     *       {@code Company#isNtsVerified} 의 화이트리스트와 <b>같은 집합</b>이며, V38 주석의 감사 쿼리
     *       ("국세청 검증을 증명할 수 없는 회사")와도 정확히 같은 조건이다.</li>
     *   <li>{@code status = 'REJECTED'} — 반려 회사는 국세청 쿼터를 쓰며 재조회할 이유가 없다.</li>
     *   <li>{@code verification_status = 'FAILED'} — 이미 확정 불량(위 두 갈래 어디에도 안 걸린다).</li>
     * </ul>
     *
     * <p>화이트리스트의 <b>여집합</b>({@code not in})으로 쓴 이유: 미래에 새 {@code ntsOutcome} 라벨이
     * 생겼을 때 "조용히 재검증 대상에서 빠지는" fail-open 이 아니라 "일단 다시 확인하는" fail-safe 로
     * 기울인다. ({@code ntsOutcome} 값 공간은 enum 이 아니다 — {@code Company} 클래스 javadoc 참고.)
     *
     * <p><b>정렬 = 라운드로빈</b>({@code ntsLastAttemptAt} 오름차순, 동률은 {@code id} — #1367 P1-C).
     * 예전에는 {@code id asc} 였는데, MISMATCH·SUSPENDED 를 자동 강등하지 않기로 하면서(#1367) 그 회사들이
     * <b>상태가 변하지 않아 집합을 떠나지 않는 "영구 거주자"</b>가 됐다. 데모 회사도 {@code ntsOutcome}
     * 키가 없어 두 번째 갈래에 영구 매칭된다. {@code id asc + LIMIT n} 은 <b>id 가 작은 앞 n 건만</b>
     * 반복 처리하므로, 영구 거주자가 상한(기본 20)에 도달하면 <b>id 가 큰 신규 가입 회사는 영원히
     * 재검증되지 않는다</b> — 이 배치는 FAILED 를 찍는 유일한 런타임 경로라, 미등록·폐업 업체가 가입해도
     * 아무도 강등하지 않는 상태가 <b>조회조차 안 돼 로그도 남지 않은 채</b> 성립한다(무증상 fail-open).
     * 마지막 시도 시각 오름차순(미시도 = 빈 문자열이 가장 먼저)으로 정렬하면 모든 대상이 순환한다.
     *
     * <p>스탬프({@code Company#stampNtsReverifyAttempt})는 {@code ntsOutcome} 을 건드리지 않으므로
     * <b>대상 집합(where 절)은 이 변경으로 달라지지 않는다</b> — 바뀐 것은 "같은 집합을 어떤 순서로
     * 소비하는가"뿐이다.
     *
     * <p>jsonb 연산자({@code ->>})가 필요해 네이티브 쿼리다. 정렬은 <b>SQL 에 고정</b>하므로
     * <b>{@code Pageable} 에 {@code Sort} 를 넣지 말 것</b>(네이티브 쿼리 동적 정렬은 Spring Data 가
     * 보장하지 않는다). 회차당 처리 상한만 {@code Pageable} 로 건다 — 총 건수 카운트가 불필요해
     * {@code Page} 가 아닌 {@code List} 를 반환한다({@code countQuery} 도 불필요).
     */
    @Query(value = """
            select c.*
            from companies c
            where c.business_start_date is not null
              and c.status <> 'REJECTED'
              and (c.verification_status = 'PENDING'
                   or (c.verification_status = 'VERIFIED'
                       and coalesce(c.business_registration_ocr_raw ->> 'ntsOutcome', '')
                           not in ('VERIFIED', 'LEGACY_VERIFIED')))
            order by coalesce(c.business_registration_ocr_raw ->> 'ntsLastAttemptAt', '') asc, c.id asc
            """, nativeQuery = true)
    List<Company> findNtsReverifyTargets(Pageable pageable);

    // 플랫폼 관리자 콘솔 — 회사별 마지막 ADMIN 보호(PR머신 2차 검토 P2). count-후-쓰기 사이 TOCTOU를
    // 막기 위해 대상 회사 행을 먼저 잠가 같은 회사를 대상으로 한 동시 강등/정지 요청을 직렬화한다
    // (FacilityRepository#findByIdForUpdate와 동일 패턴 — 값 자체는 쓰지 않고 잠금 획득 용도).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") Long id);

    boolean existsByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumberAndRepresentativeName(
            String businessRegistrationNumber, String representativeName);

    Optional<Company> findByBusinessRegistrationNumberAndName(
            String businessRegistrationNumber, String name);
}
