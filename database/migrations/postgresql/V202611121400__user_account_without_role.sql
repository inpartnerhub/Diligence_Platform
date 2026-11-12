-- #152: an account imported by an SSO broker has no application role until a Super Admin assigns one. It used to be
-- mirrored as OBSERVER although Keycloak held no role, so the user administration showed a role the user did not
-- have (and selecting OBSERVER changed nothing). NULL now means "no role yet"; such users are refused by the API.
ALTER TABLE adm.user_account ALTER COLUMN global_role DROP NOT NULL;
