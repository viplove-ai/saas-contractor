FROM node:22-alpine AS dev
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev"]

FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine AS prod
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.default.conf.template /etc/nginx/templates/default.conf.template
# Substitute only these two — leave nginx's own $host, $remote_addr etc. alone.
ENV NGINX_ENVSUBST_FILTER="^(API_UPSTREAM|DNS_RESOLVER)$" \
    API_UPSTREAM="backend:8080" \
    DNS_RESOLVER="127.0.0.11"
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
