import Link from 'next/link';
import RevealAnimation from '../animation/RevealAnimation';

const DetailsBody = ({ title, longDescription }: { title: string; longDescription: string }) => {
  return (
    <section className="pb-14 md:pb-28">
      <div className="main-container">
        <RevealAnimation delay={0.1}>
          <div className="bg-background-3 dark:bg-background-8 mx-auto max-w-[950px] space-y-6 rounded-4xl p-9 md:p-14">
            <RevealAnimation delay={0.2}>
              <div className="flex items-center gap-x-3">
                <p className="text-secondary dark:text-accent flex items-center gap-x-1 font-medium">
                  <Link href="./glossary">Glossary</Link>
                  <span className="flex size-[22px] shrink-0 items-center justify-center">
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      width={20}
                      height={20}
                      viewBox="0 0 20 20"
                      fill="none"
                      className="stroke-secondary dark:stroke-accent">
                      <path d="M7.5 15L12.5 10L7.5 5" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </span>
                </p>
                <p>What is {title}?</p>
              </div>
            </RevealAnimation>
            <div className="space-y-2">
              <RevealAnimation delay={0.3}>
                <h3 className="text-heading-5 font-normal">What is {title}?</h3>
              </RevealAnimation>
              <RevealAnimation delay={0.4}>
                <p>{longDescription}</p>
              </RevealAnimation>
            </div>
          </div>
        </RevealAnimation>
      </div>
    </section>
  );
};
DetailsBody.displayName = 'DetailsBody';
export default DetailsBody;
