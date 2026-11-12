export const dynamic = "force-static";

const PAGE = `<!doctype html>
<html lang="id">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Diligence Platform</title></head>
<body>
<h1>Belum ada akses / No access yet</h1>
<p>Akun Anda sudah terdaftar, tetapi belum memiliki peran di Diligence Platform. Minta administrator untuk menetapkan
peran Anda, lalu masuk kembali.</p>
<p>Your account exists but has no role in the Diligence Platform yet. Ask an administrator to assign your role, then
sign in again.</p>
<p><a href="/auth/login">Masuk kembali / Sign in again</a></p>
</body>
</html>
`;

/**
 * Shown after signing out an account that has no application role (#152). A route handler rather than a page: it must
 * render without a session, and /auth/* responses carry the non-document Content-Security-Policy (no scripts or
 * styles are needed here).
 */
export function GET() {
  return new Response(PAGE, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
