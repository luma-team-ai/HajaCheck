package com.hajacheck.core.media.entity;

/**
 * 미디어 파일 유형 — DDL media_file_type(이미지/영상).
 * 이번 PR(dev-05-03)은 IMAGE만 사용 — VIDEO(영상 프레임 추출)는 후속 PR 범위.
 */
public enum MediaFileType {
    IMAGE,
    VIDEO
}
