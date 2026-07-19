package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.config.FileStorageProperties;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private FileStorageProperties properties;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        properties = new FileStorageProperties();
        properties.setBaseDir(tempDir.toString());
        properties.setBaseUrlPath("/files");
        properties.setAllowedContentTypes(List.of("image/jpeg", "image/png", "application/pdf"));
        properties.setMaxSizeBytes(1_000_000L);
        storage = new LocalFileStorage(properties);
    }

    @Test
    void store_빈파일_FILE_REQUIRED() {
        MockMultipartFile empty = new MockMultipartFile(
                "businessRegistrationFile", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> storage.store(empty, "business-registration",
                        properties.getAllowedContentTypes(), properties.getMaxSizeBytes()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_REQUIRED));
    }

    @Test
    void store_잘못된MIME_FILE_INVALID_TYPE() {
        MockMultipartFile bad = new MockMultipartFile(
                "businessRegistrationFile", "a.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> storage.store(bad, "business-registration",
                        properties.getAllowedContentTypes(), properties.getMaxSizeBytes()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    @Test
    void store_용량초과_FILE_TOO_LARGE() {
        properties.setMaxSizeBytes(4L);
        MockMultipartFile big = new MockMultipartFile(
                "businessRegistrationFile", "a.png", "image/png", "0123456789".getBytes());

        assertThatThrownBy(() -> storage.store(big, "business-registration",
                        properties.getAllowedContentTypes(), properties.getMaxSizeBytes()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void store_정상_UUID랜덤명_baseDir내부_확장자는contentType기준() throws IOException {
        // 원본 파일명은 evil.exe(악성) 이지만 contentType 이 image/png → 확장자는 .png 로만 결정.
        MockMultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "evil.exe", "image/png", "PNGDATA".getBytes());

        StoredFile stored = storage.store(file, "business-registration",
                properties.getAllowedContentTypes(), properties.getMaxSizeBytes());

        // 저장키·URL 형식 검증
        assertThat(stored.storageKey()).startsWith("business-registration/");
        assertThat(stored.storageKey()).endsWith(".png");
        assertThat(stored.storageKey()).doesNotContain("evil");
        assertThat(stored.url()).isEqualTo("/files/" + stored.storageKey());

        // 실제 파일이 baseDir 내부에 저장됨
        Path saved = tempDir.resolve(stored.storageKey());
        assertThat(Files.exists(saved)).isTrue();
        assertThat(saved.normalize().startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
        assertThat(Files.readAllBytes(saved)).isEqualTo("PNGDATA".getBytes());
    }

    @Test
    void store_카테고리에경로조작문자_새니타이즈되어baseDir내부저장() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "a.pdf", "application/pdf", "PDF".getBytes());

        StoredFile stored = storage.store(file, "../../etc",
                properties.getAllowedContentTypes(), properties.getMaxSizeBytes());

        Path saved = tempDir.resolve(stored.storageKey()).normalize();
        // 경로 조작 문자가 제거되어 최종 경로가 baseDir 하위여야 한다.
        assertThat(saved.startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
        assertThat(stored.storageKey()).doesNotContain("..");
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    void store_사업자등록증실제한도10MB초과_servlet전역한도20MB이내여도FILE_TOO_LARGE() {
        // 리뷰 P2: 미디어 업로드 때문에 servlet 전역 max-file-size 가 20MB로 상향됐다. 사업자등록증은
        // FileStorageProperties.maxSizeBytes(운영값 10MB)로 별도 앱 레벨 상한을 가지므로, "servlet은
        // 통과하지만 앱 한도는 초과"하는 15MB 크기에서도 여전히 거부되어야 계약이 유지된다.
        properties.setMaxSizeBytes(10_485_760L);
        MockMultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "brn.png", "image/png", new byte[15 * 1024 * 1024]);

        assertThatThrownBy(() -> storage.store(file, "business-registration",
                        properties.getAllowedContentTypes(), properties.getMaxSizeBytes()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void delete_저장된파일_삭제됨() {
        MockMultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "a.png", "image/png", "X".getBytes());
        StoredFile stored = storage.store(file, "business-registration",
                properties.getAllowedContentTypes(), properties.getMaxSizeBytes());
        Path saved = tempDir.resolve(stored.storageKey());
        assertThat(Files.exists(saved)).isTrue();

        storage.delete(stored.storageKey());

        assertThat(Files.exists(saved)).isFalse();
    }

    @Test
    void delete_null이나빈값_예외없이무시() {
        // 보상삭제는 best-effort — 예외를 던지지 않아야 한다.
        storage.delete(null);
        storage.delete("");
        storage.delete("business-registration/does-not-exist.png");
    }

    @Test
    void storeBytes_정상_저장후읽으면동일바이트() {
        StoredFile stored = storage.storeBytes("THUMBDATA".getBytes(), "image/jpeg", "inspection-media-thumb",
                List.of("image/jpeg"), 1_000_000L);

        assertThat(stored.storageKey()).startsWith("inspection-media-thumb/");
        assertThat(stored.storageKey()).endsWith(".jpg");
        assertThat(storage.read(stored.storageKey())).isEqualTo("THUMBDATA".getBytes());
    }

    @Test
    void storeBytes_빈바이트_FILE_REQUIRED() {
        assertThatThrownBy(() -> storage.storeBytes(new byte[0], "image/jpeg", "inspection-media-thumb",
                        List.of("image/jpeg"), 1_000_000L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_REQUIRED));
    }

    @Test
    void storeBytes_허용되지않는타입_FILE_INVALID_TYPE() {
        assertThatThrownBy(() -> storage.storeBytes("data".getBytes(), "image/gif", "inspection-media-thumb",
                        List.of("image/jpeg"), 1_000_000L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_INVALID_TYPE));
    }

    @Test
    void read_저장하지않은storageKey_FILE_NOT_FOUND() {
        // 리뷰 P2: DB 행은 있으나 디스크 파일이 없는 경우(보상삭제 경합 등)는 서버 오류(500)가 아니라
        // 리소스 없음(404)이 더 정확하다 — 이전에는 FILE_UPLOAD_FAILED(500)를 계약으로 고정했으나 이 결정으로 갱신.
        assertThatThrownBy(() -> storage.read("business-registration/does-not-exist.png"))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND));
    }
}
