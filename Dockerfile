# ---- Etapa 1: Build ----
FROM gradle:8.6-jdk17 AS build
WORKDIR /app

COPY . .

# ejecutar con el wrapper
RUN ./gradlew shadowJar --no-daemon

# ---- Etapa 2: Runtime ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# copiar el shadow jar generado
COPY --from=build /app/build/libs/*-shadow.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
