import RevealAnimation from '@/components/animation/RevealAnimation';
import Image from 'next/image';

const WhyChooseUs = () => {
  return (
    <section className="bg-background-3 dark:bg-background-7 overflow-hidden py-20 lg:py-[120px]">
      <div className="main-container flex flex-col items-center justify-between gap-12 lg:flex-row">
        <div>
          <RevealAnimation delay={0.1}>
            <span className="badge badge-green mb-5">Why Samvad</span>
          </RevealAnimation>
          <RevealAnimation delay={0.2}>
            <h2 className="mb-3">
              Private by default,
              <br className="hidden lg:block" />
              calm by design.
            </h2>
          </RevealAnimation>
          <RevealAnimation delay={0.3}>
            <p className="lg:max-w-[536px]">
              Samvad collects as little as possible and gives you clear control over what you share.
            </p>
          </RevealAnimation>
          <ul className="mt-8 space-y-1 lg:mt-14">
            <RevealAnimation delay={0.4}>
              <li className="flex list-none items-center gap-4 py-2">
                <span className="ns-shape-8 text-secondary dark:text-accent text-[36px]"> </span>
                <strong className="text-tagline-1 text-secondary dark:text-accent font-medium">
                  Messages queue and send when you are back online.
                </strong>
              </li>
            </RevealAnimation>
            <RevealAnimation delay={0.5}>
              <li className="flex list-none items-center gap-4 py-2">
                <span className="ns-shape-9 text-secondary dark:text-accent text-[36px]"> </span>
                <strong className="text-tagline-1 text-secondary dark:text-accent font-medium">
                  No ads and no analytics.
                </strong>
              </li>
            </RevealAnimation>
            <RevealAnimation delay={0.6}>
              <li className="flex list-none items-center gap-4 py-2">
                <span className="ns-shape-12 text-secondary dark:text-accent text-[36px]"> </span>
                <strong className="text-tagline-1 text-secondary dark:text-accent font-medium">
                  Contact numbers are never stored on our servers.
                </strong>
              </li>
            </RevealAnimation>
            <RevealAnimation delay={0.7}>
              <li className="flex list-none items-center gap-4 py-2">
                <span className="ns-shape-21 text-secondary dark:text-accent text-[36px]"> </span>
                <strong className="text-tagline-1 text-secondary dark:text-accent font-medium">
                  Delete your account and data from Settings.
                </strong>
              </li>
            </RevealAnimation>
          </ul>
        </div>
        <div className="relative w-full max-w-[520px]">
          <RevealAnimation delay={0.2} direction="up" offset={150}>
            <figure className="flex aspect-square w-full items-center justify-center rounded-[20px] bg-linear-[180deg,_#CDE8D6_0%,_#8FC7A6_100%] p-10">
              <Image src="/samvad-mark.png" alt="Samvad logo" width={420} height={420} className="size-full object-contain" />
            </figure>
          </RevealAnimation>
        </div>
      </div>
    </section>
  );
};

export default WhyChooseUs;
