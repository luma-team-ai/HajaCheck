// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { BarChart } from './BarChart';
import { mockChartContainerSize } from './rechartsTestEnv';

afterEach(cleanup);

interface GradeCount {
  grade: string;
  count: number;
}

describe('BarChart', () => {
  beforeEach(() => {
    mockChartContainerSize();
  });

  it('데이터가 있으면 svg와 시리즈별 bar를 렌더링한다', () => {
    const data: GradeCount[] = [
      { grade: 'A', count: 5 },
      { grade: 'B', count: 3 },
    ];

    const { container } = render(
      <BarChart data={data} xKey="grade" series={[{ dataKey: 'count', name: '건수' }]} />,
    );

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-bar-rectangle').length).toBe(2);
  });

  it('data가 빈 배열이어도 에러 없이 svg를 렌더링한다', () => {
    const { container } = render(<BarChart<GradeCount> data={[]} xKey="grade" series={[{ dataKey: 'count' }]} />);

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-bar-rectangle').length).toBe(0);
  });
});
