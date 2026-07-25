package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UsageCounter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 사용량 집계 저장소.
 *
 * <p>⚠️ 카운터 증감은 {@link UsageCounter} 엔티티를 읽어 수정하지 않는다(엔티티 javadoc·table_design.md
 * §usage_counters "동시성 정책 확정"). 이 엔티티에는 의도적으로 {@code @Version}이 없고, 한도 판정은
 * 아래 <b>원자적 조건부 UPDATE</b>가 담당한다 — 갱신 행 수 0 = 한도 초과. PostgreSQL READ COMMITTED에서
 * 같은 행을 노리는 두 UPDATE는 행 잠금으로 직렬화되고, 뒤 트랜잭션은 잠금 해제 후 <b>갱신된 값으로
 * WHERE를 재평가</b>하므로 "조회 후 증가"의 TOCTOU가 원천 차단된다.
 *
 * <p>period 행 최초 생성 경합은 {@code unique (user_plan_id, period)} 기반 UPSERT
 * ({@link #insertPeriodRowIfAbsent})로 흡수한다.
 *
 * <p><b>예외 — 스냅샷 한도(시설물·좌석)</b>: 이 둘은 판정 기준이 저장된 카운터가 아니라
 * {@code facilities}/{@code users} 실측 count(*)라, 예약 UPDATE 의 WHERE 에 카운터 컬럼이 등장하지
 * 않는다. 따라서 그 문장들은 <b>단독으로는 원자적이지 않으며</b> 반드시 {@link #lockPeriodRow}로
 * 집계 행을 먼저 잠근 트랜잭션 안에서만 호출해야 한다(잠금 → 실측 → 조건부 UPDATE 순서). 이를
 * 시그니처로 강제하기 위해 두 예약/동기화 메서드는 대상 행을 잠금 메서드가 돌려준 id 로만 받는다.
 */
public interface UsageCounterRepository extends JpaRepository<UsageCounter, Long> {

    Optional<UsageCounter> findByUserPlanIdAndPeriod(Long userPlanId, LocalDate period);

    // 집계 행 존재 확인 전용(#843) — 엔티티를 영속성 컨텍스트에 올리지 않는다. 아래 네이티브 UPDATE 들은
    // 1차 캐시를 우회하므로, 같은 트랜잭션에서 엔티티를 미리 로드해두면 stale 스냅샷이 남는다.
    boolean existsByUserPlanIdAndPeriod(Long userPlanId, LocalDate period);

    // 플랫폼 관리자 서비스 통계(#633) — 전 구독(개인+회사) 대상 기간별 사용량 합산 원천 데이터.
    List<UsageCounter> findByPeriodBetween(LocalDate from, LocalDate to);

    /**
     * 당월 집계 행이 없으면 만든다(있으면 아무것도 하지 않는다). 스냅샷 성격 카운터
     * (facility_count·seat_count)는 생성 시점의 <b>실측값</b>으로 시드해, 월이 바뀌어 새 행이 생겨도
     * "현재 보유량"이 0으로 리셋되지 않게 한다. 누적 성격 카운터(analyzed_image_count 등)는 0에서 시작한다.
     *
     * <p>동시 최초 생성은 UNIQUE 제약이 흡수한다 — 뒤 트랜잭션의 INSERT는 앞 트랜잭션 커밋까지 대기했다가
     * {@code do nothing}으로 조용히 끝난다(예외 없음).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into usage_counters
                (user_plan_id, period, analyzed_image_count, facility_count,
                 analysis_request_count, seat_count, counsel_ticket_count, pdf_generation_count)
            values (:userPlanId, cast(:period as date), 0, cast(:facilityCount as integer),
                    0, cast(:seatCount as integer), 0, 0)
            on conflict (user_plan_id, period) do nothing
            """, nativeQuery = true)
    int insertPeriodRowIfAbsent(@Param("userPlanId") Long userPlanId,
                                @Param("period") LocalDate period,
                                @Param("facilityCount") int facilityCount,
                                @Param("seatCount") int seatCount);

    /**
     * 당월 집계 행을 배타 잠금한다(호출 트랜잭션 커밋까지 유지).
     *
     * <p>스냅샷 한도(시설물·좌석)는 카운터 자체가 아니라 <b>실측 count(*)</b>를 기준으로 판정하는데,
     * 실측을 잠금 없이 하면 두 요청이 같은 값을 읽어 한도가 샌다. 이 잠금을 먼저 잡고 그 뒤에 실측하면
     * (READ COMMITTED에서 잠금 획득 후 실행되는 조회는 앞 트랜잭션 커밋 결과를 보는 새 스냅샷을 쓴다)
     * 실측값이 항상 최신이 되어 드리프트 없이 정확히 직렬화된다.
     *
     * @return 잠근 행의 id (행이 없으면 empty)
     */
    @Query(value = "select id from usage_counters "
            + "where user_plan_id = :userPlanId and period = cast(:period as date) for update",
            nativeQuery = true)
    Optional<Long> lockPeriodRow(@Param("userPlanId") Long userPlanId, @Param("period") LocalDate period);

    /**
     * 시설물 1건 등록 슬롯을 예약한다 — {@code measured}(잠금 후 실측한 현재 보유 시설물 수)가 한도 미만일
     * 때만 갱신되고, 0행이면 한도 초과다.
     *
     * <p>⚠️ <b>이 UPDATE 의 WHERE 에는 카운터 컬럼 참조가 없다</b>(판정 기준이 저장된 값이 아니라 실측값이라
     * 그렇다). 즉 이 문장 하나만으로는 원자적이지 않고, 안전성은 전적으로 <b>같은 트랜잭션에서
     * {@link #lockPeriodRow} 로 그 행을 먼저 잠갔다는 사실</b>에 의존한다. 그 불변식을 호출 순서 관례가
     * 아니라 시그니처로 강제하기 위해, 대상 행을 (userPlanId, period) 가 아니라 <b>잠금 메서드가 돌려준
     * {@code usageCounterId}</b> 로만 지정한다 — 잠그지 않고서는 이 id 를 얻을 경로가 없다.
     *
     * <p>⚠️ 호출부는 <b>실제 시설물 INSERT 와 같은 트랜잭션</b>이어야 한다. 잠금을 쥔 채 커밋해야 다음
     * 요청의 실측이 방금 만든 시설물을 포함해서 세기 때문이다.
     *
     * @param usageCounterId {@link #lockPeriodRow} 가 반환한, 이 트랜잭션이 잠그고 있는 집계 행 id
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set facility_count = cast(:measured as integer) + 1
             where id = :usageCounterId
               and cast(:measured as integer) < cast(:maxFacilities as integer)
            """, nativeQuery = true)
    int reserveFacilitySlot(@Param("usageCounterId") Long usageCounterId,
                            @Param("measured") int measured,
                            @Param("maxFacilities") int maxFacilities);

    /**
     * 시설물 보유량 표시값 동기화(무제한 요금제·삭제 후) — 한도 판정 없이 실측값을 그대로 반영한다.
     * {@link #reserveFacilitySlot} 과 같은 이유로 잠금 메서드가 돌려준 id 로만 대상을 지정한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set facility_count = cast(:value as integer)
             where id = :usageCounterId
            """, nativeQuery = true)
    int syncFacilityCount(@Param("usageCounterId") Long usageCounterId, @Param("value") int value);

    /** 좌석 1석 예약 — {@link #reserveFacilitySlot} 과 동일 규약(잠금 선행 필수, 0행 = 한도 초과). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set seat_count = cast(:measured as integer) + 1
             where id = :usageCounterId
               and cast(:measured as integer) < cast(:maxSeats as integer)
            """, nativeQuery = true)
    int reserveSeat(@Param("usageCounterId") Long usageCounterId,
                    @Param("measured") int measured,
                    @Param("maxSeats") int maxSeats);

    /** 좌석 사용량 표시값 동기화(무제한 요금제) — 한도 판정 없이 실측값을 그대로 반영한다. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set seat_count = cast(:value as integer)
             where id = :usageCounterId
            """, nativeQuery = true)
    int syncSeatCount(@Param("usageCounterId") Long usageCounterId, @Param("value") int value);

    /**
     * 월 분석 한도 차감 — 누적 카운터라 실측 원천이 없고, 저장된 값 자체를 조건으로 쓰는 순수 원자적
     * 조건부 UPDATE다(handoff §2 규정 형태). 요청 1건 = 이미지 N장이므로 요청 수와 이미지 수를 함께
     * 올리되, 한도({@code max_monthly_analyses})는 화면(마이페이지 "월 분석 N장/50장")과 정합하도록
     * <b>이미지 장수</b> 기준으로 판정한다.
     *
     * @return 1이면 차감 성공, 0이면 한도 초과
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set analyzed_image_count = analyzed_image_count + cast(:images as integer),
                   analysis_request_count = analysis_request_count + 1
             where user_plan_id = :userPlanId
               and period = cast(:period as date)
               and analyzed_image_count + cast(:images as integer) <= cast(:maxImages as integer)
            """, nativeQuery = true)
    int consumeAnalysisQuota(@Param("userPlanId") Long userPlanId,
                             @Param("period") LocalDate period,
                             @Param("images") int images,
                             @Param("maxImages") int maxImages);

    /** 무제한 요금제(max_monthly_analyses = null)용 — 한도 조건 없이 사용량만 적립한다. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set analyzed_image_count = analyzed_image_count + cast(:images as integer),
                   analysis_request_count = analysis_request_count + 1
             where user_plan_id = :userPlanId and period = cast(:period as date)
            """, nativeQuery = true)
    int consumeAnalysisQuotaUnlimited(@Param("userPlanId") Long userPlanId,
                                      @Param("period") LocalDate period,
                                      @Param("images") int images);

    /**
     * 분석 실행이 실패해 차감을 되돌린다(큐 포화·선점 실패 등). {@code greatest(..., 0)} 으로 감싸
     * {@code ck_usage_counters_nonnegative} CHECK 위반을 막는다(월 경계에서 행이 새로 만들어져 차감
     * 이력이 없는 경우 등).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update usage_counters
               set analyzed_image_count = greatest(analyzed_image_count - cast(:images as integer), 0),
                   analysis_request_count = greatest(analysis_request_count - 1, 0)
             where user_plan_id = :userPlanId and period = cast(:period as date)
            """, nativeQuery = true)
    int refundAnalysisQuota(@Param("userPlanId") Long userPlanId,
                            @Param("period") LocalDate period,
                            @Param("images") int images);
}
