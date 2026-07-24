package com.hajacheck.core.analysis.support;

/** AI 분석 진행 상태 Redis 키(dev-05-04) — SpringBoot_코드_컨벤션.md §8 콜론 규약. */
public final class RedisAnalysisKeys {

    private static final String PROGRESS_PREFIX = "analysis:progress:";

    // 워커 펜싱용 세대 토큰 키(코드 리뷰 P1) — GENERATION_PREFIX 참고.
    private static final String GENERATION_PREFIX = "analysis:generation:";

    private RedisAnalysisKeys() {
    }

    public static String progressKey(Long inspectionId) {
        return PROGRESS_PREFIX + inspectionId;
    }

    public static String generationKey(Long inspectionId) {
        return GENERATION_PREFIX + inspectionId;
    }
}
