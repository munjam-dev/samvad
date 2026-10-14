import Link from 'next/link';
import { CardGrid, PageHero, Prose, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Security | Samvad', 'How Samvad protects your account and data, and what it does not yet do.', '/security');

const Security = () => (
  <Shell>
    <PageHero badge="Security" title="Honest about how we protect you" lead="What is in place today, and what is not." />
    <CardGrid
      cards={[
        { title: 'Phone verification', body: 'You sign in with a one-time code sent to your number.' },
        { title: 'Encrypted in transit', body: 'All traffic uses TLS. Data is stored in Google Cloud regions in Mumbai and Singapore.' },
        { title: 'App integrity checks', body: 'Firebase App Check helps keep other apps from using our backend.' },
        { title: 'Minimal data', body: 'No ads, no analytics, and contact numbers are never stored on our servers.' },
      ]}
    />
    <Prose>
      <blockquote>
        <p>
          <strong>In short:</strong> messages are not yet end-to-end encrypted. We say so plainly so you can decide what to share.
        </p>
      </blockquote>
      <p>
        Read the full detail in our <Link href="/legal/security">security policy</Link>, including how to report a vulnerability.
      </p>
    </Prose>
  </Shell>
);

export default Security;
