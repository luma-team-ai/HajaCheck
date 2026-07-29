/**
 * 기간 필터('3m' | '6m' | '1y')와 서버 수신 월 목록에 맞춰 N개월의 연속 월 리스트('yyyy-MM')를 생성한다.
 * 데이터에 포함되지 않은 전월이더라도 필터 기간(3개월/6개월/12개월)에 맞춰 N개월치 열을 모두 표시한다.
 */
export function getMonthsForPeriod(period?: string, dataMonths: string[] = []): string[] {
  const targetCount = period === '3m' ? 3 : period === '1y' ? 12 : 6;

  let endYear: number;
  let endMonth: number;

  if (dataMonths.length > 0) {
    const lastMonthStr = [...dataMonths].sort().pop()!;
    const [y, m] = lastMonthStr.split('-').map(Number);
    endYear = y || new Date().getFullYear();
    endMonth = m || new Date().getMonth() + 1;
  } else {
    const now = new Date();
    endYear = now.getFullYear();
    endMonth = now.getMonth() + 1;
  }

  const result: string[] = [];
  for (let i = targetCount - 1; i >= 0; i--) {
    let year = endYear;
    let month = endMonth - i;
    while (month <= 0) {
      month += 12;
      year -= 1;
    }
    const monthStr = `${year}-${String(month).padStart(2, '0')}`;
    result.push(monthStr);
  }

  // 데이터에 더 넓은 범위의 월이 존재할 경우 포괄하여 오름차순 정렬
  const combined = Array.from(new Set([...result, ...dataMonths])).sort();
  return combined;
}
