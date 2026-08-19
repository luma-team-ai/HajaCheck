package com.hajacheck.core.inspection.repository;

/**
 * 점검 회차 번호 배치 조회용 인터페이스 프로젝션(#1706).
 *
 * <p>알림센터는 저장된 payload의 "{roundNo}회차" 문자열 대신 <b>조회 시점의 현재 회차</b>를 보여줘야
 * 한다(#1702 재정렬로 회차가 밀리면 옛 번호가 그대로 남기 때문). 목록 응답 조립 경로라 알림 건별
 * 단건 조회(N+1)는 금지이며, 회차 번호 외의 컬럼은 필요 없으므로 엔티티 대신 이 프로젝션으로
 * (id, roundNo)만 한 번에 읽는다.
 */
public interface InspectionRoundNoProjection {

    Long getId();

    Integer getRoundNo();
}
