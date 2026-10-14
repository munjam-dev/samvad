import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Static site for Firebase Hosting (samvad-messenger.web.app).
  output: 'export',
  turbopack: {
    resolveAlias: {
      '@': './src',
      '@public': './public',
    },
  },
  images: {
    unoptimized: true,
    qualities: [25, 50, 75, 100],
  },
};

export default nextConfig;
