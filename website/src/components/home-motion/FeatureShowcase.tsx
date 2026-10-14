'use client';

import { motion, useMotionValue, useMotionValueEvent, useReducedMotion, useScroll, useSpring, useTransform } from 'framer-motion';
import { useRef, useState } from 'react';
import { EASE } from './motion';
import FamilyChatDemo, { type DemoMode } from './FamilyChatDemo';
import Parallax from './Parallax';
import PhoneMockup from './PhoneMockup';

interface Feature {
  id: DemoMode;
  title: string;
  body: string;
  icon: string;
}

const LEFT: Feature[] = [
  { id: 'chat', title: 'Conversations that feel alive', body: 'Smooth messaging, expressive reactions and fluid chat transitions.', icon: 'M4 5h16v11H8l-4 4V5z' },
  { id: 'privacy', title: 'Your privacy, always', body: 'No ads, no analytics, and plain-language security you can read.', icon: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6l8-3z' },
  { id: 'languages', title: 'Every language belongs', body: 'Built for how India chats, with more Indian languages coming.', icon: 'M3 5h8M7 3v2m-3 8c3-1 5-4 6-8M6 9c1 2 3 4 5 5m3 6l4-10 4 10m-6.5-3h5' },
];
const RIGHT: Feature[] = [
  { id: 'calls', title: 'Calls that bring you closer', body: 'Voice and video calling is on the way. Coming soon.', icon: 'M5 4h4l2 5-2.5 1.5a11 11 0 005 5L15 13l5 2v4a2 2 0 01-2 2A16 16 0 013 6a2 2 0 012-2z' },
  { id: 'moments', title: 'Your moments, beautifully shared', body: 'Photos, reactions and everyday memories in one thread.', icon: 'M4 5h16v14H4zM4 15l5-5 4 4 3-3 4 4' },
  { id: 'groups', title: 'Spaces for your circles', body: 'Family groups, close friends and conversations that matter.', icon: 'M8 11a3 3 0 100-6 3 3 0 000 6zm8 0a3 3 0 100-6 3 3 0 000 6zM2 20c0-3 3-5 6-5s6 2 6 5m2-4c3 0 6 1.5 6 4' },
];
const ALL = [...LEFT, ...RIGHT];

const Icon = ({ d }: { d: string }) => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={d} />
  </svg>
);

const FeatureCard = ({ f, active, onSelect, from }: { f: Feature; active: boolean; onSelect: () => void; from: 'left' | 'right' }) => {
  const reduce = useReducedMotion();
  const rx = useMotionValue(0);
  const ry = useMotionValue(0);
  const srx = useSpring(rx, { stiffness: 220, damping: 34 });
  const sry = useSpring(ry, { stiffness: 220, damping: 34 });
  return (
    <motion.button
      type="button"
      onClick={onSelect}
      onFocus={onSelect}
      onPointerEnter={(e) => e.pointerType === 'mouse' && onSelect()}
      onPointerMove={(e) => {
        if (e.pointerType !== 'mouse' || reduce) return;
        const r = e.currentTarget.getBoundingClientRect();
        ry.set(((e.clientX - r.left) / r.width - 0.5) * 8);
        rx.set(-((e.clientY - r.top) / r.height - 0.5) * 8);
      }}
      onPointerLeave={() => {
        rx.set(0);
        ry.set(0);
      }}
      aria-pressed={active}
      initial={reduce ? false : { opacity: 0, x: from === 'left' ? -40 : 40 }}
      whileInView={{ opacity: 1, x: 0 }}
      viewport={{ once: true, amount: 0.4 }}
      transition={{ duration: 0.3, ease: EASE }}
      style={{ rotateX: srx, rotateY: sry, transformPerspective: 800 }}
      className={`relative w-[78vw] max-w-[320px] shrink-0 snap-center rounded-[22px] p-[1.5px] text-left transition-shadow duration-300 lg:w-full lg:max-w-none ${active ? 'bg-gradient-to-br from-primary-400 via-emerald-300 to-lime-300 shadow-[0_0_40px_-6px_rgba(22,123,82,0.55)]' : 'bg-primary-100/80 dark:bg-white/10'}`}>
      <span className="dark:bg-background-6 flex h-full flex-col gap-2 rounded-[21px] bg-white p-5">
        <span className={`flex size-10 items-center justify-center rounded-xl transition-colors ${active ? 'bg-primary-500 text-white' : 'bg-primary-50 text-primary-600'}`}>
          <Icon d={f.icon} />
        </span>
        <span className="text-heading-6 text-secondary dark:text-accent">{f.title}</span>
        <span className="text-tagline-2 text-secondary/70 dark:text-accent/70">{f.body}</span>
      </span>
    </motion.button>
  );
};

