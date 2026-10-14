import { CardGrid, Cta, PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Features | Samvad', 'Everything in Samvad: messaging, groups, scheduled and starred messages, themes and safety tools.', '/features');

const Features = () => (
  <Shell>
    <PageHero badge="Features" title="Built for everyday conversations" lead="The things you use daily, done carefully." />
    <CardGrid
      heading="Messaging"
      cards={[
        { title: 'One to one and groups', body: 'Text, photos and replies, with delivery and read ticks.' },
        { title: 'Edit and delete', body: 'Fix a typo within the time limit, or delete a message for everyone.' },
        { title: 'Reactions', body: 'Answer with an emoji without adding another message.' },
      ]}
    />
    <CardGrid
      heading="Make it yours"
      cards={[
        { title: 'Schedule send', body: 'Quick choices for tonight and tomorrow morning, or pick a date within 30 days.' },
        { title: 'Starred messages', body: 'Save any message and find it again in one list.' },
        { title: 'Themes and wallpapers', body: 'Five themes with an India-inspired doodle, light and dark, plus your own photo wallpaper.' },
      ]}
    />
    <CardGrid
      heading="Safety"
      cards={[
        { title: 'Block and report', body: 'Reports include the last five messages and are kept for 90 days.' },
        { title: 'Two-step verification', body: 'An extra PIN on top of your phone number.' },
        { title: 'Delete your account', body: 'Remove your account and data from Settings or from the web.' },
      ]}
    />
    <div className="flex justify-center pb-20">
      <Cta href="/download">Get Samvad</Cta>
    </div>
  </Shell>
);

export default Features;
