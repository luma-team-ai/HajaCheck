// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { FALLBACK_GRADE_COLOR, FALLBACK_GRADE_LABEL, GRADE_COLOR, GRADE_LABEL } from '../constants';
import { buildInfoWindowContent, createFacilityMarker, isValidCoordinate } from './createFacilityMarker';
import type { FacilityLocation } from '../types';

const baseFacility: FacilityLocation = {
  id: 1,
  name: '테스트 시설물',
  latitude: 37.5,
  longitude: 127.0,
  highestGrade: 'RED',
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
    expect(gradeEl.textContent).toBe(GRADE_LABEL.RED);
    expect(gradeEl.style.color).toBe(toRgb(GRADE_COLOR.RED));
  });

  it('알 수 없는 등급 값이면 fallback 색상/라벨로 대체한다', () => {
    const facility = { ...baseFacility, highestGrade: 'UNKNOWN' } as unknown as FacilityLocation;
    const content = buildInfoWindowContent(facility);
    const gradeEl = content.querySelector('span') as HTMLSpanElement;
    expect(gradeEl.textContent).toBe(FALLBACK_GRADE_LABEL);
    expect(gradeEl.style.color).toBe(toRgb(FALLBACK_GRADE_COLOR));
  });
});

describe('isValidCoordinate', () => {
  it('유효 범위 내 좌표는 true를 반환한다', () => {
    expect(isValidCoordinate(37.5, 127.0)).toBe(true);
    expect(isValidCoordinate(-90, -180)).toBe(true);
    expect(isValidCoordinate(90, 180)).toBe(true);
  });

  it('(0,0)은 EXIF GPS 결측 센티널로 간주해 false를 반환한다', () => {
    expect(isValidCoordinate(0, 0)).toBe(false);
  });

  it('NaN 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(NaN, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, NaN)).toBe(false);
  });

  it('Infinity 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(Infinity, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, -Infinity)).toBe(false);
  });

  it('범위를 벗어난 좌표는 false를 반환한다', () => {
    expect(isValidCoordinate(91, 127.0)).toBe(false);
    expect(isValidCoordinate(-91, 127.0)).toBe(false);
    expect(isValidCoordinate(37.5, 181)).toBe(false);
    expect(isValidCoordinate(37.5, -181)).toBe(false);
  });
});

describe('createFacilityMarker', () => {
  function stubKakaoMaps() {
    const addListener = vi.fn();
    (window as unknown as { kakao: unknown }).kakao = {
      maps: {
        LatLng: vi.fn(),
        Size: vi.fn(),
        MarkerImage: vi.fn(),
        Marker: vi.fn(function Marker(this: Record<string, unknown>, options: Record<string, unknown>) {
          Object.assign(this, options);
        }),
        event: { addListener },
      },
    };
    return { addListener };
  }

  it('알 수 없는 등급이어도 예외 없이 fallback 색상으로 마커 이미지를 생성한다', () => {
    stubKakaoMaps();
    const facility = { ...baseFacility, highestGrade: 'UNKNOWN' } as unknown as FacilityLocation;

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
});
