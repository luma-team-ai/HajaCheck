// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockDefects } from '../mocks/defect.mock';
import { DefectStatusReasonModal } from './DefectStatusReasonModal';

afterEach(() => cleanup());

describe('DefectStatusReasonModal', () => {
  it('사유 없이는 확인 버튼이 비활성화되고, 입력하면 활성화된다', () => {
    const onSubmit = vi.fn();
    render(
      <DefectStatusReasonModal
        defect={mockDefects[0]}
        targetStatus="DETECTED"
        onCancel={vi.fn()}
        onSubmit={onSubmit}
      />,
    );

    const confirmButton = screen.getByRole('button', { name: '확인' }) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('사유'), {
      target: { value: '점검자 재확인 요청으로 되돌림' },
    });
    expect(confirmButton.disabled).toBe(false);
  });

  it('확인을 누르면 trim된 사유로 onSubmit을 호출한다', () => {
    const onSubmit = vi.fn();
    render(
      <DefectStatusReasonModal
        defect={mockDefects[0]}
        targetStatus="DETECTED"
        onCancel={vi.fn()}
        onSubmit={onSubmit}
      />,
    );

    fireEvent.change(screen.getByLabelText('사유'), {
      target: { value: '  되돌림 사유  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(onSubmit).toHaveBeenCalledWith('되돌림 사유');
  });

  it('취소를 누르면 onCancel을 호출한다', () => {
    const onCancel = vi.fn();
    render(
      <DefectStatusReasonModal
        defect={mockDefects[0]}
        targetStatus="DETECTED"
        onCancel={onCancel}
        onSubmit={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('공백만 입력하면 여전히 확인 버튼이 비활성화된다', () => {
    render(
      <DefectStatusReasonModal
        defect={mockDefects[0]}
        targetStatus="DETECTED"
        onCancel={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText('사유'), { target: { value: '   ' } });
    const confirmButton = screen.getByRole('button', { name: '확인' }) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);
  });
});
