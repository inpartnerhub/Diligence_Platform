/** Demo users created by `make demo-setup` (branch `demo`): one per role, all members of the demo project. */
export interface DemoRole {
  key: string;
  username: string;
  title: string;
  side: "Tim konsultan" | "Klien" | "Pihak target" | "Administrasi" | "Audit";
  description: string;
}

export const DEMO_ROLES: readonly DemoRole[] = [
  {
    key: "partner",
    username: "demo.partner",
    title: "Partner / Director",
    side: "Tim konsultan",
    description: "Menyetujui dan merilis temuan ke klien, menerbitkan laporan, menutup proyek.",
  },
  {
    key: "project-manager",
    username: "demo.pm",
    title: "Project Manager",
    side: "Tim konsultan",
    description:
      "Mengatur proyek dan tim, merilis request list, menyetujui temuan dan pertanyaan, menyusun laporan.",
  },
  {
    key: "analyst",
    username: "demo.analyst",
    title: "Analis / Reviewer",
    side: "Tim konsultan",
    description: "Mereview dokumen di data room, mencatat temuan, mengajukan pertanyaan ke pihak target.",
  },
  {
    key: "client",
    username: "demo.client",
    title: "Klien (buy-side)",
    side: "Klien",
    description:
      "Memantau progres, membaca temuan yang dirilis dan laporan yang diterbitkan. Tidak melihat data room.",
  },
  {
    key: "target",
    username: "demo.target",
    title: "Pihak target (sell-side)",
    side: "Pihak target",
    description:
      "Mengunggah dokumen yang diminta dan menjawab pertanyaan. Tidak pernah melihat temuan atau laporan.",
  },
  {
    key: "observer",
    username: "demo.observer",
    title: "Pengamat / Auditor",
    side: "Audit",
    description: "Melihat proyek tanpa bisa mengubah apa pun.",
  },
  {
    key: "super-admin",
    username: "demo.admin",
    title: "Super Admin",
    side: "Administrasi",
    description: "Mengelola pengguna, organisasi, template, matriks risiko, dan membaca log audit.",
  },
];

export function demoRole(key: string): DemoRole | undefined {
  return DEMO_ROLES.find((role) => role.key === key);
}
