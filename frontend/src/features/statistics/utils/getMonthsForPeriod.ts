/**
 * 기간 필터('3m' | '6m' | '1y')에 맞춰 오늘(현재 연·월)을 끝점으로 하는 N개월의 연속 월
 * 리스트('yyyy-MM')를 생성한다. 필터에 시작·종료일 지정 UI가 없는 상대 기간(최근 N개월)이므로
 * 축의 끝은 항상 오늘이다 — 그 달 점검이 0건이라 서버 응답(dataMonths)에 없어도 열이 빠지면 안
 * 된다(#1696: 이전에는 dataMonths의 마지막 월을 끝점으로 삼아 당월 점검이 0건일 때 당월 열 자체가
 * 사라지는 회귀가 있었다).
 * dataMonths는 끝점 결정에 관여하지 않고, 기간(N개월) 밖의 과거 월을 포괄하는 용도로만 쓰인다.
 */
export function getMonthsForPeriod(period?: string, dataMonths: string[] = []): string[] {
  const targetCount = period === '3m' ? 3 : period === '1y' ? 12 : 6;

  const now = new Date();
  const endYear = now.getFullYear();
  const endMonth = now.getMonth() + 1;

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
