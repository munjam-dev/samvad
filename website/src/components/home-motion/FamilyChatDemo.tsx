'use client';

import { AnimatePresence, motion, useInView, useReducedMotion } from 'framer-motion';
import { useEffect, useRef, useState } from 'react';
import { EASE } from './motion';

export type DemoMode = 'chat' | 'privacy' | 'languages' | 'calls' | 'moments' | 'groups';

interface Msg {
  id: number;
  mine: boolean;
  who?: string;
  text: string;
  reaction?: string;
}

/** Demo conversation. It is sample content and never real user data. */
const SCRIPT: Msg[] = [
  { id: 1, mine: false, who: 'Amma', text: 'Dinner at home today? ❤️', reaction: '❤️' },
  { id: 2, mine: true, text: 'Yes! I’ll be there 😊' },
  { id: 3, mine: false, who: 'Rohan', text: 'Sending the photos now!', reaction: '😍' },
  { id: 4, mine: true, text: 'Save me a seat 🙏' },
];

const AVATARS = [
  ['A', '#167b52'],
  ['R', '#d97706'],
  ['P', '#7c3aed'],
  ['S', '#0891b2'],
];

const Wave = () => (
  <div className="flex h-6 items-end gap-[3px]" aria-hidden="true">
    {Array.from({ length: 14 }).map((_, i) => (
      <motion.span
        key={i}
        className="w-[3px] rounded-full bg-primary-500"
        animate={{ height: [6, 8 + ((i * 7) % 16), 6] }}
        transition={{ duration: 1.1, repeat: Infinity, delay: i * 0.06, ease: 'easeInOut' }}
      />
    ))}
  </div>
);

