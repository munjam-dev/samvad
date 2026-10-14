import { notFound } from 'next/navigation';
import type { Metadata } from 'next';
import LegalDocumentView from '@/components/legal/LegalDocumentView';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';
import { allLegalDocs, getLegalDoc, legalSlugs } from '@/utils/legal';

export const dynamicParams = false;

export function generateStaticParams() {
  return legalSlugs().map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  if (!legalSlugs().includes(slug)) return {};
  const doc = getLegalDoc(slug);
  return buildMetadata(`${doc.title} | Samvad`, doc.summary, `/legal/${slug}`);
}

const LegalDocumentPage = async ({ params }: { params: Promise<{ slug: string }> }) => {
  const { slug } = await params;
  if (!legalSlugs().includes(slug)) notFound();
  const doc = getLegalDoc(slug);
  const all = allLegalDocs().filter((d) => d.slug !== slug);
  // Same group first, then the core documents, three in total.
  const related = [...all.filter((d) => d.group === doc.group), ...all.filter((d) => d.group !== doc.group)].slice(0, 3);
  return (
    <main className="dark:bg-background-7 bg-white">
      <LegalDocumentView doc={doc} related={related} />
    </main>
  );
};

export default LegalDocumentPage;
