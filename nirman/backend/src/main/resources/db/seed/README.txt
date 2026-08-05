Dev-only sample data. Loaded because application-dev.yml adds this folder to the Flyway
locations; the prod profile never lists it, so V900 cannot reach a real deployment.

Versioned at 900 to stay clear of the real migrations: schema changes keep counting up from
V2 for years without ever colliding with the seed.

Every seeded login uses the password Nirman@123. Never reuse these hashes anywhere real.
