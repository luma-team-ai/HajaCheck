// @vitest-environment jsdom
// FacilityListPanel 회귀 테스트 — Figma 대조 후속(선택 카드 강조, 등급 배지 형식,
// 결함/주의 심각도 아이콘 임계값) 검증(code-reviewer P2, 2026-07-17).
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
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

  it('좌표(latitude/longitude)가 유효하면 "좌표 없음" 배지를 표시하지 않는다', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ latitude: 37.5145, longitude: 126.9631 })]}
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

    expect(screen.queryByText('좌표 없음')).toBeNull();
  });

  it('좌표가 null이어도 목록에는 남기고 "좌표 없음" 배지를 표시한다(#1657 — 마커에서만 제외)', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ latitude: null, longitude: null, address: null })]}
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

    expect(screen.getByText('한강대교 북단')).toBeTruthy();
    expect(screen.getByText('좌표 없음')).toBeTruthy();
    expect(screen.getByText('주소 정보 없음')).toBeTruthy();
  });

  it('좌표가 (0,0)·범위밖 등 유효하지 않아도 "좌표 없음" 배지를 표시한다(EXIF GPS 결측 센티널)', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ latitude: 0, longitude: 0 })]}
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

    expect(screen.getByText('좌표 없음')).toBeTruthy();
  });

  it('headerAction prop을 검색/필터 영역 하단에 렌더링한다(#1657 — 좌표 일괄 보정 버튼 배선용 슬롯)', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility()]}
        isLoading={false}
        isError={false}
        searchQuery=""
        onSearchQueryChange={noop}
        selectedCategory="전체"
        onSelectCategory={noop}
        selectedFacilityId={null}
        onSelectFacility={noop}
        headerAction={<button type="button">일괄 보정</button>}
      />,
    );

    expect(screen.getByRole('button', { name: '일괄 보정' })).toBeTruthy();
  });

  it('썸네일 로드가 실패하면 alt 텍스트 대신 "사진 없음"을 표시한다', () => {
    render(
      <FacilityListPanel
        facilities={[buildFacility({ thumbnailUrl: '/api/media/missing/thumbnail' })]}
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

    // ImageWithFallback은 첫 실패를 즉시 fallback으로 고정하지 않고 1회 재시도한다(#1494) —
    // 재시도 대기 중인 같은 <img>에 에러를 다시 쏴서 "재시도까지 실패"를 재현한다.
    const image = screen.getByRole('img', { name: '한강대교 북단' });
    fireEvent.error(image);
    fireEvent.error(image);

    expect(screen.getByText('사진 없음')).toBeTruthy();
    expect(screen.queryByRole('img', { name: '한강대교 북단' })).toBeNull();
  });
});
