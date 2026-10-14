import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '../ui/accordion';

export const faqData = [
  {
    id: 1,
    question: 'How do I schedule a message?',
    answer:
      "Type your message, touch and hold send, then choose tonight, tomorrow morning or a date up to 30 days ahead. You can cancel it from the chat before it sends.",
  },
  {
    id: 2,
    question: 'What are starred messages?',
    answer:
      "A private list of messages you want to find again. Touch and hold any message and choose Star.",
  },
  {
    id: 3,
    question: 'Can I edit or delete a message?',
    answer:
      "You can edit a message within the time limit and delete it for everyone. Deleted photos are removed from our storage.",
  },
  {
    id: 4,
    question: 'Can I change how chats look?',
    answer:
      "Yes. Pick one of five themes, each with a soft India-inspired doodle, or use your own photo as the wallpaper.",
  },
  {
    id: 5,
    question: 'What is Calm mode?',
    answer:
      "Calm mode hides unread counts and the unread summary on your home screen. Messages still arrive as usual.",
  },
];

const ChangelogTab = () => {
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

export default ChangelogTab;
