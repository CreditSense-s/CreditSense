FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# The bundled ML service never sleeps, so the browser-side wake-up used on free hosts is off here
ENV VITE_ML_WAKE_URL=""
RUN npm run build

FROM nginx:1.27-alpine
# Rendered at start-up by the image's entrypoint (envsubst only touches the variables set here).
ENV BACKEND_URL=http://backend:8080 NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
