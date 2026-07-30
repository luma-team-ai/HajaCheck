package com.hajacheck.platformadmin.dto;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 최근 에러 로그 1건 — frontend ErrorLogItem(monitoring.types.ts) 1:1.
 * {@code service} 는 로그를 발생시킨 로거 이름의 단순 클래스명(RedisErrorLogAppender 참고).
 *
 * @param timestamp "yyyy-MM-dd HH:mm:ss"(Asia/Seoul) 포맷 문자열 — RedisErrorLogAppender 저장 시점에 고정.
 */
public record ErrorLogItemResponse(
        String id,
        String timestamp,
        ErrorLogLevel level,
        String service,
        String message
) {
}
