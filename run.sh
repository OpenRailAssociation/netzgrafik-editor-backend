docker compose up -d

export DB_URL=jdbc:postgresql://localhost/netzgrafikeditor
export DB_USER=netzgrafikeditor
export DB_PASSWORD=netzgrafikeditor
export CORS_ALLOWED_ORIGINS=*
export AUTH_SERVICE_NAME=fc44839c-e95f-4854-a52d-449867a9aa62

mvn spring-boot:run
