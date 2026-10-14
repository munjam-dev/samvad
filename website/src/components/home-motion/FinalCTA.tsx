'use client';

import { motion, useReducedMotion, useScroll, useTransform } from 'framer-motion';
import Link from 'next/link';
import { useRef } from 'react';
import FamilyChatDemo from './FamilyChatDemo';
import Parallax from './Parallax';
import PhoneMockup from './PhoneMockup';

const FinalCTA = () => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] });
  const rotateX = useTransform(scrollYProgress, [0, 0.5, 1], reduce ? [0, 0, 0] : [18, 0, -10]);
  const y = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [60, -60]);
  const orbY = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [-40, 80]);
  return (
    <section ref={ref} className="relative mt-10 overflow-hidden rounded-t-[60px] bg-gradient-to-br from-[#0b4530] via-[#0f6a47] to-[#167b52] py-20 text-white lg:rounded-t-[100px] lg:py-[120px]" aria-labelledby="cta-title">
      <motion.span style={{ y: orbY }} aria-hidden="true" className="pointer-events-none absolute -top-20 -left-20 size-80 rounded-full bg-lime-300/25 blur-3xl" />
      <motion.span style={{ y: orbY }} aria-hidden="true" className="pointer-events-none absolute -right-24 bottom-0 size-96 rounded-full bg-emerald-300/25 blur-3xl" />
      {[0, 1, 2].map((n) => (
        <Parallax key={n} distance={120 + n * 60} className="pointer-events-none absolute" >
          <span aria-hidden="true" className="block rounded-3xl border border-white/15" style={{ width: 80 + n * 40, height: 80 + n * 40, marginLeft: `${10 + n * 28}vw`, marginTop: `${6 + n * 12}vh` }} />
        </Parallax>
      ))}
      <div className="main-container relative grid items-center gap-12 lg:grid-cols-2">
        <div className="space-y-6 text-center lg:text-left">
          <h2 id="cta-title" className="text-white">Your next favorite conversation starts here.</h2>
          <p className="text-white/80">Bring your people closer with Samvad.</p>
          <div className="flex flex-wrap items-center justify-center gap-3 lg:justify-start">
            <Link href="/download" className="btn btn-lg btn-white"><span>Get Samvad</span></Link>
            <Link href="/features" className="btn btn-lg border border-white/40 text-white hover:bg-white/10"><span>Explore the experience</span></Link>
          </div>
        </div>
        <motion.div style={{ y, rotateX, transformPerspective: 1000 }} className="mx-auto w-[240px] sm:w-[270px]">
          <PhoneMockup>
            <FamilyChatDemo mode="chat" />
          </PhoneMockup>
        </motion.div>
      </div>
    </section>
  );
};

export default FinalCTA;
