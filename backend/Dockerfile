# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --no-create-home --shell /usr/sbin/nologin ekuiseo
COPY --from=build /build/target/ekuiseo-api.jar ./ekuiseo-api.jar
# Pieces d identite chiffrees (V20, ekuiseo.storage.identity-dir = ./data/identity) : le
# repertoire existe dans l image et appartient a l utilisateur applicatif, pour qu un volume
# nomme monte a cet endroit (docker-compose.prod.yml) herite de ces droits a sa creation.
RUN mkdir -p /app/data/identity && chown -R ekuiseo:ekuiseo /app/data
USER ekuiseo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/ekuiseo-api.jar"]
