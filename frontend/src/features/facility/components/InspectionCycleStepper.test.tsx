// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InspectionCycleStepper } from './InspectionCycleStepper';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('InspectionCycleStepper', () => {
  it('+ 버튼을 누르면 onChange가 1 증가한 값으로 호출된다', () => {
    const onChange = vi.fn();
    render(<InspectionCycleStepper months={6} onChange={onChange} />);

    fireEvent.click(screen.getByLabelText('주기 1개월 증가'));

    expect(onChange).toHaveBeenCalledWith(7);
  });

  it('− 버튼을 누르면 onChange가 1 감소한 값으로 호출된다', () => {
    const onChange = vi.fn();
    render(<InspectionCycleStepper months={6} onChange={onChange} />);

    fireEvent.click(screen.getByLabelText('주기 1개월 감소'));

    expect(onChange).toHaveBeenCalledWith(5);
  });

  it('최소값(1)에서는 − 버튼이 비활성화된다', () => {
    const onChange = vi.fn();
    render(<InspectionCycleStepper months={1} onChange={onChange} />);

    const decrementButton = screen.getByLabelText('주기 1개월 감소') as HTMLButtonElement;
    expect(decrementButton.disabled).toBe(true);
  });

  it('퀵칩(6개월)을 클릭하면 해당 개월 수로 onChange가 호출된다', () => {
    const onChange = vi.fn();
    render(<InspectionCycleStepper months={3} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: '6개월' }));

    expect(onChange).toHaveBeenCalledWith(6);
  });

  it('현재 개월 수와 일치하는 퀵칩은 선택 상태(aria-pressed=true)로 표시된다', () => {
    const onChange = vi.fn();
    render(<InspectionCycleStepper months={12} onChange={onChange} />);

    expect(screen.getByRole('button', { name: '1년' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: '3개월' }).getAttribute('aria-pressed')).toBe('false');
  });
});
