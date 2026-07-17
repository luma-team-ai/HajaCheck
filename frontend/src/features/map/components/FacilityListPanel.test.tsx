// @vitest-environment jsdom
// FacilityListPanel 회귀 테스트 — Figma 대조 후속(선택 카드 강조, 등급 배지 형식,
// 결함/주의 심각도 아이콘 임계값) 검증(code-reviewer P2, 2026-07-17).
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { FacilityLocation } from '../types';
import { FacilityListPanel, getCountSeverityColor } from './FacilityListPanel';

afterEach(cleanup);

function buildFacility(overrides: Partial<FacilityLocation> = {}): FacilityLocation {
  return {
    id: 1,
    name: '한강대교 북단',
    address: '서울 용산구 이촌동 302-14',
    category: '교량',
    latitude: 37.5145,
    longitude: 126.9589,
    highestGrade: 'E',
    warningCount: 0,
    cautionCount: 0,
    thumbnailUrl: null,
    ...overrides,
  };
}

const noop = () => {};

describe('getCountSeverityColor', () => {
  it('10 이상이면 빨강(E 등급 색)을 반환한다', () => {
    expect(getCountSeverityColor(10)).toBe('#dc2626');
    expect(getCountSeverityColor(25)).toBe('#dc2626');
  });

  it('3 이상 10 미만이면 노랑(C 등급 색)을 반환한다', () => {
    expect(getCountSeverityColor(3)).toBe('#eab308');
    expect(getCountSeverityColor(9)).toBe('#eab308');
  });

  it('3 미만이면 초록(A 등급 색)을 반환한다', () => {
    expect(getCountSeverityColor(0)).toBe('#16a34a');
    expect(getCountSeverityColor(2)).toBe('#16a34a');
  });
});

describe('FacilityListPanel', () => {
  it('GradeBadge가 "등급 {A~E}" 형식으로 표시된다', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ highestGrade: 'E' })]}
        isLoading={false}
        isError={false}
        searchQuery=""
        onSearchQueryChange={noop}
        selectedCategory="전체"
        onSelectCategory={noop}
        selectedFacilityId={null}
        onSelectFacility={noop}
      />,
    );

    expect(screen.getByText('등급 E')).toBeTruthy();
  });

  it('선택된 카드는 bg-primary/5 강조 클래스를, 선택되지 않은 카드는 강조 클래스를 갖지 않는다', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ id: 1 }), buildFacility({ id: 2, name: '남산호텔점' })]}
        isLoading={false}
        isError={false}
        searchQuery=""
        onSearchQueryChange={noop}
        selectedCategory="전체"
        onSelectCategory={noop}
        selectedFacilityId={1}
        onSelectFacility={noop}
      />,
    );

    const selectedButton = screen.getByText('한강대교 북단').closest('button');
    const unselectedButton = screen.getByText('남산호텔점').closest('button');

    expect(selectedButton?.className).toContain('bg-primary/5');
    expect(unselectedButton?.className).not.toContain('bg-primary/5');
  });
});
