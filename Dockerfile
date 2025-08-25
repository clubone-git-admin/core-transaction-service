# Use Amazon Corretto as the base image
FROM amazoncorretto:17

# Set the working directory
WORKDIR /app

# Copy the Spring Boot jar file
COPY target/transaction-service-0.0.1-SNAPSHOT.jar /app/core-transaction-service.jar

# Expose the container port (8000) for ECS to map dynamically assigned host port
EXPOSE 8000

# Set environment variables for the application to run on port 8000
ENV SERVER_PORT=8000

# Run the Spring Boot application
CMD ["java", "-jar", "/app/core-transaction-service.jar", "--server.port=8000"]
