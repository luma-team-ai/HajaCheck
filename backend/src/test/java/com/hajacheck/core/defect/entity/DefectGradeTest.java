package com.hajacheck.core.defect.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DefectGrade 선언 순서 = DB defect_grade_type 선언 순서(V1__baseline_schema.sql) 정합성 검증(UT-050).
 * DefectRepositoryImpl의 등급 비교 네이티브 쿼리가 이 선언순(A<B<C<D<E)에 의존하므로,
 * 순서가 흔들리면 등급 필터링이 조용히 깨진다.
 */
class DefectGradeTest {

    @Test
    void values_A부터E까지_선언순서정합() {
        assertThat(DefectGrade.values()).containsExactly(
                DefectGrade.A, DefectGrade.B, DefectGrade.C, DefectGrade.D, DefectGrade.E);
    }
}
