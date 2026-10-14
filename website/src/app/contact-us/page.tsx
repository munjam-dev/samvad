import ContactInfo from '@/components/contact-page/ContactInfo';
import CTA from '@/components/shared/cta/CTA';
import { defaultMetadata } from '@/utils/generateMetaData';
import { Metadata } from 'next';

export const metadata: Metadata = {
  ...defaultMetadata,
  title: 'Contact us | Samvad',
};

const ContactUs = () => {
  return (
    <main className="bg-background-3 dark:bg-background-7">
      <ContactInfo />
      <CTA
        className="dark:bg-background-5 bg-white"
        badgeClass="badge-yellow-v2"
        badgeText="Samvad"
        ctaBtnText="Contact us"
        ctaHeading="Get in touch"
        description="Questions, feedback or ideas? We would love to hear from you."
      />
    </main>
  );
};

export default ContactUs;
