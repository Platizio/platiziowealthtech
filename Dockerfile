# syntax=docker/dockerfile:1

###############################################################################
# 1. Build the Vite/React app
###############################################################################
FROM node:20-alpine AS build
WORKDIR /app

# Vite inlines `import.meta.env.VITE_*` at BUILD time, so these must be present
# while `npm run build` runs. Render passes any service env var whose name
# matches an ARG here as a --build-arg automatically.
#   VITE_API_BASE_URL   -> keep "/api/v1" so the browser calls the SAME origin
#                          (nginx proxies it to the backend → auth cookies work)
#   VITE_BACKEND_ORIGIN -> your backend's PUBLIC URL, used only for the
#                          investor-action links (full-page links to the backend)
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_BACKEND_ORIGIN
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_BACKEND_ORIGIN=$VITE_BACKEND_ORIGIN

COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

###############################################################################
# 2. Serve the built static files with nginx (+ same-origin API proxy)
###############################################################################
FROM nginx:1.27-alpine AS serve

# The official nginx image runs envsubst on /etc/nginx/templates/*.template at
# startup. Restrict substitution to OUR vars so nginx runtime vars ($host,
# $uri, $remote_addr, ...) are left untouched.
ENV NGINX_ENVSUBST_FILTER="^(PORT|BACKEND_URL)$"
# Render injects PORT at runtime (defaults to 10000); this is just a fallback.
ENV PORT=10000

COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 10000
# CMD/ENTRYPOINT inherited from the base image: its entrypoint runs envsubst,
# then starts `nginx -g 'daemon off;'`.
