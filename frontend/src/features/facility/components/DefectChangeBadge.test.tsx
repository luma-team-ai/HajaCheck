// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DefectChangeBadge } from './DefectChangeBadge';
import { DEFECT_CHANGE_TYPE_LABEL } from '../constants';
import type { DefectChangeType } from '../types';

afterEach(cleanup);

describe('DefectChangeBadge', () => {
  it.each(Object.keys(DEFECT_CHANGE_TYPE_LABEL) as DefectChangeType[])(
    '%s 타입은 대응 라벨을 렌더링한다',
    (changeType) => {
      render(<DefectChangeBadge changeType={changeType} />);
      expect(screen.getByText(DEFECT_CHANGE_TYPE_LABEL[changeType])).not.toBeNull();
    },
  );

  it('악화는 빨강 계열 배지 클래스를 사용한다', () => {
    render(<DefectChangeBadge changeType="worsened" />);
    expect(screen.getByText('악화').className).toContain('text-[#dc2626]');
  });

  it('조치완료는 초록 계열 배지 클래스를 사용한다', () => {
    render(<DefectChangeBadge changeType="resolved" />);
    expect(screen.getByText('조치완료').className).toContain('text-[#16a34a]');
  });
});