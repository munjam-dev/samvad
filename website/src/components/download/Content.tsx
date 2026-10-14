import { AndroidIcon } from '@/icons';
import Image, { StaticImageData } from 'next/image';
import { FC } from 'react';
import RevealAnimation from '../animation/RevealAnimation';

interface DownloadItem {
  id: string;
  title: string;
  description: string;
  icon?: FC<{ className?: string }>;
  iconClassName?: string;
  image?: StaticImageData;
  imageAlt?: string;
  instant?: boolean;
}

const downloadItems: DownloadItem[] = [
  {
    id: '1',
    title: 'Android',
    description: 'Samvad for Android is coming soon to Google Play. Requires Android 8.0 or newer.',
    icon: AndroidIcon,
    iconClassName: 'size-6',
  },
];

const Content = () => {
  return (
    <section className="pb-16 lg:pb-20 xl:pb-28">
      <div className="main-container">
        <div className="mx-auto w-full max-w-[652px] space-y-2">
          {downloadItems.map((item, index) => {
            const delay = (index + 1) * 0.1;
            const IconComponent = item.icon;

            return (
              <RevealAnimation key={item.id} delay={delay} instant={item.instant}>
                <div className="bg-background-3 dark:bg-background-6 flex items-center justify-between rounded-xl p-5 sm:p-6">
                  <div className="flex items-center gap-x-3">
                    <figure className="flex size-13 shrink-0 items-center justify-center rounded-lg bg-white p-2">
                      {item.image && <Image src={item.image} alt={item.imageAlt || 'ios-icon'} />}
                      {!item.image && IconComponent && <IconComponent className={item.iconClassName} />}
                    </figure>
                    <div>
                      <p className="text-secondary dark:text-accent">{item.title}</p>
                      <p className="text-tagline-2">{item.description}</p>
                    </div>
                  </div>
                  <span className="badge badge-green text-tagline-3 shrink-0">Coming soon</span>
                </div>
              </RevealAnimation>
            );
          })}
        </div>
      </div>
    </section>
  );
};

Content.displayName = 'Content';
export default Content;
