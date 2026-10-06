-- Supabase exposes every table in the public schema through its own REST API (PostgREST), which anyone holding the
-- project URL and the public "anon" key can call. This application never uses that API: the Spring Boot backend connects
-- directly as the table owner, and the owner is not subject to row level security.
--
-- So: switch RLS on for every table and add no policies. Direct connections keep working; the Supabase REST API, which
-- uses the "anon" and "authenticated" roles, then sees no rows and can change none. The roles are also stripped of
-- their table privileges. On a plain PostgreSQL (local Docker, tests) those roles do not exist and that part is skipped.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.tablename);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon;
        REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon;
        REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM authenticated;
        REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM authenticated;
        REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM authenticated;
    END IF;
END
$$;

-- Supabase's "function search path mutable" warning: pin the trigger function's search path.
ALTER FUNCTION public.audit_logs_append_only() SET search_path = public, pg_temp;
