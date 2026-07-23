// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PLAN_POLICY_DEFAULTS } from '../planPolicy.constants';
import type { PlanPolicyForm } from '../planPolicy.types';
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

  it('설정 저장을 누르면 곧바로 저장하지 않고 확인 단계를 먼저 보여준다', () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={handleSave}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }));

    expect(handleSave).not.toHaveBeenCalled();
    expect(screen.getByText('플랜 정책 저장 확인')).toBeTruthy();
  });

  it('#689 P3 — 확인 단계 진입 시 onEnterConfirm으로 상위의 이전 저장 실패 메시지를 리셋한다', () => {
    const handleEnterConfirm = vi.fn();
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={vi.fn().mockResolvedValue(undefined)}
        submitErrorMessage="이전 저장 실패 메시지"
        onEnterConfirm={handleEnterConfirm}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }));

    expect(handleEnterConfirm).toHaveBeenCalledTimes(1);
  });

  it('확인 단계에서 뒤로를 누르면 편집 화면으로 되돌아가고 onSave는 호출되지 않는다', () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
    render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={PLAN_POLICY_DEFAULTS}
        onSave={handleSave}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '설정 저장' }));
    fireEvent.click(screen.getByRole('button', { name: '뒤로' }));

    expect(handleSave).not.toHaveBeenCalled();
    expect(screen.getByText('플랜 정책 설정 (Plan Policy Settings)')).toBeTruthy();
  });

  it('PR #686 P2 후속(#689) — 동일한 값을 담은 새 initialValues 객체 참조로 부모가 리렌더돼도 편집 중인 draft가 유지된다', () => {
    // usePlanPolicies 백그라운드 재조회처럼, 값은 같지만 매번 새 객체 리터럴을 만드는 상황을 재현한다
    // (toPlanPolicyForm이 매 호출마다 새 객체를 반환하는 것과 동일한 패턴).
    function cloneInitialValues(): PlanPolicyForm {
      return JSON.parse(JSON.stringify(PLAN_POLICY_DEFAULTS)) as PlanPolicyForm;
    }

    const handleSave = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={cloneInitialValues()}
        onSave={handleSave}
      />,
    );

    fireEvent.change(screen.getByLabelText('Standard 월 구독 가격'), {
      target: { value: '59000' },
    });

    // open은 그대로 true인데, initialValues만 값이 같은 새 객체 참조로 바뀐 채 리렌더된다.
    rerender(
      <PlanPolicyModal
        open
        onClose={vi.fn()}
        initialValues={cloneInitialValues()}
        onSave={handleSave}
      />,
    );

    expect((screen.getByLabelText('Standard 월 구독 가격') as HTMLInputElement).value).toBe('59000');
  });

  it('값을 수정하고 확인까지 마치면 수정된 값 그대로 onSave에 전달되고 모달이 닫힌다', async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
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
    fireEvent.click(screen.getByRole('button', { name: '저장 확정' }));

    await vi.waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          STANDARD: expect.objectContaining({ priceMonthly: '59000' }),
        }),
      );
      expect(handleClose).toHaveBeenCalled();
    });
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

  it('토글을 클릭하면 해당 플랜의 값만 반전된다', async () => {
    const handleSave = vi.fn().mockResolvedValue(undefined);
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
    fireEvent.click(screen.getByRole('button', { name: '저장 확정' }));

    await vi.waitFor(() => {
      expect(handleSave).toHaveBeenCalledWith(
        expect.objectContaining({
          FREE: expect.objectContaining({ hasPdfWatermark: false }),
        }),
      );
    });
  });
});
