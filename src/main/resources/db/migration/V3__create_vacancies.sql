CREATE TABLE vacancies (
    id TEXT PRIMARY KEY NOT NULL,
    source TEXT NOT NULL CHECK (length(trim(source)) > 0),
    external_id TEXT NOT NULL CHECK (length(trim(external_id)) > 0),
    title TEXT NOT NULL CHECK (length(trim(title)) > 0),
    employer_name TEXT NOT NULL CHECK (length(trim(employer_name)) > 0),
    area TEXT NOT NULL CHECK (length(trim(area)) > 0),
    -- TEXT preserves BigDecimal precision; SQLite NUMERIC affinity can convert decimals to REAL.
    salary_from TEXT,
    salary_to TEXT,
    currency TEXT,
    url TEXT NOT NULL CHECK (length(trim(url)) > 0),
    published_at TEXT,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'SAVED', 'APPLIED', 'IGNORED')),
    UNIQUE (source, external_id)
);

CREATE TABLE vacancy_search_matches (
    vacancy_id TEXT NOT NULL REFERENCES vacancies(id) ON DELETE CASCADE,
    search_query_id TEXT NOT NULL REFERENCES search_queries(id) ON DELETE CASCADE,
    UNIQUE (vacancy_id, search_query_id)
);

CREATE INDEX idx_vacancies_first_seen ON vacancies(first_seen_at, id);
CREATE INDEX idx_vacancies_status_first_seen ON vacancies(status, first_seen_at, id);
CREATE INDEX idx_vacancies_source_first_seen ON vacancies(source, first_seen_at, id);
CREATE INDEX idx_matches_search_vacancy ON vacancy_search_matches(search_query_id, vacancy_id);
