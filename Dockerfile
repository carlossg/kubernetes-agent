# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Pre-fetch dependencies into a cache mount so they survive across builds
# even when the GHA layer cache is evicted. Mount only the repository
# subdirectory so any base-image settings.xml / config under ~/.m2 stays
# visible.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B

# Copy source and build. -Dmaven.test.skip=true also skips test compilation
# (vs -DskipTests which only skips execution), saving more time.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package -B -Dmaven.test.skip=true

# Runtime image
FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

# Copy the built JAR
COPY --from=build /app/target/kubernetes-agent-*.jar /app/kubernetes-agent.jar

# Create non-root user
RUN groupadd -r k8sagent && useradd -r -g k8sagent k8sagent
RUN chown -R k8sagent:k8sagent /app
USER k8sagent

EXPOSE 8080

ENV JAVA_OPTS="-Xmx1g -XX:+UseG1GC"
ENV GEMINI_MODEL="gemini-3-flash-preview"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/kubernetes-agent.jar"]


