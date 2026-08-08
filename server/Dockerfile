# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY server/settings.gradle.kts server/build.gradle.kts server/gradle.properties ./server/
COPY server/src ./server/src

# Strip host-only Gradle settings that break Linux container builds
# (e.g. Windows org.gradle.java.home pointing at Android Studio JBR).
RUN sed -i '/^org\.gradle\.java\.home=/d' gradle.properties server/gradle.properties \
    && chmod +x ./gradlew \
    && ./gradlew -p server clean installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system maodou \
    && useradd --system --gid maodou --home-dir /app --shell /usr/sbin/nologin maodou \
    && mkdir -p /app/uploads \
    && chown -R maodou:maodou /app

COPY --from=build --chown=maodou:maodou /workspace/server/build/install/maodouchat-server ./

ENV HOST=0.0.0.0 \
    PORT=8080 \
    STORAGE_DIR=/app/uploads

EXPOSE 8080

USER maodou

ENTRYPOINT ["./bin/maodouchat-server"]
