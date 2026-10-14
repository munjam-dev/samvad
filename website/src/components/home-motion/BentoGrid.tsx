'use client';

import { AnimatePresence, motion, useInView, useReducedMotion, type Variants } from 'framer-motion';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { EASE } from './motion';

const container: Variants = { hidden: {}, show: { transition: { staggerChildren: 0.09 } } };
const item: Variants = { hidden: { opacity: 0, y: 28 }, show: { opacity: 1, y: 0, transition: { duration: 0.3, ease: EASE } } };

const Card = ({ className = '', title, body, children, tag }: { className?: string; title: string; body: string; children: ReactNode; tag?: string }) => (
  <motion.article
    variants={item}
    whileHover={{ y: -6, scale: 1.01 }}
    transition={{ duration: 0.3, ease: EASE }}
    className={`group dark:bg-background-6 relative flex flex-col justify-between gap-5 overflow-hidden rounded-[24px] border border-primary-100 bg-white p-6 shadow-sm dark:border-white/10 ${className}`}>
    <div aria-hidden="true" className="pointer-events-none absolute -top-24 -right-24 size-56 rounded-full bg-primary-200/40 blur-3xl transition-opacity duration-500 group-hover:opacity-100 dark:bg-primary-500/20" />
    <div className="relative min-h-0 flex-1">{children}</div>
    <div className="relative space-y-1">
      <h3 className="text-heading-6 text-secondary dark:text-accent flex items-center gap-2">
        {title}
        {tag && <span className="rounded-full bg-primary-50 px-2 py-0.5 text-[11px] font-medium text-primary-700">{tag}</span>}
      </h3>
      <p className="text-tagline-2 text-secondary/70 dark:text-accent/70">{body}</p>
    </div>
  </motion.article>
);

const TYPED = 'See you at the station at 6 😊';

const Typewriter = () => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const inView = useInView(ref, { amount: 0.5 });
  const [n, setN] = useState(reduce ? TYPED.length : 0);
  useEffect(() => {
    if (reduce || !inView) return;
    setN(0);
    const id = setInterval(() => setN((v) => (v >= TYPED.length + 14 ? 0 : v + 1)), 110);
    return () => clearInterval(id);
  }, [reduce, inView]);
  return (
    <div ref={ref} className="space-y-3">
      <div className="w-fit rounded-2xl rounded-bl-sm bg-primary-50 px-4 py-2 text-tagline-2 text-secondary">Reached? 🚆</div>
      <div className="ml-auto min-h-[38px] w-fit max-w-[85%] rounded-2xl rounded-br-sm bg-primary-500 px-4 py-2 text-tagline-2 text-white">
        {TYPED.slice(0, Math.min(n, TYPED.length))}
        {n < TYPED.length && <span className="ml-0.5 inline-block h-3 w-px animate-pulse bg-white align-middle" />}
      </div>
      <div className="flex items-center gap-1.5 pl-1" aria-hidden="true">
        {[0, 1, 2].map((i) => (
          <motion.span key={i} className="size-1.5 rounded-full bg-primary-400" animate={{ y: [0, -4, 0] }} transition={{ duration: 0.9, repeat: Infinity, delay: i * 0.15 }} />
        ))}
        <motion.span initial={{ scale: 0 }} whileInView={{ scale: 1 }} transition={{ delay: 0.6, duration: 0.3, ease: EASE }} className="ml-2 rounded-full bg-white px-2 py-0.5 text-sm shadow">👍</motion.span>
      </div>
    </div>
  );
};

const VideoTiles = () => (
  <div className="relative grid grid-cols-2 gap-2">
    {['#a7e3c1', '#f4d9a8', '#c9bff0', '#bfe3f0'].map((c, i) => (
      <div key={c} className="relative h-20 rounded-xl" style={{ background: c }}>
        <span className="absolute bottom-1.5 left-2 text-[11px] font-medium text-secondary/70">{['Amma', 'Rohan', 'Priya', 'You'][i]}</span>
      </div>
    ))}
    <div className="col-span-2 flex items-center gap-3 rounded-xl bg-secondary/90 px-3 py-2" aria-hidden="true">
      <motion.span className="size-2.5 rounded-full bg-emerald-400" animate={{ scale: [1, 1.5, 1], opacity: [1, 0.5, 1] }} transition={{ duration: 1.4, repeat: Infinity }} />
      <div className="flex h-5 flex-1 items-center gap-[3px]">
        {Array.from({ length: 22 }).map((_, i) => (
          <motion.span key={i} className="w-[3px] rounded-full bg-emerald-300" animate={{ height: [4, 6 + ((i * 5) % 14), 4] }} transition={{ duration: 1, repeat: Infinity, delay: i * 0.05 }} />
        ))}
      </div>
    </div>
  </div>
);

