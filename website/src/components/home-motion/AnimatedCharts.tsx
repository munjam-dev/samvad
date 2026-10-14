'use client';

import { animate, motion, useInView, useReducedMotion } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';
import { EASE } from './motion';

const WEEK = [
  ['Mon', 42],
  ['Tue', 58],
  ['Wed', 35],
  ['Thu', 71],
  ['Fri', 88],
  ['Sat', 64],
  ['Sun', 96],
] as const;

const LINE = [12, 30, 22, 48, 40, 66, 58, 82];

const Counter = ({ to, suffix = '' }: { to: number; suffix?: string }) => {
  const ref = useRef<HTMLSpanElement>(null);
  const inView = useInView(ref, { once: true });
  const reduce = useReducedMotion();
  const [v, setV] = useState(reduce ? to : 0);
  useEffect(() => {
    if (!inView || reduce) return;
    const c = animate(0, to, { duration: 1.6, ease: 'easeOut', onUpdate: (x) => setV(Math.round(x)) });
    return () => c.stop();
  }, [inView, reduce, to]);
  return (
    <span ref={ref}>
      {v}
      {suffix}
    </span>
  );
};

const AnimatedCharts = () => {
  const w = 320;
  const h = 120;
  const pts = LINE.map((y, i) => [(i / (LINE.length - 1)) * w, h - (y / 100) * h] as const);
  const d = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
  return (
    <section className="py-20 lg:py-[120px]" aria-labelledby="charts-title">
      <div className="main-container">
        <div className="mx-auto mb-12 max-w-[700px] space-y-3 text-center">
          <span className="badge badge-green">Insights</span>
          <h2 id="charts-title">See your conversations come together.</h2>
          <p className="text-secondary/70 dark:text-accent/70">A design preview of how Samvad could summarise your week.</p>
        </div>
        <motion.div initial={{ opacity: 0, y: 40 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, amount: 0.2 }} transition={{ duration: 0.7 }} className="relative mx-auto max-w-[1100px] overflow-hidden rounded-[32px] border border-white/10 bg-gradient-to-br from-[#0b2a1f] via-[#0f3d2c] to-[#0b2a1f] p-6 text-white shadow-2xl md:p-10">
          <div aria-hidden="true" className="pointer-events-none absolute -top-20 -right-20 size-72 rounded-full bg-emerald-400/20 blur-3xl" />
          <p className="mb-6 inline-block rounded-full border border-white/20 bg-white/10 px-3 py-1 text-[12px]">Illustrative demo data, not real analytics</p>
          <div className="grid gap-6 lg:grid-cols-3">
            <div className="rounded-3xl bg-white/5 p-5 backdrop-blur-sm lg:col-span-2">
              <p className="text-tagline-2 text-white/70">Messages this week (demo)</p>
              <p className="text-heading-3"><Counter to={456} /></p>
              <svg viewBox={`0 0 ${w} ${h}`} className="mt-3 h-40 w-full" role="img" aria-label="Demo line chart of weekly activity rising over the week" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="lg1" x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0" stopColor="#6ee7b7" />
                    <stop offset="1" stopColor="#d9f99d" />
                  </linearGradient>
                </defs>
                <motion.path d={d} fill="none" stroke="url(#lg1)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" initial={{ pathLength: 0 }} whileInView={{ pathLength: 1 }} viewport={{ once: true }} transition={{ duration: 1.8, ease: 'easeInOut' }} />
              </svg>
              <div className="mt-4 flex items-end justify-between gap-2" role="img" aria-label="Demo bar chart of messages per day">
                {WEEK.map(([day, v], i) => (
                  <div key={day} className="flex flex-1 flex-col items-center gap-1">
                    <motion.div className="w-full max-w-[34px] rounded-t-lg bg-gradient-to-t from-emerald-500 to-lime-300" initial={{ height: 0 }} whileInView={{ height: v * 0.9 }} viewport={{ once: true }} transition={{ delay: i * 0.08, duration: 0.3, ease: EASE }} />
                    <span className="text-[11px] text-white/60">{day}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="flex flex-col gap-6">
              <div className="rounded-3xl bg-white/5 p-5 backdrop-blur-sm">
                <p className="text-tagline-2 text-white/70">Replied within an hour (demo)</p>
                <div className="relative mx-auto my-3 size-32">
                  <svg viewBox="0 0 120 120" className="size-full -rotate-90" aria-hidden="true">
                    <circle cx="60" cy="60" r="50" fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="12" />
                    <motion.circle cx="60" cy="60" r="50" fill="none" stroke="#6ee7b7" strokeWidth="12" strokeLinecap="round" initial={{ pathLength: 0 }} whileInView={{ pathLength: 0.82 }} viewport={{ once: true }} transition={{ duration: 1.6, ease: 'easeOut' }} />
                  </svg>
                  <span className="absolute inset-0 flex items-center justify-center text-heading-5"><Counter to={82} suffix="%" /></span>
                </div>
              </div>
              <div className="rounded-3xl bg-white/5 p-5 backdrop-blur-sm">
                <p className="text-tagline-2 mb-3 text-white/70">Timeline (demo)</p>
                <ul className="space-y-3">
                  {['Morning check-in', 'Photos shared', 'Evening group chat'].map((t, i) => (
                    <motion.li key={t} initial={{ opacity: 0, x: -16 }} whileInView={{ opacity: 1, x: 0 }} viewport={{ once: true }} transition={{ delay: 0.2 + i * 0.15 }} className="flex items-center gap-3 text-tagline-2">
                      <span className="size-2.5 rounded-full bg-emerald-300" aria-hidden="true" />
                      {t}
                    </motion.li>
                  ))}
                </ul>
              </div>
            </div>
          </div>
          <p className="text-tagline-3 mt-6 text-white/60">Samvad does not analyse your private conversations. These figures are sample values for the design only.</p>
        </motion.div>
      </div>
    </section>
  );
};

export default AnimatedCharts;
