// Minimal, valid one-page PDFs with distinct text, so each upload has its own SHA-256 (no duplicate detection) and
// gives Tika text to extract and Gotenberg a page to render. Plain ASCII, so string length equals byte length.

const WORDS = [
  "akta", "pendirian", "perseroan", "modal", "saham", "kontrak", "perjanjian", "laporan", "keuangan", "pajak",
  "audit", "izin", "usaha", "karyawan", "aset", "utang", "jaminan", "sengketa", "direksi", "komisaris",
  "agreement", "contract", "liability", "warranty", "indemnity", "revenue", "tax", "license", "employee",
  "lease", "shareholder", "dividend", "compliance", "environmental", "litigation",
];

/** Deterministic pseudo-random words for file `n`, so a re-run produces the same files. */
function line(n, row) {
  let seed = (n * 7919 + row * 104729) >>> 0;
  const words = [];
  for (let i = 0; i < 10; i++) {
    seed = (seed * 1103515245 + 12345) >>> 0;
    words.push(WORDS[seed % WORDS.length]);
  }
  return words.join(" ");
}

/** Single search token that only file `n` of run `run` contains. */
export function marker(run, n) {
  return `bulk${run}n${n}`;
}

/** A one-page A4 PDF titled with `n`, its {@link marker} and 30 lines of due-diligence vocabulary. */
export function onePagePdf(n, run = "x") {
  const text = [`(Bulk upload document ${n} ${marker(run, n)}) Tj`];
  for (let row = 0; row < 30; row++) text.push(`T* (${line(n, row)}) Tj`);
  const stream = `BT /F1 11 Tf 14 TL 56 780 Td ${text.join(" ")} ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R " +
      "/Resources << /Font << /F1 5 0 R >> >> >>",
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [];
  objects.forEach((body, i) => {
    offsets.push(pdf.length);
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) pdf += `${String(offset).padStart(10, "0")} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return pdf;
}
