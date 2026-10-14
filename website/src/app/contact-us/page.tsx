import { LEGAL_DETAILS } from '@/config/legal';
import { PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Contact us | Samvad', 'How to reach the Samvad team.', '/contact-us');

const ContactUs = () => (
  <Shell>
    <PageHero badge="Contact" title="Get in touch" lead="For help, feedback or press, write to us. For complaints, see the Grievance Officer in the Legal centre." />
    <section className="pb-24 text-center">
      <a className="text-primary-500 dark:text-primary-300 text-tagline-1 underline underline-offset-4" href={`mailto:${LEGAL_DETAILS.CONTACT_EMAIL}`}>
        {LEGAL_DETAILS.CONTACT_EMAIL}
      </a>
    </section>
  </Shell>
);

export default ContactUs;
