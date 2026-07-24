package com.hajacheck.platformadmin.support;

import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import java.util.List;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) 최근 에러 로그 저장소 — {@link RedisErrorLogAppender}가 기록하고
 * PlatformAdminMonitoringService 가 조회한다. 실 구현은 Redis capped list(RedisErrorLogStore,
 * {@code @Profile("!test")}) — test 프로파일은 RedisAutoConfiguration 제외로 StringRedisTemplate 이
 * 없어 in-memory fake(InMemoryErrorLogStore)로 대체된다(RateLimiter/TokenStore 와 동일한 레포 관례).
 */
public interface ErrorLogStore {

    void record(ErrorLogItemResponse item);

    List<ErrorLogItemResponse> recent(int limit);
}
