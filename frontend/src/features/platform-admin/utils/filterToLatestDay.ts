/**
 * "YYYY-MM-DD ..."로 시작하는 타임스탬프를 기준으로, 데이터에 존재하는 날짜 중 가장 최근 날짜에
 * 해당하는 항목만 남긴다 — 분석 잡 큐·에러 로그를 "1일치만" 노출하라는 요구(#729 후속)에 대응.
 * 서버가 아직 기간 필터 파라미터를 지원하지 않아, 우선 클라이언트에서 최신 날짜로 좁힌다.
 */
export function filterToLatestDay<T>(items: T[], getTimestamp: (item: T) => string): T[] {
  if (items.length === 0) return items;

  const latestDate = items.reduce((max, item) => {
    const date = getTimestamp(item).slice(0, 10);
    return date > max ? date : max;
  }, getTimestamp(items[0]).slice(0, 10));

  return items.filter((item) => getTimestamp(item).slice(0, 10) === latestDate);
}
