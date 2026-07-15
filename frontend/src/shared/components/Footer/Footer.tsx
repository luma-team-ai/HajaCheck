import { Link } from 'react-router-dom';
import brandMark from '../../../assets/brand/brand-mark.png';

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
    <footer className="border-t border-border bg-surface-muted px-8 pt-[65px] pb-8">
      <div className="mx-auto flex max-w-7xl flex-wrap gap-10 pb-8">
        <div className="grow shrink basis-60 min-w-50">
          <div className="mb-3 flex items-center gap-2 text-base font-bold text-black">
            <img className="h-4 w-4 object-contain" src={brandMark} alt="" />
            <span>HajaCheck</span>
          </div>
          <p className="m-0 text-sm leading-[1.6] text-text-secondary">{tagline}</p>
        </div>

        {columns.map((column) => (
          <div key={column.title} className="grow shrink basis-40 min-w-35">
            <h4 className="m-0 mb-4 text-sm font-semibold tracking-[0.05em] text-black uppercase">
              {column.title}
            </h4>
            <ul className="m-0 flex list-none flex-col gap-3 p-0">
              {column.links.map((link) => (
                <li key={link.href}>
                  <Link to={link.href} className="text-sm text-text-secondary no-underline">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="mx-auto max-w-7xl border-t border-border pt-[33px] text-sm text-text-subtle">
        {copyright}
      </div>
    </footer>
  );
}
