// Refreshes openapi/api-docs.json from a running backend (default http://localhost:8080).
// Run this after changing backend controllers/DTOs, then `npm run generate:api`.
import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const baseUrl = process.env.API_BASE_URL ?? "http://localhost:8080";

const response = await fetch(`${baseUrl}/v3/api-docs`);
if (!response.ok) {
  throw new Error(`Failed to fetch OpenAPI spec from ${baseUrl}: ${response.status} ${response.statusText}`);
}
const spec = await response.json();

const outPath = fileURLToPath(new URL("../openapi/api-docs.json", import.meta.url));
await writeFile(outPath, JSON.stringify(spec, null, 2) + "\n");

console.log(`Wrote ${outPath}`);
