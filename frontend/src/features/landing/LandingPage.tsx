import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../auth/store/authStore';
import { LandingFooter } from './components/LandingFooter';
import { LandingHeader } from './components/LandingHeader';
import { PARTNERS, PRICING_TIERS, formatPricingTiersFromApi } from './constants';
import { publicPlanApi } from './api/publicPlanApi';
import heroVisualImage from '../../assets/brand/landing-hero-ai-scan.svg';
import analysisViewerImage from '../../assets/brand/landing-screens/analysis-viewer.png';
import inspectionCycleImage from '../../assets/brand/landing-screens/inspection-cycle.png';
import defectDetailImage from '../../assets/brand/landing-screens/defect-detail.png';
import './landing.css';

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function scrollToBottom() {
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
}

export default function LandingPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);

  // 이미 로그인된 사용자는 랜딩 대신 대시보드로 이동
  useEffect(() => {
    if (user) {
      navigate('/dashboard', { replace: true });
    }
  }, [user, navigate]);

  const { data: planData, isError, refetch } = useQuery({
    queryKey: ['publicPlans'],
    queryFn: ({ signal }) => publicPlanApi.getPlans(signal).then((res) => res.data.plans),
    staleTime: 5 * 60 * 1000,
  });

  const shouldShowPricingError = isError && !planData;

  const pricingTiers = useMemo(
    () => (planData ? formatPricingTiersFromApi(planData) : PRICING_TIERS),
    [planData],
  );

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
          시설물 정보부터 점검 결과와 하자 이력까지 한 곳에서 관리하세요.
          AI 분석과 보고서 생성으로 점검 업무를 더 빠르게 이어갑니다.
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
          시설물 정보와 점검 이력을 한 곳에서 관리하고, 점검 회차별 결과와 하자 현황을 확인하세요.
          <br />
          시설물별 점검 주기를 설정해 다음 점검 일정을 관리할 수 있습니다.
        </p>
        <div className="landing-visual landing-screen-visual">
          <img src={defectDetailImage} alt="하자 상세 화면" className="landing-screen-image" loading="lazy" />
        </div>
      </section>

      <section id="inspection" className="landing-section">
        <p className="landing-eyebrow">INSPECTION MANAGEMENT</p>
        <h2>체계적인 점검 일정과 이력 관리</h2>
        <p>
          정기·정밀·긴급 점검 회차를 만들고, 사진과 영상을 업로드해 점검을 시작하세요.
          <br />
          분석 결과와 검수 상태를 확인하며 점검 이력을 관리할 수 있습니다.
        </p>
        <div className="landing-visual landing-screen-visual">
          <img src={inspectionCycleImage} alt="시설물 점검 주기 설정 화면" className="landing-screen-image" loading="lazy" />
        </div>
      </section>

      <section id="ai-analysis" className="landing-section">
        <p className="landing-eyebrow">AI DEFECT ANALYSIS</p>
        <h2>
          인공지능이 찾아내는
          <br />
          미세한 결함
        </h2>
        <p>
          현장 사진과 영상을 업로드하면, AI 비전 기술이 균열, 누수, 박리·박락 등{' '}
          <span className="landing-nowrap">육안으로 놓치기</span> 쉬운 하자를 자동으로
          탐지하고 등급과 위치를 확인할 수 있습니다.
        </p>
        <div className="landing-badge-row">
          <span className="landing-pill landing-pill--danger">
            <span className="landing-pill-dot" aria-hidden="true">●</span> 크랙 심각도 High
          </span>
          <span className="landing-pill landing-pill--warning">
            <span className="landing-pill-dot" aria-hidden="true">●</span> 누수 징후 Medium
          </span>
        </div>
        <div className="landing-visual landing-screen-visual">
          <img src={analysisViewerImage} alt="분석 결과 뷰어 화면" className="landing-screen-image" loading="lazy" />
        </div>
      </section>

      <section id="pricing" className="landing-pricing">
        <p className="landing-eyebrow">PRICING</p>
        <h2>합리적인 요금제</h2>
        <p>시설물 규모와 필요 기능에 맞춰 최적의 플랜을 선택하세요.</p>

        {shouldShowPricingError ? (
          <div className="landing-pricing-error" role="alert">
            <p>요금제 정보를 불러오지 못했습니다.</p>
            <button type="button" onClick={() => refetch()} className="landing-pricing-retry-btn">
              다시 시도
            </button>
          </div>
        ) : (
          <div className="landing-pricing-cards">
            {pricingTiers.map((tier) => (
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
              </div>
            ))}
          </div>
        )}
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
