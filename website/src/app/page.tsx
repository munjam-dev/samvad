import { CardGrid, Cta, PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata(
  'Samvad | A private messenger made in India',
  'Calm, fast messaging with schedule send, starred messages and chat themes that feel like home.',
  '/',
);

const Home = () => (
  <Shell>
    <PageHero
      badge="Made in India"
      title="Conversations that feel like home"
      lead="Samvad is a calm, private messenger. Chat one to one or in groups, schedule messages for later, star what matters and make every chat yours.">
      <Cta href="/download">Get Samvad</Cta>
      <Cta href="/features" secondary>
        See features
      </Cta>
    </PageHero>
    <CardGrid
      cards={[
        { title: 'Schedule messages', body: 'Write now, send later. Pick tonight, tomorrow morning or any time up to 30 days ahead.' },
        { title: 'Starred messages', body: 'Keep the address, the recipe or the good news in one place, private to you.' },
        { title: 'Chat themes', body: 'Ivory, Sage, Monsoon, Marigold and Night Garden, each with a soft India-inspired doodle.' },
        { title: 'Groups and replies', body: 'Reply to a message, react, edit within the time limit and delete for everyone.' },
        { title: 'No ads, no tracking', body: 'We do not run ads or analytics, and we never store your contact list.' },
        { title: 'Your data, your call', body: 'Delete your account and its data from Settings at any time.' },
      ]}
    />
  </Shell>
);

export default Home;