const Lock = () => (
  <svg viewBox="0 0 120 120" className="mx-auto h-28 w-28" fill="none" aria-hidden="true">
    <motion.path d="M38 54V40a22 22 0 0144 0v14" stroke="#167b52" strokeWidth="7" strokeLinecap="round" initial={{ pathLength: 0 }} whileInView={{ pathLength: 1 }} viewport={{ once: true }} transition={{ duration: 1.2 }} />
    <motion.rect x="28" y="54" width="64" height="46" rx="12" fill="#167b52" initial={{ scale: 0.8, opacity: 0 }} whileInView={{ scale: 1, opacity: 1 }} viewport={{ once: true }} style={{ originX: '60px', originY: '77px' }} transition={{ duration: 0.3, ease: EASE, delay: 0.3 }} />
    <circle cx="60" cy="74" r="6" fill="#fff" />
    <rect x="57" y="76" width="6" height="12" rx="3" fill="#fff" />
  </svg>
);

const Avatars = () => (
  <div className="relative h-28">
    {[['A', '#167b52'], ['R', '#d97706'], ['P', '#7c3aed'], ['S', '#0891b2'], ['M', '#e11d48']].map(([l, c], i) => (
      <motion.span key={l} initial={{ opacity: 0, x: -20 }} whileInView={{ opacity: 1, x: 0 }} viewport={{ once: true }} transition={{ delay: i * 0.1, duration: 0.3, ease: EASE }} className="absolute top-6 flex size-14 items-center justify-center rounded-full border-4 border-white text-lg font-semibold text-white shadow" style={{ left: i * 38, background: c }}>
        {l}
      </motion.span>
    ))}
    {['❤️', '😂', '👏'].map((e, i) => (
      <motion.span key={e} className="absolute text-xl" style={{ left: 30 + i * 60, top: 80 }} animate={{ y: [0, -50], opacity: [0, 1, 0] }} transition={{ duration: 3, repeat: Infinity, delay: i * 1 }}>
        {e}
      </motion.span>
    ))}
  </div>
);

const SNIPPETS = [
  ['English', 'Good morning, how are you?'],
  ['हिन्दी', 'सुप्रभात, आप कैसे हैं?'],
  ['తెలుగు', 'శుభోదయం, మీరు ఎలా ఉన్నారు?'],
  ['தமிழ்', 'காலை வணக்கம், எப்படி இருக்கிறீர்கள்?'],
];

const LanguageSwap = () => {
  const reduce = useReducedMotion();
  const [i, setI] = useState(0);
  useEffect(() => {
    if (reduce) return;
    const id = setInterval(() => setI((v) => (v + 1) % SNIPPETS.length), 2600);
    return () => clearInterval(id);
  }, [reduce]);
  return (
    <div className="space-y-2" aria-live="off">
      <p className="text-tagline-3 text-primary-600">{SNIPPETS[i][0]}</p>
      <div className="relative h-16">
        <AnimatePresence mode="wait">
          <motion.p key={i} initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -14 }} transition={{ duration: 0.35 }} className="absolute inset-0 rounded-2xl rounded-bl-sm bg-primary-50 px-4 py-3 text-tagline-1 text-secondary">
            {SNIPPETS[i][1]}
          </motion.p>
        </AnimatePresence>
      </div>
    </div>
  );
};

const Gallery = () => (
  <div className="grid grid-cols-3 gap-2">
    {['#a7e3c1', '#f4d9a8', '#c9bff0', '#bfe3f0', '#fbc4c4', '#d8e8a8'].map((c, i) => (
      <motion.div key={c} initial={{ opacity: 0, scale: 0.7 }} whileInView={{ opacity: 1, scale: 1 }} viewport={{ once: true }} whileHover={{ scale: 1.08, rotate: i % 2 ? 2 : -2 }} transition={{ delay: i * 0.07, duration: 0.3, ease: EASE }} className={`rounded-xl ${i === 0 ? 'col-span-2 row-span-2 h-[104px]' : 'h-[48px]'}`} style={{ background: c }} />
    ))}
  </div>
);

const BentoGrid = () => (
  <section className="py-20 lg:py-[120px]" aria-labelledby="bento-title">
    <div className="main-container">
      <div className="mx-auto mb-12 max-w-[700px] space-y-3 text-center">
        <span className="badge badge-green">Features</span>
        <h2 id="bento-title">Everything you need to stay close.</h2>
        <p className="text-secondary/70 dark:text-accent/70">Thoughtful features. Beautifully connected.</p>
      </div>
      <motion.div variants={container} initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:auto-rows-[minmax(230px,auto)] lg:grid-cols-6">
        <Card className="lg:col-span-3 lg:row-span-2" title="Expressive messaging" body="Replies, reactions and typing indicators that make every chat feel alive.">
          <Typewriter />
        </Card>
        <Card className="lg:col-span-3" title="Voice and video" tag="Coming soon" body="Calling is on the way. This is a preview of the experience.">
          <VideoTiles />
        </Card>
        <Card className="lg:col-span-3" title="Privacy by design" body="Encrypted in transit, optional app lock and two-step PIN, no ads and no analytics. Not end-to-end encrypted yet.">
          <Lock />
        </Card>
        <Card className="lg:col-span-2" title="Family and groups" body="Circles for the people who matter.">
          <Avatars />
        </Card>
        <Card className="lg:col-span-2" title="Language and connection" tag="More coming" body="Built for how India chats.">
          <LanguageSwap />
        </Card>
        <Card className="lg:col-span-2" title="Everyday moments" body="Share photos and keep memories together.">
          <Gallery />
        </Card>
      </motion.div>
    </div>
  </section>
);

export default BentoGrid;
