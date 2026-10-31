import RevealAnimation from '../animation/RevealAnimation';

const Hero = () => {
  return (
    <section className="xl:pt-[170px] pt-[140px] xl:pb-28 lg:pb-20 pb-16">
      <div className="main-container">
        <RevealAnimation delay={0.1} instant>
          <h1 className="text-center font-normal">Download Samvad</h1>
        </RevealAnimation>
        <RevealAnimation delay={0.2} instant>
          <p className="text-tagline-1 mt-4 text-center">Private, fast messaging built for India.</p>
        </RevealAnimation>
      </div>
    </section>
  );
};
Hero.displayName = 'Hero';
export default Hero;
