package com.hajacheck.support;

import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import com.hajacheck.platformadmin.support.ErrorLogStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 테스트용 in-memory ErrorLogStore — test 프로파일은 RedisAutoConfiguration 제외로
 * RedisErrorLogStore(@Profile("!test"))가 뜨지 않으므로 이 fake 로 대체한다(InMemoryRateLimiter 선례).
 */
public class InMemoryErrorLogStore implements ErrorLogStore {

    private static final int MAX_ENTRIES = 200;

    private final Deque<ErrorLogItemResponse> items = new ArrayDeque<>();

    @Override
    public synchronized void record(ErrorLogItemResponse item) {
        items.addFirst(item);
        while (items.size() > MAX_ENTRIES) {
            items.removeLast();
        }
    }

    @Override
    public synchronized List<ErrorLogItemResponse> recent(int limit) {
        List<ErrorLogItemResponse> result = new ArrayList<>();
        for (ErrorLogItemResponse item : items) {
            if (result.size() >= limit) {
                break;
            }
            result.add(item);
        }
        return result;
    }

    /** 테스트 간 상태 격리용. */
    public synchronized void reset() {
        items.clear();
    }
}
