import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

/** Frontend build and dev server; `dev` proxies the api to a board started with `npm start`. */
export default defineConfig({
  root: 'web',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./web/src', import.meta.url)) },
  },
  build: { outDir: '../dist/web', emptyOutDir: true },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:4173', ws: true },
    },
  },
})
