CREATE TABLE collection_runs (
    id TEXT PRIMARY KEY NOT NULL,
    search_query_id TEXT REFERENCES search_queries(id) ON DELETE SET NULL,
    search_query_name TEXT NOT NULL,
    source TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'INTERRUPTED')),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    fetched_count INTEGER NOT NULL DEFAULT 0 CHECK (fetched_count >= 0),
    created_count INTEGER NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    updated_count INTEGER NOT NULL DEFAULT 0 CHECK (updated_count >= 0),
    error_message TEXT,
    CHECK ((status = 'RUNNING' AND finished_at IS NULL)
        OR (status != 'RUNNING' AND finished_at IS NOT NULL))
);

CREATE INDEX idx_collection_runs_started ON collection_runs(started_at, id);
CREATE INDEX idx_collection_runs_search_started ON collection_runs(search_query_id, started_at, id);
CREATE INDEX idx_collection_runs_status_started ON collection_runs(status, started_at, id);