const Overlay = ({ mode }: { mode: DemoMode }) => {
  const box = 'mx-3 mt-2 rounded-2xl border border-primary-100 bg-white/90 px-3 py-2 text-[11px] text-secondary shadow-sm';
  switch (mode) {
    case 'privacy':
      return (
        <div className={box}>
          <p className="font-medium">Private by default</p>
          <p className="text-secondary/60">No ads, no analytics. Encrypted in transit.</p>
        </div>
      );
    case 'languages':
      return (
        <div className={box}>
          <p className="font-medium">नमस्ते · వందనం · வணக்கம்</p>
          <p className="text-secondary/60">More languages are coming to Samvad.</p>
        </div>
      );
    case 'calls':
      return (
        <div className={`${box} flex items-center gap-3`}>
          <motion.span className="size-2.5 rounded-full bg-primary-500" animate={{ scale: [1, 1.6, 1], opacity: [1, 0.4, 1] }} transition={{ duration: 1.4, repeat: Infinity }} />
          <div className="flex-1">
            <p className="font-medium">Family call (coming soon)</p>
            <Wave />
          </div>
        </div>
      );
    case 'moments':
      return (
        <div className={`${box} flex gap-2`}>
          {['#a7e3c1', '#f4d9a8', '#c9bff0'].map((c, i) => (
            <motion.span key={c} initial={{ scale: 0.6, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} transition={{ delay: i * 0.12 }} className="h-10 flex-1 rounded-lg" style={{ background: c }} />
          ))}
        </div>
      );
    case 'groups':
      return (
        <div className={`${box} flex items-center gap-2`}>
          <div className="flex -space-x-2">
            {AVATARS.map(([l, c]) => (
              <span key={l} className="flex size-6 items-center justify-center rounded-full border-2 border-white text-[10px] font-semibold text-white" style={{ background: c }}>
                {l}
              </span>
            ))}
          </div>
          <span className="text-secondary/70">4 in this circle</span>
        </div>
      );
    default:
      return null;
  }
};

/** A working chat: bubbles arrive one after another with typing dots, reactions and delivery ticks. */
const FamilyChatDemo = ({ mode = 'chat' }: { mode?: DemoMode }) => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const inView = useInView(ref, { amount: 0.3 });
  const [count, setCount] = useState(reduce ? SCRIPT.length : 0);
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    if (reduce || !inView) return;
    let step = 0;
    let t1: ReturnType<typeof setTimeout>;
    let t2: ReturnType<typeof setTimeout>;
    const next = () => {
      if (step >= SCRIPT.length + 2) {
        step = 0;
        setCount(0);
      }
      if (step < SCRIPT.length) {
        setTyping(true);
        t1 = setTimeout(() => {
          setTyping(false);
          setCount((c) => c + 1);
          step += 1;
          t2 = setTimeout(next, 1100);
        }, 900);
      } else {
        step += 1;
        t2 = setTimeout(next, 1800);
      }
    };
    next();
    return () => {
      clearTimeout(t1);
      clearTimeout(t2);
    };
  }, [reduce, inView]);

  const shown = SCRIPT.slice(0, count);
  return (
    <div ref={ref} className="flex size-full flex-col bg-gradient-to-b from-[#eef6f1] to-[#f9fbf9] pt-9">
      <header className="flex items-center gap-2 border-b border-black/5 px-3 pb-2">
        <div className="flex -space-x-2">
          {AVATARS.slice(0, 3).map(([l, c]) => (
            <span key={l} className="relative flex size-7 items-center justify-center rounded-full border-2 border-white text-[11px] font-semibold text-white" style={{ background: c }}>
              {l}
              {l === 'A' && <span className="absolute -right-0.5 -bottom-0.5 size-2 rounded-full border border-white bg-emerald-400" aria-hidden="true" />}
            </span>
          ))}
        </div>
        <div className="min-w-0 flex-1 leading-tight">
          <p className="truncate text-[12px] font-semibold text-secondary">The Family ❤️</p>
          <p className="text-[10px] text-primary-600">3 online</p>
        </div>
      </header>
      <AnimatePresence mode="wait">
        <motion.div key={mode} initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.25 }}>
          <Overlay mode={mode} />
        </motion.div>
      </AnimatePresence>
      <div className="flex min-h-0 flex-1 flex-col justify-end gap-2 overflow-hidden px-3 pb-2">
        <AnimatePresence initial={false}>
          {shown.map((m) => (
            <motion.div
              key={m.id}
              layout
              initial={{ opacity: 0, y: 14, scale: 0.95 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              transition={{ duration: 0.3, ease: EASE }}
              className={`relative max-w-[78%] rounded-2xl px-3 py-1.5 text-[12px] leading-snug shadow-sm ${m.mine ? 'self-end rounded-br-sm bg-primary-500 text-white' : 'self-start rounded-bl-sm bg-white text-secondary'}`}>
              {!m.mine && <span className="block text-[10px] font-semibold text-primary-600">{m.who}</span>}
              {m.text}
              {m.mine && <span className="ml-1 text-[9px] text-white/80" aria-label="Delivered">✓✓</span>}
              {m.reaction && (
                <motion.span initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ delay: 0.5, duration: 0.3, ease: EASE }} className="absolute -right-2 -bottom-3 rounded-full bg-white px-1 text-[11px] shadow">
                  {m.reaction}
                </motion.span>
              )}
            </motion.div>
          ))}
          {typing && (
            <motion.div key="typing" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex w-fit items-center gap-1 self-start rounded-2xl rounded-bl-sm bg-white px-3 py-2 shadow-sm" aria-label="Someone is typing">
              {[0, 1, 2].map((i) => (
                <motion.span key={i} className="size-1.5 rounded-full bg-secondary/40" animate={{ y: [0, -3, 0] }} transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.15 }} />
              ))}
            </motion.div>
          )}
        </AnimatePresence>
        <div className="flex items-center gap-2 rounded-2xl bg-white px-3 py-2 shadow-sm" aria-hidden="true">
          <Wave />
          <span className="ml-auto text-[10px] text-secondary/50">0:12</span>
        </div>
      </div>
      <div className="flex items-center gap-2 border-t border-black/5 bg-white/80 px-3 py-2" aria-hidden="true">
        <span className="flex-1 rounded-full bg-[#eef3ef] px-3 py-1.5 text-[11px] text-secondary/40">Message</span>
        <span className="flex size-7 items-center justify-center rounded-full bg-primary-500 text-[12px] text-white">➤</span>
      </div>
    </div>
  );
};

export default FamilyChatDemo;
