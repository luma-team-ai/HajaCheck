package com.hajacheck.core.defect.entity;

/**
 * 결함 위험 또는 심각도 등급 — DDL defect_grade_type(A~E, A가 가장 양호·E가 가장 심각).
 * 선언 순서가 PG named enum 의 순서와 동일해야 DB 정렬(order by grade desc = E→A)이 의도대로 동작한다.
 */
public enum DefectGrade {
    A,
    B,
    C,
    D,
    E
}
