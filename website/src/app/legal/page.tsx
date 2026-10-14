import Link from 'next/link';
import RevealAnimation from '@/components/animation/RevealAnimation';
import { LEGAL_DETAILS, LEGAL_GROUPS, type LegalGroup } from '@/config/legal';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';
import { allLegalDocs, formatLegalDate } from '@/utils/legal';

export const metadata = buildMetadata(
  'Legal centre | Samvad',
  "Samvad's privacy policy, terms of service, community guidelines, account deletion, child safety standards and other policies.",
  '/legal',
);

/** Pattern: index / hub. Every policy, grouped, with who runs Samvad and how to reach the Grievance Officer. */
const LegalCentre = () => {
  const docs = allLegalDocs();
  const groups = Object.keys(LEGAL_GROUPS) as LegalGroup[];
  return (
    <main className="dark:bg-background-7 bg-white">
      <section className="pt-32 pb-12 sm:pt-36 md:pt-42 xl:pt-[170px]">
        <div className="main-container">
          <div className="mx-auto max-w-[884px] space-y-4 text-center">
            <RevealAnimation delay={0.1} instant>
              <span className="badge badge-green">Legal centre</span>
            </RevealAnimation>
            <RevealAnimation delay={0.15} instant>
              <h1 className="font-normal">Clear policies for real conversations</h1>
            </RevealAnimation>
            <RevealAnimation delay={0.2} instant>
              <p className="text-tagline-1 text-secondary/70 dark:text-accent/70 mx-auto max-w-[640px]">
                How Samvad handles your information, the rules everyone agrees to, and how to reach us. Each document
                starts with a short summary in plain language.
              </p>
            </RevealAnimation>
          </div>
        </div>
      </section>

      <section className="pb-20 md:pb-28">
        <div className="main-container">
          <div className="mx-auto max-w-[1209px] space-y-14">
            {groups.map((g, gi) => {
              const inGroup = docs.filter((d) => d.group === g);
              if (inGroup.length === 0) return null;
              return (
                <div key={g} className="space-y-5">
                  <RevealAnimation delay={0.1 + gi * 0.05}>
                    <div>
                      <h2 className="text-heading-4">{LEGAL_GROUPS[g].title}</h2>
                      <p className="text-tagline-1 text-secondary/60 dark:text-accent/60">{LEGAL_GROUPS[g].description}</p>
                    </div>
                  </RevealAnimation>
                  <ul className="grid grid-cols-1 gap-5 md:grid-cols-2 lg:grid-cols-3">
                    {inGroup.map((d) => (
                      <li key={d.slug}>
                        <Link
                          href={`/legal/${d.slug}`}
                          className="bg-background-3 dark:bg-background-6 hover:border-primary-300 group flex h-full flex-col justify-between gap-6 rounded-[20px] border border-transparent p-7 transition-colors duration-300">
                          <span className="space-y-2">
                            <span className="text-heading-6 text-secondary dark:text-accent block">{d.title}</span>
                            <span className="text-tagline-2 text-secondary/70 dark:text-accent/70 block">{d.summary}</span>
                          </span>
                          <span className="text-tagline-3 text-secondary/60 dark:text-accent/60 flex items-center justify-between">
                            <span>Effective {formatLegalDate(d.updated)}</span>
                            <span aria-hidden="true" className="text-primary-500 dark:text-primary-300 transition-transform duration-300 group-hover:translate-x-1">
                              →
                            </span>
                          </span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      <section className="bg-background-3 dark:bg-background-6 py-16 md:py-20">
        <div className="main-container">
          <div className="mx-auto grid max-w-[1209px] gap-6 md:grid-cols-2">
            <div className="dark:bg-background-7 space-y-2 rounded-[20px] bg-white p-8">
              <h2 className="text-heading-5">Who runs Samvad</h2>
              <p className="text-tagline-1 text-secondary/70 dark:text-accent/70">
                {LEGAL_DETAILS.OPERATOR}
                <br />
                {LEGAL_DETAILS.ADDRESS}
              </p>
              <p className="text-tagline-1">
                <a className="text-primary-500 dark:text-primary-300 underline underline-offset-4" href={`mailto:${LEGAL_DETAILS.CONTACT_EMAIL}`}>
                  {LEGAL_DETAILS.CONTACT_EMAIL}
                </a>
              </p>
            </div>
            <div className="dark:bg-background-7 space-y-2 rounded-[20px] bg-white p-8">
              <h2 className="text-heading-5">Grievance Officer</h2>
              <p className="text-tagline-1 text-secondary/70 dark:text-accent/70">
                {LEGAL_DETAILS.GRIEVANCE_NAME}. Complaints are acknowledged within 24 hours and resolved within 15 days.
              </p>
              <p className="text-tagline-1">
                <a className="text-primary-500 dark:text-primary-300 underline underline-offset-4" href={`mailto:${LEGAL_DETAILS.GRIEVANCE_EMAIL}`}>
                  {LEGAL_DETAILS.GRIEVANCE_EMAIL}
                </a>
              </p>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
};

export default LegalCentre;
