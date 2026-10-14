'use client';

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { useState } from 'react';

/** Sample feedback for the design only. Replace with real, permissioned quotes once Samvad has users. */
const SAMPLES = [
  { name: 'Sample user A', role: 'Sample feedback', quote: 'The chat feels calm. Scheduling a birthday message the night before is my favourite thing.' },
  { name: 'Sample user B', role: 'Sample feedback', quote: 'I like that it says plainly what is and is not protected. No marketing speak.' },
  { name: 'Sample user C', role: 'Sample feedback', quote: 'The themes make our family group feel like our own space.' },
];

const TestimonialCarousel = () => {
  const reduce = useReducedMotion();
  const [i, setI] = useState(0);
  const [dir, setDir] = useState(1);
  const go = (n: number) => {
    setDir(n > i || (i === SAMPLES.length - 1 && n === 0) ? 1 : -1);
    setI((n + SAMPLES.length) % SAMPLES.length);
  };
  const s = SAMPLES[i];
  return (
    <section className="bg-background-3 dark:bg-background-7 py-20 lg:py-[120px]" aria-labelledby="community-title">
      <div className="main-container">
        <div className="mx-auto mb-10 max-w-[700px] space-y-3 text-center">
          <span className="badge badge-green">Community</span>
          <h2 id="community-title">Words from the people we are building for.</h2>
          <p className="text-tagline-2 rounded-full bg-amber-100 px-4 py-1 text-amber-900 inline-block">Sample feedback shown for design. Not real reviews.</p>
        </div>
        <div className="mx-auto max-w-[760px]" role="region" aria-roledescription="carousel" aria-label="Sample feedback">
          <div className="relative min-h-[260px] overflow-hidden rounded-[28px] bg-gradient-to-br from-white to-primary-50 p-8 shadow-lg sm:p-12 dark:from-background-6 dark:to-background-8">
            <AnimatePresence mode="wait" custom={dir}>
              <motion.figure key={i} custom={dir} initial={reduce ? false : { opacity: 0, x: dir * 60 }} animate={{ opacity: 1, x: 0 }} exit={reduce ? undefined : { opacity: 0, x: dir * -60 }} transition={{ duration: 0.35 }} aria-live="polite">
                <blockquote className="text-heading-5 text-secondary dark:text-accent">“{s.quote}”</blockquote>
                <figcaption className="mt-6 flex items-center gap-3">
                  <span className="flex size-11 items-center justify-center rounded-full bg-primary-500 text-white" aria-hidden="true">{s.name.slice(-1)}</span>
                  <span className="text-tagline-2 text-secondary dark:text-accent">
                    {s.name}
                    <br />
                    <span className="text-secondary/60 dark:text-accent/60">{s.role}</span>
                  </span>
                </figcaption>
              </motion.figure>
            </AnimatePresence>
          </div>
          <div className="mt-6 flex items-center justify-center gap-4">
            <button type="button" onClick={() => go(i - 1)} aria-label="Previous feedback" className="flex size-11 items-center justify-center rounded-full bg-white shadow hover:bg-primary-50 dark:bg-background-6">←</button>
            <div className="flex gap-2" role="tablist" aria-label="Choose feedback">
              {SAMPLES.map((x, n) => (
                <button key={x.name} type="button" role="tab" aria-selected={n === i} aria-label={`Feedback ${n + 1}`} onClick={() => go(n)} className={`size-3 rounded-full transition-all ${n === i ? 'w-8 bg-primary-500' : 'bg-primary-200'}`} />
              ))}
            </div>
            <button type="button" onClick={() => go(i + 1)} aria-label="Next feedback" className="flex size-11 items-center justify-center rounded-full bg-white shadow hover:bg-primary-50 dark:bg-background-6">→</button>
          </div>
        </div>
      </div>
    </section>
  );
};

export default TestimonialCarousel;
