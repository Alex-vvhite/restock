import { randomBytes } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import pg from 'pg';

const { Pool } = pg;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS locations (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS items (
  id TEXT PRIMARY KEY,
  location_id TEXT NOT NULL REFERENCES locations(id),
  name TEXT NOT NULL,
  unit TEXT NOT NULL,
  stock NUMERIC NOT NULL CHECK (stock >= 0),
  recommended_stock NUMERIC NOT NULL CHECK (recommended_stock >= 0),
  source_row INTEGER
);

CREATE TABLE IF NOT EXISTS movements (
  id TEXT PRIMARY KEY,
  item_id TEXT NOT NULL,
  item_name TEXT NOT NULL,
  location_id TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('receipt', 'writeoff')),
  quantity NUMERIC NOT NULL CHECK (quantity > 0),
  stock_after NUMERIC NOT NULL CHECK (stock_after >= 0),
  unit TEXT NOT NULL,
  comment TEXT NOT NULL DEFAULT '',
  actor TEXT NOT NULL DEFAULT '',
  actor_name TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS movements_created_at_idx ON movements (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS movements_with_comment_idx ON movements (created_at DESC, id DESC)
  WHERE btrim(comment) <> '';

CREATE TABLE IF NOT EXISTS app_metadata (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`;

export async function createPostgresPool(databaseUrl = process.env.DATABASE_URL) {
  if (!databaseUrl) throw new Error('DATABASE_URL is required for PostgreSQL storage');
  const pool = new Pool({
    connectionString: databaseUrl,
    max: Number(process.env.PG_POOL_MAX || 5),
    connectionTimeoutMillis: 10_000,
    idleTimeoutMillis: 30_000
  });
  await pool.query('SELECT 1');
  return pool;
}

export async function ensureSchema(pool) {
  await pool.query(SCHEMA);
}

function fromItemRow(row) {
  return {
    id: row.id,
    locationId: row.location_id,
    name: row.name,
    unit: row.unit,
    stock: Number(row.stock),
    recommendedStock: Number(row.recommended_stock),
    ...(row.source_row == null ? {} : { sourceRow: Number(row.source_row) })
  };
}

function fromMovementRow(row) {
  return {
    id: row.id,
    itemId: row.item_id,
    itemName: row.item_name,
    locationId: row.location_id,
    type: row.type,
    quantity: Number(row.quantity),
    stockAfter: Number(row.stock_after),
    unit: row.unit,
    comment: row.comment,
    actor: row.actor,
    actorName: row.actor_name,
    createdAt: new Date(row.created_at).toISOString()
  };
}

class PostgresStorage {
  constructor(pool) { this.pool = pool; }

  async health() {
    await this.pool.query('SELECT 1');
    return { ok: true, storage: 'postgres' };
  }

  async locations() {
    const { rows } = await this.pool.query('SELECT id, name FROM locations ORDER BY id');
    return rows;
  }

  async items({ locationId, query } = {}) {
    const params = [];
    const where = [];
    if (locationId) { params.push(locationId); where.push(`location_id = $${params.length}`); }
    if (query) { params.push(`%${query}%`); where.push(`name ILIKE $${params.length}`); }
    const clause = where.length ? `WHERE ${where.join(' AND ')}` : '';
    const { rows } = await this.pool.query(
      `SELECT id, location_id, name, unit, stock, recommended_stock, source_row FROM items ${clause} ORDER BY name`,
      params
    );
    return rows.map(fromItemRow);
  }

  async movements({ withComment = false, limit = 25 } = {}) {
    const filter = withComment ? "WHERE btrim(comment) <> ''" : '';
    const { rows } = await this.pool.query(
      `SELECT id, item_id, item_name, location_id, type, quantity, stock_after, unit, comment, actor, actor_name, created_at
       FROM movements ${filter} ORDER BY created_at DESC, id DESC ${withComment ? '' : 'LIMIT $1'}`,
      withComment ? [] : [limit]
    );
    return rows.map(fromMovementRow);
  }

  async applyMovement(input) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const itemResult = await client.query(
        'SELECT id, location_id, name, unit, stock, recommended_stock, source_row FROM items WHERE id = $1 FOR UPDATE',
        [input.itemId]
      );
      if (!itemResult.rowCount) throw Object.assign(new Error('Товар не найден'), { status: 404 });
      const item = fromItemRow(itemResult.rows[0]);
      const nextStock = input.type === 'receipt' ? item.stock + input.quantity : item.stock - input.quantity;
      if (input.type === 'writeoff' && nextStock < -1e-9) {
        throw Object.assign(new Error(`Недостаточно остатка. Сейчас доступно: ${item.stock} ${item.unit}`), {
          status: 409,
          currentStock: item.stock
        });
      }
      const stockAfter = Math.max(0, nextStock);
      const movement = {
        id: randomBytes(12).toString('hex'),
        itemId: item.id,
        itemName: item.name,
        locationId: item.locationId,
        type: input.type,
        quantity: input.quantity,
        stockAfter,
        unit: item.unit,
        comment: String(input.comment || '').trim().slice(0, 300),
        actor: input.actor,
        actorName: input.actorName,
        createdAt: new Date().toISOString()
      };
      await client.query('UPDATE items SET stock = $2 WHERE id = $1', [item.id, stockAfter]);
      await client.query(
        `INSERT INTO movements
          (id, item_id, item_name, location_id, type, quantity, stock_after, unit, comment, actor, actor_name, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
        [movement.id, movement.itemId, movement.itemName, movement.locationId, movement.type,
          movement.quantity, movement.stockAfter, movement.unit, movement.comment,
          movement.actor, movement.actorName, movement.createdAt]
      );
      await client.query('COMMIT');
      return { movement, item: { ...item, stock: stockAfter } };
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {});
      throw error;
    } finally {
      client.release();
    }
  }
}

