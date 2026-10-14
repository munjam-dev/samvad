'use client';

import { useNavbarScroll } from '@/hooks/useScrollHeader';
import { cn } from '@/utils/cn';
import { useLenis } from 'lenis/react';

interface AlphabeticalFilterButtonsProps {
  letters: { letter: string; id: string }[];
  activeCard: string | null;
}

const AlphabeticalFilterButtons = ({ letters, activeCard }: AlphabeticalFilterButtonsProps) => {
  const { isScrolled } = useNavbarScroll(300);
  const lenis = useLenis();

  const handleScroll = (letter: string) => {
    lenis?.scrollTo('#' + letter.toLowerCase(), {
      offset: -120,
    });
  };

  return (
    <div
      className={cn(
        'glossary-filters-buttons-container sticky top-25 flex flex-wrap items-center justify-center gap-x-2 gap-y-2 transition-all duration-500 ease-in-out md:justify-start xl:gap-y-0',
        isScrolled && 'z-10 py-5 backdrop-blur-[25px]',
      )}>
      {letters.map((letter) => (
        <button
          onClick={() => handleScroll(letter.letter.toLowerCase())}
          key={letter.id}
          className={cn(
            'font-inter-tight text-tagline-1 size-[41.5px] shrink-0 cursor-pointer rounded-[10px] p-2 leading-[150%] font-normal transition-colors duration-500',
            activeCard === letter.letter.toLowerCase()
              ? 'bg-primary-500 text-white'
              : 'bg-background-12 text-secondary',
          )}>
          {letter.letter}
        </button>
      ))}
    </div>
  );
};

AlphabeticalFilterButtons.displayName = 'AlphabeticalFilterButtons';
export default AlphabeticalFilterButtons;
