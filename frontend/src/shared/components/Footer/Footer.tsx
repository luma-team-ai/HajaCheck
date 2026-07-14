import { Link } from 'react-router-dom';
import brandMark from '../../../assets/brand/brand-mark.png';
import './Footer.css';

export interface FooterLink {
  label: string;
  href: string;
}

export interface FooterColumn {
  title: string;
  links: FooterLink[];
}

interface FooterProps {
  columns?: FooterColumn[];
  tagline?: string;
  copyright?: string;
}

// Figma node-id 112-264 "Footer - FOOTER" 기준
const DEFAULT_COLUMNS: FooterColumn[] = [
  {
    title: '제품',
    links: [
      { label: '시설물 정보 관리', href: '/facilities' },
      { label: '검사(점검) 관리', href: '/inspections' },
      { label: 'AI 하자 분석', href: '/ai-analysis' },
      { label: '요금제', href: '/pricing' },
    ],
  },
  {
    title: '회사',
    links: [
      { label: '소개', href: '/about' },
      { label: '블로그', href: '/blog' },
      { label: '채용', href: '/careers' },
      { label: '문의하기', href: '/contact' },
    ],
  },
  {
    title: '법적 고지',
    links: [
      { label: '이용약관', href: '/terms' },
      { label: '개인정보처리방침', href: '/privacy' },
    ],
  },
];

export function Footer({
  columns = DEFAULT_COLUMNS,
  tagline = '데이터와 AI 기술로 시설물 관리의 새로운 기준을 제시합니다.',
  copyright = '© 2026 FACIL.AI Inc. All rights reserved.',
}: FooterProps) {
  return (
    <footer className="site-footer">
      <div className="site-footer-top">
        <div className="site-footer-brand">
          <div className="site-footer-logo">
            <img className="site-footer-logo-mark" src={brandMark} alt="" />
            <span>HajaCheck</span>
          </div>
          <p className="site-footer-tagline">{tagline}</p>
        </div>

        {columns.map((column) => (
          <div key={column.title} className="site-footer-column">
            <h4>{column.title}</h4>
            <ul>
              {column.links.map((link) => (
                <li key={link.href}>
                  <Link to={link.href}>{link.label}</Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="site-footer-bottom">{copyright}</div>
    </footer>
  );
}
