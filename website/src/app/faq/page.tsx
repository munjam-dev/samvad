import Contact from '@/components/faq/Contact';
import FaqTab from '@/components/faq/FaqTab';
import CTA from '@/components/shared/cta/CTA';
import { defaultMetadata } from '@/utils/generateMetaData';
import { Metadata } from 'next';

export const metadata: Metadata = {
  ...defaultMetadata,
  title: 'FAQ | Samvad',
};

const FAQ = () => {
  return (
    <main className="bg-background-3 dark:bg-background-7">
      <FaqTab />
      <Contact />
      <CTA
        className="dark:bg-background-6 bg-white"
        badgeClass="!badge-cyan"
        badgeText="Samvad"
        ctaHeading="Ready to start talking?"
        description="If you have any questions, feel free to reach out to our team."
        btnClass="hover:btn-secondary dark:hover:btn-accent"
        ctaBtnText="Download"
      />
    </main>
  );
};

export default FAQ;
