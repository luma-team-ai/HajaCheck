// 보고서 PDF 관련 공용 유틸 — 원래 features/report/utils/exportReportToPdf.ts에 있었으나
// features/mypage(MyReportListItem)에서도 동일 로직이 필요해져 feature 간 직접 import가
// 발생했다. React 코드 컨벤션("feature 간 직접 import 금지 — 공유가 필요해지면 shared/로 승격")에
// 따라 이 파일로 승격했다(#1472). 순수 이동이며 로직은 변경하지 않았다.

// 레거시 pdfUrl 정규화(#1186/#1235) — 과거 데이터에 "localhost:8080/api/reports/..."처럼
// 프로토콜 없이 저장된 값이 섞여 있어, fetch 전에 절대/상대 URL로 정리한다. Report.pdfUrl 컬럼을
// 읽는 화면(ReportGeneratePage, MyReportListItem 등) 어디서든 fetch 직전 이 함수를 거쳐야 한다.
export function normalizePdfPreviewUrl(pdfUrl: string): string {
  const trimmed = pdfUrl.trim();
  const candidate = /^localhost(?::\d+)?\//i.test(trimmed)
    ? `${window.location.protocol}//${trimmed}`
    : trimmed;

  try {
    const url = new URL(candidate, window.location.origin);
    if (url.pathname.startsWith("/api/reports/")) {
      return `${url.pathname}${url.search}`;
    }
    return url.href;
  } catch {
    return candidate;
  }
}

export function buildReportPdfFileName(inspectionId: number): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, "0");
  const dd = String(today.getDate()).padStart(2, "0");
  return `점검보고서_${inspectionId}_${yyyy}${mm}${dd}.pdf`;
}
