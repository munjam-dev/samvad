'use client';

import { useEffect, useState } from 'react';

interface TocItem {
  id: string;
  title: string;
  level: 2 | 3;
}

/**
 * Sticky contents for a legal document. Highlights the section being read. On phones it collapses
 * into a disclosure above the text so it never takes the screen.
 */
const LegalToc = ({ items }: { items: TocItem[] }) => {
  const [active, setActive] = useState<string | null>(items[0]?.id ?? null);

  useEffect(() => {
    const headings = items.map((i) => document.getElementById(i.id)).filter((h): h is HTMLElement => h !== null);
    if (headings.length === 0) return;
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]) setActive(visible[0].target.id);
      },
      { rootMargin: '-120px 0px -65% 0px', threshold: 0 },
    );
    headings.forEach((h) => observer.observe(h));
    return () => observer.disconnect();
  }, [items]);

  const list = (
    <ol className="space-y-1">
      {items.map((item) => {
        const current = item.id === active;
        return (
          <li key={item.id} className={item.level === 3 ? 'pl-4' : ''}>
            <a
              href={`#${item.id}`}
              aria-current={current ? 'location' : undefined}
              className={`text-tagline-2 block rounded-lg px-3 py-1.5 transition-colors duration-300 ${
                current
                  ? 'bg-primary-50 text-primary-700 dark:bg-background-6 dark:text-primary-300 font-medium'
                  : 'text-secondary/70 hover:text-secondary dark:text-accent/70 dark:hover:text-accent'
              }`}>
              {item.title}
            </a>
          </li>
        );
      })}
    </ol>
  );

  return (
    <nav aria-label="On this page" className="legal-no-print">
      <details className="bg-background-3 dark:bg-background-6 rounded-[20px] p-4 lg:hidden">
        <summary className="text-tagline-1 text-secondary dark:text-accent cursor-pointer font-medium">On this page</summary>
        <div className="mt-3">{list}</div>
      </details>
      <div className="hidden lg:block">
        <p className="text-tagline-3 text-secondary/60 dark:text-accent/60 mb-3 px-3 font-medium tracking-wider uppercase">On this page</p>
        {list}
      </div>
    </nav>
  );
};

export default LegalToc;
