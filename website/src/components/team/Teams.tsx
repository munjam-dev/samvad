import { ITeamMember } from '@/interface';
import getMarkDownData from '@/utils/getMarkDownData';
import Image from 'next/image';
import Link from 'next/link';
import RevealAnimation from '../animation/RevealAnimation';
import SocialLinkV3 from '../shared/SocialLinkV3';

const Teams = () => {
  const teams: ITeamMember[] = getMarkDownData<ITeamMember & { [key: string]: unknown }>(
    'src/data/team',
    false,
    'name',
  ).slice(0, 8);
  return (
    <section className="pt-14 pb-[50px] md:pt-16 md:pb-[70px] lg:pt-[88px] lg:pb-[85px] xl:pt-[100px] xl:pb-[100px]">
      <RevealAnimation delay={0.1}>
        <div className="main-container bg-background-2 dark:bg-background-8 rounded-2xl py-[100px]">
          <div>
            <div className="mx-auto mb-[70px] max-w-[620px] text-center md:w-full">
              <RevealAnimation delay={0.2}>
                <span className="badge badge-cyan mb-5">Our team</span>
              </RevealAnimation>
              <RevealAnimation delay={0.3}>
                <h2 className="mb-3">Our innovative, dynamic, and talented team</h2>
              </RevealAnimation>
              <RevealAnimation delay={0.4}>
                <p>
                  Our innovative, dynamic, and talented team is the driving force behind our success. Each member brings
                  a unique blend of expertise
                </p>
              </RevealAnimation>
            </div>
            {/* team members  */}
            <div className="mb-14 flex flex-wrap items-center justify-center gap-x-6 gap-y-18">
              {teams.map((member, index) => (
                <RevealAnimation key={member.name} delay={0.4 + index * 0.1}>
                  <div className="mx-auto h-[312px] w-[298px] space-y-[34px]">
                    {/* team member image */}
                    <figure className="mx-auto flex size-[156px] items-center justify-center overflow-hidden rounded-full bg-[#d5dbe3]">
                      <Link href={`/team/${member.slug}`}>
                        <Image
                          src={member.userImg}
                          className="size-full object-cover"
                          alt={`${member.name} profile img`}
                          loading="lazy"
                          width={200}
                          height={300}
                        />
                      </Link>
                    </figure>

                    {/* team member info */}
                    <div className="space-y-[27px]">
                      <div className="text-center">
                        <Link href={`/team/${member.slug}`}>
                          <h3 className="text-heading-5">{member.name}</h3>
                        </Link>
                        <p className="text-tagline-2">{member.role}</p>
                      </div>

                      {/* social links */}
                      <SocialLinkV3 social={member.social} />
                    </div>
                  </div>
                </RevealAnimation>
              ))}
            </div>
          </div>
        </div>
      </RevealAnimation>
    </section>
  );
};

Teams.displayName = 'Teams';
export default Teams;
