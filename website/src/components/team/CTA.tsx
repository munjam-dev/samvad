import { CheckIcon } from '@/icons';
import RevealAnimation from '../animation/RevealAnimation';

const CTA = () => {
  return (
    <section className="bg-background-2 dark:bg-background-6 pt-[120px] pb-[200px]">
      <div className="main-container">
        <div className="">
          <div className="space-y-3 text-center">
            <RevealAnimation delay={0.1}>
              <span className="badge badge-cyan mb-5 inline-block">Get started</span>
            </RevealAnimation>
            <RevealAnimation delay={0.2}>
              <h2 className="mx-auto max-w-[1042px]">
                NextSaaS – your ultimate email &amp; transactional messaging platform
              </h2>
            </RevealAnimation>
            <RevealAnimation delay={0.3}>
              <p className="mx-auto max-w-[600px]">
                Drive more engagement. improve deliverability. automate your emails.
              </p>
            </RevealAnimation>
          </div>
          <div className="mt-[62px] space-y-8 max-sm:mx-auto max-sm:w-[80%]">
            <RevealAnimation delay={0.4}>
              <form action="#" method="post" className="flex items-center justify-center gap-3 max-sm:flex-col">
                <input
                  type="email"
                  name="email"
                  id="userEmail"
                  placeholder="Enter your email"
                  className="shadow-1 placeholder:text-secondary/50 border-stroke-1 dark:bg-background-6 text-secondary/60 dark:bg-dark-200 focus:border-primary-500 dark:focus:border-primary-500 focus:ring-primary-500/20 h-12 w-full max-w-[440px] rounded-full border px-[18px] py-3 font-normal placeholder:font-normal focus:ring-2 focus:outline-none dark:border-[#31332F] dark:text-white/60 dark:placeholder:text-white/40"
                />
                <button
                  type="submit"
                  className="btn btn-md btn-primary dark:btn-accent dark:hover:btn-primary border-primary-400 hover:btn-secondary h-12 max-sm:w-full">
                  <span>Get started</span>
                </button>
              </form>
            </RevealAnimation>
            <ul className="flex items-center justify-center gap-[42px]">
              <RevealAnimation delay={0.2}>
                <li className="flex items-center justify-center gap-2">
                  <span className="bg-secondary dark:bg-accent flex size-[18px] items-center justify-center rounded-full">
                    <CheckIcon />
                  </span>
                  <p className="text-tagline-2">No credit card required</p>
                </li>
              </RevealAnimation>
              <RevealAnimation delay={0.3}>
                <li className="flex items-center justify-center gap-2">
                  <span className="bg-secondary dark:bg-accent flex size-[18px] items-center justify-center rounded-full">
                    <CheckIcon />
                  </span>
                  <p className="text-tagline-2">14-Day free trial</p>
                </li>
              </RevealAnimation>
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
};

export default CTA;
