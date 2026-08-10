/**
 * Firestore REST reads — port of android FirestoreRest.kt.
 *
 * Three requests exist, all reads, all billed to Traverse's project: the
 * events heartbeat (one document), the paged schedule list, and the card
 * batchGet. Nothing else may talk to Firestore.
 */

import { PROJECT_ID, TraverseError } from './auth.js';

const BASE = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents`;
const DOCUMENT_PREFIX = `projects/${PROJECT_ID}/databases/(default)/documents`;

const PAGE_SIZE = 300;
const MAX_PAGES = 50;
export const CARD_BATCH_SIZE = 150;
export const CARD_BATCH_PAUSE_MS = 3000;

const MAX_FIELD_DEPTH = 3;
const MAX_FIELD_STRINGS = 64;

function redact(url) {
  return url.split('?')[0].replace(/\/userNames\/[^/]+/, '/userNames/{uid}');
}

// Chrome kills an MV3 service worker when a fetch takes >30s to respond, and
// a killed worker records no error anywhere — so every request carries a
// client-side timeout that fails loudly into the normal error path first.
const REQUEST_TIMEOUT_MS = 25_000;

async function request(url, idToken, { allow404 = false, ...init } = {}) {
  const response = await fetch(url, {
    ...init,
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { Authorization: `Bearer ${idToken}`, ...(init.headers ?? {}) },
  });
  if (allow404 && response.status === 404) return null;
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 400);
    throw new TraverseError(`HTTP ${response.status} from ${redact(url)}: ${detail}`, response.status);
  }
  return response.json();
}

// Firestore's REST JSON types whole doubles as integerValue, and
// integerValue is always a string.
function str(fields, key) {
  return fields?.[key]?.stringValue ?? '';
}

function bool(fields, key, fallback) {
  return fields?.[key]?.booleanValue ?? fallback;
}

/**
 * Number of reviews recorded today — the one-document heartbeat that gates
 * full pulls. The date is the CLIENT'S local date: Traverse keys the doc
 * by the date on the phone, and 404 means no reviews yet, not an error.
 */
export async function reviewCountOn(idToken, uid, date) {
  const url = `${BASE}/userNames/${encodeURIComponent(uid)}/events/${encodeURIComponent(date)}`;
  const json = await request(url, idToken, { allow404: true });
  return json?.fields?.review?.arrayValue?.values?.length ?? 0;
}

/**
 * Every schedule row, minimally projected: the extension needs card
 * identity, template, author, and suspension — not the SRS numbers.
 * dueTime is still checked because its absence is the schema-change
 * signal the quality gate watches for.
 *
 * @returns {Promise<Array<{cardId: string, template: string, author: string, suspended: boolean}>>}
 */
export async function allScheduleRows(idToken, uid) {
  const rows = [];
  let dropped = 0;
  let total = 0;
  let pageToken = null;
  let pages = 0;

  do {
    const params = `pageSize=${PAGE_SIZE}${pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : ''}`;
    const json = await request(
      `${BASE}/userNames/${encodeURIComponent(uid)}/schedules?${params}`,
      idToken,
    );
    for (const doc of json.documents ?? []) {
      total++;
      const fields = doc.fields;
      if (!fields?.dueTime?.timestampValue) {
        dropped++;
        continue;
      }
      rows.push({
        cardId: str(fields, 'cardId'),
        template: str(fields, 'template'),
        author: str(fields, 'authorUserName'),
        suspended: bool(fields, 'suspended', false),
      });
    }
    pageToken = json.nextPageToken || null;
    pages++;
  } while (pageToken !== null && pages < MAX_PAGES);

  if (pageToken !== null) {
    throw new TraverseError(`Schedule pagination exceeded ${MAX_PAGES} pages — aborting`);
  }
  if (dropped > 0 && dropped * 10 > total) {
    throw new TraverseError(`Could not parse ${dropped} of ${total} schedules — schema changed?`);
  }
  return rows;
}

function collectStrings(value, out, depth) {
  if (out.length >= MAX_FIELD_STRINGS || depth > MAX_FIELD_DEPTH) return;
  const s = value?.stringValue;
  if (typeof s === 'string' && s.trim() !== '') out.push(s);
  for (const nested of Object.values(value?.mapValue?.fields ?? {})) {
    collectStrings(nested, out, depth + 1);
  }
  for (const nested of value?.arrayValue?.values ?? []) {
    collectStrings(nested, out, depth + 1);
  }
}

/**
 * Content fields of a card document, keys lowercased — some cards carry
 * both `WORD` and `Word`. Content sits one level down at
 * fields.fields.mapValue.fields; the outer `fields` is Traverse's own
 * bookkeeping.
 */
function cardDoc(found) {
  const content = found.fields?.fields?.mapValue?.fields ?? {};
  const fields = new Map();
  for (const [key, value] of Object.entries(content)) {
    const s = value?.stringValue;
    if (typeof s === 'string' && s.trim() !== '') {
      fields.set(key.toLowerCase(), s);
    } else {
      const strings = [];
      collectStrings(value, strings, 0);
      fields.set(key.toLowerCase(), strings.join(' '));
    }
  }
  return { fields };
}

/**
 * One batchGet of at most 150 card documents. Sequential by contract —
 * the caller paces requests; two in flight is not politeness.
 *
 * @returns {Promise<Map<string, {fields: Map<string, string>}>>} found docs
 *   by card id; absent means Firestore reported the document missing.
 */
export async function fetchCards(idToken, author, cardIds) {
  if (cardIds.length > CARD_BATCH_SIZE) {
    throw new TraverseError(`batchGet limited to ${CARD_BATCH_SIZE} documents, got ${cardIds.length}`);
  }
  // The prefix is a Firestore resource name inside a JSON body, not a URL —
  // percent-encoding here would corrupt it. Android does the same.
  const prefix = `${DOCUMENT_PREFIX}/userNames/${author}/cards/`;
  const entries = await request(`${BASE}:batchGet`, idToken, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ documents: cardIds.map((id) => prefix + id) }),
  });

  const found = new Map();
  for (const entry of entries) {
    if (!entry.found?.name) continue;
    const cardId = entry.found.name.split('/').pop();
    found.set(cardId, cardDoc(entry.found));
  }
  return found;
}
