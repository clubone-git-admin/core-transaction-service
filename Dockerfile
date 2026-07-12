# Use Amazon Corretto 21 (virtual threads + modern GC)
FROM amazoncorretto:21

WORKDIR /app

COPY target/transaction-service-0.0.1-SNAPSHOT.jar /app/core-transaction-service.jar

EXPOSE 8000

ENV SERVER_PORT=8000

# Cap heap to container RAM; exit on OOM so ECS restarts cleanly.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -XX:+UseG1GC"

CMD ["java", "-jar", "/app/core-transaction-service.jar", "--server.port=8000"]
