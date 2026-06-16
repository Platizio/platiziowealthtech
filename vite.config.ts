import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import {defineConfig, loadEnv} from 'vite';

/** Spring sends access_token + refresh_token as separate Set-Cookie headers; proxies often merge them with a comma, which browsers reject. */
function splitCombinedSetCookie(setCookie: string | string[]): string[] {
  const raw = Array.isArray(setCookie) ? setCookie : [setCookie];
  const result: string[] = [];
  for (const header of raw) {
    if (!header) continue;
    const parts = header.split(/,(?=\s*[a-zA-Z0-9_-]+=)/);
    for (const part of parts) {
      const trimmed = part.trim();
      if (trimmed) result.push(trimmed);
    }
  }
  return result;
}

function normalizeProxySetCookies(setCookie: string | string[] | undefined): string[] | undefined {
  if (!setCookie) return undefined;
  return splitCombinedSetCookie(setCookie).map((cookie) =>
    cookie.replace(/; Domain=[^;]+/gi, '').replace(/; Secure/gi, ''),
  );
}

export default defineConfig(({mode}) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [react(), tailwindcss()],
    define: {
      'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
      },
    },
    server: {
      host: 'localhost',
      port: 3000,
      strictPort: true,
      // HMR is disabled in AI Studio via DISABLE_HMR env var.
      // Do not modifyâfile watching is disabled to prevent flickering during agent edits.
      hmr: process.env.DISABLE_HMR !== 'true',
      // Required when VITE_API_BASE_URL="/api/v1" — same-origin API + HttpOnly auth cookies on HTTP dev.
      proxy: {
        '/api/v1': {
          target: env.VITE_BACKEND_ORIGIN || 'http://localhost:8081',
          changeOrigin: true,
          secure: false,
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyRes) => {
              const normalized = normalizeProxySetCookies(proxyRes.headers['set-cookie']);
              if (normalized?.length) {
                proxyRes.headers['set-cookie'] = normalized;
              }
            });
          },
        },
      },
    },
  };
});
