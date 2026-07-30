// 시설물 카드 "최근 점검 MM.dd"(HAJA-514/#1074) — ISO date(YYYY-MM-DD) → "06.21".
// mypage/utils/myInspectionsFormat.ts의 slice+replace 관례와 동일한 스타일이나
// cross-feature import 금지(React_코드_컨벤션.md §1)라 facility 로컬로 재정의한다.
export function formatLastInspectedAt(lastInspectedAt: string | null): string | null {
  if (!lastInspectedAt) {
    return null;
  }
  return lastInspectedAt.slice(5, 10).replaceAll('-', '.');
}
