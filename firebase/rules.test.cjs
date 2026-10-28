const { test, before, after } = require('node:test');
const fs = require('node:fs');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { ref, get, set, update, serverTimestamp } = require('firebase/database');
let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-circlet-cloud',
    database: { host: '127.0.0.1', port: Number(process.env.FIREBASE_DATABASE_EMULATOR_HOST?.split(':').at(-1) || 9000), rules: fs.readFileSync('database.rules.json', 'utf8') },
  });
});
after(async () => { await env?.cleanup(); });
const row = () => ({ deleted: false, data: 'compressed-record', updatedAt: serverTimestamp() });
const databaseSource = fs.readFileSync('../data/src/main/kotlin/app/circlet/data/db/CircletDatabase.kt', 'utf8');
const currentSchema = Number(databaseSource.match(/const val VERSION\s*=\s*(\d+)/)[1]);

test('the app schema and the previous published schema can upload atomically', async () => {
  const db = env.authenticatedContext('schema-check').database();
  for (const schema of [3, currentSchema]) {
    await assertSucceeds(update(ref(db, 'users/schema-check'), {
      'meta/format': 1, 'meta/schema': schema, 'v1/sample_hours/31': row(),
    }));
  }
  await assertFails(set(ref(db, 'users/schema-check/meta/schema'), currentSchema + 1));
  await assertFails(set(ref(db, 'users/schema-check/meta/schema'), '4'));
});
test('an account can atomically upload and read only its own health rows', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertSucceeds(update(ref(db, 'users/alice'), { 'meta/format': 1, 'meta/schema': 3, 'v1/sample_hours/31': row() }));
  await assertSucceeds(get(ref(db, 'users/alice/v1/sample_hours')));
  await assertFails(get(ref(env.authenticatedContext('bob').database(), 'users/alice')));
  await assertFails(set(ref(env.authenticatedContext('bob').database(), 'users/alice/v1/sample_hours/32'), row()));
});
test('signed-out clients cannot read or change health records', async () => {
  const db = env.unauthenticatedContext().database();
  await assertFails(get(ref(db, 'users/alice/v1')));
  await assertFails(set(ref(db, 'users/alice/v1/sample_hours/33'), row()));
});
test('unknown collections, malformed records, and future timestamps are rejected', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertFails(set(ref(db, 'users/alice/v1/unexpected/31'), row()));
  await assertFails(set(ref(db, 'users/alice/v1/sample_hours/34'), { deleted: false, updatedAt: serverTimestamp() }));
  await assertFails(set(ref(db, 'users/alice/v1/sample_hours/35'), { ...row(), updatedAt: Date.now() + 86400000 }));
  await assertFails(set(ref(db, 'users/alice/v1/sample_hours/not-a-key'), row()));
});
test('a deletion tombstone is accepted without a data payload', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertSucceeds(set(ref(db, 'users/alice/v1/sample_hours/36'), { deleted: true, updatedAt: serverTimestamp() }));
});
test('per-second sample nodes are refused; history goes up as hourly bundles', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertFails(set(ref(db, 'users/alice/v1/samples/37'), row()));
  await assertFails(set(ref(db, 'users/alice/v1/raw_records/37'), row()));
  await assertSucceeds(set(ref(db, 'users/alice/v1/sample_hours/37'), row()));
});
