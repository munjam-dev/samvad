import { PageHero, Prose, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Changelog | Samvad', 'What is new in Samvad.', '/changelog');

const Changelog = () => (
  <Shell>
    <PageHero badge="Changelog" title="What is new" lead="Recent changes to Samvad." />
    <Prose>
      <h2>Schedule send and starred messages</h2>
      <p>Write a message now and send it later, and star messages to find them again.</p>
      <h2>Chat themes</h2>
      <p>Five themes with a soft India-inspired doodle, in light and dark.</p>
    </Prose>
  </Shell>
);

export default Changelog;
