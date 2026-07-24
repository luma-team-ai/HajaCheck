// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  GeocodeFailedError,
  GeocodeNotFoundError,
} from '../../../shared/lib/kakaoMap/geocodeAddress';
import { computeNextInspectionDueAt } from '../utils/computeNextInspectionDueAt';
import { FacilityFormModal } from './FacilityFormModal';

const { geocodeAddressMock, openPostcodeSearchMock } = vi.hoisted(() => ({
  geocodeAddressMock: vi.fn(),
  openPostcodeSearchMock: vi.fn(),
}));

vi.mock('../../../shared/lib/kakaoMap/geocodeAddress', async () => {
  const actual = await vi.importActual<
    typeof import('../../../shared/lib/kakaoMap/geocodeAddress')
  >('../../../shared/lib/kakaoMap/geocodeAddress');
  return {
    ...actual,
    geocodeAddress: geocodeAddressMock,
  };
});

// 다음(카카오) 우편번호 팝업은 실제 외부 스크립트를 로드하므로 컴포넌트 테스트에서는 훅 자체를
// 모킹해 "주소검색" 버튼 클릭 → onComplete 콜백 호출만 검증한다.
vi.mock('../hooks/useFacilityPostcodeSearch', () => ({
  useFacilityPostcodeSearch: () => ({ openPostcodeSearch: openPostcodeSearchMock }),
}));

// 담당자 select 옵션(react-query + MSW)은 이 컴포넌트 테스트 범위 밖이라 훅 자체를 모킹한다.
vi.mock('../hooks/useFacilityAssignableUsers', () => ({
  useFacilityAssignableUsers: () => ({
    data: [{ id: 101, name: '김도현 검사자' }],
    isLoading: false,
  }),
}));

afterEach(() => {
  cleanup();
  geocodeAddressMock.mockReset();
  openPostcodeSearchMock.mockReset();
});

function fillRequiredFields() {
  fireEvent.change(screen.getByLabelText(/시설물명/), {
    target: { value: '강남 오피스타워 A동' },
  });
  // #731 — 유형 옵션이 {종류}-{점검유형}-{주기} 조합 12종으로 확장돼 단순 '건물'은 더 이상
  // 유효한 <option>이 아니다(없는 옵션 값을 select.value에 대입하면 jsdom이 selectedIndex를
  // -1로 두고 값이 반영되지 않는다) — 실제 12개 옵션 중 하나를 선택한다.
  fireEvent.change(screen.getByLabelText(/시설물 유형/), {
    target: { value: '건물-정기-4개월' },
  });
}

// "주소검색" 버튼을 클릭하고, 모킹된 openPostcodeSearch에 전달된 onComplete 콜백을 호출해
// 도로명주소가 채워진 것처럼 시뮬레이션한다.
function searchAndFillAddress(address: string) {
  fireEvent.click(screen.getByRole('button', { name: '주소검색' }));
  const onComplete = openPostcodeSearchMock.mock.calls.at(-1)?.[0] as (address: string) => void;
  act(() => {
    onComplete(address);
  });
}

