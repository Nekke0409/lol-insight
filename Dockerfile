FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew

COPY src ./src
# CI is responsible for the test suite. The image build produces only the executable artifact.
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar /tmp/lol-insight.jar

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl \
    && addgroup --system app \
    && adduser --system --ingroup app app

WORKDIR /app

COPY --from=build --chown=app:app /tmp/lol-insight.jar /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
