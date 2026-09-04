# syntax=docker/dockerfile:1
FROM node:22-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

# Vite n'inline que les variables VITE_* presentes au moment du build :
# toute variable lue par le code doit donc etre declaree ici, sinon la valeur
# passee par docker-compose n'a aucun effet sur le bundle produit.
# Liste exhaustive des `import.meta.env.VITE_*` du code source :
#   VITE_API_URL          (src/api/client.ts)
#   VITE_DEMO_FALLBACK    (src/api/resilient.ts)
#   VITE_MAP_STYLE_URL    (src/components/trip/RouteMap.tsx)
ARG VITE_API_URL=http://localhost:8080
ENV VITE_API_URL=$VITE_API_URL

# Donnees de demonstration : desactivees sauf valeur exactement 'true'.
# Le defaut vide garantit qu'une image de production ne sert jamais de
# donnees fictives, meme si la variable est oubliee au moment du build.
ARG VITE_DEMO_FALLBACK=
ENV VITE_DEMO_FALLBACK=$VITE_DEMO_FALLBACK

# Fond de carte MapLibre. Vide => trace schematique au lieu d'une carte cassee.
ARG VITE_MAP_STYLE_URL=
ENV VITE_MAP_STYLE_URL=$VITE_MAP_STYLE_URL

RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
