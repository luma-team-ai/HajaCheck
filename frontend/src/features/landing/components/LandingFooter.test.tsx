// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { LandingFooter } from './LandingFooter';

afterEach(cleanup);

describe('LandingFooter', () => {
  it('NAV_ITEMS와 같은 제품 라벨로 랜딩 섹션 링크를 제공한다', () => {
    render(<LandingFooter />);

    expect(screen.getByRole('heading', { name: '제품' })).not.toBeNull();
    expect(screen.getByRole('link', { name: '시설물 정보' }).getAttribute('href')).toBe(
      '/#facility-info',
    );
    expect(screen.getByRole('link', { name: '점검 관리' }).getAttribute('href')).toBe('/#inspection');
    expect(screen.getByRole('link', { name: 'AI 분석' }).getAttribute('href')).toBe('/#ai-analysis');
    expect(screen.getByRole('link', { name: '요금제' }).getAttribute('href')).toBe('/#pricing');
  });

  it('회사 컬럼을 제거하고 기존 법적 고지 경로를 유지한다', () => {
    render(<LandingFooter />);

    const footerNavigation = screen.getByRole('navigation', { name: '푸터 링크' });

    expect(footerNavigation.querySelectorAll('.landing-footer-column')).toHaveLength(2);
    expect(screen.queryByRole('heading', { name: '회사' })).toBeNull();
    expect(screen.queryByRole('link', { name: '소개' })).toBeNull();
    expect(screen.queryByRole('link', { name: '블로그' })).toBeNull();
    expect(screen.queryByRole('link', { name: '채용' })).toBeNull();
    expect(screen.queryByRole('link', { name: '문의하기' })).toBeNull();
    expect(screen.getByRole('link', { name: '이용약관' }).getAttribute('href')).toBe(
      '/policy/terms-of-service',
    );
    expect(screen.getByRole('link', { name: '개인정보처리방침' }).getAttribute('href')).toBe(
      '/policy/privacy',
    );
    expect(screen.getByAltText('HajaCheck')).not.toBeNull();
    expect(screen.getByText(/데이터와 AI 기술로 시설물 관리의 새로운/)).not.toBeNull();
    expect(screen.getByText('© 2026 HAJA. All rights reserved.')).not.toBeNull();
  });
});
