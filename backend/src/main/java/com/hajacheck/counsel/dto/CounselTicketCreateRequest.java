package com.hajacheck.counsel.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 상담 티켓 생성 요청 — 어떤 시나리오 리프(leadsToCounselor=true 노드)에서 상담원 연결을 요청했는지.
 * 서버는 이 노드의 트리를 타고 올라가 category/title 을 스냅샷으로 저장한다.
 */
public record CounselTicketCreateRequest(
        @NotNull
        Long scenarioId) {
}
