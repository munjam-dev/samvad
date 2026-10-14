import { MobileMenuGroup } from '@/components/shared/mobile-menu/MobileMenu';

export const primaryNav = [
  { label: 'Features', href: '/features' },
  { label: 'Security', href: '/security' },
  { label: 'Download', href: '/download' },
  { label: 'FAQ', href: '/faq' },
  { label: 'Support', href: '/support' },
  { label: 'Legal', href: '/legal' },
];

export const mobileMenuData: MobileMenuGroup[] = [
  {
    id: 'samvad',
    title: 'Samvad',
    submenu: [
      { id: 'features', label: 'Features', href: '/features' },
      { id: 'security', label: 'Security', href: '/security' },
      { id: 'download', label: 'Download', href: '/download' },
      { id: 'changelog', label: 'Changelog', href: '/changelog' },
      { id: 'brandkit', label: 'Brand kit', href: '/brandkit' },
    ],
  },
  {
    id: 'help',
    title: 'Help',
    submenu: [
      { id: 'faq', label: 'FAQ', href: '/faq' },
      { id: 'support', label: 'Support', href: '/support' },
      { id: 'contact', label: 'Contact us', href: '/contact-us' },
    ],
  },
  {
    id: 'legal',
    title: 'Legal',
    submenu: [
      { id: 'legal', label: 'Legal centre', href: '/legal' },
      { id: 'privacy', label: 'Privacy policy', href: '/legal/privacy-policy' },
      { id: 'terms', label: 'Terms of service', href: '/legal/terms-of-service' },
      { id: 'community', label: 'Community guidelines', href: '/legal/community-guidelines' },
      { id: 'deletion', label: 'Account deletion', href: '/legal/account-deletion' },
    ],
  },
];
