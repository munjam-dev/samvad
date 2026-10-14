'use client';

import { useEffect, useRef } from 'react';
import { initStackCards } from '../utils/stack-card/stackCards';

export interface UseStackCardsOptions {
  topOffset?: string;
  gap?: string;
  initDelay?: number;
  disabled?: boolean;
}

export const useStackCards = (options: UseStackCardsOptions = {}) => {
  const { topOffset = '50vh', gap = '20px', initDelay = 100, disabled = false } = options;

  const stackCardsRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (globalThis.window === undefined || disabled || !stackCardsRef.current) {
      return;
    }

    const initialize = () => {
      // Use requestAnimationFrame to ensure DOM is ready
      requestAnimationFrame(() => {
        // Ensure the element is in the DOM and has the class
        if (stackCardsRef.current?.classList.contains('js-stack-cards')) {
          initStackCards();
        }
      });
    };

    const timer = setTimeout(initialize, initDelay);

    return () => {
      clearTimeout(timer);
    };
  }, [initDelay, disabled]);

  const stackCardsProps = {
    ref: stackCardsRef,
    className: 'js-stack-cards',
    style: {
      '--stack-cards-top-offset': topOffset,
      '--stack-cards-gap': gap,
    } as React.CSSProperties,
  };

  return {
    stackCardsRef,
    stackCardsProps,
  };
};
