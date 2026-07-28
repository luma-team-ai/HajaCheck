// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import LandingPage from './LandingPage';

afterEach(() => {
  cleanup();
});

describe('LandingPage 제품 스크린샷', () => {
  it('초기 화면 아래의 제품 스크린샷을 lazy loading 한다', () => {
    render(
      <MemoryRouter>
        <LandingPage />
      </MemoryRouter>,
    );

    const productScreenshots = [
      screen.getByAltText('분석 결과 뷰어 화면'),
      screen.getByAltText('시설물 점검 주기 설정 화면'),
      screen.getByAltText('하자 상세 화면'),
    ];

    productScreenshots.forEach((image) => {
      expect(image.getAttribute('loading')).toBe('lazy');
    });
  });
});