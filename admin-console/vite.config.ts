import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// Gateway port (exchange-gateway default 9002). Override with VITE_GATEWAY_URL.
const gatewayTarget = process.env.VITE_GATEWAY_URL ?? 'http://localhost:9002'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // all backend calls go through the gateway — single entry, no CORS
      '/api': {
        target: gatewayTarget,
        changeOrigin: true,
      },
    },
  },
})
