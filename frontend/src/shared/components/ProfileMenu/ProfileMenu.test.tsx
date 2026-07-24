// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProfileMenu } from './ProfileMenu';

afterEach(cleanup);

function renderMenu(overrides: Partial<Parameters<typeof ProfileMenu>[0]> = {}) {
  const props = {
    companyName: '하자체크',
    planLabel: 'Free',
    name: '김승현',
    email: 'dsagom@gmail.com',
    onMyInfoClick: vi.fn(),
    onMyPlanClick: vi.fn(),
    onLogout: vi.fn(),
    ...overrides,
  };
  render(<ProfileMenu {...props} />);
  return props;
}

describe('ProfileMenu', () => {
  it('기업명·플랜·이름·이메일을 렌더링한다', () => {
    renderMenu();

    expect(screen.getByText('하자체크')).not.toBeNull();
    expect(screen.getByText('Free')).not.toBeNull();
    expect(screen.getByText('김승현')).not.toBeNull();
    expect(screen.getByText('dsagom@gmail.com')).not.toBeNull();
  });

  it('내 정보 클릭 시 onMyInfoClick이 호출된다', () => {
    const props = renderMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: /내 정보/ }));

    expect(props.onMyInfoClick).toHaveBeenCalledTimes(1);
  });

  it('내 플랜 클릭 시 onMyPlanClick이 호출된다', () => {
    const props = renderMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: /내 플랜/ }));

    expect(props.onMyPlanClick).toHaveBeenCalledTimes(1);
  });

  it('로그아웃 클릭 시 onLogout이 호출된다', () => {
    const props = renderMenu();

    fireEvent.click(screen.getByRole('menuitem', { name: /로그아웃/ }));

    expect(props.onLogout).toHaveBeenCalledTimes(1);
  });

  it('onClose 제공 시 바깥 클릭·ESC로 닫을 수 있다', () => {
    const handleClose = vi.fn();
    renderMenu({ onClose: handleClose });

    fireEvent.mouseDown(document.body);
    expect(handleClose).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(handleClose).toHaveBeenCalledTimes(2);
  });
});
