import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';

const faqData = [
  {
    id: 1,
    question: 'How do I delete my account?',
    answer:
      "Settings, then Delete account. This removes your account and data. You can also follow the steps on the Account deletion page in the Legal centre.",
  },
  {
    id: 2,
    question: 'How do I turn on two-step verification?',
    answer:
      "Settings, then Two-step verification. You set a PIN that is asked for when you sign in on a new phone.",
  },
  {
    id: 3,
    question: 'What if I lose my phone?',
    answer:
      "Install Samvad on your new phone and verify your number. If you use two-step verification you will also need your PIN.",
  },
  {
    id: 4,
    question: 'Who do I contact for a complaint?',
    answer:
      "Our Grievance Officer. Details are in the Legal centre, and complaints are acknowledged within 24 hours.",
  },
];

const TermsConditionsTab = () => {
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

export default TermsConditionsTab;
