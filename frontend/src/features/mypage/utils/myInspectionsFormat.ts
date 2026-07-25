// 마이페이지 "내 점검 이력 / 보고서" 표시 포맷 유틸(#844, HAJA-442) — BE는 원시값만 내려주고
// (roundNo:number, inspectionDate:ISO, issuedAt:ISO, fileSizeBytes:number|null) 화면 표기 조립은
// 전부 여기서 담당한다(전역 컨벤션: 표시 포맷은 클라이언트 책임).

// 회차 표기 조립 — 'yyyy-MM-dd' 또는 'yyyy-MM-ddTHH:mm:ss' 형태 ISO 문자열의 앞 2자리(연도 뒤 2자리)와
// 회차 번호(2자리 zero-pad)를 합쳐 '24-03' 형태로 만든다(handoff §2-2 계약).
export function formatRoundLabel(isoDate: string, roundNo: number): string {
  const yearSuffix = isoDate.slice(2, 4);
  const round = String(roundNo).padStart(2, '0');
  return `${yearSuffix}-${round}`;
}

// 점검일 표시 — ISO 'yyyy-MM-dd'(LocalDate 직렬화 기본형) → '2024.03.15'.
export function formatInspectionDate(inspectionDateIso: string): string {
  return inspectionDateIso.replaceAll('-', '.');
}

// 보고서 발급일 표시 — ISO datetime(LocalDateTime, 'yyyy-MM-ddTHH:mm:ss') → '2024.03.16'(날짜만).
export function formatIssuedDate(issuedAtIso: string): string {
  return issuedAtIso.slice(0, 10).replaceAll('-', '.');
}

// 파일 크기 표시 — bytes → '1.2MB'. null(PDF 조회 실패/미존재, handoff §2-3)이면 null을 그대로
// 반환해 호출부가 크기 표시 자체를 감추게 한다(값을 던지지 않는다 — 목록 전체를 죽이지 않는 계약과 정합).
export function formatFileSize(bytes: number | null): string | null {
  if (bytes == null) {
    return null;
  }
  const megabytes = bytes / (1024 * 1024);
  return `${megabytes.toFixed(1)}MB`;
}

// 보고서 제목 조립 — '[{yy-RR}] {facilityName} 점검 보고서'(handoff §2-3 — title 컬럼이 BE에 없어
// facilityName+roundNo로 프론트가 조립). yy는 발급일(issuedAt) 기준 — Report 엔티티에는 원 점검일이
// 없어(inspectionDate 미제공) 발급 시점 연도로 대체한다(회차 번호 자체는 report.roundNo 그대로).
export function formatReportTitle(facilityName: string, issuedAtIso: string, roundNo: number): string {
  const round = formatRoundLabel(issuedAtIso, roundNo);
  return `[${round}] ${facilityName} 점검 보고서`;
}
