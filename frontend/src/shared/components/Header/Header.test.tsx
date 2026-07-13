// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Header } from './Header';

afterEach(cleanup);

describe('Header', () => {
  it('breadcrumb 항목을 순서대로 렌더링한다', () => {
    render(<Header breadcrumb={[{ label: '하자 관리' }, { label: '하자 목록' }]} />);

    expect(screen.getByText('하자 관리')).not.toBeNull();
    expect(screen.getByText('하자 목록')).not.toBeNull();
  });

  it('알림 버튼 클릭 시 onNotificationClick이 호출된다', () => {
    const handleClick = vi.fn();
    render(<Header breadcrumb={[{ label: '대시보드' }]} onNotificationClick={handleClick} />);

    fireEvent.click(screen.getByLabelText('알림'));

    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('미읽음 알림이 있으면 aria-label에 건수가 표시된다', () => {
    render(<Header breadcrumb={[{ label: '대시보드' }]} unreadCount={5} />);

    expect(screen.getByLabelText('알림 (미읽음 5건)')).not.toBeNull();
  });

  it('프로필 버튼 클릭 시 onProfileClick이 호출된다', () => {
    const handleClick = vi.fn();
    render(<Header breadcrumb={[{ label: '대시보드' }]} onProfileClick={handleClick} />);

    fireEvent.click(screen.getByLabelText('내 프로필'));

    expect(handleClick).toHaveBeenCalledTimes(1);
  });
});
