FROM node:22-alpine
WORKDIR /app
COPY package.json review.mjs diagnostics.mjs preflight.mjs identity.mjs causes.mjs cause-knowledge.json server.mjs ./
USER node
ENV HOST=0.0.0.0 PORT=8080
EXPOSE 8080
CMD ["node","server.mjs"]
