import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    include: ['test/**/*.test.ts', 'web/test/**/*.test.tsx'],
    setupFiles: ['web/test/setup.ts'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts', 'web/src/**/*.{ts,tsx}'],
      exclude: ['web/src/main.tsx'],
      thresholds: { lines: 90, branches: 90, functions: 90 },
    },
  },
})
