# Build the jar. Tests are skipped here; run them with ./mvnw test.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -q -B -DskipTests package

# Run it on a plain JRE as a non-root user.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir data && chown app data
COPY --from=build /src/target/newtonite-ops-0.1.0.jar app.jar
USER app

# Sized for a 512 MB container: heap at 60% of memory, small thread stacks, a single-threaded
# GC and C1-only JIT so a slow shared CPU still starts the app in reasonable time.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "app.jar"]