class JsonStorage {
  constructor({ statePath, seedPath }) {
    this.statePath = statePath;
    this.seedPath = seedPath;
    this.writes = Promise.resolve();
  }

  async init() {
    try {
      this.state = JSON.parse((await readFile(this.statePath, 'utf8')).replace(/^\uFEFF/, ''));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      this.state = JSON.parse((await readFile(this.seedPath, 'utf8')).replace(/^\uFEFF/, ''));
      await this.save(this.state);
    }
  }

  async save(next) {
    await mkdir(dirname(this.statePath), { recursive: true });
    const tmp = `${this.statePath}.tmp`;
    await writeFile(tmp, JSON.stringify(next, null, 2), 'utf8');
    await rename(tmp, this.statePath);
  }

  health() { return Promise.resolve({ ok: true, storage: 'json-local' }); }
  locations() { return Promise.resolve(this.state.locations); }
  items({ locationId, query } = {}) {
    let items = this.state.items;
    if (locationId) items = items.filter((item) => item.locationId === locationId);
    if (query) items = items.filter((item) => item.name.toLocaleLowerCase('ru-RU').includes(query.toLocaleLowerCase('ru-RU')));
    return Promise.resolve(items);
  }
  movements({ withComment = false, limit = 25 } = {}) {
    const movements = withComment
      ? this.state.movements.filter((movement) => String(movement.comment || '').trim())
      : this.state.movements.slice(-limit);
    return Promise.resolve([...movements].reverse());
  }

  async applyMovement(input) {
    const task = this.writes.then(async () => {
      const index = this.state.items.findIndex((candidate) => candidate.id === input.itemId);
      if (index < 0) throw Object.assign(new Error('Товар не найден'), { status: 404 });
      const item = this.state.items[index];
      const nextStock = input.type === 'receipt' ? item.stock + input.quantity : item.stock - input.quantity;
      if (input.type === 'writeoff' && nextStock < -1e-9) {
        throw Object.assign(new Error(`Недостаточно остатка. Сейчас доступно: ${item.stock} ${item.unit}`), {
          status: 409,
          currentStock: item.stock
        });
      }
      const movement = {
        id: randomBytes(12).toString('hex'), itemId: item.id, itemName: item.name,
        locationId: item.locationId, type: input.type, quantity: input.quantity,
        stockAfter: Math.max(0, nextStock), unit: item.unit,
        comment: String(input.comment || '').trim().slice(0, 300),
        actor: input.actor, actorName: input.actorName, createdAt: new Date().toISOString()
      };
      const next = structuredClone(this.state);
      next.items[index].stock = movement.stockAfter;
      next.movements.push(movement);
      await this.save(next);
      this.state = next;
      return { movement, item: this.state.items[index] };
    });
    this.writes = task.catch(() => {});
    return task;
  }
}

export async function createStorage(paths) {
  if (process.env.DATABASE_URL) {
    const pool = await createPostgresPool();
    await ensureSchema(pool);
    if (process.env.NODE_ENV === 'production') {
      const imported = await pool.query("SELECT 1 FROM app_metadata WHERE key = 'json_state_imported_at'");
      if (!imported.rowCount) {
        await pool.end();
        throw new Error('Production database has no imported state. Run npm run import:state before starting the API.');
      }
    }
    return new PostgresStorage(pool);
  }
  if (process.env.NODE_ENV === 'production') {
    throw new Error('DATABASE_URL is required in production; refusing to use ephemeral JSON storage.');
  }
  const store = new JsonStorage(paths);
  await store.init();
  return store;
}

export async function createMigrationPool() {
  const pool = await createPostgresPool();
  await ensureSchema(pool);
  return pool;
}

export { fromItemRow, fromMovementRow };
