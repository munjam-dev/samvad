import { IWhitePaper } from '@/interface';
import Image, { StaticImageData } from 'next/image';
import Link from 'next/link';

const FeaturedCard = ({ card }: { card: IWhitePaper }) => {
  return (
    <div className="col-span-12">
      <div className="dark:bg-background-6 group flex w-full flex-col items-start justify-between gap-5 rounded-4xl bg-white p-2 md:flex-row">
        <div className="flex h-full w-full max-w-[472px] flex-col items-start justify-between gap-y-5 p-5 md:min-h-[405px]">
          <div>
            <h2 className="text-heading-5 font-normal">{card?.title}</h2>
            <p>{card?.description}</p>
          </div>
          <div>
            <Link href={`/whitepaper/${card?.slug}`} aria-label="Read more" className="btn btn-md btn-accent">
              <span>Read More</span>
            </Link>
          </div>
        </div>
        <figure className="w-full overflow-hidden rounded-[20px] md:min-h-[405px] md:max-w-[613px]">
          <Image
            width={613}
            height={405}
            src={card?.img as StaticImageData}
            alt={card?.title || ''}
            className="h-full w-full rounded-[20px] object-cover transition-all duration-500 ease-in-out group-hover:scale-105 md:min-h-[405px]"
          />
        </figure>
      </div>
    </div>
  );
};

FeaturedCard.displayName = 'FeaturedCard';
export default FeaturedCard;
