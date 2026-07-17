// @vitest-environment jsdom
// SelectedFacilityPopup 등급 배지 fallback 색상 테스트 — GradeBadge와 동일한 FALLBACK_GRADE_COLOR
// 상수를 쓰는지 확인한다(P3, PR #265/#130 리뷰 — 이전엔 '#9CA3AF' 하드코딩이 중복돼 있었음).
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FALLBACK_GRADE_COLOR } from '../constants';
import type { DefectGrade, FacilityLocation } from '../types';
import { SelectedFacilityPopup } from './SelectedFacilityPopup';

const baseFacility: FacilityLocation = {
  id: 1,
  name: '한강대교 북단',
  address: '서울 용산구 이촌동 302-14',
  category: '교량',
  latitude: 37.5145,
  longitude: 126.9631,
  highestGrade: 'E',
  warningCount: 12,
  cautionCount: 5,
  thumbnailUrl: null,
};

describe('SelectedFacilityPopup', () => {
  it('실 API가 A~E 밖의 예상치 못한 등급 값을 내려줘도 GradeBadge와 동일한 FALLBACK_GRADE_COLOR를 배지 배경색으로 쓴다', () => {
    const facility: FacilityLocation = {
      ...baseFacility,
      // 런타임 방어 케이스 검증 — 백엔드가 계약 밖 값을 내려주는 상황을 흉내낸다
      highestGrade: 'Z' as unknown as DefectGrade,
    };

    render(
      <SelectedFacilityPopup facility={facility} onViewDetail={() => {}} onGoToInspectionResult={() => {}} />,
    );

    // jest-dom 매처는 이 프로젝트에 setup되어 있지 않아 기본 매처로 검증.
    // jsdom은 inline style의 hex 색상을 rgb()로 정규화해 저장하므로 동일하게 변환해 비교한다.
    const badge = screen.getByText('Z');
    const probe = document.createElement('div');
    probe.style.backgroundColor = FALLBACK_GRADE_COLOR;
    expect((badge.parentElement as HTMLElement).style.backgroundColor).toBe(probe.style.backgroundColor);
  });
});
