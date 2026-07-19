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

  it('프리셋에서 직접입력으로 재전환하면 도메인 input이 다시 노출된다', () => {
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

    expect(onCustomModeChange).toHaveBeenCalledWith(true);
    expect(onDomainChange).toHaveBeenCalledWith('');

    rerender(
      <EmailDomainField
        localPart="new-company"
        domain=""
        isCustomDomain
        onLocalPartChange={vi.fn()}
        onDomainChange={onDomainChange}
        onCustomModeChange={onCustomModeChange}
      />,
    );

    expect(screen.getByLabelText('이메일 도메인 직접입력')).not.toBeNull();
  });
});
