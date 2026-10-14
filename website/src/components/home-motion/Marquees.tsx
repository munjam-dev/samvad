'use client';

import { useReducedMotion } from 'framer-motion';

/** Endless, battery-friendly marquee (CSS transform only). It stops for visitors who prefer reduced motion. */
const Marquee = ({ items, reverse = false, label }: { items: { text: string; sub?: string }[]; reverse?: boolean; label: string }) => {
  const reduce = useReducedMotion();
  const row = (hidden: boolean) => (
    <ul className="flex shrink-0 items-center gap-4 pr-4" aria-hidden={hidden || undefined}>
      {items.map((it) => (
        <li key={it.text} className="dark:bg-background-6 flex items-center gap-2 rounded-full border border-primary-100 bg-white px-6 py-3 text-heading-6 whitespace-nowrap text-secondary shadow-sm dark:border-white/10 dark:text-accent">
          {it.text}
          {it.sub && <span className="text-tagline-3 text-secondary/50 dark:text-accent/50">{it.sub}</span>}
        </li>
      ))}
    </ul>
  );
  return (
    <div role="group" aria-label={label} className="relative overflow-hidden [mask-image:linear-gradient(to_right,transparent,black_10%,black_90%,transparent)]">
      <div className={`flex w-max ${reduce ? 'flex-wrap' : reverse ? 'marquee-track marquee-reverse' : 'marquee-track'}`}>
        {row(false)}
        {!reduce && row(true)}
      </div>
    </div>
  );
};

const LANGUAGES = [
  { text: 'English', sub: 'available' },
  { text: 'हिन्दी' },
  { text: 'తెలుగు' },
  { text: 'தமிழ்' },
  { text: 'বাংলা' },
  { text: 'मराठी' },
  { text: 'ಕನ್ನಡ' },
  { text: 'ગુજરાતી' },
  { text: 'മലയാളം' },
  { text: 'ਪੰਜਾਬੀ' },
  { text: 'ଓଡ଼ିଆ' },
  { text: 'اردو' },
];

const BUILT_ON = [
  { text: 'Android' },
  { text: 'Jetpack Compose' },
  { text: 'Google Cloud', sub: 'Mumbai' },
  { text: 'Firebase' },
  { text: 'Cloud Firestore' },
  { text: 'Firebase Auth' },
  { text: 'Cloud Functions' },
  { text: 'Firebase App Check' },
];

export const LanguageMarquee = () => (
  <section className="py-16 lg:py-24" aria-labelledby="lang-title">
    <div className="main-container mb-10 text-center">
      <span className="badge badge-green">Languages</span>
      <h2 id="lang-title" className="mt-3">Every language belongs.</h2>
      <p className="text-secondary/70 dark:text-accent/70 mx-auto mt-2 max-w-[560px]">Samvad is in English today. These Indian languages are coming.</p>
    </div>
    <div className="space-y-4">
      <Marquee items={LANGUAGES} label="Languages coming to Samvad" />
      <Marquee items={[...LANGUAGES].reverse()} reverse label="Languages coming to Samvad, second row" />
    </div>
  </section>
);

export const BuiltOnMarquee = () => (
  <section className="pb-16 lg:pb-24" aria-labelledby="built-title">
    <div className="main-container mb-8 text-center">
      <h2 id="built-title" className="text-heading-5">Built on dependable foundations</h2>
      <p className="text-tagline-2 text-secondary/60 dark:text-accent/60 mt-1">The technology Samvad runs on. Product names belong to their owners and do not imply endorsement.</p>
    </div>
    <Marquee items={BUILT_ON} label="Technology Samvad is built on" />
  </section>
);
