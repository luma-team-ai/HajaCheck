package com.hajacheck.auth.config;

import com.hajacheck.core.media.config.MediaUploadProperties;
import com.hajacheck.core.media.support.DetectedFileType;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 업로드 허용목록(MIME 화이트리스트)이 <b>매직바이트 판별기가 아는 타입</b>의 부분집합인지 기동 시 검증한다.
 *
 * <p><b>왜 필요한가</b>: {@code LocalFileStorage} 는 이제 저장 전에 실제 시그니처를 대조하므로
 * ({@link DetectedFileType}), 허용목록에만 타입을 추가하고 판별기를 갱신하지 않으면 <b>그 타입의 모든
 * 업로드가 런타임에 {@code FILE_INVALID_TYPE} 로 조용히 실패</b>한다. {@code application.yml} 의
 * media-upload 주석이 "영상은 후속 PR 범위"라고 예고하고 있어 실제로 밟기 쉬운 지뢰다(2026-08-04 리뷰 P3).
 *
 * <p>런타임에 사용자 요청이 실패하는 것보다 <b>기동을 실패시키는 편이 낫다</b> — 설정 실수는 배포 시점에
 * 크게 터져야 발견된다(같은 기조: {@code plans} 시드 부팅 가드 #517/#518).
 *
 * <p>타입을 하나 추가할 때 손봐야 하는 곳: ①{@link DetectedFileType}(시그니처) ②{@code LocalFileStorage}
 * 의 {@code CONTENT_TYPE_EXTENSIONS}(확장자) ③해당 허용목록 프로퍼티. 이 검증은 ①의 누락을 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadContentTypeStartupValidator {

    private final FileStorageProperties fileStorageProperties;
    private final MediaUploadProperties mediaUploadProperties;

    @PostConstruct
    void validate() {
        Set<String> known = Arrays.stream(DetectedFileType.values())
                .map(DetectedFileType::mimeType)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> unknown = new LinkedHashSet<>();
        unknown.addAll(unsupported(fileStorageProperties.getAllowedContentTypes(), known));
        unknown.addAll(unsupported(mediaUploadProperties.getAllowedContentTypes(), known));

        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "업로드 허용목록에 매직바이트 판별기가 모르는 타입이 있습니다: " + unknown
                            + " — DetectedFileType 에 시그니처를 추가하고 LocalFileStorage 의 확장자 매핑도 함께 갱신할 것"
                            + "(추가하지 않으면 해당 타입 업로드가 전부 FILE_INVALID_TYPE 로 실패한다).");
        }
        log.info("업로드 허용목록 ↔ 매직바이트 판별기 정합 확인 — 알려진 타입={}", known);
    }

    private static List<String> unsupported(List<String> allowed, Set<String> known) {
        return allowed == null ? List.of()
                : allowed.stream().filter(type -> !known.contains(type)).toList();
    }
}
