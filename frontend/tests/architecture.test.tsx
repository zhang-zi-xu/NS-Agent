import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const sourceRoot = fileURLToPath(new URL('../src/', import.meta.url));
const sourceFiles = (directory: string): string[] =>
  readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? sourceFiles(join(directory, entry.name))
      : /\.tsx?$/.test(entry.name)
        ? [join(directory, entry.name)]
        : [],
  );

test('shared frontend utilities never depend on application or feature code', () => {
  for (const file of sourceFiles(join(sourceRoot, 'shared'))) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /from\s+['"]@\/(?:app|features)\//, file);
  }
});

test('feature modules never import application orchestration', () => {
  for (const file of sourceFiles(join(sourceRoot, 'features'))) {
    assert.doesNotMatch(readFileSync(file, 'utf8'), /from\s+['"]@\/app\//, file);
  }
});

test('frontend sources use only the organized module paths and a composition-only entry', () => {
  for (const file of sourceFiles(sourceRoot)) {
    assert.doesNotMatch(
      readFileSync(file, 'utf8'),
      /from\s+['"]@\/(?:components|lib|types)(?:\/|['"])/,
      file,
    );
  }
  const entry = readFileSync(join(sourceRoot, 'App.tsx'), 'utf8');
  assert.doesNotMatch(entry, /useState|useEffect|fetch\(|api</);
  assert.match(entry, /<Workspace\s*\/>/);
  const frontendRoot = dirname(sourceRoot.slice(0, -1));
  assert.equal(
    JSON.parse(readFileSync(join(frontendRoot, 'package.json'), 'utf8')).name,
    'nongxin-frontend',
  );
});
