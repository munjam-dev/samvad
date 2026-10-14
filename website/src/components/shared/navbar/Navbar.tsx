'use client';

import RevealAnimation from '@/components/animation/RevealAnimation';
import { MobileMenuProvider } from '@/context/MobileMenuContext';
import { mobileMenuData, primaryNav } from '@/data/navbar-data';
import { useNavbarScroll } from '@/hooks/useScrollHeader';
import { cn } from '@/utils/cn';
import Image from 'next/image';
import Link from 'next/link';
import MobileMenu from '../mobile-menu/MobileMenu';
import MobileMenuButton from '../mobile-menu/MobileMenuButton';

const Navbar = () => {
  const { isScrolled } = useNavbarScroll(150);

  return (
    <MobileMenuProvider>
      <header
        className={cn(
          'lp:!max-w-[1290px] fixed top-5 left-1/2 z-50 mx-auto w-full max-w-[350px] -translate-x-1/2 transition-all duration-500 min-[425px]:max-w-[375px] min-[500px]:max-w-[450px] sm:max-w-[540px] md:max-w-[720px] lg:max-w-[960px] xl:max-w-[1140px]',
          isScrolled && 'top-2',
        )}>
        <RevealAnimation direction="up" offset={100} delay={0.1} instant>
          <div className="border-stroke-2 dark:border-stroke-6 bg-accent dark:bg-background-9 mx-auto flex items-center justify-between rounded-full border px-4 py-2.5 xl:py-2">
            <Link href="/" className="text-heading-6 text-secondary dark:text-accent inline-flex min-h-11 items-center font-semibold">
              <Image src="/samvad-mark-256.png" alt="" width={32} height={32} className="mr-2 size-8" priority />
              Samvad
            </Link>
            <nav aria-label="Main" className="hidden xl:block">
              <ul className="flex items-center">
                {primaryNav.map(({ label, href }) => (
                  <li key={href}>
                    <Link
                      href={href}
                      className="hover:border-stroke-2 dark:hover:border-stroke-7 text-tagline-1 text-secondary/60 hover:text-secondary dark:text-accent/60 dark:hover:text-accent flex items-center rounded-full border border-transparent px-4 py-2 font-normal transition-all duration-200">
                      {label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
            <div className="hidden xl:flex">
              <Link href="/download" className="btn btn-md btn-primary hover:btn-white-dark dark:hover:btn-white">
                <span>Download</span>
              </Link>
            </div>
            <MobileMenuButton />
          </div>
        </RevealAnimation>
      </header>
      <MobileMenu menuData={mobileMenuData} />
    </MobileMenuProvider>
  );
};

Navbar.displayName = 'Navbar';
export default Navbar;
