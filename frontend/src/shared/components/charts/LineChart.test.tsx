// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LineChart } from './LineChart';
import { mockChartContainerSize } from './rechartsTestEnv';
import type { ChartSeries } from './types';

afterEach(cleanup);

interface MonthlyCount {
  month: string;
  count: number;
}

describe('LineChart', () => {
  beforeEach(() => {
    mockChartContainerSize();
  });

  it('데이터가 있으면 svg와 시리즈별 line을 렌더링한다', () => {
    const data: MonthlyCount[] = [
      { month: '1월', count: 10 },
      { month: '2월', count: 20 },
    ];

    const { container } = render(
      <LineChart data={data} xKey="month" series={[{ dataKey: 'count', name: '건수' }]} ariaLabel="월별 건수 추이" />,
    );

    expect(container.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('월별 건수 추이');
    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-line').length).toBe(1);
  });

  it('data가 빈 배열이면 공통 빈 상태를 렌더링한다', () => {
    const { getByRole } = render(
      <LineChart<MonthlyCount>
        data={[]}
        xKey="month"
        series={[{ dataKey: 'count' }]}
        ariaLabel="빈 월별 건수 추이"
        emptyMessage="월별 데이터가 없습니다."
      />,
    );

    expect(getByRole('status', { name: '빈 월별 건수 추이' }).textContent).toBe('월별 데이터가 없습니다.');
  });

  it('존재하지 않는 dataKey는 타입 오류로 차단한다', () => {
    const invalidSeries: ChartSeries<MonthlyCount>[] = [
      // @ts-expect-error MonthlyCount에 unknownCount 필드는 없다.
      { dataKey: 'unknownCount' },
    ];

    expect(invalidSeries).toHaveLength(1);
  });
});
