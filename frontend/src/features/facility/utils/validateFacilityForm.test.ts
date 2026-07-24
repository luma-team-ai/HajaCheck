import { describe, expect, it } from 'vitest';
import {
  FACILITY_FORM_INITIAL_VALUES,
  hasFacilityFormErrors,
  toCreateFacilityRequest,
  validateFacilityForm,
} from './validateFacilityForm';

describe('validateFacilityForm', () => {
  it('name과 type이 비어있으면 필수 에러를 반환한다', () => {
    const errors = validateFacilityForm(FACILITY_FORM_INITIAL_VALUES);

    expect(errors.name).toBe('시설물명을 입력해 주세요.');
    expect(errors.type).toBe('시설물 유형을 선택해 주세요.');
    expect(hasFacilityFormErrors(errors)).toBe(true);
  });

  it('필수 값만 채우면 에러가 없다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
    });

    expect(hasFacilityFormErrors(errors)).toBe(false);
  });

  it('준공년도가 정수가 아니면 에러를 반환한다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      builtYear: '2008.5',
    });

    expect(errors.builtYear).toBeDefined();
  });

  // #224 P2 → #352: 정수 검사만 있으면 -100·999999 같은 값이 그대로 서버로 전송된다.
  // 상한은 현재연도+1("내년 준공 예정" 허용)이라 테스트도 연도를 동적으로 계산한다(하드코딩 시 해가 바뀌면 깨짐).
  it.each([
    ['하한 미만', '1899'],
    ['음수', '-100'],
    ['과대값', '999999'],
    ['상한 초과(현재연도+2)', String(new Date().getFullYear() + 2)],
  ])('준공년도가 범위를 벗어나면(%s) 에러를 반환한다', (_label, builtYear) => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      builtYear,
    });

    expect(errors.builtYear).toBeDefined();
  });

  it.each([
    ['하한 경계', '1900'],
    ['상한 경계(현재연도+1)', String(new Date().getFullYear() + 1)],
    ['일반값', '2008'],
  ])('준공년도가 범위 안이면(%s) 에러가 없다', (_label, builtYear) => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      builtYear,
    });

    expect(errors.builtYear).toBeUndefined();
  });

  it('시설물명이 최대 길이를 넘으면 에러를 반환한다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: 'a'.repeat(201),
      type: '건물',
    });

    expect(errors.name).toBeDefined();
  });

  it('담당자 ID가 정수가 아니면 에러를 반환한다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      assigneeUserId: 'abc',
    });

    expect(errors.assigneeUserId).toBeDefined();
  });

  it('메모가 최대 길이를 넘으면 에러를 반환한다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      memo: 'a'.repeat(2001),
    });

    expect(errors.memo).toBeDefined();
  });

  it('주소와 상세주소를 합친 길이가 최대 길이를 넘으면 에러를 반환한다', () => {
    const errors = validateFacilityForm({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '테스트',
      type: '건물',
      address: 'a'.repeat(280),
      addressDetail: 'b'.repeat(30),
    });

    expect(errors.address).toBeDefined();
  });
});

describe('toCreateFacilityRequest', () => {
  it('빈 문자열 옵셔널 필드는 null로 변환한다(latitude/longitude는 항상 null 반환 — Geocoder 병합 전)', () => {
    const request = toCreateFacilityRequest({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
    });

    expect(request).toEqual({
      name: '강남 오피스타워 A동',
      type: '건물',
      address: null,
      latitude: null,
      longitude: null,
      builtYear: null,
      scale: null,
      inspectionCycleMonths: null,
      nextInspectionDueAt: null,
      initialGrade: null,
      assigneeUserId: null,
      memo: null,
    });
  });

  it('도로명주소와 상세주소를 하나의 문자열로 합친다', () => {
    const request = toCreateFacilityRequest({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
      address: '서울 강남구 테헤란로 123',
      addressDetail: '10층 1001호',
    });

    expect(request.address).toBe('서울 강남구 테헤란로 123 10층 1001호');
  });

  it('입력된 숫자 필드는 Number로 변환한다', () => {
    const request = toCreateFacilityRequest({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
      address: '서울 강남구',
      builtYear: '2008',
    });

    expect(request.latitude).toBeNull();
    expect(request.longitude).toBeNull();
    expect(request.builtYear).toBe(2008);
  });

  it('점검주기를 폼에서 제거했으므로 nextInspectionDueAt은 항상 null이다(#629)', () => {
    const request = toCreateFacilityRequest({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
    });

    expect(request.nextInspectionDueAt).toBeNull();
    expect(request.inspectionCycleMonths).toBeNull();
    expect(request.scale).toBeNull();
  });

  it('초기등급/담당자/메모를 CreateFacilityRequest에 포함한다(#628)', () => {
    const request = toCreateFacilityRequest({
      ...FACILITY_FORM_INITIAL_VALUES,
      name: '강남 오피스타워 A동',
      type: '건물',
      initialGrade: 'B',
      assigneeUserId: '101',
      memo: '외벽 균열 재점검 예정',
    });

    expect(request.initialGrade).toBe('B');
    expect(request.assigneeUserId).toBe(101);
    expect(request.memo).toBe('외벽 균열 재점검 예정');
  });
});
