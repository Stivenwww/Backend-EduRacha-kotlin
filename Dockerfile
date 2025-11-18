# 1. Etapa de construcción
FROM gradle:8-jdk17 AS build
WORKDIR /app

COPY . .
RUN gradle clean build -x test

# 2. Etapa de ejecución
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copia el JAR “fat” generado por Ktor
COPY --from=build /app/build/libs/*.jar app.jar

# Sevalla asigna el puerto vía variable PORT
ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
