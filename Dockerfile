FROM gradle:8.5-jdk17 AS build
WORKDIR /app

COPY build.gradle.kts .
COPY EliServer.kt .

RUN mkdir -p src/main/kotlin && cp EliServer.kt src/main/kotlin/

RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/eli-server.jar app.jar
EXPOSE 3000
ENV PORT=3000
CMD ["java", "-jar", "app.jar"]
