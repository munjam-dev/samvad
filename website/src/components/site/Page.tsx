import Link from 'next/link';
import type { ReactNode } from 'react';
import RevealAnimation from '@/components/animation/RevealAnimation';

export interface Card {
  title: string;
  body: string;
}

export const PageHero = ({ badge, title, lead, children }: { badge?: string; title: string; lead: string; children?: ReactNode }) => (
  <section className="pt-32 pb-12 sm:pt-36 md:pt-42 xl:pt-[170px]">
    <div className="main-container">
      <div className="mx-auto max-w-[884px] space-y-4 text-center">
        {badge && (
          <RevealAnimation delay={0.1} instant>
            <span className="badge badge-green">{badge}</span>
          </RevealAnimation>
        )}
        <RevealAnimation delay={0.15} instant>
          <h1 className="font-normal">{title}</h1>
        </RevealAnimation>
        <RevealAnimation delay={0.2} instant>
          <p className="text-tagline-1 text-secondary/70 dark:text-accent/70 mx-auto max-w-[640px]">{lead}</p>
        </RevealAnimation>
        {children && <div className="flex flex-wrap items-center justify-center gap-3 pt-2">{children}</div>}
      </div>
    </div>
  </section>
);

export const CardGrid = ({ cards, heading }: { cards: Card[]; heading?: string }) => (
  <section className="pb-16 md:pb-24">
    <div className="main-container">
      <div className="mx-auto max-w-[1209px] space-y-8">
        {heading && <h2 className="text-heading-4">{heading}</h2>}
        <ul className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
          {cards.map((c) => (
            <li key={c.title} className="bg-background-3 dark:bg-background-6 space-y-2 rounded-[20px] p-7">
              <h3 className="text-heading-6">{c.title}</h3>
              <p className="text-tagline-2 text-secondary/70 dark:text-accent/70">{c.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </div>
  </section>
);

export const Prose = ({ children }: { children: ReactNode }) => (
  <section className="pb-20 md:pb-28">
    <div className="main-container">
      <div className="legal-prose mx-auto max-w-[780px]">{children}</div>
    </div>
  </section>
);

export const Cta = ({ href, children, secondary }: { href: string; children: ReactNode; secondary?: boolean }) => (
  <Link href={href} className={secondary ? 'btn btn-md btn-white dark:btn-white-dark' : 'btn btn-md btn-primary'}>
    <span>{children}</span>
  </Link>
);

export const Shell = ({ children }: { children: ReactNode }) => <main className="dark:bg-background-7 bg-white">{children}</main>;
