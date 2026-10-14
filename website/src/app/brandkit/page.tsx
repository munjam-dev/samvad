import { PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('Brand kit | Samvad', 'Samvad name, colours and usage.', '/brandkit');

const swatches = [
  ['Samvad green', '#167B52'],
  ['Deep green', '#0B4530'],
  ['Ivory', '#F8F5EC'],
];

const Brandkit = () => (
  <Shell>
    <PageHero badge="Brand kit" title="Using the Samvad brand" lead="Logo files will be added here soon. Please do not alter the mark or imply endorsement." />
    <section className="pb-24">
      <div className="main-container">
        <ul className="mx-auto grid max-w-[780px] grid-cols-1 gap-5 sm:grid-cols-3">
          {swatches.map(([n, c]) => (
            <li key={n} className="border-stroke-4 dark:border-stroke-6 overflow-hidden rounded-[20px] border">
              <div style={{ background: c }} className="h-24" />
              <p className="text-tagline-2 p-4">
                {n}
                <br />
                <span className="text-secondary/60 dark:text-accent/60">{c}</span>
              </p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  </Shell>
);

export default Brandkit;
