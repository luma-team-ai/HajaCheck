import type { GradeDistributionItem, RecentInspectionItem } from '../../features/dashboard/types';

/** 실제 Dashboard API 응답 DTO 형식과 동일한 쇼케이스 전용 예시 데이터. */
export const showcaseGradeDistribution = [
  { grade: 'A', percent: 38 },
  { grade: 'B', percent: 27 },
  { grade: 'C', percent: 19 },
  { grade: 'D', percent: 11 },
  { grade: 'E', percent: 5 },
] satisfies GradeDistributionItem[];

/** 실제 RecentInspectionItem 응답 형식을 유지하며 개인정보가 없는 가상 시설명만 사용한다. */
export const showcaseRecentInspections = [
  {
    id: 901,
    facilityName: '한빛 업무시설',
    inspectedAt: '2026-07-01',
    inspector: '점검 담당자 A',
    defectCount: 3,
    status: '완료',
  },
  {
    id: 902,
    facilityName: '새롬 복합센터',
    inspectedAt: '2026-07-03',
    inspector: '점검 담당자 B',
    defectCount: 8,
    status: '조치대기',
  },
  {
    id: 903,
    facilityName: '푸른 물류동',
    inspectedAt: '2026-07-05',
    inspector: '점검 담당자 C',
    defectCount: 0,
    status: '분석중',
  },
  {
    id: 904,
    facilityName: '가람 연구시설',
    inspectedAt: '2026-07-07',
    inspector: '점검 담당자 D',
    defectCount: 5,
    status: '검수대기',
  },
  {
    id: 905,
    facilityName: '누리 장기시설물명 예시 센터',
    inspectedAt: '2026-07-09',
    inspector: '점검 담당자 E',
    defectCount: 12,
    status: '완료',
  },
  {
    id: 906,
    facilityName: '마루 업무동',
    inspectedAt: '2026-07-11',
    inspector: '점검 담당자 F',
    defectCount: 7,
    status: '완료',
  },
] satisfies RecentInspectionItem[];
