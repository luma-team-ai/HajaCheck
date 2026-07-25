package com.hajacheck.membership.service;

import java.time.LocalDate;

/**
 * 월 분석 한도 차감이 <b>실제로 갱신한 {@code usage_counters} 좌표</b>(#843 머신 검수 P2).
 *
 * <p>보상 차감은 이 좌표만 되돌린다 — 보상 시점에 구독이나 기간을 <b>다시 계산하지 않는다</b>. 예전에는
 * {@code refundAnalysisQuota}가 {@code imageCount}만 받고 대상 행을 스스로 재조회·재계산했는데, 분석 워커는
 * {@code @Async}로 수 분(PRD: 100장 10분)을 돌기 때문에 차감과 보상 사이에 두 축이 어긋날 수 있었다:
 * <ul>
 *   <li><b>기간(period)</b> — 말일 23:59에 차감(M월 행)하고 다음 달 00:0x에 보상하면, M+1 행이 없으면
 *       0행 갱신으로 보상이 소실되고(M월 사용량 과대 집계 잔류), M+1 행이 있으면 소비하지도 않은 다음 달
 *       한도를 깎아 두 달치 회계가 어긋났다. FREE(월 50장)는 관리자 보정 API가 없어 그 달 내내 복구 불가.</li>
 *   <li><b>구독(userPlanId)</b> — 차감~보상 사이에 요금제가 바뀌면(모의 결제·관리자 플랜 변경) 차감했던
 *       구독이 아니라 새 구독의 행을 감산했다(머신 검수 P3-1).</li>
 * </ul>
 *
 * <p>두 값을 차감 시점에 확정해 보상까지 그대로 들고 다니면 시간이 얼마나 흘렀든, 구독이 바뀌었든
 * 정확히 "깎았던 그 행"만 되돌아간다. {@code imageCount}까지 함께 담아 호출부가 장수를 따로 들고 다니다
 * 어긋나게 넘길 여지도 없앤다.
 *
 * @param userPlanId 차감된 구독(null이면 차감이 일어나지 않음)
 * @param period     차감된 집계 기간(해당 월 1일, KST 기준)
 * @param imageCount 차감된 이미지 장수
 */
public record AnalysisQuotaCharge(Long userPlanId, LocalDate period, int imageCount) {

    /** 차감이 일어나지 않았음을 뜻하는 값 — 보상은 이 값을 받으면 아무것도 하지 않는다. */
    private static final AnalysisQuotaCharge NONE = new AnalysisQuotaCharge(null, null, 0);

    /**
     * 차감 없음. 호출부가 {@code null}이나 별도 플래그를 다루지 않도록, "차감하지 않았다"도 항상 같은
     * 타입의 값으로 표현한다 — 반환값을 그대로 보상에 넘기기만 하면 어느 경우든 옳게 동작한다.
     */
    public static AnalysisQuotaCharge none() {
        return NONE;
    }

    public static AnalysisQuotaCharge of(Long userPlanId, LocalDate period, int imageCount) {
        return new AnalysisQuotaCharge(userPlanId, period, imageCount);
    }

    /** 실제로 되돌릴 차감이 있는지. */
    public boolean isCharged() {
        return userPlanId != null && period != null && imageCount > 0;
    }
}
