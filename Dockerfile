FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY EliServer.kt .
RUN mkdir -p src/main/kotlin && cp EliServer.kt src/main/kotlin/
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/server.jar app.jar
EXPOSE 3000
ENV PORT=3000
CMD ["java", "-jar", "app.jar"]
