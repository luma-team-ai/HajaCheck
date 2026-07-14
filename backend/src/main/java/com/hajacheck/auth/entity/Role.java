package com.hajacheck.auth.entity;

/**
 * 사용자 권한 역할 — DDL role_type (관리자/검사자/일반/상담사).
 * 로컬 ddl-auto=update 에서는 varchar 로 생성되며, prod(validate) 의 PG enum 정합성은
 * 후속 인프라 과제(엔티티↔PG enum 매핑)로 남긴다.
 */
public enum Role {
    ADMIN,
    INSPECTOR,
    USER,
    COUNSELOR
}
