package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectGrade;

/**
 * 마이페이지 "내 보고서" gradeDots(#844) — 점검(회차)별로 실제 존재하는(중복 제거된) 하자 등급을
 * 배치 조회하기 위한 프로젝션. {@link GradeCountProjection}은 전달된 inspectionId 집합 전체를
 * 하나로 뭉쳐 등급별 합계를 내므로 "보고서 카드 1건 = 점검 1건"의 개별 등급 분포를 복원할 수 없다
 * (inspectionId가 없음) — 이 프로젝션은 inspectionId를 함께 반환해 그룹핑을 서비스 계층에서
 * 재구성할 수 있게 한다.
 */
public interface InspectionGradeProjection {
    Long getInspectionId();

    DefectGrade getGrade();
}
