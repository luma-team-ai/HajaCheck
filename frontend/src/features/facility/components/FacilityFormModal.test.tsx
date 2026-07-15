// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FacilityFormModal } from './FacilityFormModal';

afterEach(cleanup);

function fillRequiredFields() {
  fireEvent.change(screen.getByLabelText(/시설물명/), {
    target: { value: '강남 오피스타워 A동' },
  });
  fireEvent.change(screen.getByLabelText(/시설물 유형/), { target: { value: '건물' } });
}

describe('FacilityFormModal', () => {
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
    expect(screen.getByLabelText(/시설물명/)).toHaveValue('강남 오피스타워 A동');
    expect(screen.getByLabelText(/시설물 유형/)).toHaveValue('건물');
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
    expect(screen.getByLabelText(/시설물명/)).toHaveValue('');
    expect(screen.getByLabelText(/시설물 유형/)).toHaveValue('');
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
    expect(screen.getByText('시설물명을 입력해 주세요.')).toBeInTheDocument();
  });
});
