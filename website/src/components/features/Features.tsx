import cardOneImg from '@public/images/ns-img-90.png';
import cardTwoImg from '@public/images/ns-img-91.png';
import cardThreeImg from '@public/images/ns-img-92.png';
import cardFourImg from '@public/images/ns-img-93.png';
import cardFiveImg from '@public/images/ns-img-94.png';
import cardOneImgDark from '@public/images/ns-img-dark-63.png';
import cardTwoImgDark from '@public/images/ns-img-dark-64.png';
import cardThreeImgDark from '@public/images/ns-img-dark-65.png';
import cardFourImgDark from '@public/images/ns-img-dark-66.png';
import cardFiveImgDark from '@public/images/ns-img-dark-67.png';
import Image from 'next/image';
import RevealAnimation from '../animation/RevealAnimation';
const Features = () => {
  return (
    <section className="pt-[100px] pb-[100px] md:pt-[160px]" aria-label="Features">
      <div className="main-container">
        <div className="space-y-[70px]">
          {/* feature heading  */}
          <div className="space-y-3 text-center">
            <RevealAnimation delay={0.3}>
              <h2 className="mx-auto max-w-[814px]">Everything you need to manage money smarter</h2>
            </RevealAnimation>
            <RevealAnimation delay={0.4}>
              <p className="mx-auto max-w-[734px]">
                NextSaaS simplifies finance with powerful tools designed for clarity, speed, and growth. Whether you’re
                budgeting, forecasting, or tracking cash flow—our platform keeps you in control.
              </p>
            </RevealAnimation>
          </div>
          {/* feature cards  */}
          {/* 1st row cards  */}
          <div className="flex flex-col items-center justify-center gap-y-8 sm:flex-row sm:gap-x-8">
            {/* card one  */}

            <RevealAnimation delay={0.5}>
              <div className="space-y-3">
                {/* card img  */}
                <div className="dark:bg-background-5 w-full max-w-[409px] rounded-[20px] bg-white p-2.5">
                  <figure className="bg-background-3 dark:bg-background-7 overflow-hidden rounded-2xl p-4">
                    <Image
                      src={cardOneImg}
                      alt="Fast and secure cloud upload feature"
                      className="h-full w-full object-cover dark:hidden"
                      loading="lazy"
                    />
                    <Image
                      src={cardOneImgDark}
                      alt="Fast and secure cloud upload feature"
                      className="hidden h-full w-full object-cover dark:block"
                      loading="lazy"
                    />
                  </figure>
                </div>
                {/* card content  */}
                <div className="space-y-1">
                  <h3 className="text-heading-5">Fast &amp; secure cloud upload</h3>
                  <p>Store, access, and manage data from anywhere.</p>
                </div>
              </div>
            </RevealAnimation>
            {/* card two  */}
            <RevealAnimation delay={0.6}>
              <div className="space-y-3">
                {/* card img  */}
                <div className="dark:bg-background-5 w-full max-w-[409px] rounded-[20px] bg-white p-2.5">
                  <figure className="bg-background-3 dark:bg-background-7 overflow-hidden rounded-2xl p-4">
                    <Image
                      src={cardTwoImg}
                      alt="Automated workflows feature"
                      className="h-full w-full object-cover dark:hidden"
                      loading="lazy"
                    />
                    <Image
                      src={cardTwoImgDark}
                      alt="Automated workflows feature"
                      className="hidden h-full w-full object-cover dark:block"
                      loading="lazy"
                    />
                  </figure>
                </div>
                {/* card content  */}
                <div className="space-y-1">
                  <h3 className="text-heading-5">Automated workflows</h3>
                  <p>Store, access, and manage data from anywhere.</p>
                </div>
              </div>
            </RevealAnimation>
            {/* card three  */}
            <RevealAnimation delay={0.7}>
              <div className="space-y-3">
                {/* card img  */}
                <div className="dark:bg-background-5 w-full max-w-[409px] rounded-[20px] bg-white p-2.5">
                  <figure className="bg-background-3 dark:bg-background-7 overflow-hidden rounded-2xl p-4">
                    <Image
                      src={cardThreeImg}
                      alt="Smart search and AI insights feature"
                      className="h-full w-full object-cover dark:hidden"
                      loading="lazy"
                    />
                    <Image
                      src={cardThreeImgDark}
                      alt="Smart search and AI insights feature"
                      className="hidden h-full w-full object-cover dark:block"
                      loading="lazy"
                    />
                  </figure>
                </div>
                {/* card content  */}
                <div className="space-y-1">
                  <h3 className="text-heading-5">Smart search &amp; AI insights</h3>
                  <p>Store, access, and manage data from anywhere.</p>
                </div>
              </div>
            </RevealAnimation>
          </div>
          {/* 2nd row cards  */}

          <div className="flex flex-col items-center justify-center gap-y-8 sm:flex-row sm:gap-x-8">
            {/* card four  */}
            <RevealAnimation delay={0.8}>
              <div className="space-y-3">
                {/* card img  */}
                <div className="dark:bg-background-5 w-full max-w-[409px] rounded-[20px] bg-white p-2.5">
                  <figure className="bg-background-3 dark:bg-background-7 overflow-hidden rounded-2xl p-4">
                    <Image
                      src={cardFourImg}
                      alt="Teamwork and leadership feature"
                      className="h-full w-full object-cover dark:hidden"
                      loading="lazy"
                    />
                    <Image
                      src={cardFourImgDark}
                      alt="Teamwork and leadership feature"
                      className="hidden h-full w-full object-cover dark:block"
                      loading="lazy"
                    />
                  </figure>
                </div>
                {/* card content  */}
                <div className="space-y-1">
                  <h3 className="text-heading-5">Teamwork &amp; leadership</h3>
                  <p>Store, access, and manage data from anywhere.</p>
                </div>
              </div>
            </RevealAnimation>
            {/* card five  */}
            <RevealAnimation delay={0.9}>
              <div className="space-y-3">
                {/* card img  */}
                <div className="dark:bg-background-5 w-full max-w-[409px] rounded-[20px] bg-white p-2.5">
                  <figure className="bg-background-3 dark:bg-background-7 overflow-hidden rounded-2xl p-4">
                    <Image
                      src={cardFiveImg}
                      alt="Custom dashboards feature"
                      className="h-full w-full object-cover dark:hidden"
                      loading="lazy"
                    />
                    <Image
                      src={cardFiveImgDark}
                      alt="Custom dashboards feature"
                      className="hidden h-full w-full object-cover dark:block"
                      loading="lazy"
                    />
                  </figure>
                </div>
                {/* card content  */}
                <div className="space-y-1">
                  <h3 className="text-heading-5">Custom dashboards</h3>
                  <p>Store, access, and manage data from anywhere.</p>
                </div>
              </div>
            </RevealAnimation>
          </div>
        </div>
      </div>
    </section>
  );
};
Features.displayName = 'Features';
export default Features;
