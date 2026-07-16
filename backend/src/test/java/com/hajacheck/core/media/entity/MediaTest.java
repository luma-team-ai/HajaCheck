package com.hajacheck.core.media.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MediaTest {

    @Test
    void create_영상메타데이터를생성() {
        Instant capturedAt = Instant.parse("2026-07-16T01:00:00Z");

        Media media = Media.create(
                10L,
                MediaFileType.VIDEO,
                "https://files.example/video.mp4",
                "https://files.example/video-thumb.jpg",
                capturedAt,
                new BigDecimal("37.566500"),
                new BigDecimal("126.978000"),
                false,
                "video/mp4");

        assertThat(media.getInspectionId()).isEqualTo(10L);
        assertThat(media.getFileType()).isEqualTo(MediaFileType.VIDEO);
        assertThat(media.getCapturedAt()).isEqualTo(capturedAt);
        assertThat(media.getMimeType()).isEqualTo("video/mp4");
        assertThat(media.isMimeSignatureVerified()).isFalse();
    }

    @Test
    void extractedFrame_원본영상과프레임순번을기록() {
        Media frame = Media.extractedFrame(
                10L,
                "https://files.example/frame.jpg",
                null,
                20L,
                15,
                null,
                null,
                null,
                true,
                "image/jpeg");

        assertThat(frame.getFileType()).isEqualTo(MediaFileType.IMAGE);
        assertThat(frame.getSourceVideoId()).isEqualTo(20L);
        assertThat(frame.getFrameIndex()).isEqualTo(15);
        assertThat(frame.isMimeSignatureVerified()).isTrue();
    }

    @Test
    void markMimeSignatureVerified_검증상태를변경() {
        Media media = Media.create(
                10L, MediaFileType.IMAGE, "image.jpg", null,
                null, null, null, false, "image/jpeg");

        media.markMimeSignatureVerified();

        assertThat(media.isMimeSignatureVerified()).isTrue();
    }
}
