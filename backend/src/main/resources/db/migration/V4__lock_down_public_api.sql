-- Supabase exposes every table in the public schema through its own REST API (PostgREST), which anyone holding the
-- project URL and the public "anon" key can call. This application never uses that API: the Spring Boot backend connects
-- directly as the table owner, and the owner is not subject to row level security.
--
-- So: switch RLS on for every table and add no policies. Direct connections keep working; the Supabase REST API, which
-- uses the "anon" and "authenticated" roles, then sees no rows and can change none. The roles are also stripped of
-- their table privileges. On a plain PostgreSQL (local Docker, tests) those roles do not exist and that part is skipped.
--
-- flyway_schema_history is skipped on purpose: Flyway is using it while this script runs, so altering it here waits for
-- a lock Flyway itself holds and times out. Run once by hand (Supabase SQL editor):
--     ALTER TABLE public.flyway_schema_history ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE
    t record;
    r text;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables
             WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.tablename);
        FOR r IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %I', t.tablename, r);
        END LOOP;
    END LOOP;

    FOR r IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated') LOOP
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', r);
        EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM %I', r);
    END LOOP;
END
$$;

-- Supabase's "function search path mutable" warning: pin the trigger function's search path.
ALTER FUNCTION public.audit_logs_append_only() SET search_path = public, pg_temp;
