//Glossary heading section

import RevealAnimation from '../animation/RevealAnimation';

const Heading = () => {
  return (
    <section className="pt-[150px] pb-14 md:pt-[192px] md:pb-28">
      <div className="main-container">
        <div className="space-y-4 text-center lg:text-left">
          <RevealAnimation delay={0.1}>
            <h1 className="md:text-heading-2 text-heading-3 font-normal">Compliance glossary</h1>
          </RevealAnimation>
          <RevealAnimation delay={0.2}>
            <p>Glossary of productivity, time management, & work-life balance terms.</p>
          </RevealAnimation>
        </div>
      </div>
    </section>
  );
};

Heading.displayName = 'Heading';
export default Heading;
