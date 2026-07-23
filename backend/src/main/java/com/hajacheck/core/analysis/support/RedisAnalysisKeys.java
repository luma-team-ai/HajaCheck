package com.hajacheck.core.analysis.support;

/** AI 분석 진행 상태 Redis 키(dev-05-04) — SpringBoot_코드_컨벤션.md §8 콜론 규약. */
public final class RedisAnalysisKeys {

    private static final String PREFIX = "analysis:progress:";

    private RedisAnalysisKeys() {
    }

    public static String progressKey(Long inspectionId) {
        return PREFIX + inspectionId;
    }
}
