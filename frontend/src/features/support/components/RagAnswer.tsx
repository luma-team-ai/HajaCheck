import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface RagAnswerProps {
  text: string;
}

// AI 어시스턴트 답변 말풍선 — #1700: 모델이 내는 마크다운(**볼드**, - 리스트 등)을 렌더한다.
// 선례: features/policy/components/PolicyContent.tsx(react-markdown+remark-gfm). 이 화면은
// Tailwind 기반이라 별도 CSS 파일 대신 components prop으로 Tailwind 클래스를 매핑한다.
//
// 보안(필수): rehype-raw 등 raw HTML 활성화 금지 — react-markdown 기본값(HTML 비활성)을 유지해야
// LLM 출력을 경유한 XSS가 원천 차단된다. 링크(a)도 클릭 가능한 앵커로 렌더하지 않고 텍스트로
// 떨군다 — RAG 답변의 URL은 근거 문서 링크가 아니라 모델 환각일 수 있고, 출처는 참고문서 칩
// (SourcesToggle/SourceChip)이 전담한다.
//
// whitespace-pre-wrap을 문단(p)에 둔 이유: RAG_NO_RESULT_TEXT처럼 개행이 의미 있는 안내 문구가
// 있다 — CommonMark의 단일 개행(soft break)은 텍스트 노드에 "\n"으로 남지만 기본 white-space:
// normal에서는 공백으로 접힌다. pre-wrap이어야 그 개행이 그대로 보인다.
export function RagAnswer({ text }: RagAnswerProps) {
  return (
    <div className="max-w-[816px] rounded-2xl rounded-tl-sm border border-border bg-white px-5 py-4 text-base font-normal leading-7 text-primary">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => (
            <p className="m-0 whitespace-pre-wrap first:mt-0 [&:not(:first-child)]:mt-3">{children}</p>
          ),
          ul: ({ children }) => <ul className="my-3 list-disc space-y-1 pl-5 first:mt-0 last:mb-0">{children}</ul>,
          ol: ({ children }) => (
            <ol className="my-3 list-decimal space-y-1 pl-5 first:mt-0 last:mb-0">{children}</ol>
          ),
          li: ({ children }) => <li className="leading-7">{children}</li>,
          strong: ({ children }) => <strong className="font-semibold">{children}</strong>,
          code: ({ children }) => (
            <code className="rounded bg-surface-sunken px-1.5 py-0.5 text-sm">{children}</code>
          ),
          h3: ({ children }) => <h3 className="mb-2 mt-4 text-base font-semibold first:mt-0">{children}</h3>,
          // RAG 답변 URL은 모델 환각일 수 있어 앵커로 렌더하지 않는다(위 컴포넌트 설명 참고).
          a: ({ children }) => <>{children}</>,
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
