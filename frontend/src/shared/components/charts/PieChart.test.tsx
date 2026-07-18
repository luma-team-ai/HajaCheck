// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PieChart } from './PieChart';
import { mockChartContainerSize } from './rechartsTestEnv';

afterEach(cleanup);

interface GradeShare {
  grade: string;
  ratio: number;
}

describe('PieChart', () => {
  beforeEach(() => {
    mockChartContainerSize();
  });

  it('데이터가 있으면 svg와 조각(cell)을 렌더링한다', () => {
    const data: GradeShare[] = [
      { grade: 'A', ratio: 60 },
      { grade: 'B', ratio: 40 },
    ];

    const { container } = render(<PieChart data={data} dataKey="ratio" nameKey="grade" />);

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-pie-sector').length).toBe(2);
  });

  it('data가 빈 배열이어도 에러 없이 svg를 렌더링한다', () => {
    const { container } = render(<PieChart<GradeShare> data={[]} dataKey="ratio" nameKey="grade" />);

    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-pie-sector').length).toBe(0);
  });
});
