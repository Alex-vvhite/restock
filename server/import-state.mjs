import { readFile } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMigrationPool } from './storage.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const inputPath = process.argv[2]
  ? (isAbsolute(process.argv[2]) ? process.argv[2] : resolve(process.cwd(), process.argv[2]))
  : join(root, 'data', 'state.json');
const importKey = 'json_state_imported_at';

if (!process.env.DATABASE_URL) {
  console.error('Set DATABASE_URL to the target PostgreSQL connection string.');
  process.exit(1);
}

const source = JSON.parse((await readFile(inputPath, 'utf8')).replace(/^\uFEFF/, ''));
if (!Array.isArray(source.locations) || !Array.isArray(source.items) || !Array.isArray(source.movements)) {
  throw new Error('Input JSON must contain locations, items, and movements arrays.');
}

const pool = await createMigrationPool();
const client = await pool.connect();
try {
  await client.query('BEGIN');
  await client.query('LOCK TABLE locations, items, movements, app_metadata IN ACCESS EXCLUSIVE MODE');

  const marker = await client.query('SELECT value FROM app_metadata WHERE key = $1', [importKey]);
  if (marker.rowCount) throw new Error(`This database already imported state JSON at ${marker.rows[0].value}.`);

  const counts = await client.query(`
    SELECT
      (SELECT count(*)::int FROM locations) AS locations,
      (SELECT count(*)::int FROM items) AS items,
      (SELECT count(*)::int FROM movements) AS movements
  `);
  const existing = counts.rows[0];
  if (existing.locations || existing.items || existing.movements) {
    throw new Error('Refusing to import into non-empty tables. Use an empty Railway PostgreSQL database.');
  }

  for (const location of source.locations) {
    await client.query('INSERT INTO locations (id, name) VALUES ($1, $2)', [location.id, location.name]);
  }
  for (const item of source.items) {
    await client.query(
      `INSERT INTO items (id, location_id, name, unit, stock, recommended_stock, source_row)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [item.id, item.locationId, item.name, item.unit, item.stock,
        item.recommendedStock, item.sourceRow ?? null]
    );
  }
  for (const movement of source.movements) {
    await client.query(
      `INSERT INTO movements
        (id, item_id, item_name, location_id, type, quantity, stock_after, unit, comment, actor, actor_name, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
      [movement.id, movement.itemId, movement.itemName, movement.locationId,
        movement.type, movement.quantity, movement.stockAfter, movement.unit,
        movement.comment || '', movement.actor || '', movement.actorName || '',
        movement.createdAt]
    );
  }
  await client.query('INSERT INTO app_metadata (key, value) VALUES ($1, $2)', [importKey, new Date().toISOString()]);
  await client.query('COMMIT');
  console.log(`Imported ${source.locations.length} locations, ${source.items.length} items, and ${source.movements.length} movements from ${inputPath}.`);
} catch (error) {
  await client.query('ROLLBACK').catch(() => {});
  throw error;
} finally {
  client.release();
  await pool.end();
}
