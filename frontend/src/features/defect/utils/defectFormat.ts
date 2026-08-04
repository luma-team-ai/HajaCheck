// 하자 화면과 exportDefectsToPdf(PDF 내보내기)가 동일한 표기를 쓰도록 공용화한
// 포맷 함수(code-reviewer P3 — 포맷이 바뀔 때 한쪽만 고치고 놓치는 걸 방지).
export function formatDefectCode(id: number): string {
  return `DEF-${String(id).padStart(4, '0')}`;
}

export function formatDefectDate(createdAt: string): string {
  return createdAt.slice(2, 10).replaceAll('-', '.');
}

export function formatDefectActivityDateTime(createdAt: string): string {
  const date = new Date(createdAt);
  const pad = (value: number) => String(value).padStart(2, '0');

  return (
    `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

// 점검 목록(InspectionTable)과 하자 상세 헤더(InspectionDefectsPage)가 동일한 표기를 쓰도록
// 공용화(#1179 — 하자 상세 헤더가 "점검 #101" 형식으로 따로 노출돼 목록의 INS-0101 표기와 어긋났다).
export function formatInspectionCode(id: number): string {
  return `INS-${String(id).padStart(4, '0')}`;
}
