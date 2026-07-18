// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mockChartContainerSize } from '../../shared/components/charts/rechartsTestEnv';
import { ChartShowcasePage } from './ChartShowcasePage';

afterEach(cleanup);

describe('ChartShowcasePage', () => {
  beforeEach(() => {
    mockChartContainerSize(900, 300);
  });

  it('DTO 예시 데이터로 세 차트 유형과 빈 상태를 함께 렌더링한다', () => {
    const { container, getByRole } = render(<ChartShowcasePage />);

    expect(getByRole('heading', { name: 'Recharts 공용 컴포넌트 쇼케이스' })).not.toBeNull();
    expect(container.querySelectorAll('.recharts-line').length).toBe(1);
    expect(container.querySelectorAll('.recharts-bar-rectangle').length).toBe(5);
    expect(container.querySelectorAll('.recharts-pie').length).toBe(2);
    expect(getByRole('status', { name: '빈 점검 추이 차트' })).not.toBeNull();
  });
});
