package com.hajacheck.membership.dto;

import java.util.List;

/**
 * 플랜 하향 시 새 한도를 넘게 되는 자원 요약(#890 Phase 1).
 *
 * <p>확정 정책(#890): 데이터를 지우지 않되 한도 초과분을 <b>정지/읽기전용으로 전환</b>한다 —
 * 목록에서 숨기지 않는다. 숨기면 그 계정이 여전히 로그인·수정 가능한데 관리자는 존재조차 모르게 되어
 * 한도 초과보다 위험하다.
 *
 * @param seatUserIdsToSuspend 정지 대상 사용자 id(오름차순). 오래된 계정을 남기고 뒤쪽을 정지한다.
 *                             <b>owner 는 절대 포함되지 않는다</b>(정지되면 회사가 관리 불능).
 * @param facilityOverflowCount 읽기 전용으로 전환될 시설물 수. 시설물은 상태 컬럼 없이
 *                              "id 오름차순 한도 초과분"으로 <b>계산 판정</b>하므로(한도가 다시
 *                              올라가면 자동 복구된다) 여기서는 건수만 미리 보여준다.
 */
public record DowngradeOverflow(List<Long> seatUserIdsToSuspend, int facilityOverflowCount) {

    public static DowngradeOverflow none() {
        return new DowngradeOverflow(List.of(), 0);
    }

    /** 하향해도 넘치는 자원이 하나라도 있는가 — 명시적 확인을 요구할지 판단하는 기준. */
    public boolean exists() {
        return !seatUserIdsToSuspend.isEmpty() || facilityOverflowCount > 0;
    }

    public int seatOverflowCount() {
        return seatUserIdsToSuspend.size();
    }
}
