// @vitest-environment jsdom
// #417 — 기업 회원가입 이메일 도메인 selectbox. 기본값은 직접입력(회귀 위험 최소)이고, 프리셋
// 도메인 선택 시 도메인 input이 숨겨지는 전환 경로를 고정한다. controlled 컴포넌트라 상태는
// 테스트가 rerender로 시뮬레이션한다(페이지가 상태를 소유하는 실제 사용 방식과 동일).
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmailDomainField } from './EmailDomainField';

afterEach(cleanup);

describe('EmailDomainField', () => {
  it('기본(직접입력) 모드에서는 도메인 직접입력 input이 노출된다', () => {
    render(
      <EmailDomainField
        localPart=""
        domain=""
        isCustomDomain
        onLocalPartChange={vi.fn()}
        onDomainChange={vi.fn()}
        onCustomModeChange={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('이메일 도메인 직접입력')).not.toBeNull();
  });

  it('select에서 프리셋(naver.com) 선택 시 onDomainChange·onCustomModeChange(false)가 호출되고, 반영 후 도메인 input이 숨겨진다', () => {
    const onDomainChange = vi.fn();
    const onCustomModeChange = vi.fn();

    const { rerender } = render(
      <EmailDomainField
        localPart="new-company"
        domain=""
        isCustomDomain
        onLocalPartChange={vi.fn()}
        onDomainChange={onDomainChange}
        onCustomModeChange={onCustomModeChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('이메일 도메인 선택'), {
      target: { value: 'naver.com' },
    });

    expect(onDomainChange).toHaveBeenCalledWith('naver.com');
    expect(onCustomModeChange).toHaveBeenCalledWith(false);

    rerender(
      <EmailDomainField
        localPart="new-company"
        domain="naver.com"
        isCustomDomain={false}
        onLocalPartChange={vi.fn()}
        onDomainChange={onDomainChange}
        onCustomModeChange={onCustomModeChange}
      />,
    );

    expect(screen.queryByLabelText('이메일 도메인 직접입력')).toBeNull();
    expect(screen.getByText('naver.com')).not.toBeNull();
  });

  it('프리셋에서 직접입력으로 재전환하면 도메인 input이 다시 노출된다 (도메인 값 복원은 페이지 책임)', () => {
    const onDomainChange = vi.fn();
    const onCustomModeChange = vi.fn();

    const { rerender } = render(
      <EmailDomainField
        localPart="new-company"
        domain="naver.com"
        isCustomDomain={false}
        onLocalPartChange={vi.fn()}
        onDomainChange={onDomainChange}
        onCustomModeChange={onCustomModeChange}
      />,
    );

    expect(screen.queryByLabelText('이메일 도메인 직접입력')).toBeNull();

    fireEvent.change(screen.getByLabelText('이메일 도메인 선택'), {
      target: { value: '__custom__' },
    });

    // 컴포넌트는 모드 전환만 알린다 — 도메인 값을 임의로 비우지 않는다(code-reviewer P2, #417).
    // 실제 도메인 값 복원(직전 커스텀 값 vs 빈 문자열)은 페이지의 onCustomModeChange(true)
    // 핸들러 책임이라 여기서는 onDomainChange가 호출되지 않는 것만 확인한다.
    expect(onCustomModeChange).toHaveBeenCalledWith(true);
    expect(onDomainChange).not.toHaveBeenCalled();

    // 페이지가 이전에 입력했던 커스텀 도메인(mycompany.io)으로 복원해 내려준 경우를 시뮬레이션.
    rerender(
      <EmailDomainField
        localPart="new-company"
        domain="mycompany.io"
        isCustomDomain
        onLocalPartChange={vi.fn()}
        onDomainChange={onDomainChange}
        onCustomModeChange={onCustomModeChange}
      />,
    );

    const restoredInput = screen.getByLabelText('이메일 도메인 직접입력') as HTMLInputElement;
    expect(restoredInput.value).toBe('mycompany.io');
  });
});
