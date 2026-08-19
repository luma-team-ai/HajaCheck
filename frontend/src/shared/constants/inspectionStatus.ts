// 점검(회차) 진행 상태 — 백엔드 Inspection.status PG enum(6종)과 1:1.
//
// shared로 승격한 이유(#1693): defect feature(features/defect/types.ts)와 mypage
// feature(features/mypage/statusClasses.ts)가 같은 이름(INSPECTION_STATUS_LABEL)의 상수를 각자
// 다른 내용으로 로컬 정의하고 있었다 — defect는 실 6종 라벨, mypage는 백엔드가 6종을 3종으로 압축해
// 내려주는 MyInspectionDisplayStatus 라벨(별도 타입, InspectionHistoryStatus)이라 같은 이름인데
// 값이 전혀 달라 오독을 유발했다(#1693 배경). 실 6종 정의는 여기 하나로 단일화하고, defect/types.ts는
// re-export로만 남긴다(기존 참조처 4곳 — InspectionTable/InspectionFilterBar/InspectionAppliedFilters/
// inspectionListFiltersUrl — 무변경). mypage 쪽 3종 상수는 이름을 MY_INSPECTION_DISPLAY_*로 바꿔
// 서로 다른 축임을 이름에서부터 구분한다(statusClasses.ts 참고).
//
// ⚠️ 라벨 문구는 절대 바꾸지 않는다 — 목록 화면(InspectionTable 등) 회귀 및 URL 필터
// 직렬화(defect/utils/inspectionListFiltersUrl.ts)에 그대로 노출되는 값이다.
export type InspectionStatus = 'CREATED' | 'UPLOADING' | 'ANALYZING' | 'ANALYZED' | 'REVIEWED' | 'REPORTED';

export const INSPECTION_STATUS_LABEL: Record<InspectionStatus, string> = {
  CREATED: '생성됨',
  UPLOADING: '업로드중',
  ANALYZING: '분석중',
  ANALYZED: '분석완료',
  REVIEWED: '검수완료',
  REPORTED: '보고완료',
};

// 점검 상태 배지 dot 색상 — mypage/statusClasses.ts의 기존 톤(검수완료=emerald·검수대기=amber·
// 분석중=blue)을 참고해 6종 전체로 확장한다. 신규 hex 도입 없이 Tailwind 표준 팔레트만 사용
// (defect/constants/defectPresentation.ts와 동일 컨벤션): 생성됨(zinc, 아직 아무 작업 전) →
// 업로드중(sky) → 분석중(blue, mypage와 동일 색) → 분석완료(amber, mypage REVIEW_PENDING과 같은
// "검수 대기" 성격) → 검수완료(emerald, mypage REVIEW_DONE과 동일 색) → 보고완료(진한 emerald,
// 검수완료와 같은 계열이되 최종 종료 상태임을 명도로 구분).
export const INSPECTION_STATUS_DOT_CLASS: Record<InspectionStatus, string> = {
  CREATED: 'bg-zinc-400',
  UPLOADING: 'bg-sky-500',
  ANALYZING: 'bg-blue-500',
  ANALYZED: 'bg-amber-500',
  REVIEWED: 'bg-emerald-500',
  REPORTED: 'bg-emerald-600',
};
