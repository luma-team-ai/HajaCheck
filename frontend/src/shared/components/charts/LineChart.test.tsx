// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LineChart } from './LineChart';
import { mockChartContainerSize } from './rechartsTestEnv';

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
      <LineChart data={data} xKey="month" series={[{ dataKey: 'count', name: '건수' }]} />,
    );

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-line').length).toBe(1);
  });

  it('data가 빈 배열이어도 에러 없이 svg를 렌더링한다', () => {
    const { container } = render(<LineChart<MonthlyCount> data={[]} xKey="month" series={[{ dataKey: 'count' }]} />);

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-line').length).toBe(0);
  });
});
