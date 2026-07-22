package com.hajacheck.auth.entity;

/**
 * 사용자 권한 역할 — DDL role_type (관리자/검사자/일반/상담사/플랫폼 관리자).
 * 로컬 ddl-auto=update 에서는 varchar 로 생성되며, prod(validate) 의 PG enum 정합성은
 * 후속 인프라 과제(엔티티↔PG enum 매핑)로 남긴다.
 *
 * <p>PLATFORM_ADMIN(#534, PRD v0.47) — company_id가 없는 플랫폼 운영진 전용 축. 기존 ADMIN(기업
 * 관리자, company_id 스코프)과는 별개 계층이며, docs/design/db/migrations의 role_type PG enum
 * 마이그레이션과 이 값이 반드시 함께 존재해야 한다(그렇지 않으면 로그인 시 InternalAuthenticationServiceException).
 */
public enum Role {
    ADMIN,
    INSPECTOR,
    USER,
    COUNSELOR,
    PLATFORM_ADMIN
}
