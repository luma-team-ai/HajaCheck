import { DASHBOARD_COLOR_CLASS } from '../colors';
import type { DefectGrade } from '../types';
import { getGradeBgClass } from '../utils/gradeDistribution';

type Props = {
  grade: DefectGrade | null;
};

// grade가 null이면 등급색 대신 중립 배지('-')로 대체 — PendingPriorityResponse.grade 정합(HAJA-17 dev-03-01).
//
// 표기는 "분석중"이 아니라 "미분류"로 확정(#326). 근거:
//  - 이 배지가 쓰이는 처리대기 목록은 DefectStatus.ACTION_PENDING(조치대기)만 조회한다 → 분석이 끝나 조치를 기다리는 하자다.
//  - DefectStatus(DETECTED/CONFIRMED/ACTION_PENDING/IN_PROGRESS/RESOLVED)에 "분석중" 자체가 없다.
//    "분석중"은 점검(Inspection) 회차 레벨의 상태이지 하자(Defect) 레벨이 아니다.
//  - 하자 레코드의 존재 = 이미 탐지된 분석 산출물. 등급만 비어 있으므로 "미분류"가 정확하다.
// 원형 배지는 A~E와 동일한 28px 단일문자 규격이라 "미분류" 텍스트가 들어가지 않는다 → '-'로 표기하고
// 의미는 aria-label/title 로 명시한다(스크린리더·툴팁).
export function GradeBadge({ grade }: Props) {
  const isUnclassified = grade === null;
  const bgClass = isUnclassified ? DASHBOARD_COLOR_CLASS.gradeUnknownBg : getGradeBgClass(grade);
  // 라벨 형식은 대시보드 컨벤션을 따른다(GradeDistributionCard 의 "{등급} 등급" 표기와 일치).
  const label = isUnclassified ? '등급 미분류' : `${grade} 등급`;

  return (
    <span
      className={`inline-flex items-center justify-center w-7 h-7 rounded-full text-white text-[13px] font-bold shrink-0 ${bgClass}`}
      aria-label={label}
      title={label}
    >
      {grade ?? '-'}
    </span>
  );
}
