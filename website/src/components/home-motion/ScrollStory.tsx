'use client';

import { motion, useReducedMotion, useScroll, useTransform, type MotionValue } from 'framer-motion';
import { useRef } from 'react';
import FamilyChatDemo from './FamilyChatDemo';
import PhoneMockup from './PhoneMockup';

const OneMessage = () => (
  <div className="flex size-full items-center justify-center bg-gradient-to-b from-[#eef6f1] to-white px-5">
    <motion.div animate={{ boxShadow: ['0 0 0px rgba(22,123,82,0)', '0 0 40px rgba(22,123,82,0.45)', '0 0 0px rgba(22,123,82,0)'] }} transition={{ duration: 3, repeat: Infinity }} className="rounded-2xl rounded-bl-sm bg-white px-4 py-3 text-[13px] text-secondary">
      Thinking of you ❤️
    </motion.div>
  </div>
);

const Scene = ({ p, range, children }: { p: MotionValue<number>; range: [number, number, number, number]; children: React.ReactNode }) => {
  const opacity = useTransform(p, range, [0, 1, 1, 0]);
  const y = useTransform(p, range, [40, 0, 0, -40]);
  const scale = useTransform(p, range, [0.92, 1, 1, 1.04]);
  return (
    <motion.div style={{ opacity, y, scale }} className="absolute inset-0 flex items-center justify-center">
      {children}
    </motion.div>
  );
};

const Orbit = ({ p }: { p: MotionValue<number> }) => {
  const rot = useTransform(p, [0.6, 1], [0, 60]);
  return (
    <motion.div style={{ rotate: rot }} className="absolute inset-0" aria-hidden="true">
      {[['A', '#167b52'], ['R', '#d97706'], ['P', '#7c3aed'], ['S', '#0891b2'], ['M', '#e11d48']].map(([l, c], i) => {
        const a = (i / 5) * Math.PI * 2;
        return (
          <span key={l} className="absolute flex size-12 items-center justify-center rounded-full border-4 border-white text-sm font-semibold text-white shadow-lg" style={{ left: `calc(50% + ${Math.cos(a) * 170}px - 24px)`, top: `calc(50% + ${Math.sin(a) * 170}px - 24px)`, background: c }}>
            {l}
          </span>
        );
      })}
    </motion.div>
  );
};

type Range = [number, number, number, number];
const SCENES: Range[] = [
  [0, 0.02, 0.28, 0.36],
  [0.3, 0.38, 0.62, 0.7],
  [0.62, 0.7, 0.98, 1],
];

const Captions = ['The message', 'The connection', 'The moment'];
const Texts = ['One message can change a whole day.', 'It becomes a family, talking, laughing and reacting together.', 'Calls, photos and memories, all in one place.'];

/** Pinned cutscene on desktop; three compact scroll-triggered scenes on phones. */
const ScrollStory = () => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start start', 'end end'] });
  const phoneScale = useTransform(scrollYProgress, [0, 0.62, 0.85], reduce ? [1, 1, 1] : [1, 1, 0.8]);
  const phoneX = useTransform(scrollYProgress, [0.62, 0.85], reduce ? [0, 0] : [0, -150]);

  return (
    <section aria-labelledby="story-title" className="relative">
      <div className="main-container pt-20 text-center lg:pt-[120px]">
        <span className="badge badge-green">The feeling</span>
        <h2 id="story-title" className="mx-auto mt-3 max-w-[760px]">
          More than messages. A feeling of connection.
        </h2>
      </div>

      <div ref={ref} className="relative hidden h-[300vh] lg:block">
        <div className="sticky top-0 flex h-screen items-center overflow-hidden">
          <div aria-hidden="true" className="absolute inset-0 -z-10 bg-gradient-to-b from-white via-primary-50 to-white dark:from-background-7 dark:via-background-8 dark:to-background-7" />
          <div className="main-container relative grid grid-cols-2 items-center gap-10">
            <div className="relative h-[420px]">
              {Captions.map((c, i) => {
                return (
                  <Scene key={c} p={scrollYProgress} range={SCENES[i]}>
                    <div className="max-w-[440px] space-y-3">
                      <p className="text-tagline-2 font-medium text-primary-600">Scene {i + 1} · {c}</p>
                      <p className="text-heading-4 text-secondary dark:text-accent">{Texts[i]}</p>
                    </div>
                  </Scene>
                );
              })}
            </div>
            <div className="relative flex h-[560px] items-center justify-center">
              <Orbit p={scrollYProgress} />
              <motion.div style={{ scale: phoneScale, x: phoneX }} className="relative w-[280px]">
                <PhoneMockup float={false}>
                  <div className="relative size-full">
                    <Scene p={scrollYProgress} range={SCENES[0]}>
                      <OneMessage />
                    </Scene>
                    <Scene p={scrollYProgress} range={SCENES[1]}>
                      <div className="size-full"><FamilyChatDemo mode="groups" /></div>
                    </Scene>
                    <Scene p={scrollYProgress} range={SCENES[2]}>
                      <div className="size-full"><FamilyChatDemo mode="moments" /></div>
                    </Scene>
                  </div>
                </PhoneMockup>
              </motion.div>
            </div>
          </div>
        </div>
      </div>

      <div className="main-container space-y-16 py-14 lg:hidden">
        {Captions.map((c, i) => (
          <motion.div key={c} initial={reduce ? false : { opacity: 0, y: 40 }} whileInView={{ opacity: 1, y: 0 }} viewport={{ once: true, amount: 0.3 }} transition={{ duration: 0.6 }} className="space-y-4 text-center">
            <p className="text-tagline-2 font-medium text-primary-600">Scene {i + 1} · {c}</p>
            <p className="text-heading-5 text-secondary dark:text-accent">{Texts[i]}</p>
            <div className="mx-auto w-[220px]">
              <PhoneMockup float={false}>{i === 0 ? <OneMessage /> : <FamilyChatDemo mode={i === 1 ? 'groups' : 'moments'} />}</PhoneMockup>
            </div>
          </motion.div>
        ))}
      </div>
    </section>
  );
};

export default ScrollStory;
