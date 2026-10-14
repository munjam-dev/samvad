'use client';

import { motion, useReducedMotion } from 'framer-motion';
import type { ReactNode } from 'react';

/**
 * A CSS-built phone: thin metallic frame, rounded screen, dynamic island, soft shadow.
 * It floats gently unless the visitor prefers reduced motion.
 */
const PhoneMockup = ({ children, className = '', float = true, label = 'Samvad app preview' }: { children: ReactNode; className?: string; float?: boolean; label?: string }) => {
  const reduce = useReducedMotion();
  return (
    <motion.div
      role="img"
      aria-label={label}
      animate={float && !reduce ? { y: [0, -10, 0] } : undefined}
      transition={float && !reduce ? { duration: 6, repeat: Infinity, ease: 'easeInOut' } : undefined}
      className={`relative mx-auto aspect-[9/19] w-full max-w-[300px] rounded-[48px] bg-gradient-to-b from-[#d9dfdc] via-[#9aa5a0] to-[#c9d1cd] p-[3px] shadow-[0_40px_80px_-20px_rgba(11,69,48,0.55)] ${className}`}>
      <div className="relative size-full rounded-[45px] bg-[#0b0f0d] p-[9px]">
        <div className="relative size-full overflow-hidden rounded-[37px] bg-[#f4f8f5]">
          <div className="absolute top-2.5 left-1/2 z-30 h-[22px] w-[84px] -translate-x-1/2 rounded-full bg-black" aria-hidden="true" />
          <div className="pointer-events-none absolute inset-0 z-30 rounded-[37px] bg-gradient-to-br from-white/25 via-transparent to-transparent" aria-hidden="true" />
          {children}
        </div>
      </div>
      <span className="absolute top-28 -left-[3px] h-10 w-[3px] rounded-l bg-[#9aa5a0]" aria-hidden="true" />
      <span className="absolute top-44 -right-[3px] h-14 w-[3px] rounded-r bg-[#9aa5a0]" aria-hidden="true" />
    </motion.div>
  );
};

export default PhoneMockup;
