import Feature from '@/components/features/Feature';
import Features from '@/components/features/Features';
import WhyChooseUs from '@/components/features/WhyChooseUs';
import CTA from '@/components/shared/cta/CTA';
import { defaultMetadata } from '@/utils/generateMetaData';
import { Metadata } from 'next';

export const metadata: Metadata = {
  ...defaultMetadata,
  title: 'Features | Samvad',
};

const page = () => {
  return (
    <main className="bg-background-3 dark:bg-background-7">
      <Features />
      <Feature />
      <WhyChooseUs />
      <CTA
        className="dark:bg-background-6 bg-white"
        badgeClass="hidden"
        ctaHeading="Get Samvad and start"
        spanText="talking"
        description="Samvad for Android is coming soon to Google Play."
        btnClass="hover:btn-secondary dark:hover:btn-accent"
        ctaBtnText="Download"
      />
    </main>
  );
};

export default page;
