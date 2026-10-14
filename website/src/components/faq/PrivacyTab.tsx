import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';

const faqData = [
  {
    id: 1,
    question: 'Are my messages end-to-end encrypted?',
    answer:
      "Not yet. They are encrypted in transit and protected by access rules, but our systems can technically read them. We will announce clearly when this changes.",
  },
  {
    id: 2,
    question: 'Do you store my contacts?',
    answer:
      "No. Contact numbers are matched on your phone and are never stored on our servers.",
  },
  {
    id: 3,
    question: 'Do you show ads or track me?',
    answer:
      "No. Samvad has no ads and no analytics.",
  },
  {
    id: 4,
    question: 'How do I report someone?',
    answer:
      "Open the chat menu and choose Report. A report includes the last five messages and is kept for 90 days.",
  },
  {
    id: 5,
    question: 'Can I hide my last seen?',
    answer:
      "Yes. In Settings you can hide your last seen and turn read receipts off.",
  },
];

const PrivacyTab = () => {
  return (
    <Accordion
      className="mx-auto w-full max-w-[850px] space-y-4"
      defaultValue="1"
      enableScrollAnimation={true}
      animationDelay={0.1}>
      {faqData.map((item) => (
        <AccordionItem
          className="dark:bg-background-7 rounded-[20px] bg-white px-6 sm:px-8"
          key={item.id}
          value={item.id.toString()}>
          <AccordionTrigger
            titleClassName="flex-1 text-left sm:text-heading-6 text-tagline-1 font-normal text-secondary dark:text-accent"
            className="flex w-full cursor-pointer items-center justify-between pt-5 pb-5 sm:pt-8 sm:pb-8"
            value={item.id.toString()}
            iconType="arrow">
            {item.question}
          </AccordionTrigger>
          <AccordionContent value={item.id.toString()}>{item.answer}</AccordionContent>
        </AccordionItem>
      ))}
    </Accordion>
  );
};

export default PrivacyTab;
