CREATE TABLE search_queries (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    query_text TEXT NOT NULL CHECK (length(trim(query_text)) > 0),
    interval_minutes INTEGER NOT NULL CHECK (interval_minutes >= 10),
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    next_run_at TEXT
);