describe('FacilityFormModal', () => {
  // X 버튼이 아닌 배경(오버레이) 클릭으로 입력 중이던 값이 유실되던 문제(#500) — 이 모달은
  // closeOnOverlayClick={false}로 배경 클릭 시 닫히지 않아야 한다.
  it('배경(오버레이)을 클릭해도 모달이 닫히지 않는다(#500)', () => {
    const handleClose = vi.fn();

    render(
      <FacilityFormModal open onClose={handleClose} onSubmit={vi.fn()} isSubmitting={false} />,
    );

    fireEvent.click(screen.getByRole('presentation'));

    expect(handleClose).not.toHaveBeenCalled();
  });

  it('X 버튼을 클릭하면 모달이 닫힌다', () => {
    const handleClose = vi.fn();

    render(
      <FacilityFormModal open onClose={handleClose} onSubmit={vi.fn()} isSubmitting={false} />,
    );

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('등록 실패 시 사용자가 입력한 폼 값을 초기화하지 않고 유지한다', async () => {
    const handleSubmit = vi.fn().mockRejectedValue(new Error('등록 실패'));

    render(
      <FacilityFormModal
        open
        onClose={vi.fn()}
        onSubmit={handleSubmit}
        isSubmitting={false}
        submitErrorMessage="시설물 등록에 실패했습니다."
      />,
    );

    fillRequiredFields();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    // 실패했으므로 입력값이 그대로 남아있어야 한다(제출 이전 상태로 초기화되지 않음)
    expect((screen.getByLabelText(/시설물명/) as HTMLInputElement).value).toBe(
      '강남 오피스타워 A동',
    );
    expect((screen.getByLabelText(/시설물 유형/) as HTMLSelectElement).value).toBe(
      '건물-정기-4개월',
    );
  });

  it('등록 성공 시 폼 값을 초기화한다', async () => {
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect((screen.getByLabelText(/시설물명/) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(/시설물 유형/) as HTMLSelectElement).value).toBe('');
  });

  it('필수값 검증 실패 시 onSubmit을 호출하지 않는다', async () => {
    const handleSubmit = vi.fn();

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).not.toHaveBeenCalled();
    expect(screen.getByText('시설물명을 입력해 주세요.')).not.toBeNull();
  });

  it('주소검색으로 도로명주소를 채우면 Geocoder로 좌표를 계산해 onSubmit payload에 포함한다(#618, #629)', async () => {
    geocodeAddressMock.mockResolvedValue({ latitude: 37.5006, longitude: 127.0364 });
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();
    searchAndFillAddress('서울 강남구 테헤란로 123');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(geocodeAddressMock).toHaveBeenCalledWith('서울 강남구 테헤란로 123');
    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      address: '서울 강남구 테헤란로 123',
      latitude: 37.5006,
      longitude: 127.0364,
    });
  });

  it('상세주소를 함께 입력하면 도로명주소와 합쳐 하나의 address로 전송한다(#629)', async () => {
    geocodeAddressMock.mockResolvedValue({ latitude: 37.5006, longitude: 127.0364 });
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();
    searchAndFillAddress('서울 강남구 테헤란로 123');
    fireEvent.change(screen.getByPlaceholderText('상세주소를 입력해 주세요'), {
      target: { value: '10층 1001호' },
    });

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    // 도로명주소만 Geocoder에 전달된다(상세주소는 매칭률을 떨어뜨릴 수 있어 제외)
    expect(geocodeAddressMock).toHaveBeenCalledWith('서울 강남구 테헤란로 123');
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      address: '서울 강남구 테헤란로 123 10층 1001호',
    });
  });

  // 사용자 결정(#629 재조정) — 주소는 있는데 Geocoder가 실패해도 등록을 차단하지 않고 좌표
  // null로 best-effort 진행한다. 주소가 아예 없을 때(null로 등록되는 기존 경로)와 동일한 결과.
  it('Geocoder가 주소를 찾지 못해도 등록을 막지 않고 좌표 없이 onSubmit을 진행한다(#618, #629 best-effort)', async () => {
    geocodeAddressMock.mockRejectedValue(new GeocodeNotFoundError('존재하지 않는 주소'));
    const handleSubmit = vi.fn().mockResolvedValue(undefined);
    const handleGeocodeFailure = vi.fn();

    render(
      <FacilityFormModal
        open
        onClose={vi.fn()}
        onSubmit={handleSubmit}
        isSubmitting={false}
        onGeocodeFailure={handleGeocodeFailure}
      />,
    );

    fillRequiredFields();
    searchAndFillAddress('존재하지 않는 주소');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      address: '존재하지 않는 주소',
      latitude: null,
      longitude: null,
    });
    // onGeocodeFailure로 넘어간 메시지가 상위(FacilityListPage)에서 인라인 배너로 쓰인다 —
    // 등록 성공 시 이 모달 내부의 geocodeErrorMessage는 정리되므로(다음 오픈 시 잔류 방지),
    // 실패 사실을 검증하는 지점은 여기(콜백 인자)다.
    expect(handleGeocodeFailure).toHaveBeenCalledWith(
      expect.stringContaining('존재하지 않는 주소'),
    );
  });

  it('Geocoder 호출 자체가 실패해도 등록을 막지 않고 좌표 없이 onSubmit을 진행한다(#618, #629 best-effort)', async () => {
    geocodeAddressMock.mockRejectedValue(new GeocodeFailedError('서울 강남구'));
    const handleSubmit = vi.fn().mockResolvedValue(undefined);
    const handleGeocodeFailure = vi.fn();

    render(
      <FacilityFormModal
        open
        onClose={vi.fn()}
        onSubmit={handleSubmit}
        isSubmitting={false}
        onGeocodeFailure={handleGeocodeFailure}
      />,
    );

    fillRequiredFields();
    searchAndFillAddress('서울 강남구');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      address: '서울 강남구',
      latitude: null,
      longitude: null,
    });
    expect(handleGeocodeFailure).toHaveBeenCalledWith(expect.stringContaining('좌표 변환에 실패했습니다'));
  });

  // 재검수 P1 회귀고정 — geocode 실패 + onSubmit(등록 API)도 실패하는 경우, "등록되었습니다"
  // 문구가 담긴 onGeocodeFailure는 절대 호출되면 안 된다(등록이 실제로 성공한 적이 없으므로).
  it('Geocoder도 실패하고 onSubmit도 실패하면 onGeocodeFailure를 호출하지 않는다(#629 재검수 P1)', async () => {
    geocodeAddressMock.mockRejectedValue(new GeocodeNotFoundError('존재하지 않는 주소'));
    const handleSubmit = vi.fn().mockRejectedValue(new Error('등록 실패'));
    const handleGeocodeFailure = vi.fn();

    render(
      <FacilityFormModal
        open
        onClose={vi.fn()}
        onSubmit={handleSubmit}
        isSubmitting={false}
        onGeocodeFailure={handleGeocodeFailure}
      />,
    );

    fillRequiredFields();
    searchAndFillAddress('존재하지 않는 주소');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({ latitude: null, longitude: null });
    // 등록 API가 실패했으므로 "좌표 없이 등록되었습니다" 배너용 콜백은 호출되면 안 된다.
    expect(handleGeocodeFailure).not.toHaveBeenCalled();
  });

  it('초기 등급 pill을 선택하면 onSubmit payload에 initialGrade를 포함한다(#628)', async () => {
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: 'B' }));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit.mock.calls[0][0]).toMatchObject({ initialGrade: 'B' });
  });

  it('선택된 초기 등급 pill을 다시 클릭하면 선택이 해제된다(#628)', async () => {
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();
    fireEvent.click(screen.getByRole('button', { name: 'B' }));
    fireEvent.click(screen.getByRole('button', { name: 'B' }));

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit.mock.calls[0][0]).toMatchObject({ initialGrade: null });
  });

  it('담당자와 메모를 입력하면 onSubmit payload에 포함한다(#628)', async () => {
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fillRequiredFields();
    fireEvent.change(screen.getByLabelText('담당자'), { target: { value: '101' } });
    fireEvent.change(screen.getByLabelText('메모'), {
      target: { value: '외벽 균열 재점검 예정' },
    });

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      assigneeUserId: 101,
      memo: '외벽 균열 재점검 예정',
    });
  });

  // #731 — 시설물 유형 select가 {종류}-{점검유형}-{주기} 조합 12종으로 확장되면서, 선택한 옵션의
  // 주기(cycleMonths)가 별도 입력 없이 onSubmit payload의 inspectionCycleMonths·nextInspectionDueAt에
  // 자동 반영돼야 한다.
  it('유형으로 "건물-정밀-24개월"을 선택하면 inspectionCycleMonths=24, nextInspectionDueAt이 24개월 뒤로 계산돼 onSubmit payload에 실린다(#731)', async () => {
    const handleSubmit = vi.fn().mockResolvedValue(undefined);

    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={handleSubmit} isSubmitting={false} />,
    );

    fireEvent.change(screen.getByLabelText(/시설물명/), {
      target: { value: '강남 오피스타워 A동' },
    });
    fireEvent.change(screen.getByLabelText(/시설물 유형/), {
      target: { value: '건물-정밀-24개월' },
    });

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '등록하기' }));
    });

    expect(handleSubmit).toHaveBeenCalledTimes(1);
    expect(handleSubmit.mock.calls[0][0]).toMatchObject({
      type: '건물-정밀-24개월',
      inspectionCycleMonths: 24,
      nextInspectionDueAt: computeNextInspectionDueAt(24),
    });
  });

  it('점검주기·규모 필드는 더 이상 등록 폼에 없다(#629)', () => {
    render(
      <FacilityFormModal open onClose={vi.fn()} onSubmit={vi.fn()} isSubmitting={false} />,
    );

    expect(screen.queryByLabelText(/점검주기/)).toBeNull();
    expect(screen.queryByLabelText(/^규모$/)).toBeNull();
  });
});
