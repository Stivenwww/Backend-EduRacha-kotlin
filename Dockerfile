# ---------- 1. Build ----------
# Gradle 8.14.3 = misma versión que el wrapper del proyecto (gradle-wrapper.properties).
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app
COPY . .
# installDist genera build/install/ktor-sample/ (lib/ con todas las dependencias + bin/ con el script).
# Evita la ambigüedad de varios .jar en build/libs (jar normal sin dependencias + shadowJar).
RUN gradle installDist -x test --no-daemon --console=plain

# ---------- 2. Runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home --shell /usr/sbin/nologin eduracha
COPY --from=build /app/build/install/ktor-sample/ /app/
USER eduracha

# Sevalla define PORT en tiempo de ejecución (8080 por defecto). application.yaml lo lee.
ENV PORT=8080
# Producción: desactiva el modo desarrollo de Ktor (build.gradle lo activa para `gradle run`)
# y ajusta la memoria de la JVM al tamaño del pod.
ENV JAVA_OPTS="-Dio.ktor.development=false -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

CMD ["/app/bin/ktor-sample"]
