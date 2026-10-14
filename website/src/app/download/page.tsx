import { PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Download | Samvad', 'Samvad for Android is coming soon to Google Play.', '/download');

const Download = () => (
  <Shell>
    <PageHero badge="Android" title="Coming soon to Google Play" lead="Samvad for Android is almost ready. Check back here for the Play Store link." />
    <div className="pb-24 text-center">
      <span className="badge badge-green">Requires Android 8.0 or newer</span>
    </div>
  </Shell>
);

export default Download;
