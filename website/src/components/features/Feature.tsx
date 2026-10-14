import { CheckIcon } from '@/icons';
import aboutBg from '@public/images/ns-img-14.png';
import Image from 'next/image';
import RevealAnimation from '../animation/RevealAnimation';
import LinkButton from '../ui/button/LinkButton';

interface FeatureItem {
  id: string;
  text: string;
}
const features: FeatureItem[] = [
  {
    id: 'innovative-startups',
    text: 'Schedule messages for tonight, tomorrow morning or any time up to 30 days ahead',
  },
  {
    id: 'enterprise-teams',
    text: 'Make every chat yours with themes and wallpapers',
  },
  {
    id: 'agencies-consultants',
    text: 'Keep a private list of starred messages',
  },
  {
    id: 'finance-marketing',
    text: 'Delete your account and data whenever you like',
  },
];

const Feature = () => {
  return (
    <section>
      <RevealAnimation delay={0.2}>
        <div className="main-container">
          <div className="relative z-10">
            <div className="absolute top-0 right-0 bottom-0 left-0 -z-10 overflow-hidden rounded-[20px]">
              <Image src={aboutBg} alt="about bg" className="h-full w-full object-cover" />
            </div>
            <div className="grid grid-cols-2 gap-5 px-6 py-14 max-lg:grid-cols-1 max-sm:gap-10 lg:px-11">
              <div className="max-w-[500px]">
                <h2 className="text-accent sm:text-heading-5 text-heading-6 mb-8">
                  Messaging that respects you, with the tools you actually use.
                </h2>
                <LinkButton href="/download" className="btn-accent dark:btn-dark hover:btn-primary btn-md btn border-0">
                  Get Samvad
                </LinkButton>
              </div>
              <div>
                <ul className="space-y-4">
                  {features.map((feature) => (
                    <li key={feature.id} className="flex items-center gap-2">
                      <span className="bg-accent/17 dark:bg-accent/10 flex size-5 shrink-0 items-center justify-center rounded-full">
                        <CheckIcon className="dark:fill-accent" />
                      </span>
                      <span className="text-accent">{feature.text}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          </div>
        </div>
      </RevealAnimation>
    </section>
  );
};

export default Feature;
