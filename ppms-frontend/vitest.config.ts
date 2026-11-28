import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    server: {
      fs: { allow: ['.', '../ppms-backend/src/main/java/com/ppms/audit'] },
    },
    test: {
      pool: 'threads',
      css: false,
      env: { TZ: 'UTC' },
      restoreMocks: true,
      projects: [
        {
          extends: true,
          test: {
            // A .test.ts file must not need a browser; tests that render or touch the DOM are .test.tsx.
            name: 'node',
            include: ['src/**/*.test.ts'],
            environment: 'node',
            setupFiles: ['./src/test/setup.node.ts'],
          },
        },
        {
          extends: true,
          test: {
            name: 'dom',
            include: ['src/**/*.test.tsx'],
            environment: 'jsdom',
            setupFiles: ['./src/test/setup.ts'],
          },
        },
      ],
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/main.tsx'],
        reporter: ['text-summary', 'text', 'html'],
      },
    },
  }),
)
