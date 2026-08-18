// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { FALLBACK_GRADE_COLOR, FALLBACK_GRADE_LABEL, GRADE_COLOR, GRADE_LABEL } from '../constants';
import { buildInfoWindowContent, createFacilityMarker } from './createFacilityMarker';
import type { PositionedFacilityLocation } from '../types';

// isValidCoordinate 자체의 단위 테스트는 shared/lib/isValidCoordinate.test.ts로 이동했다(#1657 —
// shared로 승격되며 단일 소스가 됨). 이 파일은 마커 생성(createFacilityMarker)만 검증한다.
const baseFacility: PositionedFacilityLocation = {
  id: 1,
  name: '테스트 시설물',
  address: '서울 강남구 테헤란로 1',
  category: '건물',
  latitude: 37.5,
  longitude: 127.0,
  highestGrade: 'E',
  warningCount: 2,
  cautionCount: 1,
  thumbnailUrl: null,
};

// jsdom은 style.color를 rgb()로 정규화해 반환하므로 동일한 정규화를 거쳐 비교한다
function toRgb(hex: string): string {
  const probe = document.createElement('span');
  probe.style.color = hex;
  return probe.style.color;
}

describe('buildInfoWindowContent', () => {
  it('정의된 등급이면 GRADE_COLOR/GRADE_LABEL 값을 그대로 사용한다', () => {
    const content = buildInfoWindowContent(baseFacility);
    const gradeEl = content.querySelector('span') as HTMLSpanElement;
    expect(gradeEl.textContent).toBe(GRADE_LABEL.E);
    expect(gradeEl.style.color).toBe(toRgb(GRADE_COLOR.E));
  });

  it('알 수 없는 등급 값이면 fallback 색상/라벨로 대체한다', () => {
    const facility = { ...baseFacility, highestGrade: 'UNKNOWN' } as unknown as PositionedFacilityLocation;
    const content = buildInfoWindowContent(facility);
    const gradeEl = content.querySelector('span') as HTMLSpanElement;
    expect(gradeEl.textContent).toBe(FALLBACK_GRADE_LABEL);
    expect(gradeEl.style.color).toBe(toRgb(FALLBACK_GRADE_COLOR));
  });
});

describe('createFacilityMarker', () => {
  function stubKakaoMaps() {
    const addListener = vi.fn();
    (window as unknown as { kakao: unknown }).kakao = {
      maps: {
        LatLng: vi.fn(),
        Size: vi.fn(),
        Point: vi.fn(function Point(this: Record<string, unknown>, x: number, y: number) {
          this.x = x;
          this.y = y;
        }),
        MarkerImage: vi.fn(),
        Marker: vi.fn(function Marker(this: Record<string, unknown>, options: Record<string, unknown>) {
          Object.assign(this, options);
          this.setZIndex = vi.fn((zIndex: number) => {
            this.zIndex = zIndex;
          });
          this.setImage = vi.fn();
        }),
        event: { addListener },
      },
    };
    return { addListener };
  }

  it('알 수 없는 등급이어도 예외 없이 fallback 색상으로 마커 이미지를 생성한다', () => {
    stubKakaoMaps();
    const facility = { ...baseFacility, highestGrade: 'UNKNOWN' } as unknown as PositionedFacilityLocation;

    expect(() => createFacilityMarker({} as never, facility, vi.fn())).not.toThrow();
    const markerImageCall = (window.kakao.maps.MarkerImage as ReturnType<typeof vi.fn>).mock.calls[0];
    const decodedSvg = atob((markerImageCall[0] as string).split(',')[1]);
    expect(decodedSvg).toContain(FALLBACK_GRADE_COLOR);
  });

  it('마커 클릭 시 onSelect가 facility와 마커 인스턴스를 함께 전달한다', () => {
    const { addListener } = stubKakaoMaps();
    const onSelect = vi.fn();

    const marker = createFacilityMarker({} as never, baseFacility, onSelect);
    const clickHandler = addListener.mock.calls[0][2] as () => void;
    clickHandler();

    expect(onSelect).toHaveBeenCalledWith(baseFacility, marker);
  });

  it('isSelected가 true이면 Figma 시안 정합 콤팩트 선택 마커(32x36) SVG 마커와 z-index 20을 생성한다', () => {
    stubKakaoMaps();
    const marker = createFacilityMarker({} as never, baseFacility, vi.fn(), true);

    const markerImageCall = (window.kakao.maps.MarkerImage as ReturnType<typeof vi.fn>).mock.calls[0];
    const decodedSvg = atob((markerImageCall[0] as string).split(',')[1]);
    expect(decodedSvg).toContain('viewBox="0 0 32 36"');
    expect((marker as unknown as { zIndex: number }).zIndex).toBe(20);
  });
});
