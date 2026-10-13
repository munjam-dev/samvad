import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['rules-tests/**/*.test.ts', 'functions/test/**/*.test.ts'],
    fileParallelism: false,
    testTimeout: 30000,
    hookTimeout: 30000,
  },
});
