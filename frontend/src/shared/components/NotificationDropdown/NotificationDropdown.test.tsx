// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotificationDropdown, type NotificationItem } from './NotificationDropdown';

afterEach(cleanup);

const notifications: NotificationItem[] = [
  {
    id: 1,
    category: 'analysis',
    title: 'AI 분석 완료',
    description: '강남 오피스타워 8회차 · 하자 87건 탐지',
    timestamp: '방금 전',
    read: false,
    actionLabel: '결과 보기',
    onAction: vi.fn(),
  },
  {
    id: 2,
    category: 'inspection',
    title: '점검일 도래',
    timestamp: '3시간 전',
    read: true,
  },
];

describe('NotificationDropdown', () => {
  it('알림 목록과 미읽음 카운트를 렌더링한다', () => {
    render(<NotificationDropdown notifications={notifications} unreadCount={1} />);

    expect(screen.getByText('AI 분석 완료')).not.toBeNull();
    expect(screen.getByText('점검일 도래')).not.toBeNull();
    expect(screen.getByText('미읽음 1')).not.toBeNull();
  });

  it('알림 행의 액션 버튼 클릭 시 onAction이 호출된다', () => {
    const handleAction = vi.fn();
    render(
      <NotificationDropdown
        notifications={[{ ...notifications[0], onAction: handleAction }]}
        unreadCount={1}
      />,
    );

    fireEvent.click(screen.getByText('결과 보기'));

    expect(handleAction).toHaveBeenCalledTimes(1);
  });

  it('모두 읽음 버튼 클릭 시 onMarkAllRead가 호출된다', () => {
    const handleMarkAllRead = vi.fn();
    render(
      <NotificationDropdown
        notifications={notifications}
        unreadCount={1}
        onMarkAllRead={handleMarkAllRead}
      />,
    );

    fireEvent.click(screen.getByText('모두 읽음'));

    expect(handleMarkAllRead).toHaveBeenCalledTimes(1);
  });

  it('필터 선택 시 onFilterChange가 호출되고 해당 카테고리만 표시된다', () => {
    const handleFilterChange = vi.fn();
    const { rerender } = render(
      <NotificationDropdown
        notifications={notifications}
        unreadCount={1}
        filters={[
          { key: 'all', label: '전체' },
          { key: 'analysis', label: '분석' },
        ]}
        activeFilter="all"
        onFilterChange={handleFilterChange}
      />,
    );

    fireEvent.click(screen.getByText('분석'));
    expect(handleFilterChange).toHaveBeenCalledWith('analysis');

    rerender(
      <NotificationDropdown
        notifications={notifications}
        unreadCount={1}
        filters={[
          { key: 'all', label: '전체' },
          { key: 'analysis', label: '분석' },
        ]}
        activeFilter="analysis"
        onFilterChange={handleFilterChange}
      />,
    );

    expect(screen.getByText('AI 분석 완료')).not.toBeNull();
    expect(screen.queryByText('점검일 도래')).toBeNull();
  });
});
