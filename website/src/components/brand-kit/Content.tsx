import Image from 'next/image';
import Link from 'next/link';
import RevealAnimation from '../animation/RevealAnimation';

const rules = [
  {
    id: '1',
    title: 'Use the mark as it is',
    description:
      'Use the Samvad mark in its original colours on a plain light or dark background. Keep clear space around it equal to the width of one leaf.',
  },
  {
    id: '2',
    title: 'Do not alter it',
    description:
      'Do not recolour, stretch, rotate, outline, add effects to or rearrange the leaves, and do not place it on a busy photo.',
  },
  {
    id: '3',
    title: 'Do not imply endorsement',
    description:
      'Samvad is a product of Arlo Technologies. Do not use the name or mark in a way that suggests we sponsor, endorse or are affiliated with you without our written permission.',
  },
];

const colours = [
  ['Samvad green', '#167B52'],
  ['Deep green', '#0B4530'],
  ['Ivory', '#F8F5EC'],
];

const Content = () => {
  return (
    <section>
      <div className="main-container">
        <div className="mx-auto w-full max-w-[850px] space-y-17.5 pb-16 lg:pb-20 xl:pb-28">
          <RevealAnimation delay={0.1}>
            <div className="space-y-6">
              <h2 className="text-heading-4 font-normal">Download the Samvad mark</h2>
              <div className="flex flex-col items-start gap-y-5 md:gap-y-8 lg:flex-row lg:gap-x-8">
                <div className="w-full space-y-4">
                  <figure className="bg-secondary flex w-full items-center justify-center overflow-hidden rounded-[20px] px-[92px] py-[100px]">
                    <Image src="/samvad-mark.png" alt="Samvad mark on a dark background" width={200} height={200} />
                  </figure>
                  <Link
                    download=""
                    href="/samvad-mark.png"
                    className="text-tagline-1 text-secondary dark:text-accent px-5 font-normal underline underline-offset-2">
                    Download (PNG)
                  </Link>
                </div>
                <div className="w-full space-y-4">
                  <figure className="flex w-full items-center justify-center overflow-hidden rounded-[20px] bg-white px-[92px] py-[100px]">
                    <Image src="/samvad-mark.png" alt="Samvad mark on a light background" width={200} height={200} />
                  </figure>
                  <Link
                    download=""
                    href="/samvad-mark.png"
                    className="text-tagline-1 text-secondary dark:text-accent px-5 font-normal underline underline-offset-2">
                    Download (PNG)
                  </Link>
                </div>
              </div>
            </div>
          </RevealAnimation>
          <RevealAnimation delay={0.2}>
            <div className="space-y-6">
              <h2 className="text-heading-4 font-normal">About Samvad</h2>
              <p className="text-secondary/80 dark:text-accent/80">
                Samvad means conversation. It is a private messenger made in India by Arlo Technologies, built to feel
                calm, fast and honest about your data. It has no ads and no analytics.
              </p>
              <p className="text-secondary/80 dark:text-accent/80">
                The mark is seven glassy green leaves in a pinwheel, for people gathering around a conversation.
              </p>
            </div>
          </RevealAnimation>
          <RevealAnimation delay={0.3}>
            <div className="space-y-6">
              <h2 className="text-heading-4 font-normal">Colours</h2>
              <ul className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                {colours.map(([name, hex]) => (
                  <li key={name} className="border-stroke-1 dark:border-stroke-7 overflow-hidden rounded-[20px] border">
                    <div style={{ background: hex }} className="h-24" />
                    <p className="text-tagline-2 text-secondary dark:text-accent p-4">
                      {name}
                      <br />
                      <span className="text-secondary/60 dark:text-accent/60">{hex}</span>
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          </RevealAnimation>
          <div className="space-y-6">
            <RevealAnimation delay={0.4}>
              <h2 className="text-heading-4 font-normal">Using the brand</h2>
            </RevealAnimation>
            <div className="space-y-2">
              {rules.map((card, i) => (
                <RevealAnimation key={card.id} delay={0.5 + i * 0.1}>
                  <div className="border-stroke-1 dark:border-stroke-7 bg-background-1 dark:bg-background-7 space-y-2 rounded-[20px] border p-6">
                    <h3 className="text-heading-5 font-normal">{card.title}</h3>
                    <p>{card.description}</p>
                  </div>
                </RevealAnimation>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};

Content.displayName = 'Content';
export default Content;
