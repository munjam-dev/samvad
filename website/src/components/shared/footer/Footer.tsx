import RevealAnimation from '@/components/animation/RevealAnimation';
import { footerLinks } from '@/data/footer-data';
import { cn } from '@/utils/cn';
import gradientImg from '@public/images/ns-img-532.png';
import Image from 'next/image';
import Link from 'next/link';
import ThemeToggle from '../ThemeToggle';
import FooterDivider from './FooterDivider';

const Footer = ({ className }: { className?: string }) => {
  return (
    <footer className={cn('bg-secondary dark:bg-background-8 relative z-0 overflow-hidden', className)}>
      <RevealAnimation delay={0.3} offset={50} direction="up">
        <figure className="pointer-events-none absolute -top-[1320px] left-1/2 -z-1 size-[1635px] -translate-x-1/2 select-none">
          <Image src={gradientImg} alt="footer-four-gradient" className="size-full object-cover" />
        </figure>
      </RevealAnimation>
      <div className="main-container px-5">
        <div className="grid grid-cols-12 justify-between gap-x-0 gap-y-16 pt-16 pb-12 xl:pt-[90px]">
          <RevealAnimation delay={0.1}>
            <div className="col-span-12 xl:col-span-4">
              <div className="max-w-[306px]">
                <p className="text-heading-5 text-primary-50 flex items-center gap-2 font-semibold">
                  <Image src="/samvad-mark-256.png" alt="" width={36} height={36} className="size-9" />
                  Samvad
                </p>
                <p className="text-accent/60 text-tagline-1 mt-4 font-normal">
                  A private messenger made in India. Calm, fast and honest about your data.
                </p>
              </div>
            </div>
          </RevealAnimation>
          <div className="col-span-12 grid grid-cols-12 gap-x-0 gap-y-8 xl:col-span-8">
            {footerLinks.map(({ title, links }, index) => (
              <div className="col-span-12 md:col-span-4" key={title}>
                <RevealAnimation delay={0.2 + index * 0.1}>
                  <div className="space-y-8">
                    <p className="sm:text-heading-6 text-tagline-1 text-primary-50 font-normal">{title}</p>
                    <ul className="space-y-5">
                      {links.map(({ label, href }) => (
                        <li key={label}>
                          <Link href={href} className="footer-link">
                            {label}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </div>
                </RevealAnimation>
              </div>
            ))}
          </div>
        </div>
        <div className="relative pt-[26px] pb-[42px] text-center">
          <FooterDivider className="bg-accent/10 dark:bg-stroke-6" />
          <RevealAnimation delay={0.7} offset={10} start="top 105%">
            <p className="text-tagline-1 text-primary-50 font-normal">
              © 2026 Samvad. Made in India.
            </p>
          </RevealAnimation>
        </div>
      </div>
      <ThemeToggle />
    </footer>
  );
};

Footer.displayName = 'Footer';
export default Footer;
