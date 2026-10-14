import featureImgTwo from '@public/images/ns-img-81.png';
import featureImgThree from '@public/images/ns-img-96.png';
import featureImgOne from '@public/images/ns-img-97.png';
import featureImgTwoDark from '@public/images/ns-img-dark-55.png';
import featureImgThreeDark from '@public/images/ns-img-dark-69.png';
import featureImgOneDark from '@public/images/ns-img-dark-70.png';
import Image from 'next/image';
import RevealAnimation from '../animation/RevealAnimation';

const Features = () => {
  return (
    <section className="overflow-hidden pt-14 pb-14 md:pt-16 md:pb-16 lg:pt-[88px] lg:pb-[88px] xl:pt-[100px] xl:pb-[100px]">
      <div className="main-container">
        <div className="lp:gap-[100px] grid grid-cols-12 items-center gap-y-14 lg:gap-14 lg:gap-y-0 xl:gap-20">
          <div className="lp:col-span-7 col-span-12 lg:col-span-6">
            <div className="relative mx-auto max-w-[704px] lg:mx-0">
              <RevealAnimation delay={0.3} direction="right">
                <figure className="absolute top-[40%] right-0 z-10 w-full max-w-[150px] sm:max-w-[200px] xl:top-[38%] xl:-right-7 xl:max-w-[253px]">
                  <Image src={featureImgOne} alt="features" className="inline-block h-full w-full dark:hidden" />
                  <Image src={featureImgOneDark} alt="features" className="hidden h-full w-full dark:block" />
                </figure>
              </RevealAnimation>
              <RevealAnimation delay={0.2}>
                <figure className="mx-auto w-full max-w-[300px] sm:max-w-[400px] lg:mx-0 xl:max-w-[504px]">
                  <Image
                    src={featureImgTwo}
                    alt="features"
                    className="inline-block h-full w-full object-cover dark:hidden"
                  />
                  <Image
                    src={featureImgTwoDark}
                    alt="features"
                    className="hidden h-full w-full object-cover dark:block"
                  />
                </figure>
              </RevealAnimation>
              <RevealAnimation delay={0.5} direction="right">
                <figure className="shadow-2 absolute right-0 bottom-10 w-full max-w-[200px] overflow-hidden rounded-xl sm:max-w-[300px] xl:bottom-1/12 xl:max-w-[395px]">
                  <Image src={featureImgThree} alt="features" className="inline-block h-full w-full dark:hidden" />
                  <Image src={featureImgThreeDark} alt="features" className="hidden h-full w-full dark:block" />
                </figure>
              </RevealAnimation>
            </div>
          </div>
          <div className="lp:col-span-5 col-span-12 lg:col-span-6">
            <div className="space-y-8">
              <div className="space-y-5">
                <RevealAnimation delay={0.1}>
                  <span className="badge badge-cyan">Reasons to select us</span>
                </RevealAnimation>
                <div className="space-y-3">
                  <RevealAnimation delay={0.2}>
                    <h2 className="max-w-[521px]">Easily manage your cash flow.</h2>
                  </RevealAnimation>
                  <RevealAnimation delay={0.3}>
                    <p>
                      Until recently, the prevailing view assumed lorem ipsum was born as a nonsense text nothing Before
                      &amp; After magazine.
                    </p>
                  </RevealAnimation>
                </div>
              </div>
              <div>
                <ul className="space-y-2">
                  <RevealAnimation delay={0.2}>
                    <li className="flex items-center gap-4 p-2">
                      <span className="ns-shape-8 text-secondary dark:text-accent text-[36px]" />
                      <span className="text-tagline-1 text-secondary dark:text-accent font-medium">
                        A version for offline use is available.
                      </span>
                    </li>
                  </RevealAnimation>
                  <RevealAnimation delay={0.3}>
                    <li className="flex items-center gap-4 p-2">
                      <span className="ns-shape-9 text-secondary dark:text-accent text-[36px]" />
                      <span className="text-tagline-1 text-secondary dark:text-accent font-medium">
                        Designed to be both scalable and secure.
                      </span>
                    </li>
                  </RevealAnimation>
                  <RevealAnimation delay={0.4}>
                    <li className="flex items-center gap-4 p-2">
                      <span className="ns-shape-12 text-secondary dark:text-accent text-[36px]" />
                      <span className="text-tagline-1 text-secondary dark:text-accent font-medium">
                        Feature powered by artificial intelligence.
                      </span>
                    </li>
                  </RevealAnimation>
                  <RevealAnimation delay={0.5}>
                    <li className="flex items-center gap-4 p-2">
                      <span className="ns-shape-21 text-secondary dark:text-accent text-[36px]" />
                      <span className="text-tagline-1 text-secondary dark:text-accent font-medium">
                        It offers both scalability and robust security.
                      </span>
                    </li>
                  </RevealAnimation>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};

Features.displayName = 'Features';
export default Features;
