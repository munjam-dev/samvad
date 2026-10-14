'use client';

import { motion, useReducedMotion, useScroll, useTransform } from 'framer-motion';
import { useRef, type ReactNode } from 'react';

/**
 * Moves its children against the scroll for depth. Transform only, so it stays on the GPU.
 * `distance` is the total travel in px across the element's pass through the viewport.
 */
const Parallax = ({ children, distance = 80, className = '', scale = 0 }: { children: ReactNode; distance?: number; className?: string; scale?: number }) => {
  const reduce = useReducedMotion();
  const ref = useRef<HTMLDivElement>(null);
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] });
  const y = useTransform(scrollYProgress, [0, 1], reduce ? [0, 0] : [distance / 2, -distance / 2]);
  const s = useTransform(scrollYProgress, [0, 0.5, 1], reduce || !scale ? [1, 1, 1] : [1 - scale, 1, 1 + scale]);
  return (
    <div ref={ref} className={className}>
      <motion.div style={{ y, scale: s, willChange: 'transform' }}>{children}</motion.div>
    </div>
  );
};

export default Parallax;
