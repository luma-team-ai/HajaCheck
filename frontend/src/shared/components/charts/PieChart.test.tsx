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

    const { container } = render(
      <PieChart data={data} dataKey="ratio" nameKey="grade" itemKey="grade" ariaLabel="등급별 비율" />,
    );

    expect(container.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('등급별 비율');
    expect(container.querySelector('svg')).not.toBeNull();
    expect(container.querySelectorAll('.recharts-pie-sector').length).toBe(2);
  });

  it('data가 빈 배열이면 공통 빈 상태를 렌더링한다', () => {
    const { getByRole } = render(
      <PieChart<GradeShare>
        data={[]}
        dataKey="ratio"
        nameKey="grade"
        itemKey="grade"
        ariaLabel="빈 등급별 비율"
      />,
    );

    expect(getByRole('status', { name: '빈 등급별 비율' })).not.toBeNull();
  });

  it('빈 사용자 색상 배열은 기본 팔레트로 대체한다', () => {
    const data: GradeShare[] = [{ grade: 'A', ratio: 100 }];
    const { container } = render(
      <PieChart
        data={data}
        dataKey="ratio"
        nameKey="grade"
        itemKey="grade"
        colors={[]}
        ariaLabel="기본 팔레트 파이"
      />,
    );

    expect(container.querySelector('.recharts-pie-sector')).not.toBeNull();
  });

  it('항목은 있지만 모든 값이 0이면 공통 빈 상태를 렌더링한다', () => {
    const data: GradeShare[] = [
      { grade: 'A', ratio: 0 },
      { grade: 'B', ratio: 0 },
    ];
    const { getByRole } = render(
      <PieChart data={data} dataKey="ratio" nameKey="grade" itemKey="grade" ariaLabel="0인 등급별 비율" />,
    );

    expect(getByRole('status', { name: '0인 등급별 비율' })).not.toBeNull();
  });

  it('숫자가 아닌 필드는 조각 값으로 지정할 수 없다', () => {
    // @ts-expect-error grade는 숫자 필드가 아니므로 dataKey로 사용할 수 없다.
    const invalidDataKey: import('./types').ChartNumericKey<GradeShare> = 'grade';

    expect(invalidDataKey).toBe('grade');
  });
});
