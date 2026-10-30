# Features

One folder per business capability, mirroring backend modules (`admin`, `project`, `dataroom`, `finding`, …):

```
src/features/<feature>/
├── api.ts          # server-only reads via apiFetch (types from schema.gen.ts)
├── actions.ts      # "use server" mutations returning ActionResult
├── types.ts        # DTO types until generated types replace them
└── *View.tsx       # client components (Ant Design), strings via next-intl
```

Features must not import each other's internals; shared UI goes in `src/components`.
