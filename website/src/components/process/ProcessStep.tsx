'use client';
import { useGSAP } from '@gsap/react';
import step from '@public/images/ns-img-149.png';
import stepDark from '@public/images/ns-img-dark-103.png';
import Image from 'next/image';
import { useRef } from 'react';
import RevealAnimation from '../animation/RevealAnimation';

import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

if (typeof window !== 'undefined') {
  gsap.registerPlugin(ScrollTrigger);
}

const ProcessStep = () => {
  const stepLine1 = useRef<SVGSVGElement>(null);
  const stepLine2 = useRef<SVGSVGElement>(null);
  const stepLine3 = useRef<SVGSVGElement>(null);
  const stepLine4 = useRef<SVGSVGElement>(null);

  const scopeRef = useRef<HTMLDivElement | null>(null);

  const animationInitialized = useRef(false);

  useGSAP(
    () => {
      if (animationInitialized.current) {
        return;
      }
      animationInitialized.current = true;

      const stepLines = [stepLine1.current, stepLine2.current, stepLine3.current, stepLine4.current].filter(
        Boolean,
      ) as SVGSVGElement[];

      if (!stepLines.length) {
        return;
      }

      gsap.set(stepLines, { height: 0 });

      stepLines.forEach((line, index) => {
        gsap.to(line, {
          height: 380,
          duration: 1.5,
          ease: 'power3.out',
          delay: index * 0.2,
          scrollTrigger: {
            trigger: line,
            start: 'top 80%',
            end: 'top 20%',
            toggleActions: 'play none none reverse',
          },
        });
      });
    },

    { scope: scopeRef },
  );
  return (
    <section className="pt-[120px] pb-[120px] lg:pt-[160px] xl:pb-[200px]">
      <div className="main-container">
        <div className="mb-[70px] space-y-5 text-center">
          <RevealAnimation delay={0.1}>
            <span className="badge badge-green">Process</span>
          </RevealAnimation>
          <div className="space-y-3">
            <RevealAnimation delay={0.2}>
              <h2>How we’ll work together</h2>
            </RevealAnimation>
            <RevealAnimation delay={0.3}>
              <p className="mx-auto max-w-[610px]">
                I follow a process that’s transparent, collaborative, and results-driven—built around clear
                communication and creative problem-solving.
              </p>
            </RevealAnimation>
          </div>
        </div>
        <RevealAnimation delay={0.4}>
          <div className="process-steps">
            <div className="mx-auto mb-[100px] max-w-[870px]">
              <div className="relative">
                <div>
                  <div className="mx-auto flex size-[34px] items-center justify-center rounded-full bg-white drop-shadow-2xl dark:bg-black">
                    <figure className="size-7">
                      <Image src={step} alt="step" className="h-full w-full object-cover dark:hidden" />
                      <Image src={stepDark} alt="step" className="hidden h-full w-full object-cover dark:block" />
                    </figure>
                  </div>
                  <div className="bg-stroke-2 dark:bg-stroke-6 mx-auto h-[320px] w-1 lg:h-[380px]">
                    <svg
                      ref={stepLine1}
                      xmlns="http://www.w3.org/2000/svg"
                      className="step-line h-[0px] w-1"
                      viewBox="0 0 2 222"
                      fill="none">
                      <path
                        d="M1 1L0.99999 221"
                        stroke="url(#paint0_linear_1182_24963_step1)"
                        strokeWidth={2}
                        strokeLinecap="round"
                      />
                      <defs>
                        <linearGradient
                          id="paint0_linear_1182_24963_step1"
                          x1="0.5"
                          y1={1}
                          x2="0.49999"
                          y2={221}
                          gradientUnits="userSpaceOnUse">
                          <stop offset={0} stopColor="#64D9FD" />
                          <stop offset="0.25" stopColor="#7E57FD" />
                          <stop offset="0.5" stopColor="white" />
                          <stop offset={1} stopColor="#B04BFD" />
                        </linearGradient>
                      </defs>
                    </svg>
                  </div>
                </div>
                <RevealAnimation delay={0.2}>
                  <div className="card-item dark:bg-background-6 max-sm:bg-background-4 absolute top-1/2 left-1/2 w-full max-w-[370px] -translate-x-1/2 -translate-y-1/2 space-y-3 rounded-[20px] p-6 lg:top-0 lg:left-0 lg:translate-x-0 lg:translate-y-0 lg:rounded-none lg:bg-none lg:p-0 lg:dark:bg-transparent">
                    <p className="text-tagline-2 text-primary-500">STEP 1</p>
                    <div className="space-y-2">
                      <h3 className="text-heading-6 lg:text-heading-5">Kickoff call &amp; brand discovery</h3>
                      <p>
                        We begin by understanding your vision and goals through a detailed discovery call to align on
                        the project’s direction.
                      </p>
                    </div>
                  </div>
                </RevealAnimation>
              </div>
              <div className="relative">
                <div>
                  <div className="mx-auto flex size-[34px] items-center justify-center rounded-full bg-white drop-shadow-2xl dark:bg-black">
                    <figure className="size-7">
                      <Image src={step} alt="step" className="h-full w-full object-cover dark:hidden" />
                      <Image src={stepDark} alt="step" className="hidden h-full w-full object-cover dark:block" />
                    </figure>
                  </div>
                  <div className="bg-stroke-2 dark:bg-stroke-6 mx-auto h-[320px] w-1 lg:h-[380px]">
                    <svg
                      ref={stepLine2}
                      xmlns="http://www.w3.org/2000/svg"
                      className="step-line h-[0px] w-1"
                      viewBox="0 0 2 222"
                      fill="none">
                      <path
                        d="M1 1L0.99999 221"
                        stroke="url(#paint0_linear_1182_24963_step2)"
                        strokeWidth={2}
                        strokeLinecap="round"
                      />
                      <defs>
                        <linearGradient
                          id="paint0_linear_1182_24963_step2"
                          x1="0.5"
                          y1={1}
                          x2="0.49999"
                          y2={221}
                          gradientUnits="userSpaceOnUse">
                          <stop offset={0} stopColor="#64D9FD" />
                          <stop offset="0.25" stopColor="#7E57FD" />
                          <stop offset="0.5" stopColor="white" />
                          <stop offset={1} stopColor="#B04BFD" />
                        </linearGradient>
                      </defs>
                    </svg>
                  </div>
                </div>
                <RevealAnimation delay={0.3}>
                  <div className="card-item dark:bg-background-6 max-sm:bg-background-4 absolute top-1/2 right-1/2 w-full max-w-[370px] translate-x-1/2 -translate-y-1/2 space-y-3 rounded-[20px] p-6 lg:top-0 lg:right-0 lg:translate-x-0 lg:translate-y-0 lg:rounded-none lg:bg-none lg:p-0 lg:dark:bg-transparent">
                    <p className="text-tagline-2 text-primary-500">STEP 2</p>
                    <div className="space-y-2">
                      <h3 className="text-heading-6 lg:text-heading-5 max-w-[293px]">
                        Strategy, wireframes &amp; creative direction
                      </h3>
                      <p>
                        We begin by understanding your vision and goals through a detailed discovery call to align on
                        the project’s direction.
                      </p>
                    </div>
                  </div>
                </RevealAnimation>
              </div>
              <div className="relative">
                <div>
                  <div className="mx-auto flex size-[34px] items-center justify-center rounded-full bg-white drop-shadow-2xl dark:bg-black">
                    <figure className="size-7">
                      <Image src={step} alt="step" className="h-full w-full object-cover dark:hidden" />
                      <Image src={stepDark} alt="step" className="hidden h-full w-full object-cover dark:block" />
                    </figure>
                  </div>
                  <div className="bg-stroke-2 dark:bg-stroke-6 mx-auto h-[320px] w-1 lg:h-[380px]">
                    <svg
                      ref={stepLine3}
                      xmlns="http://www.w3.org/2000/svg"
                      className="step-line h-[0px] w-1"
                      viewBox="0 0 2 222"
                      fill="none">
                      <path
                        d="M1 1L0.99999 221"
                        stroke="url(#paint0_linear_1182_24963_step3)"
                        strokeWidth={2}
                        strokeLinecap="round"
                      />
                      <defs>
                        <linearGradient
                          id="paint0_linear_1182_24963_step3"
                          x1="0.5"
                          y1={1}
                          x2="0.49999"
                          y2={221}
                          gradientUnits="userSpaceOnUse">
                          <stop offset={0} stopColor="#64D9FD" />
                          <stop offset="0.25" stopColor="#7E57FD" />
                          <stop offset="0.5" stopColor="white" />
                          <stop offset={1} stopColor="#B04BFD" />
                        </linearGradient>
                      </defs>
                    </svg>
                  </div>
                </div>
                <RevealAnimation delay={0.4}>
                  <div className="card-item dark:bg-background-6 max-sm:bg-background-4 absolute top-1/2 left-1/2 w-full max-w-[370px] -translate-x-1/2 -translate-y-1/2 space-y-3 rounded-[20px] p-6 lg:top-0 lg:left-0 lg:translate-x-0 lg:translate-y-0 lg:rounded-none lg:bg-none lg:p-0 lg:dark:bg-transparent">
                    <p className="text-tagline-2 text-primary-500">STEP 3</p>
                    <div className="space-y-2">
                      <h3 className="text-heading-6 lg:text-heading-5 max-w-[236px]">
                        Design &amp; development in sprints
                      </h3>
                      <p>
                        We begin by understanding your vision and goals through a detailed discovery call to align on
                        the project’s direction.
                      </p>
                    </div>
                  </div>
                </RevealAnimation>
              </div>
              <div className="relative">
                <div>
                  <div className="mx-auto flex size-[34px] items-center justify-center rounded-full bg-white drop-shadow-2xl dark:bg-black">
                    <figure className="size-7">
                      <Image src={step} alt="step" className="h-full w-full object-cover dark:hidden" />
                      <Image src={stepDark} alt="step" className="hidden h-full w-full object-cover dark:block" />
                    </figure>
                  </div>
                  <div className="bg-stroke-2 dark:bg-stroke-6 mx-auto h-[320px] w-1 lg:h-[380px]">
                    <svg
                      ref={stepLine4}
                      xmlns="http://www.w3.org/2000/svg"
                      className="step-line h-[0px] w-1"
                      viewBox="0 0 2 222"
                      fill="none">
                      <path
                        d="M1 1L0.99999 221"
                        stroke="url(#paint0_linear_1182_24963_step4)"
                        strokeWidth={2}
                        strokeLinecap="round"
                      />
                      <defs>
                        <linearGradient
                          id="paint0_linear_1182_24963_step4"
                          x1="0.5"
                          y1={1}
                          x2="0.49999"
                          y2={221}
                          gradientUnits="userSpaceOnUse">
                          <stop offset={0} stopColor="#64D9FD" />
                          <stop offset="0.25" stopColor="#7E57FD" />
                          <stop offset="0.5" stopColor="white" />
                          <stop offset={1} stopColor="#B04BFD" />
                        </linearGradient>
                      </defs>
                    </svg>
                  </div>
                </div>
                <RevealAnimation delay={0.5}>
                  <div className="card-item dark:bg-background-6 max-sm:bg-background-4 absolute top-1/2 right-1/2 w-full max-w-[370px] translate-x-1/2 -translate-y-1/2 space-y-3 rounded-[20px] p-6 lg:top-0 lg:right-0 lg:translate-x-0 lg:translate-y-0 lg:rounded-none lg:bg-none lg:p-0 lg:dark:bg-transparent">
                    <p className="text-tagline-2 text-primary-500">STEP 4</p>
                    <div className="space-y-2">
                      <h3 className="text-heading-6 lg:text-heading-5 max-w-[236px]">
                        Feedback, refinement &amp; final delivery
                      </h3>
                      <p>
                        We begin by understanding your vision and goals through a detailed discovery call to align on
                        the project’s direction.
                      </p>
                    </div>
                  </div>
                </RevealAnimation>
              </div>
              <div className="relative">
                <div>
                  <div className="mx-auto flex size-[34px] items-center justify-center rounded-full bg-white drop-shadow-2xl dark:bg-black">
                    <figure className="size-7">
                      <Image src={step} alt="step" className="h-full w-full object-cover dark:hidden" />
                      <Image src={stepDark} alt="step" className="hidden h-full w-full object-cover dark:block" />
                    </figure>
                  </div>
                </div>
                <RevealAnimation delay={0.6}>
                  <div className="card-item dark:bg-background-6 max-sm:bg-background-4 absolute top-16 left-1/2 w-full max-w-[370px] -translate-x-1/2 space-y-3 rounded-[20px] p-6 lg:top-0 lg:left-0 lg:translate-x-0 lg:translate-y-0 lg:rounded-none lg:bg-none lg:p-0 lg:dark:bg-transparent">
                    <p className="text-tagline-2 text-primary-500">STEP 5</p>
                    <div className="space-y-2">
                      <h3 className="text-heading-6 lg:text-heading-5 max-w-[203px]">
                        Post-launch support &amp; handoff
                      </h3>
                      <p>
                        We begin by understanding your vision and goals through a detailed discovery call to align on
                        the project’s direction.
                      </p>
                    </div>
                  </div>
                </RevealAnimation>
              </div>
            </div>
          </div>
        </RevealAnimation>
      </div>
    </section>
  );
};

export default ProcessStep;
