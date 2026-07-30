package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 서버 헬스 카드 1개 — frontend ServerHealthItem(monitoring.types.ts) 1:1.
 *
 * @param metric 부가 지표(가동률 등) — 없으면 null(frontend 는 optional 필드로 표시 생략).
 */
public record ServerHealthItemResponse(
        String id,
        String name,
        ServerHealthStatus status,
        String metric
) {
}
