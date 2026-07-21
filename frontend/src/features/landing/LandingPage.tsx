import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { LandingFooter } from './components/LandingFooter';
import { LandingHeader } from './components/LandingHeader';
import { PARTNERS, PRICING_TIERS } from './constants';
import heroVisualImage from '../../assets/brand/landing-hero-ai-scan.svg';
import './landing.css';

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function scrollToBottom() {
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
}

export default function LandingPage() {
  const location = useLocation();
  const [isAtTop, setIsAtTop] = useState(true);
  const [isAtBottom, setIsAtBottom] = useState(false);
  const [aiAnalysisPercent, setAiAnalysisPercent] = useState(0);

  useEffect(() => {
    const handleScroll = () => {
      setIsAtTop(window.scrollY === 0);
      setIsAtBottom(
        window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 10,
      );
    };

    handleScroll();
    window.addEventListener('scroll', handleScroll);
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      setAiAnalysisPercent((prev) => (prev + 1) % 101);
    }, 40);
    return () => clearInterval(interval);
  }, []);

  // 다른 페이지(예: 정책 페이지)의 헤더 nav에서 /#섹션id로 넘어온 경우, 마운트 후 해당 섹션까지 스크롤
  useEffect(() => {
    if (location.hash) {
      const id = location.hash.slice(1);
      requestAnimationFrame(() => {
        document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
      });
    }
  }, [location.hash]);

  return (
    <div className="landing">
      <LandingHeader />

      <section className="landing-hero">
        <span className="landing-badge">
          <span className="landing-badge-dot" aria-hidden="true">●</span> AI FACILITY MANAGEMENT
        </span>
        <h1>
          데이터 기반 시설물 안전,
          <br />
          미래를 예측하다
        </h1>
        <p>
          AI 기술과 체계적인 데이터 관리로 시설물의 수명을 늘리고 유지보수 비용을 절감하세요.
          도면부터 센서 데이터까지 완벽하게 통제합니다.
        </p>
        <a className="landing-cta" href="/login">
          무료로 시작하기 →
        </a>

        <div className="landing-hero-visual">
          <img
            src={heroVisualImage}
            className="landing-hero-visual-image"
            alt=""
            aria-hidden="true"
          />
          <div className="landing-scan-line" aria-hidden="true" />
          <svg
            className="landing-crack-line"
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <polyline
              points="26,32 27.8,37.25 26.3,42.5 28.8,47.75 28,53"
              vectorEffect="non-scaling-stroke"
            />
          </svg>
          <div className="landing-bbox landing-bbox--1" aria-hidden="true" />
          <div className="landing-chip landing-chip--1">
            <span className="landing-chip-dot landing-chip-dot--danger" aria-hidden="true">●</span>
            균열 · E · 0.94
          </div>
          <div className="landing-chip landing-chip--2">
            <span className="landing-chip-dot landing-chip-dot--d" aria-hidden="true">●</span>
            박리·박락 · D
          </div>
          <div className="landing-chip landing-chip--3">
            <span className="landing-chip-spin" aria-hidden="true">↻</span>
            AI 분석 중 · {aiAnalysisPercent}%
          </div>
        </div>
      </section>

      <section id="partners" className="landing-partners">
        <p className="landing-partners-label">신뢰할 수 있는 파트너</p>
        <div className="landing-partners-row">
          {PARTNERS.map((partner) => (
            <img
              key={partner.name}
              src={partner.logo}
              alt={partner.name}
              className="landing-partner"
            />
          ))}
        </div>
      </section>

      <section id="facility-info" className="landing-section">
        <p className="landing-eyebrow">FACILITY INFORMATION MANAGEMENT</p>
        <h2>
          모든 시설물 데이터를 한 곳에서
          <br />
          완벽하게
        </h2>
        <p className="landing-p--wide">
          도면, 센서 데이터, 점검 이력 등 흩어져 있는 시설물 정보를 중앙 집중화하여 언제 어디서나 쉽게
          <br />
          접근하고 관리하세요. 최신 BIM 연동을 지원합니다.
        </p>
        <div className="landing-visual" />
      </section>

      <section id="inspection" className="landing-section">
        <p className="landing-eyebrow">INSPECTION MANAGEMENT</p>
        <h2>체계적인 점검 일정과 이력 관리</h2>
        <p>
          정기, 수시 점검 일정을 누락 없이 관리하고, 현장에서 모바일로 즉시 결과를 입력하세요.
          데이터는 클라우드에 안전하게 동기화됩니다.
        </p>
        <div className="landing-visual" />
      </section>

      <section id="ai-analysis" className="landing-section">
        <p className="landing-eyebrow">AI DEFECT ANALYSIS</p>
        <h2>
          인공지능이 찾아내는
          <br />
          미세한 결함
        </h2>
        <p>
          드론 촬영 이미지나 현장 사진을 업로드하면, AI 비전 기술이 균열, 누수, 박락 등{' '}
          <span className="landing-nowrap">육안으로 놓치기</span> 쉬운 하자를 자동으로
          분석하고 심각도를 분류합니다.
        </p>
        <div className="landing-badge-row">
          <span className="landing-pill landing-pill--danger">
            <span className="landing-pill-dot" aria-hidden="true">●</span> 크랙 심각도 High
          </span>
          <span className="landing-pill landing-pill--warning">
            <span className="landing-pill-dot" aria-hidden="true">●</span> 누수 징후 Medium
          </span>
        </div>
        <div className="landing-visual" />
      </section>

      <section id="pricing" className="landing-pricing">
        <p className="landing-eyebrow">PRICING</p>
        <h2>합리적인 요금제</h2>
        <p>시설물 규모와 필요 기능에 맞춰 최적의 플랜을 선택하세요.</p>

        <div className="landing-pricing-cards">
          {PRICING_TIERS.map((tier) => (
            <div
              key={tier.name}
              className={`landing-pricing-card${tier.inverted ? ' landing-pricing-card--inverted' : ''}`}
            >
              {tier.badge && <span className="landing-pricing-badge">{tier.badge}</span>}
              <p className="landing-pricing-name">{tier.name}</p>
              <p className="landing-pricing-subtitle">{tier.subtitle}</p>
              <p>
                <span className="landing-pricing-price">{tier.price}</span>
                {tier.period && <span className="landing-pricing-period"> {tier.period}</span>}
              </p>
              <ul className="landing-pricing-features">
                {tier.features.map((feature) => (
                  <li
                    key={feature.label}
                    className={`landing-pricing-feature${feature.included === false ? ' landing-pricing-feature--excluded' : ''}`}
                  >
                    <span className="landing-pricing-feature-label">{feature.label}</span>
                  </li>
                ))}
              </ul>
              <a
                className={`landing-pricing-cta${tier.inverted ? ' landing-pricing-cta--white' : ''}`}
                href="/login"
              >
                {tier.ctaLabel}
              </a>
            </div>
          ))}
        </div>
      </section>

      <section className="landing-banner">
        <h2>
          지금 바로 미래의
          <br />
          시설물 관리를 경험하세요.
        </h2>
        <p>복잡한 설치 없이 브라우저에서 바로 시작할 수 있습니다.</p>
      </section>

      <div className="landing-floating-controls">
        {!isAtTop && (
          <button type="button" onClick={scrollToTop} aria-label="맨 위로">
            ↑
          </button>
        )}
        {!isAtBottom && (
          <button type="button" onClick={scrollToBottom} aria-label="맨 아래로">
            ↓
          </button>
        )}
      </div>

      <LandingFooter />
    </div>
  );
}
