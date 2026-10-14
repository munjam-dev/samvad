import { FooterData } from '@/interface';

export const footerLinks: FooterData[] = [
  {
    title: 'Samvad',
    links: [
      { label: 'Features', href: '/features' },
      { label: 'Security', href: '/security' },
      { label: 'Download', href: '/download' },
      { label: 'Changelog', href: '/changelog' },
      { label: 'Brand kit', href: '/brandkit' },
    ],
  },
  {
    title: 'Help',
    links: [
      { label: 'FAQ', href: '/faq' },
      { label: 'Support', href: '/support' },
      { label: 'Contact us', href: '/contact-us' },
      { label: 'Grievance Officer', href: '/legal/grievance-redressal' },
    ],
  },
  {
    title: 'Legal',
    links: [
      { label: 'Privacy policy', href: '/legal/privacy-policy' },
      { label: 'Terms of service', href: '/legal/terms-of-service' },
      { label: 'Community guidelines', href: '/legal/community-guidelines' },
      { label: 'Account deletion', href: '/legal/account-deletion' },
      { label: 'Child safety standards', href: '/legal/child-safety-standards' },
      { label: 'All policies', href: '/legal' },
    ],
  },
];
