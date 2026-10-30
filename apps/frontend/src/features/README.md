# Features

One folder per business capability, mirroring backend modules (`document`, `search`, …):

```
src/features/<feature>/
├── api.ts          # server-side calls via apiFetch (types from schema.gen.ts)
├── components/     # feature-specific Ant Design compositions
└── index.ts        # the only file other features may import
```

Features must not import each other's internals; shared UI goes in `src/components`.
