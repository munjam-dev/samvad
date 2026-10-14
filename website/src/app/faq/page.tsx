import { PageHero, Shell } from '@/components/site/Page';
import { generateMetadata as buildMetadata } from '@/utils/generateMetaData';

export const metadata = buildMetadata('FAQ | Samvad', 'Answers to common questions about Samvad.', '/faq');

const faqs: [string, string][] = [
  ['Is Samvad free?', 'Yes. There are no ads and no subscriptions.'],
  ['Are my messages end-to-end encrypted?', 'Not yet. They are encrypted in transit and protected by access rules. See the Security page.'],
  ['Do you store my contacts?', 'No. Contact numbers are matched on your phone and are not stored on our servers.'],
  ['How do I schedule a message?', 'Type your message, touch and hold send, then pick a time up to 30 days ahead.'],
  ['How do I delete my account?', 'Settings, then Delete account. You can also follow the steps on the Account deletion page.'],
  ['What is the minimum age?', 'You must be 18 or older to use Samvad.'],
  ['How do I report a problem or a person?', 'Use Report in the chat menu, or write to our Grievance Officer from the Legal centre.'],
];

const Faq = () => (
  <Shell>
    <PageHero badge="FAQ" title="Questions, answered" lead="Short answers to the things people ask most." />
    <section className="pb-24">
      <div className="main-container">
        <div className="mx-auto max-w-[780px] space-y-3">
          {faqs.map(([q, a]) => (
            <details key={q} className="bg-background-3 dark:bg-background-6 rounded-[20px] p-5">
              <summary className="text-tagline-1 cursor-pointer font-medium">{q}</summary>
              <p className="text-tagline-2 text-secondary/70 dark:text-accent/70 mt-3">{a}</p>
            </details>
          ))}
        </div>
      </div>
    </section>
  </Shell>
);

export default Faq;
