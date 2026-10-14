import NumberAnimation from '../animation/NumberAnimation';
import RevealAnimation from '../animation/RevealAnimation';

const Experience = () => {
  return (
    <section className="pt-14 pb-14 md:pt-16 md:pb-16 lg:pt-[88px] lg:pb-[88px] xl:pt-[200px] xl:pb-[100px]">
      <div className="main-container">
        <div className="flex flex-col lg:flex-row xl:gap-[100px]">
          <div className="space-y-5 text-center max-lg:mx-auto max-lg:mb-[70px] md:max-w-[650px] lg:max-w-[442px] lg:text-left">
            <RevealAnimation delay={0.1}>
              <span className="badge badge-cyan">Number</span>
            </RevealAnimation>
            <div className="space-y-3">
              <RevealAnimation delay={0.2}>
                <h2>Over a decade of experience in this.</h2>
              </RevealAnimation>
              <RevealAnimation delay={0.3}>
                <p>
                  This extensive experience not only reflects our commitment to excellence but also equips us with the
                  insight and skills necessary to meet.
                </p>
              </RevealAnimation>
            </div>
          </div>
          <div className="grid grow-1 grid-cols-1 gap-8 md:grid-cols-2">
            <RevealAnimation delay={0.2}>
              <div className="bg-background-2 dark:bg-background-8 flex flex-col justify-between rounded-xl p-8">
                <div className="flex items-center justify-between">
                  <h5 className="max-w-[200px]">Relied upon by businesses</h5>
                  <div className="shrink-0">
                    <span className="ns-shape-47 text-secondary dark:text-accent text-[52px]" />
                  </div>
                </div>
                <div className="space-y-1">
                  <h3 className="flex items-center gap-1">
                    <NumberAnimation number={83} speed={1000} interval={180} rooms={2} heightSpaceRatio={2.5}>
                      83
                    </NumberAnimation>
                    %
                  </h3>
                  <p>Avg. Completed</p>
                </div>
              </div>
            </RevealAnimation>
            <RevealAnimation delay={0.3}>
              <div className="bg-background-2 dark:bg-background-8 flex flex-col justify-between rounded-xl p-8">
                <div className="flex items-center justify-between">
                  <h5 className="max-w-[200px]">The team at NexSaaS bank</h5>
                  <div className="shrink-0">
                    <span className="ns-shape-57 text-secondary dark:text-accent text-[52px]" />
                  </div>
                </div>
                <div className="space-y-1">
                  <h3 className="flex items-center gap-1">
                    <NumberAnimation number={56} speed={1000} interval={180} rooms={2} heightSpaceRatio={2.2}>
                      56
                    </NumberAnimation>
                    %
                  </h3>
                  <p>Avg. Completed</p>
                </div>
              </div>
            </RevealAnimation>
          </div>
        </div>
      </div>
    </section>
  );
};

Experience.displayName = 'Experience';
export default Experience;
