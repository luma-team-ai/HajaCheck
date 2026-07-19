import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

interface PolicyContentProps {
  mdPath: string;
}

export function PolicyContent({ mdPath }: PolicyContentProps) {
  const [markdown, setMarkdown] = useState<string | null>(null);
  const [hasError, setHasError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setMarkdown(null);
    setHasError(false);

    fetch(mdPath)
      .then((res) => {
        if (!res.ok) throw new Error(`fetch failed: ${res.status}`);
        return res.text();
      })
      .then((text) => {
        if (!cancelled) setMarkdown(text);
      })
      .catch(() => {
        if (!cancelled) setHasError(true);
      });

    return () => {
      cancelled = true;
    };
  }, [mdPath]);

  if (hasError) {
    return <p className="policy-content-error">문서를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</p>;
  }

  if (markdown === null) {
    return <p className="policy-content-loading">불러오는 중...</p>;
  }

  return (
    <article className="policy-content">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
    </article>
  );
}
