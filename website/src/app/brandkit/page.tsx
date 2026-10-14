import Content from '@/components/brand-kit/Content';
import Hero from '@/components/brand-kit/Hero';
import { defaultMetadata } from '@/utils/generateMetaData';
import { Metadata } from 'next';

export const metadata: Metadata = {
  ...defaultMetadata,
  title: 'Brand kit | Samvad',
};

const page = () => {
  return (
    <main className="bg-background-3 dark:bg-background-7">
      <Hero />
      <Content />
    </main>
  );
};

export default page;
