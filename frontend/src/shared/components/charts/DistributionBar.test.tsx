// @vitest-environment jsdom
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DistributionBar } from './DistributionBar';

afterEach(cleanup);

describe('DistributionBar', () => {
  it('세그먼트가 있으면 비율만큼 너비를 갖는 바와 범례를 렌더링한다', () => {
    const { container, getByText } = render(
      <DistributionBar
        ariaLabel="하자 등급 분포"
        segments={[
          { key: 'A', label: 'A 등급', percent: 45, color: '#16a34a' },
          { key: 'B', label: 'B 등급', percent: 55, color: '#65a30d' },
        ]}
      />,
    );

    const bar = container.querySelector('[role="img"]');
    expect(bar?.getAttribute('aria-label')).toBe('하자 등급 분포');
    expect(bar?.children.length).toBe(2);
    expect((bar?.children[0] as HTMLElement).style.width).toBe('45%');
    expect((bar?.children[1] as HTMLElement).style.width).toBe('55%');
    expect(getByText('A 등급 (45%)')).not.toBeNull();
    expect(getByText('B 등급 (55%)')).not.toBeNull();
  });

  it('segments가 빈 배열이면 공통 빈 상태를 렌더링한다', () => {
    const { getByRole } = render(<DistributionBar ariaLabel="빈 분포" segments={[]} />);

    expect(getByRole('status', { name: '빈 분포' })).not.toBeNull();
  });

  it('모든 세그먼트의 percent가 0이면 공통 빈 상태를 렌더링한다', () => {
    const { getByRole } = render(
      <DistributionBar
        ariaLabel="0퍼센트 분포"
        segments={[{ key: 'A', label: 'A 등급', percent: 0, color: '#16a34a' }]}
      />,
    );

    expect(getByRole('status', { name: '0퍼센트 분포' })).not.toBeNull();
  });

  it('showLegend가 false면 범례를 렌더링하지 않는다', () => {
    const { queryByText } = render(
      <DistributionBar
        ariaLabel="범례 없는 분포"
        showLegend={false}
        segments={[{ key: 'A', label: 'A 등급', percent: 100, color: '#16a34a' }]}
      />,
    );

    expect(queryByText('A 등급 (100%)')).toBeNull();
  });
});
