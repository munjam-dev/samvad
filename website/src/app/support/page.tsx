import { LEGAL_DETAILS } from '@/config/legal';
import { PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Support | Samvad', 'Get help with Samvad.', '/support');

const Support = () => (
  <Shell>
    <PageHero badge="Support" title="We are here to help" lead="Check the FAQ first. If it does not answer your question, write to us." />
    <section className="pb-24 text-center">
      <a className="text-primary-500 dark:text-primary-300 text-tagline-1 underline underline-offset-4" href={`mailto:${LEGAL_DETAILS.CONTACT_EMAIL}`}>
        {LEGAL_DETAILS.CONTACT_EMAIL}
      </a>
    </section>
  </Shell>
);

export default Support;
