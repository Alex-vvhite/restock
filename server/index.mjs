import http from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createStorage } from './storage.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const dataDir = join(root, 'data');
const port = Number(process.env.PORT || 3000);
const sessionLifetimeMs = 12 * 60 * 60 * 1000;
const sessions = new Map();
const storage = await createStorage({
  statePath: join(dataDir, 'state.json'),
  seedPath: join(dataDir, 'seed.json')
});

function users() {
  try { return JSON.parse(process.env.APP_USERS_JSON || '[]'); }
  catch { throw new Error('APP_USERS_JSON must be a JSON array'); }
}

function send(res, status, payload) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  res.end(JSON.stringify(payload));
}

async function bodyJson(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 16_384) throw Object.assign(new Error('Request body too large'), { status: 413 });
  }
  try { return raw ? JSON.parse(raw) : {}; }
  catch { throw Object.assign(new Error('Invalid JSON'), { status: 400 }); }
}

function secureEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}

function authorize(req, permission = 'read') {
  const value = req.headers.authorization || '';
  const token = value.startsWith('Bearer ') ? value.slice(7) : '';
  const session = sessions.get(token);
  if (!session || session.expiresAt < Date.now()) {
    if (token) sessions.delete(token);
    return { error: 'Нужно войти в приложение', status: 401 };
  }
  if (permission === 'write' && !['manager', 'operator'].includes(session.role)) {
    return { error: 'Недостаточно прав для изменения остатков', status: 403 };
  }
  return { session };
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    if (req.method === 'GET' && url.pathname === '/api/health') {
      return send(res, 200, await storage.health());
    }

    if (req.method === 'POST' && url.pathname === '/api/login') {
      const body = await bodyJson(req);
      const user = users().find((candidate) => candidate.username === body.username && secureEqual(candidate.password, body.password || ''));
      if (!user || !['manager', 'operator', 'viewer'].includes(user.role)) {
        return send(res, 401, { error: 'Неверный логин или пароль' });
      }
      const token = randomBytes(32).toString('base64url');
      const session = {
        username: user.username,
        displayName: user.displayName || user.username,
        role: user.role,
        expiresAt: Date.now() + sessionLifetimeMs
      };
      sessions.set(token, session);
      return send(res, 200, { token, displayName: session.displayName, role: session.role });
    }

    if (req.method === 'POST' && url.pathname === '/api/logout') {
      const token = (req.headers.authorization || '').replace(/^Bearer /, '');
      sessions.delete(token);
      return send(res, 200, { ok: true });
    }

    const auth = authorize(req, req.method === 'POST' && url.pathname === '/api/movements' ? 'write' : 'read');
    if (auth.error) return send(res, auth.status, { error: auth.error });

    if (req.method === 'GET' && url.pathname === '/api/locations') {
      return send(res, 200, { locations: await storage.locations() });
    }

    if (req.method === 'GET' && url.pathname === '/api/items') {
      const items = await storage.items({
        locationId: url.searchParams.get('locationId') || undefined,
        query: (url.searchParams.get('q') || '').trim() || undefined
      });
      return send(res, 200, { items });
    }

    if (req.method === 'GET' && url.pathname === '/api/movements') {
      const withComment = url.searchParams.get('withComment') === 'true';
      const limit = Math.min(100, Math.max(1, Number(url.searchParams.get('limit') || 25)));
      return send(res, 200, { movements: await storage.movements({ withComment, limit }) });
    }

    if (req.method === 'POST' && url.pathname === '/api/movements') {
      const body = await bodyJson(req);
      const quantity = Number(body.quantity);
      if (!body.itemId || !['receipt', 'writeoff'].includes(body.type) || !Number.isFinite(quantity) || quantity <= 0) {
        return send(res, 400, { error: 'Выберите товар и действие, затем укажите количество больше нуля' });
      }
      const result = await storage.applyMovement({
        itemId: body.itemId,
        type: body.type,
        quantity,
        comment: body.comment,
        actor: auth.session.username,
        actorName: auth.session.displayName
      });
      return send(res, 201, result);
    }

    return send(res, 404, { error: 'Маршрут не найден' });
  } catch (error) {
    console.error(error);
    return send(res, error.status || 500, {
      error: error.status ? error.message : 'Внутренняя ошибка сервера',
      ...(error.currentStock == null ? {} : { currentStock: error.currentStock })
    });
  }
});

server.listen(port, '0.0.0.0', () => console.log(`Stock API listening on port ${port}`));
