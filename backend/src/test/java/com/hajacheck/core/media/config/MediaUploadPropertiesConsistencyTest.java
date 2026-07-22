package com.hajacheck.core.media.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 미디어 다중 업로드 최악값(maxFilesPerRequest × maxSizeBytes)이 servlet 전역
 * max-request-size 한도 이내로 정합한지 고정한다(리뷰 P2). 이번 PR에서 실제로 두 값이
 * 서로 어긋나 rebase 충돌로 드러난 적이 있었다 — 둘 중 하나만 바뀌고 다른 쪽을 놓치면
 * 정상 업로드가 servlet 단계에서 먼저 거부되거나, 반대로 servlet 한도만 불필요하게
 * 커져 다른 도메인(사업자등록증 등)의 DoS 노출면이 넓어질 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaUploadPropertiesConsistencyTest extends PostgresTestSupport {

    @Autowired
    private MediaUploadProperties mediaUploadProperties;
    @Autowired
    private MultipartProperties multipartProperties;

    @Test
    void 미디어업로드최악값이_servlet전역요청한도이내() {
        long worstCaseRequestBytes =
                (long) mediaUploadProperties.getMaxFilesPerRequest() * mediaUploadProperties.getMaxSizeBytes();
        long servletMaxRequestBytes = multipartProperties.getMaxRequestSize().toBytes();

        assertThat(worstCaseRequestBytes).isLessThanOrEqualTo(servletMaxRequestBytes);
    }

    @Test
    void 파일당최대용량이_servlet개별파일한도이내() {
        long servletMaxFileBytes = multipartProperties.getMaxFileSize().toBytes();

        assertThat(mediaUploadProperties.getMaxSizeBytes()).isLessThanOrEqualTo(servletMaxFileBytes);
    }
}
