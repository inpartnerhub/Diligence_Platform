import nextVitals from "eslint-config-next/core-web-vitals";
import nextTypeScript from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier";

const config = [
  ...nextVitals,
  ...nextTypeScript,
  prettier,
  { ignores: [".next/**", "node_modules/**", "src/lib/api/schema.gen.ts", "next-env.d.ts"] },
];

export default config;
