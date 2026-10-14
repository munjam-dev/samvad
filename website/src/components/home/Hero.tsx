import heroDotVectorBg from '@public/images/ns-img-161.svg';
import Image from 'next/image';
import RevealAnimation from '../animation/RevealAnimation';
import LinkButton from '../ui/button/LinkButton';

const Hero = () => {
  return (
    <section className="pb-14 md:pb-16 lg:pb-[88px] xl:pb-[100px]" aria-label="Hero Section">
      <div className="mx-auto max-w-[1440px]">
        <RevealAnimation delay={0.1}>
          <div className="relative top-4 h-auto overflow-hidden rounded-[20px] bg-linear-[180deg,_#CDE8D6_0%,_#8FC7A6_100%] lg:top-8">
            <figure
              className="absolute inset-0 z-0 translate-x-3 animate-pulse lg:translate-x-4 xl:translate-x-[75px]"
              aria-hidden="true">
              <Image src={heroDotVectorBg} alt="Decorative background pattern" />
            </figure>
            <div className="main-container relative z-10">
              <div className="flex flex-col items-center justify-center gap-y-16 pt-36 md:flex-row md:pt-40 lg:pt-[150px] xl:pt-[170px]">
                <div>
                  <div className="mb-6 space-y-2 text-center md:mb-8 md:space-y-4 md:text-left">
                    <RevealAnimation delay={0.2}>
                      <h1 className="dark:text-secondary max-w-full lg:max-w-[507px]">Conversations that feel like home</h1>
                    </RevealAnimation>
                    <RevealAnimation delay={0.4}>
                      <p className="text-secondary/60 md:max-w-[429px]">
                        Samvad is a calm, private messenger made in India. Chat one to one or in groups, schedule messages for later and make every chat yours.
                      </p>
                    </RevealAnimation>
                  </div>
                  <div className="mb-10 lg:mb-14" />
                  {/* btn-white */}
                  <RevealAnimation delay={0.8}>
                    <div className="mx-auto w-[95%] md:mx-0 md:w-full">
                      <LinkButton
                        href="/download"
                        className="btn btn-white btn-xl hover:btn-secondary dark:btn-black dark:hover:btn-accent w-full md:w-auto"
                        aria-label="Get Samvad for Android">
                        Get Samvad
                      </LinkButton>
                    </div>
                  </RevealAnimation>
                </div>
                <RevealAnimation delay={0.9}>
                  <figure className="w-full max-w-[643px]">
                    <Image src="/samvad-mark.png" alt="Samvad logo" width={512} height={512} priority className="mx-auto size-full max-w-[420px] object-contain pb-10" />
                  </figure>
                </RevealAnimation>
              </div>
            </div>
          </div>
        </RevealAnimation>
      </div>
    </section>
  );
};

export default Hero;
