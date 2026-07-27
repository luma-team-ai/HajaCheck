package com.hajacheck.core.media.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 미디어 업로드 개별 파일 최대 용량(maxSizeBytes)이 servlet 전역 개별 파일 한도(max-file-size)
 * 이내로 정합한지 고정한다(리뷰 P2). 파일 개수 상한은 두지 않으므로(요청 전체 크기는
 * servlet max-request-size가 방어) 여기서는 개별 파일 용량만 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaUploadPropertiesConsistencyTest extends PostgresTestSupport {

    @Autowired
    private MediaUploadProperties mediaUploadProperties;
    @Autowired
    private MultipartProperties multipartProperties;

    @Test
    void 파일당최대용량이_servlet개별파일한도이내() {
        long servletMaxFileBytes = multipartProperties.getMaxFileSize().toBytes();

        assertThat(mediaUploadProperties.getMaxSizeBytes()).isLessThanOrEqualTo(servletMaxFileBytes);
    }
}
