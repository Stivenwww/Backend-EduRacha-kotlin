# ---- Stage 1: Build ----
FROM gradle:8.6-jdk17 AS build

WORKDIR /app
COPY . .

RUN chmod +x ./gradlew && ./gradlew shadowJar --no-daemon

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar /app/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]