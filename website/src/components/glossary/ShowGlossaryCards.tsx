'use client';
import glossaryData from '@/data/json/glossary/glossary.json';
import { useEffect, useRef, useState } from 'react';
import RevealAnimation from '../animation/RevealAnimation';
import AlphabeticalFilterButtons from './AlphabeticalFilterButtons';
import GlossaryCard from './GlossaryCard';
export interface GlossarySection {
  letter: string;
  id: string;
  items: GlossaryItem[];
}

export interface GlossaryItem {
  id: string;
  title: string;
  description: string;
  slug: string;
  longDescription: string;
}
const ShowGlossaryCards = () => {
  const sectionsData = glossaryData as GlossarySection[];
  const cardRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [activeCard, setActiveCard] = useState<string | null>('a');

  // Callback ref to collect all card refs
  const setCardRef = (index: number) => (el: HTMLDivElement | null) => {
    if (el) {
      cardRefs.current[index] = el;
    }
  };

  useEffect(() => {
    const cards = cardRefs.current.filter((card): card is HTMLDivElement => card !== null);

    if (!cards.length) {
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const id = entry.target.getAttribute('data-id');
            if (id) {
              setActiveCard(id);
            }
          }
        });
      },
      {
        rootMargin: '0% 0px -50% 0px',
      },
    );

    cards.forEach((card) => observer.observe(card));

    return () => {
      observer.disconnect();
    };
  }, [sectionsData.length]);

  return (
    <section className="pb-14 md:pb-28">
      <div className="main-container">
        <div className="space-y-18">
          {/* show alphabet active letter buttons  */}
          <RevealAnimation delay={0.3}>
            <AlphabeticalFilterButtons
              activeCard={activeCard}
              letters={sectionsData.map((section) => {
                return { letter: section.letter, id: section.id };
              })}
            />
          </RevealAnimation>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 md:gap-2 lg:grid-cols-3 xl:grid-cols-4">
            {/* all glossary cards  */}
            {sectionsData
              .flatMap((section) => section.items.map((item) => ({ item, sectionId: section.id })))
              .map(({ item, sectionId }, flatIndex) => (
                <GlossaryCard
                  dataId={sectionId.toLowerCase()}
                  key={`${sectionId}-${item.id}`}
                  ref={setCardRef(flatIndex)}
                  title={item.title}
                  description={item.description}
                  link={item.slug}
                />
              ))}
          </div>
        </div>
      </div>
    </section>
  );
};

ShowGlossaryCards.displayName = 'ShowGlossaryCards';
export default ShowGlossaryCards;
