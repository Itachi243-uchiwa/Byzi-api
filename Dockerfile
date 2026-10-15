# syntax=docker/dockerfile:1

# =============================================================================
# Etape 1 - build
# =============================================================================
# Le JDK complet n'est necessaire qu'ici. Le separer de l'image finale evite
# d'embarquer Maven, les sources et le depot de dependances dans l'artefact
# livre - moins de poids, et surtout moins de surface d'attaque.
FROM maven:3-eclipse-temurin-26 AS build

WORKDIR /build

# pom.xml copie SEUL en premier : tant qu'il ne change pas, Docker reutilise la
# couche de dependances telechargees. Copier tout le projet d'abord relancerait
# un telechargement complet a chaque modification d'une ligne de code Java.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
# Les tests tournent en CI (etape dediee), pas dans la construction de l'image :
# ils exigeraient une base et allongeraient chaque build d'image de plusieurs minutes.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# =============================================================================
# Etape 2 - execution
# =============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Utilisateur non privilegie : une faille d'execution de code dans l'application
# ne doit pas donner root dans le conteneur (OWASP A05 - mauvaise configuration).
RUN addgroup -S byzi && adduser -S byzi -G byzi

WORKDIR /app

# curl sert au HEALTHCHECK ci-dessous ; l'image JRE alpine ne l'embarque pas.
RUN apk add --no-cache curl

COPY --from=build --chown=byzi:byzi /build/target/*.jar /app/byzi-api.jar

USER byzi

EXPOSE 8080

# MaxRAMPercentage plutot qu'un -Xmx fixe : la JVM s'ajuste a la limite memoire
# reellement accordee au conteneur, qui peut differer d'un environnement a l'autre.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

# Sonde de sante consommee par docker-compose (depends_on: service_healthy) et
# par l'orchestrateur en production. /actuator/health est volontairement le seul
# endpoint actuator expose publiquement (cf. application.yml).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# exec pour que la JVM soit le PID 1 et recoive directement SIGTERM : sinon
# "docker stop" attend le delai de grace complet au lieu d'un arret propre.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/byzi-api.jar"]
