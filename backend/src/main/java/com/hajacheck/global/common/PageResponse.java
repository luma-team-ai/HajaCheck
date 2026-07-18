package com.hajacheck.global.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 목록 페이징 응답 공통 envelope — frontend {@code shared/api/types.ts} 의
 * {@code PageResponse<T>}({@code content, page, totalElements})와 1:1 대응한다.
 * "목록 페이징 구조 통일"(프론트 주석) SOT를 백엔드가 그대로 따른다.
 */
public record PageResponse<T>(List<T> content, int page, long totalElements) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getTotalElements());
    }
}
