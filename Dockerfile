#Etapa 1: Build
FROM gradle:8.6-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

#Etapa 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]