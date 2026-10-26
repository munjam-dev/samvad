'use client';

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { EASE } from './motion';
import FamilyChatDemo, { type DemoMode } from './FamilyChatDemo';
import PhoneMockup from './PhoneMockup';

const TABS: { id: DemoMode; label: string; title: string; body: string }[] = [
  { id: 'chat', label: 'Chat', title: 'Chat that keeps up with you', body: 'Replies, reactions, edits and delivery ticks. Schedule a message for later and star the ones you want to keep.' },
  { id: 'calls', label: 'Calls', title: 'Calls are coming soon', body: 'Voice and video calling is on the roadmap. This preview shows the direction, not a shipped feature.' },
  { id: 'groups', label: 'Groups', title: 'Spaces for your circles', body: 'Start a group for family or friends, name it, add a photo and keep everyone in one thread.' },
  { id: 'privacy', label: 'Privacy', title: 'Privacy you can read', body: 'No ads and no analytics. Encrypted in transit, with optional app lock and a two-step PIN. One-to-one chats can be end-to-end encrypted; groups and calls are not yet.' },
  { id: 'moments', label: 'Sharing', title: 'Share what matters', body: 'Send photos in chat and keep your favourite messages in a private starred list.' },
];

const FeatureExplorer = () => {
  const reduce = useReducedMotion();
  const [i, setI] = useState(0);
  const uid = useId();
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const onKey = (e: KeyboardEvent) => {
    let n = i;
    if (e.key === 'ArrowRight') n = (i + 1) % TABS.length;
    else if (e.key === 'ArrowLeft') n = (i - 1 + TABS.length) % TABS.length;
    else if (e.key === 'Home') n = 0;
    else if (e.key === 'End') n = TABS.length - 1;
    else return;
    e.preventDefault();
    setI(n);
    refs.current[n]?.focus();
  };

  const t = TABS[i];
  return (
    <section className="bg-background-3 dark:bg-background-7 py-20 lg:py-[120px]" aria-labelledby="explorer-title">
      <div className="main-container">
        <div className="mx-auto mb-10 max-w-[700px] space-y-3 text-center">
          <span className="badge badge-green">Explore</span>
          <h2 id="explorer-title">Pick a feature, see it move.</h2>
        </div>
        <div role="tablist" aria-label="Samvad features" onKeyDown={onKey} className="mx-auto mb-10 flex max-w-full flex-wrap justify-center gap-1 rounded-full bg-white p-1.5 shadow-sm dark:bg-background-6">
          {TABS.map((x, n) => (
            <button
              key={x.id}
              ref={(el) => {
                refs.current[n] = el;
              }}
              role="tab"
              id={`${uid}-tab-${n}`}
              aria-selected={i === n}
              aria-controls={`${uid}-panel`}
              tabIndex={i === n ? 0 : -1}
              onClick={() => setI(n)}
              className={`text-tagline-2 relative min-h-11 rounded-full px-5 py-2 font-medium transition-colors focus-visible:outline-2 focus-visible:outline-primary-500 ${i === n ? 'text-white' : 'text-secondary/70 dark:text-accent/70'}`}>
              {i === n && <motion.span layoutId={`${uid}-pill`} className="absolute inset-0 rounded-full bg-primary-500" transition={reduce ? { duration: 0 } : { duration: 0.3, ease: EASE }} />}
              <span className="relative">{x.label}</span>
            </button>
          ))}
        </div>
        <div role="tabpanel" id={`${uid}-panel`} aria-labelledby={`${uid}-tab-${i}`} className="mx-auto grid max-w-[980px] items-center gap-10 md:grid-cols-2">
          <AnimatePresence mode="wait">
            <motion.div key={t.id + 'text'} initial={reduce ? false : { opacity: 0, x: -30 }} animate={{ opacity: 1, x: 0 }} exit={reduce ? undefined : { opacity: 0, x: -30 }} transition={{ duration: 0.3 }} className="space-y-3 text-center md:text-left">
              <h3 className="text-heading-4">{t.title}</h3>
              <p className="text-secondary/70 dark:text-accent/70">{t.body}</p>
            </motion.div>
          </AnimatePresence>
          <AnimatePresence mode="wait">
            <motion.div key={t.id + 'phone'} initial={reduce ? false : { opacity: 0, scale: 0.92, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={reduce ? undefined : { opacity: 0, scale: 0.92 }} transition={{ duration: 0.3, ease: EASE }} className="mx-auto w-[240px] md:w-[270px]">
              <PhoneMockup float={false}>
                <FamilyChatDemo mode={t.id} />
              </PhoneMockup>
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </section>
  );
};

export default FeatureExplorer;
