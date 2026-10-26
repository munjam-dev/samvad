import { CheckIcon } from '@/icons';
import RevealAnimation from '../animation/RevealAnimation';

interface ListItem {
  text: string;
}

interface ContentSection {
  id: string;
  title: string;
  description: string;
  listItems?: ListItem[];
  additionalText?: string;
}

const contentSections: ContentSection[] = [
  {
    id: '1',
    title: 'Your trust comes first',
    description:
      'Protecting your information is a foundation of Samvad, not an afterthought. We collect as little as we can, keep it only as long as we need to, and tell you plainly what is and is not protected today.',
  },
  {
    id: '2',
    title: 'What protects you today',
    description: 'These protections are in place now:',
    listItems: [
      { text: 'Phone verification with a one-time code, plus optional two-step verification with a PIN.' },
      { text: 'All traffic is encrypted in transit with TLS, and data is stored in Google Cloud regions in Mumbai and Singapore.' },
      { text: 'Firebase App Check helps keep other apps from using our backend, and access rules limit every account to its own data.' },
      { text: 'An optional app lock and secure screen setting keep your chats private on your device.' },
    ],
    additionalText: 'Contact numbers are matched on your phone and are never stored on our servers. We run no ads and no analytics.',
  },
  {
    id: '3',
    title: 'What is not protected yet',
    description:
      'One-to-one chats can be end-to-end encrypted when both people have keys. Existing chats, group chats, polls, Moments and calls are not end-to-end encrypted yet: they are encrypted in transit and protected by access rules, but our systems can technically read them. The app says which chats are encrypted, and we will update our policies if this changes.',
  },
  {
    id: '4',
    title: 'Report a vulnerability',
    description:
      'If you find a security problem, please tell us before you tell anyone else. Our security policy explains how to report it, what we promise in return and how we handle reports. Find it in the Legal centre under Security.',
  },
  {
    id: '5',
    title: 'Your controls',
    description:
      'You can hide your last seen, turn read receipts off, lock the app, block and report people, and delete your account and its data from Settings at any time.',
  },
];

const Content = () => {
  return (
    <section className="xl:pb-28 lg:pb-20 pb-16">
      <div className="main-container">
        <div className="flex items-start lg:flex-row flex-col lg:gap-x-5 gap-y-5 max-w-[1100px] mx-auto w-full">
          <RevealAnimation delay={0.1}>
            <div className="space-y-2 max-w-[350px] w-full sticky top-28">
              <p className="text-secondary dark:text-accent">Your trust comes first</p>
              <p>Last reviewed 14 October 2026</p>
            </div>
          </RevealAnimation>
          <div className="space-y-6">
            {contentSections.map((section, index) => {
              const delay = (index + 2) * 0.1;

              return (
                <RevealAnimation key={section.id} delay={delay}>
                  <div className={section.listItems ? '' : 'space-y-2'}>
                    <h2 className="text-heading-5 font-normal mb-2">{section.title}</h2>
                    <p>{section.description}</p>
                    {section.listItems && (
                      <>
                        <ul className="mt-4 mb-6 space-y-3">
                          {section.listItems.map((item) => (
                            <li key={item.text} className="flex items-center gap-x-2">
                              <span className="size-5 rounded-full bg-secondary dark:bg-accent shrink-0 flex items-center justify-center">
                                <CheckIcon className="fill-accent dark:fill-secondary" />
                              </span>
                              <p className="text-secondary/80 dark:text-accent/80">{item.text}</p>
                            </li>
                          ))}
                        </ul>
                        {section.additionalText && <p>{section.additionalText}</p>}
                      </>
                    )}
                  </div>
                </RevealAnimation>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
};

Content.displayName = 'Content';
export default Content;
