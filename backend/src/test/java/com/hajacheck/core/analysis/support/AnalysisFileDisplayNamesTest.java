package com.hajacheck.core.analysis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.media.entity.Media;
import org.junit.jupiter.api.Test;

/**
 * AI 분석 실행/상태 화면의 파일명 표시 기준({@link AnalysisFileDisplayNames#of}) 고정.
 * 이 기능의 목적이 "순번 대신 식별 가능한 파일명을 보여준다"이므로, 값이 없을 때 폴백이 반드시
 * 발동해야 한다 — 빈칸으로 표시되면 순번 라벨보다도 나쁘다.
 */
class AnalysisFileDisplayNamesTest {

    private static Media mediaWithOriginalFilename(String originalFilename) {
        return Media.builder()
                .inspectionId(1L)
                .originalFilename(originalFilename)
                .build();
    }

    @Test
    void 원본_파일명이_있으면_그대로_쓴다() {
        assertThat(AnalysisFileDisplayNames.of(mediaWithOriginalFilename("정면_균열.png"), 0))
                .isEqualTo("정면_균열.png");
    }

    @Test
    void 원본_파일명이_null이면_1base_순번으로_폴백한다() {
        assertThat(AnalysisFileDisplayNames.of(mediaWithOriginalFilename(null), 0)).isEqualTo("이미지 1");
        assertThat(AnalysisFileDisplayNames.of(mediaWithOriginalFilename(null), 2)).isEqualTo("이미지 3");
    }

    @Test
    void 원본_파일명이_빈문자열이나_공백이어도_순번으로_폴백한다() {
        // PR머신 P3 — MultipartFile#getOriginalFilename()은 계약상 ""를 반환할 수 있고, V26 이전에
        // 저장된 행에도 공백이 남아 있을 수 있다. null만 검사하면 폴백이 발동하지 않아 빈칸이 표시된다.
        assertThat(AnalysisFileDisplayNames.of(mediaWithOriginalFilename(""), 0)).isEqualTo("이미지 1");
        assertThat(AnalysisFileDisplayNames.of(mediaWithOriginalFilename("   "), 1)).isEqualTo("이미지 2");
    }
}
