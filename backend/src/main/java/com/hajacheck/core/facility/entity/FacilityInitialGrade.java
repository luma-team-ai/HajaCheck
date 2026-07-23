package com.hajacheck.core.facility.entity;

/**
 * 시설물 등록 시 입력하는 초기 등급 — DDL facility_initial_grade_type(A~E).
 *
 * <p>⚠️ 대시보드 "하자 등급 분포"가 쓰는 {@link com.hajacheck.core.defect.entity.DefectGrade}
 * (점검 이력 기반 계산값)와는 별개의 독립 개념이다 — 라벨 집합이 우연히 같을 뿐 서로 혼용하지 않는다
 * (#628 / HAJA-347).
 */
public enum FacilityInitialGrade {
    A,
    B,
    C,
    D,
    E
}
