'use client';

import { motion } from 'framer-motion';
import Link from 'next/link';

const PRINCIPLES = [
  ['Collect little', 'No ads and no analytics. Contact numbers are matched on your phone and never stored on our servers.'],
  ['Protected in transit', 'Everything travels over TLS and is stored in Google Cloud regions in Mumbai and Singapore.'],
  ['You hold the keys to your account', 'Optional app lock and a two-step PIN. Delete your account and data from Settings.'],
  ['Honest about limits', 'Messages are not end-to-end encrypted yet. We will announce clearly when that changes.'],
];

const PrivacySection = () => (
  <section className="relative overflow-hidden bg-gradient-to-b from-primary-50 to-white py-20 lg:py-[120px] dark:from-background-8 dark:to-background-7" aria-labelledby="privacy-title">
    <div className="main-container grid items-center gap-12 lg:grid-cols-2">
      <div className="space-y-5">
        <span className="badge badge-green">Trust</span>
        <h2 id="privacy-title">Your conversations belong to you.</h2>
        <p className="text-secondary/70 dark:text-accent/70">Clear principles instead of fine print.</p>
        <ul className="space-y-4">
          {PRINCIPLES.map(([t, b], i) => (
            <motion.li key={t} initial={{ opacity: 0, x: -24 }} whileInView={{ opacity: 1, x: 0 }} viewport={{ once: true, amount: 0.5 }} transition={{ delay: i * 0.1 }} className="dark:bg-background-6 rounded-2xl bg-white p-4 shadow-sm">
              <p className="text-tagline-1 font-medium text-secondary dark:text-accent">{t}</p>
              <p className="text-tagline-2 text-secondary/70 dark:text-accent/70">{b}</p>
            </motion.li>
          ))}
        </ul>
        <Link href="/legal/privacy-policy" className="btn btn-md btn-primary inline-block">
          <span>Learn about privacy</span>
        </Link>
      </div>
      <div className="relative mx-auto flex size-[300px] items-center justify-center sm:size-[380px]" aria-hidden="true">
        <motion.span className="absolute inset-0 rounded-full bg-primary-200/50" animate={{ scale: [1, 1.08, 1] }} transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }} />
        <motion.span className="absolute inset-8 rounded-full bg-primary-300/40" animate={{ scale: [1, 1.06, 1] }} transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut', delay: 0.6 }} />
        <svg viewBox="0 0 120 140" className="relative h-52 w-44">
          <motion.path d="M60 8l46 18v36c0 34-20 56-46 68C34 118 14 96 14 62V26L60 8z" fill="#167b52" initial={{ scale: 0.7, opacity: 0 }} whileInView={{ scale: 1, opacity: 1 }} viewport={{ once: true }} style={{ originX: '60px', originY: '70px' }} transition={{ type: 'spring', stiffness: 90, damping: 14 }} />
          <motion.path d="M38 70l16 16 30-34" fill="none" stroke="#fff" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" initial={{ pathLength: 0 }} whileInView={{ pathLength: 1 }} viewport={{ once: true }} transition={{ delay: 0.5, duration: 0.8 }} />
        </svg>
      </div>
    </div>
  </section>
);

export default PrivacySection;
