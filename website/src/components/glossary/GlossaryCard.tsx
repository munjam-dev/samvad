import Link from 'next/link';
import { forwardRef } from 'react';

interface GlossaryCardProps {
  title: string;
  description: string;
  dataId: string;
  link: string;
}

const GlossaryCard = forwardRef<HTMLDivElement, GlossaryCardProps>(({ title, description, dataId, link }, ref) => {
  return (
    <div
      id={dataId.toLowerCase()}
      data-id={dataId}
      ref={ref}
      className="glossary-card dark:bg-background-8 flex flex-col justify-between gap-y-9 rounded-[20px] bg-white p-8">
      <div className="space-y-6">
        <h3 className="text-heading-5 font-normal">{title}</h3>
        <p className="text-tagline-1 text-secondary/80 dark:text-accent/80 line-clamp-4">{description}</p>
      </div>
      <div>
        <Link href={`/glossary/${link}`} className="btn btn-md btn-accent hover:btn-secondary dark:hover:btn-primary">
          <span>Learn more</span>
        </Link>
      </div>
    </div>
  );
});

GlossaryCard.displayName = 'GlossaryCard';
export default GlossaryCard;
