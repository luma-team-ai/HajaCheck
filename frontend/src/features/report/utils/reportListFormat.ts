import type { ReportListFilters, ReportListItem } from '../types';

// 보고서 목록/이력 관리(#463) 표시 포맷 — reports 테이블에 title 컬럼이 없다(V1 baseline schema,
// id/inspection_id/version/content_json/.../pdf_url/status/created_at/updated_at 뿐). mypage
// feature의 formatReportTitle(#844)과 동일 원칙 — "제목은 항상 facilityName+roundNo+날짜로 프론트가
// 조립"(전역 컨벤션: 표시 포맷은 클라이언트 책임). feature 간 직접 import 금지라 로컬로 재정의한다.

function formatRoundLabel(isoDate: string, roundNo: number): string {
  const yearSuffix = isoDate.slice(2, 4);
  const round = String(roundNo).padStart(2, '0');
  return `${yearSuffix}-${round}`;
}

export function formatReportListTitle(facilityName: string, updatedAtIso: string, roundNo: number): string {
  const round = formatRoundLabel(updatedAtIso, roundNo);
  return `[${round}] ${facilityName} 점검 보고서`;
}

export function filterReportListItems(
  items: ReportListItem[],
  filters: ReportListFilters,
): ReportListItem[] {
  const period = filters.period;
  const periodFromDate = (() => {
    if (!period || period === 'ALL') return null;
    const months = period === '1M' ? 1 : period === '3M' ? 3 : 6;
    const from = new Date();
    from.setMonth(from.getMonth() - months);
    return from;
  })();
  const query = filters.query?.trim().toLowerCase();

  return items.filter((item) => {
    if (filters.facilityId && item.facilityId !== filters.facilityId) return false;
    if (filters.roundNo && item.roundNo !== filters.roundNo) return false;
    if (filters.status && item.status !== filters.status) return false;
    if (query && !item.facilityName.toLowerCase().includes(query)) return false;
    if (periodFromDate && new Date(item.updatedAt) < periodFromDate) return false;
    return true;
  });
}
