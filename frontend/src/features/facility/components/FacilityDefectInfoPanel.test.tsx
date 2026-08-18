// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FacilityDefectInfoPanel } from './FacilityDefectInfoPanel';
import type { FacilityDefectDetail } from '../types';

afterEach(cleanup);

const baseDefect: FacilityDefectDetail = {
  id: 1,
  inspectionId: 1,
  facilityId: 1,
  facilityName: '강남 오피스타워 A동',
  defectType: '균열',
  grade: 'C',
  confidencePercent: 92,
  widthMm: null,
  lengthM: null,
  areaMm2: null,
  foundCycle: 1,
  foundAt: '2026-08-01',
  location: null,
  assigneeName: '김검수', // '—' placeholder가 크기 필드에만 대응하도록 고정(담당은 별도 검증 대상 아님)
  status: 'DETECTED',
  imageUrl: null,
  bboxX: null,
  bboxY: null,
  bboxW: null,
  bboxH: null,
};

describe('FacilityDefectInfoPanel', () => {
  it('폭·길이가 둘 다 있으면 함께 표시한다', () => {
    render(
      <FacilityDefectInfoPanel
        defect={{ ...baseDefect, widthMm: 0.8, lengthM: 2.4 }}
        onTransitionClick={vi.fn()}
      />,
    );

    expect(screen.getByText('폭 0.8mm · 길이 2.4m')).not.toBeNull();
  });

  it('#1588 후속 — 폭만 있고 길이가 없어도(ai-server가 길이는 계산하지 않음) 폭은 보여준다', () => {
    render(
      <FacilityDefectInfoPanel
        defect={{ ...baseDefect, widthMm: 0.8, lengthM: null }}
        onTransitionClick={vi.fn()}
      />,
    );

    expect(screen.getByText('폭 0.8mm')).not.toBeNull();
    expect(screen.queryByText('—')).toBeNull();
  });

  it('폭·길이가 둘 다 없으면 placeholder를 표시한다', () => {
    render(
      <FacilityDefectInfoPanel
        defect={{ ...baseDefect, widthMm: null, lengthM: null }}
        onTransitionClick={vi.fn()}
      />,
    );

    expect(screen.getByText('—')).not.toBeNull();
  });

  // #1658/#1669 — 박리박락·철근노출은 widthMm/lengthM 대신 areaMm2로 크기를 나타낸다.
  it('폭·길이가 없어도 실측 면적(areaMm2)이 있으면 표시한다', () => {
    render(
      <FacilityDefectInfoPanel
        defect={{ ...baseDefect, widthMm: null, lengthM: null, areaMm2: 4200.5 }}
        onTransitionClick={vi.fn()}
      />,
    );

    expect(screen.getByText('면적 4200.5mm²')).not.toBeNull();
    expect(screen.queryByText('—')).toBeNull();
  });

  it('폭·길이·면적이 모두 없으면 placeholder를 표시한다(카드 기준물 미검출)', () => {
    render(
      <FacilityDefectInfoPanel
        defect={{ ...baseDefect, widthMm: null, lengthM: null, areaMm2: null }}
        onTransitionClick={vi.fn()}
      />,
    );

    expect(screen.getByText('—')).not.toBeNull();
  });
});
