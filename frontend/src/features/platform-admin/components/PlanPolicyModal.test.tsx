// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLAN_POLICY_DEFAULTS } from '../planPolicy.constants';
import { PlanPolicyModal } from './PlanPolicyModal';

afterEach(cleanup);

describe('PlanPolicyModal', () => {
  it('열려 있지 않으면 아무것도 렌더링하지 않는다', () => {
    render(
      <PlanPolicyModal
        open={false}
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={vi.fn()}
      />,
    );

    expect(screen.queryByText('플랜 정책 설정 (Plan Policy Settings)')).toBeNull();
  });

  it('FREE/STANDARD/ENTERPRISE 3개 플랜의 초기값을 렌더링한다', () => {
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={vi.fn()}
      />,
    );

    expect(screen.getByText('플랜 정책 설정 (Plan Policy Settings)')).toBeTruthy();
    // jest-dom 매처는 이 프로젝트에 setup되어 있지 않아 기본 매처로 검증
    expect((screen.getByLabelText('Free 월 구독 가격') as HTMLInputElement).value).toBe('0');
    expect((screen.getByLabelText('Standard 월 구독 가격') as HTMLInputElement).value).toBe('49000');
  });

  it('Enterprise의 무제한/협의 항목은 빈 값으로 표시되고, 안내 문구가 함께 뜬다', () => {
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={vi.fn()}
      />,
    );

    const facilityInput = screen.getByLabelText('Enterprise 최대 등록 시설 수') as HTMLInputElement;
    expect(facilityInput.value).toBe('');
    expect(facilityInput.placeholder).toBe('비워두면 무제한');
    expect(
      (screen.getByLabelText('Enterprise 최대 분석 가능 횟수') as HTMLInputElement).placeholder,
    ).toBe('비워두면 협의');
  });

  it('값을 수정하고 저장하면 수정된 값 그대로 onSave에 전달되고 모달이 닫힌다', () => {
    const handleSave = vi.fn();
    const handleClose = vi.fn();
    render(
      <PlanPolicyModal
        open
        onClose={handleClose}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={handleSave}
      />,
    );

    fireEvent.change(screen.getByLabelText('Standard 월 구독 가격'), {
      target: { value: '59000' },
    });
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }));

    expect(handleSave).toHaveBeenCalledWith(
      expect.objectContaining({
        STANDARD: expect.objectContaining({ priceMonthly: '59000' }),
      }),
    );
    expect(handleClose).toHaveBeenCalled();
  });

  it('취소를 누르면 onSave 없이 닫히기만 한다', () => {
    const handleSave = vi.fn();
    const handleClose = vi.fn();
    render(
      <PlanPolicyModal
        open
        onClose={handleClose}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={handleSave}
      />,
    );

    fireEvent.change(screen.getByLabelText('Free 월 구독 가격'), { target: { value: '999' } });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(handleSave).not.toHaveBeenCalled();
    expect(handleClose).toHaveBeenCalled();
  });

  it('토글을 클릭하면 해당 플랜의 값만 반전된다', () => {
    const handleSave = vi.fn();
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={handleSave}
      />,
    );

    // FREE 워터마크 표시 여부 — 기본값 true → 클릭 시 false로 반전
    fireEvent.click(screen.getByRole('switch', { name: 'Free 워터마크 표시 여부' }));
    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }));

    expect(handleSave).toHaveBeenCalledWith(
      expect.objectContaining({
        FREE: expect.objectContaining({ hasPdfWatermark: false }),
      }),
    );
  });
});
