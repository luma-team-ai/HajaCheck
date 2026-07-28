import type { DefectGrade, DefectStatus } from '../types';

// 하자 카드·점검 요약·PDF가 동일한 등급/상태 표현을 사용하도록 feature 상수로 관리한다.
export const GRADE_CLASSES: Record<DefectGrade, string> = {
  A: 'border-emerald-200 bg-emerald-50 text-emerald-600',
  B: 'border-sky-200 bg-sky-50 text-sky-600',
  C: 'border-amber-200 bg-amber-50 text-amber-600',
  D: 'border-orange-200 bg-orange-50 text-orange-600',
  E: 'border-red-200 bg-red-50 text-red-500',
};

// 화면 배지와 PDF 내보내기의 상태 표기를 일치시킨다.
export const STATUS_PRESENTATION: Record<
  DefectStatus,
  { label: string; className: string }
> = {
  DETECTED: {
    label: '신규',
    className: 'border-blue-200 bg-blue-50 text-blue-500',
  },
  CONFIRMED: {
    label: '검수확정',
    className: 'border-zinc-200 bg-zinc-50 text-zinc-700',
  },
  IN_PROGRESS: {
    label: '조치중',
    className: 'border-orange-200 bg-orange-50 text-orange-500',
  },
  RESOLVED: {
    label: '조치완료',
    className: 'border-emerald-200 bg-emerald-50 text-emerald-600',
  },
};
