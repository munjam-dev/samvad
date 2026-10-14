import AnimatedCharts from '@/components/home-motion/AnimatedCharts';
import BentoGrid from '@/components/home-motion/BentoGrid';
import FeatureExplorer from '@/components/home-motion/FeatureExplorer';
import FeatureShowcase from '@/components/home-motion/FeatureShowcase';
import FinalCTA from '@/components/home-motion/FinalCTA';
import { BuiltOnMarquee, LanguageMarquee } from '@/components/home-motion/Marquees';
import PrivacySection from '@/components/home-motion/PrivacySection';
import ScrollStory from '@/components/home-motion/ScrollStory';
import TestimonialCarousel from '@/components/home-motion/TestimonialCarousel';
import Hero from '@/components/home/Hero';
import Process from '@/components/home/Process';
import { defaultMetadata } from '@/utils/generateMetaData';
import { Metadata } from 'next';

export const metadata: Metadata = {
  ...defaultMetadata,
  title: 'Samvad | A private messenger made in India',
};

const page = () => {
  return (
    <main className="bg-background-2 dark:bg-background-5">
      <Hero />
      <FeatureShowcase />
      <BentoGrid />
      <ScrollStory />
      <Process />
      <AnimatedCharts />
      <FeatureExplorer />
      <LanguageMarquee />
      <BuiltOnMarquee />
      <PrivacySection />
      <TestimonialCarousel />
      <FinalCTA />
    </main>
  );
};

export default page;