const Connector = ({ side, active }: { side: 'left' | 'right'; active: boolean }) => (
  <motion.span
    aria-hidden="true"
    initial={{ scaleX: 0 }}
    whileInView={{ scaleX: 1 }}
    viewport={{ once: true }}
    transition={{ duration: 0.8, delay: 0.4 }}
    style={{ originX: side === 'left' ? 0 : 1 }}
    className={`absolute top-1/2 hidden h-px w-[14%] lg:block ${side === 'left' ? 'right-[33%]' : 'left-[33%]'} ${active ? 'bg-gradient-to-r from-transparent via-primary-400 to-transparent' : 'bg-primary-200/60'}`}
  />
);

/** Centrepiece: a working phone with three features either side. The feature in view drives the phone. */
const FeatureShowcase = () => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLElement>(null);
  const [active, setActive] = useState(0);
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start 0.7', 'end 0.3'] });
  useMotionValueEvent(scrollYProgress, 'change', (v) => {
    if (window.matchMedia('(pointer: fine)').matches && window.innerWidth >= 1024) return;
    setActive(Math.min(ALL.length - 1, Math.max(0, Math.floor(v * ALL.length))));
  });
  const phoneY = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [50, -50]);
  const rotate = useTransform(scrollYProgress, [0, 0.4, 1], reduce ? [0, 0, 0] : [-10, 0, 8]);

  return (
    <section ref={ref} id="showcase" className="relative overflow-hidden py-20 lg:py-[120px]" aria-labelledby="showcase-title">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 bg-gradient-to-b from-primary-50 via-[#e6f4ec] to-primary-50 dark:from-background-8 dark:via-background-7 dark:to-background-8" />
      <Parallax distance={160} className="pointer-events-none absolute inset-0 -z-10"><div aria-hidden="true" className="mx-auto mt-24 size-[520px] rounded-full bg-primary-300/30 blur-3xl" /></Parallax>
      <div className="main-container">
        <div className="mx-auto mb-12 max-w-[700px] space-y-3 text-center">
          <span className="badge badge-green">Samvad in action</span>
          <h2 id="showcase-title">Six reasons it feels like home</h2>
          <p className="text-secondary/70 dark:text-accent/70">A live, sample conversation. The chat on the phone is a demonstration, not real data.</p>
        </div>
        <div className="grid items-center gap-10 lg:grid-cols-[1fr_minmax(280px,340px)_1fr] lg:gap-14">
          <div className="relative order-2 flex snap-x gap-4 overflow-x-auto pb-4 lg:order-1 lg:flex-col lg:overflow-visible lg:pb-0">
            {LEFT.map((f, i) => (
              <FeatureCard key={f.id} f={f} from="left" active={active === i} onSelect={() => setActive(i)} />
            ))}
            <Connector side="left" active />
          </div>
          <motion.div style={{ y: phoneY, rotateZ: rotate }} className="order-1 lg:order-2">
            <motion.div initial={reduce ? false : { opacity: 0, scale: 0.85 }} whileInView={{ opacity: 1, scale: 1 }} viewport={{ once: true, amount: 0.3 }} transition={{ duration: 0.3, ease: EASE }}>
              <PhoneMockup>
                <FamilyChatDemo mode={ALL[active].id} />
              </PhoneMockup>
            </motion.div>
          </motion.div>
          <div className="relative order-3 flex snap-x gap-4 overflow-x-auto pb-4 lg:flex-col lg:overflow-visible lg:pb-0">
            {RIGHT.map((f, i) => (
              <FeatureCard key={f.id} f={f} from="right" active={active === i + 3} onSelect={() => setActive(i + 3)} />
            ))}
            <Connector side="right" active />
          </div>
        </div>
        <p className="text-tagline-3 text-secondary/60 dark:text-accent/60 mt-8 text-center" aria-live="polite">
          Showing: {ALL[active].title}
        </p>
      </div>
    </section>
  );
};

export default FeatureShowcase;
