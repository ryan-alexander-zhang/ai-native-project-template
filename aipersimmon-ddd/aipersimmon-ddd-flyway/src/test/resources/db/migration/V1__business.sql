-- Stands in for a CONSUMER's own Flyway migration (a business table) at Spring Boot's default
-- location. Proves the aipersimmon starter coexists with the consumer's own default Flyway.
CREATE TABLE IF NOT EXISTS business_widget (
    id BIGINT PRIMARY KEY
);
