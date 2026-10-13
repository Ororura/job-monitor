-- A small infrastructure table verifies migrations without introducing vacancy domain tables.
CREATE TABLE application_metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);

INSERT INTO application_metadata (key, value) VALUES ('application', 'job-monitor');
