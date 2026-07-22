// DefectTable(화면 표시)과 exportDefectsToPdf(PDF 내보내기)가 동일한 표기를 쓰도록 공용화한
// 포맷 함수(code-reviewer P3 — 포맷이 바뀔 때 한쪽만 고치고 놓치는 걸 방지).
export function formatDefectCode(id: number): string {
  return `DEF-${String(id).padStart(4, '0')}`;
}

export function formatDefectDate(createdAt: string): string {
  return createdAt.slice(2, 10).replaceAll('-', '.');
}
