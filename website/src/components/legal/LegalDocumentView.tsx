import Link from 'next/link';
import ReactMarkdown, { type Components } from 'react-markdown';
import rehypeSlug from 'rehype-slug';
import remarkGfm from 'remark-gfm';
import { LEGAL_DETAILS, LEGAL_GROUPS } from '@/config/legal';
import { formatLegalDate, type LegalDoc, type LegalDocMeta } from '@/utils/legal';
import RevealAnimation from '../animation/RevealAnimation';
import LegalToc from './LegalToc';
import PrintButton from './PrintButton';

/** Internal links use client navigation; external ones open safely in a new tab; tables scroll. */
const markdownComponents: Components = {
  a: ({ href = '', children }) => {
    if (href.startsWith('/')) return <Link href={href}>{children}</Link>;
    if (href.startsWith('mailto:') || href.startsWith('#')) return <a href={href}>{children}</a>;
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  },
  table: ({ children }) => (
    <div className="mb-6 overflow-x-auto rounded-2xl border border-stroke-4 dark:border-stroke-6" tabIndex={0} role="region" aria-label="Table">
      <table className="mb-0!">{children}</table>
    </div>
  ),
};

/**
 * Pattern: long-form document with sticky contents. Header (title, summary, effective date, print),
 * then contents beside the text on desktop and above it on phones, then related documents and help.
 */
const LegalDocumentView = ({ doc, related }: { doc: LegalDoc; related: LegalDocMeta[] }) => {
  const group = LEGAL_GROUPS[doc.group];
  return (
    <>
      <section className="pt-32 pb-10 sm:pt-36 md:pt-42 xl:pt-[170px]">
        <div className="main-container">
          <div className="mx-auto max-w-[1209px] space-y-4">
            <RevealAnimation delay={0.1} instant>
              <nav aria-label="Breadcrumb" className="text-tagline-2 text-secondary/60 dark:text-accent/60 legal-no-print">
                <Link href="/legal" className="hover:text-secondary dark:hover:text-accent underline-offset-4 hover:underline">
                  Legal centre
                </Link>
                <span aria-hidden="true"> / </span>
                <span>{group.title}</span>
              </nav>
            </RevealAnimation>
            <RevealAnimation delay={0.15} instant>
              <h1 className="max-w-[884px] font-normal">{doc.title}</h1>
            </RevealAnimation>
            <RevealAnimation delay={0.2} instant>
              <p className="text-tagline-1 text-secondary/70 dark:text-accent/70 max-w-[720px]">{doc.summary}</p>
            </RevealAnimation>
            <RevealAnimation delay={0.25} instant>
              <div className="flex flex-wrap items-center gap-3 pt-2">
                <span className="badge badge-green">Effective {formatLegalDate(doc.updated)}</span>
                <PrintButton />
              </div>
            </RevealAnimation>
          </div>
        </div>
      </section>

      <section className="pb-20 md:pb-28">
        <div className="main-container">
          <div className="mx-auto grid max-w-[1209px] gap-8 lg:grid-cols-[260px_1fr] lg:gap-14">
            <aside className="lg:sticky lg:top-28 lg:max-h-[calc(100vh-8rem)] lg:self-start lg:overflow-y-auto">
              <LegalToc items={doc.toc} />
            </aside>
            <article className="legal-prose min-w-0 max-w-[780px]">
              <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSlug]} components={markdownComponents}>
                {doc.content}
              </ReactMarkdown>
            </article>
          </div>
        </div>
      </section>

      <section className="legal-no-print bg-background-3 dark:bg-background-6 py-16 md:py-20">
        <div className="main-container">
          <div className="mx-auto grid max-w-[1209px] gap-8 md:grid-cols-2">
            <div className="space-y-4">
              <h2 className="text-heading-5">Related documents</h2>
              <ul className="space-y-3">
                {related.map((r) => (
                  <li key={r.slug}>
                    <Link
                      href={`/legal/${r.slug}`}
                      className="dark:bg-background-7 hover:border-primary-300 border-stroke-4 dark:border-stroke-6 block rounded-[20px] border bg-white px-6 py-4 transition-colors duration-300">
                      <span className="text-tagline-1 text-secondary dark:text-accent block font-medium">{r.title}</span>
                      <span className="text-tagline-2 text-secondary/60 dark:text-accent/60">{r.summary}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
            <div className="dark:bg-background-7 space-y-3 rounded-[20px] bg-white p-8">
              <h2 className="text-heading-5">Questions or complaints?</h2>
              <p className="text-tagline-1 text-secondary/70 dark:text-accent/70">
                Our Grievance Officer acknowledges complaints within 24 hours and resolves them within 15 days.
              </p>
              <p className="text-tagline-1">
                <strong>{LEGAL_DETAILS.GRIEVANCE_NAME}</strong>
                <br />
                <a className="text-primary-500 dark:text-primary-300 underline underline-offset-4" href={`mailto:${LEGAL_DETAILS.GRIEVANCE_EMAIL}`}>
                  {LEGAL_DETAILS.GRIEVANCE_EMAIL}
                </a>
              </p>
              <Link href="/legal/grievance-redressal" className="text-tagline-2 text-primary-500 dark:text-primary-300 inline-block underline underline-offset-4">
                How complaints are handled
              </Link>
            </div>
          </div>
        </div>
      </section>
    </>
  );
};

export default LegalDocumentView;
