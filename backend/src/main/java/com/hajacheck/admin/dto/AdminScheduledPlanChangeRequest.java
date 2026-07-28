package com.hajacheck.admin.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /api/admin/plan/scheduled-change 요청 — 회사 구독을 <b>다음 결제 주기부터</b> 이 요금제로 내린다
 * (#1105 / HAJA-526). planName 은 PlanName enum — 잘못된 값은 역직렬화 단계에서 400.
 *
 * <p>즉시 변경({@code PATCH /api/admin/plan})과 <b>같은 확인·선택 계약</b>을 쓴다. 필드 의미를 굳이
 * 맞춘 이유: 두 경로가 결국 같은 하향 규칙({@code PlanDowngradeService})을 타는데 확인 절차만 다르면,
 * 관리자는 "예약이면 확인 없이 정지된다"는 더 위험한 경로를 갖게 된다.
 *
 * @param confirmOverflow 하향으로 <b>한도를 넘게 되는 자원이 있을 때만</b> 의미가 있다(#890). 초과가
 *                        있는데 이 값이 참이 아니면 서버는 <b>예약을 만들지 않고</b>
 *                        {@code PLAN_DOWNGRADE_CONFIRMATION_REQUIRED}(409)로 거절한다. 예약 경로에서
 *                        이 확인이 <b>더</b> 중요한 이유: 실제 정지는 한 달 뒤 사람 없는 스케줄러가
 *                        수행하므로, 신청 시점에 결과를 인지시키지 못하면 아무도 모르는 사이에 계정이
 *                        정지된다. null 은 false 로 본다.
 * @param keepUserIds     하향으로 좌석이 넘칠 때 유지할 구성원(#890 Phase 2). 미지정(null 또는 빈
 *                        리스트)이면 자동 규칙(owner + id 오름차순). ⚠️ 이 목록은 예약 행에 저장돼 한 달
 *                        가까이 보관되므로, 실행 시점에 서버가 다시 검증한다(퇴사·정지 id 드롭 → 자동
 *                        규칙 보충) — {@code ScheduledPlanChangeWriter#resolveKeepUserIds} 참고.
 */
public record AdminScheduledPlanChangeRequest(
        @NotNull PlanName planName,
        Boolean confirmOverflow,
        // AdminPlanChangeRequest 와 같은 상한(재검토 F-14) — 회사 실 좌석 한도보다 훨씬 넉넉하게 잡아
        // 정상 사용을 막지 않으면서 과도한 배열 페이로드를 차단한다.
        @Size(max = 200) List<Long> keepUserIds) {

    public boolean overflowConfirmed() {
        return Boolean.TRUE.equals(confirmOverflow);
    }

    /** null 이면 빈 리스트로 정규화 — "미지정 = 자동 규칙" 하위 호환을 호출부에서 매번 null 체크하지 않게 한다. */
    public List<Long> resolvedKeepUserIds() {
        return keepUserIds == null ? List.of() : keepUserIds;
    }
}
