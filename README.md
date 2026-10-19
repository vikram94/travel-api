# Travel transactions + frozen invoices

A small Java 21 / Spring Boot 4.1 service over Spring JDBC and a file-backed H2
database. It stores **travel transactions** (live, editable charges) and builds
**frozen invoices** from them: the moment transactions are billed, an invoice
snapshots their state, and later edits never move a number on it.

No external services, no API keys, no OCR/auth/Redis/Kafka. Clone, run one
command, call it with curl.

- Transactions: `POST` / `GET` / `PATCH` / `DELETE` by id, plus a keyset list.
- Invoices: create from one or many transaction ids, `GET` (with paged items and
  always-full rollups), `PATCH` (rebuild from a new set), `DELETE`, keyset list.
- Money is exact `BIGINT` minor units (scale 2); timestamps are microseconds.
- `external_id` uniqueness and the "this exact set is already invoiced" rule are
  **database constraints**, not application checks.
- Lock waits and deadlocks return **503 `Retry-After: 1` `{ "error": "busy" }`**
  — never 500.

---

## Requirements

- **JDK 21 or newer** (the project compiles with `release = 21`).
- Nothing else. The Gradle wrapper is committed, so no Gradle install is needed.

## Run the API (one command)

```bash
./gradlew bootRun
```

The service listens on <http://localhost:8080>. On first start it creates the
schema and a file-backed H2 database at `./data/travel`.

```bash
curl -s localhost:8080/health
# {"status":"ok"}
```

## Run the tests (one command)

```bash
./gradlew test
```

**83 automated tests**, all green: unit tests for `Money`, `Hashing`, `Cursor`
and `RollupAccumulator`, and real-HTTP integration tests that start the full
application on a random port and call it over `java.net.http.HttpClient`.

Every test class points at its **own throwaway file-backed H2 database** under
`build/` (never `jdbc:h2:mem:`), so uniqueness, locking and snapshots are always
exercised against the real database. An HTML report is written to
`build/reports/tests/test/index.html`.

## Reset the database

The H2 database is a single file:

```bash
rm -rf ./data          # next boot recreates the schema (idempotent CREATE TABLE IF NOT EXISTS)
```

`data/` and `build/` are git-ignored. Gradle builds into `build/` (not Maven's
`target/`).

## Configuration

`src/main/resources/application.properties`:

| Property | Value | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:h2:file:./data/travel;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000` | file H2 in Postgres compatibility mode |
| `spring.datasource.hikari.maximum-pool-size` | `10` | connection pool |
| `spring.transaction.default-timeout` | `10s` | write transactions time out, not hang |
| `invoice.max-transactions` | `1000` | per-request guardrail (see below) |
| `invoice.load-chunk-size` | `500` | ids per `FOR UPDATE` chunk |
| `invoice.items-page-default` | `100` | default `items_limit` on `GET /invoices/{id}` |
| `invoice.items-page-max` | `500` | max `items_limit` |
| `spring.jackson.property-naming-strategy` | `SNAKE_CASE` | JSON is snake_case |
| `spring.jackson.deserialization.use-big-decimal-for-floats` | `true` | money stays exact |

Any value can be overridden without editing the file by using Spring's relaxed
environment binding, e.g. `SPRING_DATASOURCE_URL=jdbc:postgresql://... ./gradlew bootRun`
or `INVOICE_MAX_TRANSACTIONS=200`. There are no secrets or API keys to configure.

## Design decisions

- **`total` is gross.** Tax and fee lines are descriptive; they are never added
  on top. An invoice total is the sum of its items' `total`s, computed with
  `Math.addExact` on minor units.
- **`external_id` is unique at the database level.** It is case-sensitive,
  stored exactly as sent (no trim/lowercase), and limited to 255 characters with
  no leading/trailing whitespace or control characters. A duplicate POST returns
  **409 `duplicate_external_id`** with the existing id, even if the body differs.
- **1000 is a per-request guardrail, not a claim that invoices can't be larger.**
  A single create/patch may reference up to 1000 transactions; beyond that the
  request is **400 `too_many_transactions`** with `max: 1000`. The boundary is
  honest about how much work one request may hold locks for; larger invoices are
  a future split/async design (see `ARCHITECTURE.md` §10).
- **Keyset pagination, never `offset`.** Cursors are
  `base64url(<created_at ISO micros>|<id>)`; invoice item cursors are the last
  `position`. Pages stay stable when rows are inserted between reads — no
  duplicates, no skips. `next_cursor` is `null` on the last page.
- **The fingerprint is a 409 guard.** `txn_set_hash = SHA-256(sorted ids joined
  with "\n")` is `UNIQUE` on `invoices`, so the same set cannot be billed twice
  (including in reversed order). Concurrent identical creates resolve through the
  unique index: one **201**, the rest **409 `invoice_exists_for_set`** with the
  winner's id. `DELETE` frees the fingerprint, so an invoice can be re-issued. It
  is an implementation detail and is **not exposed** on the API.
- **PATCH is partial.** Send only what changes: an absent field keeps its stored
  value, a present field replaces it, and `tax_lines: []` (present, empty) clears
  the list while an absent `tax_lines` leaves it alone. `type`, `external_id` and
  `currency` are immutable — omit them, or echo the stored value; a different
  value is **400**. On a transaction that is already invoiced the frozen-resource
  **409** takes precedence over that **400**.
- **`created_at` and `updated_at` are both returned.** `updated_at` equals
  `created_at` until a successful `PATCH` rewrites the row; a refused PATCH rolls
  back, so it never moves. Timestamps are UTC at microsecond precision, so a
  request offset is normalized (`2026-03-11T07:40:00+01:00` echoes as
  `2026-03-11T06:40:00Z`).
- **Money is bounded before it is scaled.** Amounts are parsed as `BigDecimal`
  and a hostile exponent (`1E+999999999`) is a **400**, never a crash: magnitude is
  checked before `movePointRight`/`setScale`. Malformed bodies and wrong JSON
  types (`"total": "abc"`) return **400 `invalid_json`** rather than Spring's
  default error body.
- **Invoices are frozen snapshots.** `invoice_items` stores verbatim copies of
  each transaction (metadata and line JSON included); `GET /invoices/{id}`
  never joins live transactions. Consequently a transaction on any invoice is
  protected: `PATCH`/`DELETE` return **409 `transaction_invoiced`**.
- **Rollups are stored at write time**, so `GET` cost is independent of item
  count; every page of items still returns the full rollups.
- **`navan_fee` is a first-class transaction**, not a fee line, and never
  contributes to `fee_rollup`.
- **Contention is transient, so it is 503.** Lock waits, deadlocks, and query
  timeouts map to `503` with `Retry-After: 1` and `{ "error": "busy" }`.
- **H2 file is a single-process choice.** The service is stateless and the SQL is
  Postgres-portable; point `spring.datasource.url` at Postgres for multi-instance
  deployments.
- **Deliberately out of scope:** OCR, auth, Redis/Kafka, `Idempotency-Key`,
  payload hashes, audit logs, and LLM calls.

### Error body

All errors share one shape — `error` and `message` plus optional keys:

```json
{ "error": "duplicate_external_id", "message": "...", "existing_id": "txn_..." }
```

| Status | `error` | Extra keys |
|---|---|---|
| 400 | `validation_error` | `errors: [{field, message}]` |
| 400 | `too_many_transactions` | `max` |
| 400 | `invalid_cursor` | — |
| 400 | `invalid_json` | malformed body or wrong JSON type |
| 404 | `not_found` | — |
| 404 | `transaction_not_found` | `ids` |
| 409 | `duplicate_external_id` | `existing_id` |
| 409 | `duplicate_transaction_id` | `ids` |
| 409 | `invoice_exists_for_set` | `existing_invoice_id` |
| 409 | `mixed_currency` | `currencies` |
| 409 | `transaction_invoiced` | `transaction_id` |
| 405 | `method_not_allowed` | unsupported method on a known path |
| 415 | `unsupported_media_type` | body is not `application/json` |
| 503 | `busy` | `Retry-After: 1` header |

Framework-level client errors (405 unsupported method, 415 unsupported media
type, and similar Spring `ErrorResponse` exceptions) keep their own 4xx status
rather than falling through to a 500.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | `{ "status": "ok" }` |
| POST | `/transactions` | 201; 409 on duplicate `external_id` |
| GET | `/transactions/{id}` | 200 / 404 |
| PATCH | `/transactions/{id}` | partial: absent fields keep their stored value, present fields replace theirs, `tax_lines: []` clears; 409 if invoiced |
| DELETE | `/transactions/{id}` | 204; 409 if invoiced; repeat is 404 |
| GET | `/transactions?limit=&cursor=` | default 50, max 100 |
| POST | `/invoices` | `{"transaction_id": "..."}` or `{"transaction_ids": [...]}`; 201 |
| GET | `/invoices/{id}?items_limit=&items_cursor=` | items default 100, max 500; full rollups always |
| PATCH | `/invoices/{id}` | same body rules; rebuilds the snapshot |
| DELETE | `/invoices/{id}` | 204; transactions are untouched |
| GET | `/invoices?limit=&cursor=` | headers only; default 50, max 100 |

---

## Curl walkthrough

The examples below are verified against a running service. Fixtures live in
`fixtures/task-b/`. `seed <type>` / `field <name>` are tiny helpers that pull one
payload out of the fixtures file and one field out of a response (they only need
`python3`; any JSON tool works):

```bash
BASE=http://localhost:8080
seed() { python3 -c 'import json,sys; print(json.dumps(json.load(open("fixtures/task-b/seed-payloads.json"))[sys.argv[1]]))' "$1"; }
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
```

### Create each transaction type

`flight`, `hotel`, `rail`, and the two `navan_fee` kinds (`trip_fee`,
`agent_call_fee`). Each returns **201** with the round-tripped metadata.

```bash
FLIGHT=$(seed flight  | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)
HOTEL=$(seed hotel   | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)
RAIL=$(seed rail     | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)
TRIP=$(seed trip_fee | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)
AGENT=$(seed agent_call_fee | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)

curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d "$(seed flight)"
```

```json
{ "id": "txn_...", "type": "flight", "external_id": "flight-TXL-LHR-2026-03-11",
  "currency": "EUR", "occurred_at": "2026-03-11T06:40:00Z", "total": 220.00,
  "tax_lines": [{"name":"Airport tax","amount":15.00}],
  "fee_lines": [{"name":"YQ","amount":25.00}],
  "metadata": { "origin": "TXL", "destination": "LHR", "fare": 180.0,
                "coupons": [ { "carrier": "BA", "flight_number": "BA982", ... } ] },
  "created_at": "...", "updated_at": "..." }
```

Money renders with two decimals (`220.00`); a tax `rate` is present only when the
input had one and is echoed without trailing zeros (hotel VAT is
`{"name":"VAT","rate":0.07,"amount":21.70}`; the flight's `Airport tax` has no
`rate` key). Values inside `metadata` are echoed exactly as sent.

### Duplicate create → 409

`duplicate_flight` carries the **same `external_id`** as `flight`. Posting it
again is rejected by the database's unique index, so two rows can never exist
(even for two concurrent requests):

```bash
curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d "$(seed duplicate_flight)"
```

```json
{ "error": "duplicate_external_id", "message": "...", "existing_id": "txn_..." }
```

Prove only **one row** exists — the `external_id` appears exactly once:

```bash
curl -sS "$BASE/transactions?limit=100" | python3 -c 'import json,sys; print(sum(1 for t in json.load(sys.stdin)["items"] if t["external_id"]=="flight-TXL-LHR-2026-03-11"))'
# 1
```

### Get and list transactions

```bash
curl -sS $BASE/transactions/$FLIGHT
curl -sS "$BASE/transactions?limit=2"
# {"items":[ ... ], "next_cursor":"MjAyNi0xMC0xNi4uLnwidHhuXy4uLg"}

curl -sS "$BASE/transactions?limit=2&cursor=<next_cursor>"

# bounds are enforced
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/transactions?limit=1000"   # 400
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/transactions?cursor=garbage" # 400 invalid_cursor
```

### Create an invoice over the trip

Totals match `fixtures/task-b/expected-invoice.json` — grand total **685.70 EUR**.

```bash
INVOICE=$(curl -sS -X POST $BASE/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$AGENT\"]}" | field id)

curl -sS -X POST $BASE/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_id\":\"$FLIGHT\"}"
```

```json
{ "id": "inv_...", "currency": "EUR", "total": 685.70, "item_count": 5,
  "created_at": "...", "updated_at": "...",
  "tax_rollup": [ {"name":"Airport tax","amount":15.0},
                  {"name":"VAT","rate":0.07,"amount":21.7} ],
  "fee_rollup": [ {"name":"Booking fee","amount":4.0},
                  {"name":"YQ","amount":25.0} ],
  "items": [ ... first page ... ], "items_next_cursor": null }
```

### The same set again → 409

```bash
curl -sS -X POST $BASE/invoices -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$AGENT\"]}"
```

```json
{ "error": "invoice_exists_for_set", "message": "...", "existing_invoice_id": "inv_..." }
```

### Get an invoice and page its items

```bash
curl -sS "$BASE/invoices/$INVOICE"                              # all items, full rollups
curl -sS "$BASE/invoices/$INVOICE?items_limit=2"                # first page + items_next_cursor
curl -sS "$BASE/invoices/$INVOICE?items_limit=2&items_cursor=1" # next page
```

### List invoices (headers only)

```bash
curl -sS "$BASE/invoices?limit=10"
# {"items":[{"id":"inv_...","currency":"EUR","total":685.70,"item_count":5,
#            "created_at":"...","updated_at":"..."}], "next_cursor":null}
```

### Invoiced transactions are frozen → 409

```bash
curl -sS -X PATCH $BASE/transactions/$FLIGHT -H 'Content-Type: application/json' -d "$(seed flight)"
curl -sS -X DELETE $BASE/transactions/$FLIGHT
```

```json
{ "error": "transaction_invoiced", "message": "..." }
```

### Patch a live transaction, then repoint the invoice

`PATCH` is **partial**: send only the fields that change. An absent field keeps
its stored value, a present field replaces it, and `tax_lines: []` clears the
list. `type`, `external_id` and `currency` are immutable — omit them, or echo the
stored value. A live, un-invoiced transaction updates with **200**.

```bash
# a second rail transaction with its own external_id (the seed's id is already used)
LIVE_BODY=$(python3 -c 'import json; b=json.load(open("fixtures/task-b/seed-payloads.json"))["rail"]; b["external_id"]="rail-live-1"; print(json.dumps(b))')
LIVE=$(echo "$LIVE_BODY" | curl -sS -X POST $BASE/transactions -H 'Content-Type: application/json' -d @- | field id)

# change only the total: lines, metadata, occurred_at and the immutable fields stay put
curl -sS -X PATCH $BASE/transactions/$LIVE -H 'Content-Type: application/json' -d '{"total":99.0}'
# {"id":"txn_...","external_id":"rail-live-1","total":99.00,
#  "fee_lines":[{"name":"Booking fee","amount":4.00}], ...}

# clear just the fee lines (an absent fee_lines would have left them alone)
curl -sS -X PATCH $BASE/transactions/$LIVE -H 'Content-Type: application/json' -d '{"fee_lines":[]}'

# repoint the invoice at just that transaction: the snapshot is rebuilt
curl -sS -X PATCH $BASE/invoices/$INVOICE -H 'Content-Type: application/json' -d "{\"transaction_id\":\"$LIVE\"}"
# {"id":"inv_...","item_count":1,"total":99.00, ... rebuilt from the new set ...}
```

### Delete an invoice; its transactions survive

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/invoices/$INVOICE  # 204
curl -sS -o /dev/null -w '%{http_code}\n' $BASE/invoices/$INVOICE           # 404
curl -sS -o /dev/null -w '%{http_code}\n' $BASE/transactions/$FLIGHT        # 200
```

### Delete a live transaction

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE $BASE/transactions/$LIVE # 204
```

---

## Full happy-path script

Copy-pasteable end to end. Requires `python3` (only to slice the fixtures and
read ids). Start the service with `./gradlew bootRun` first.

```bash
BASE=http://localhost:8080
seed() { python3 -c 'import json,sys; print(json.dumps(json.load(open("fixtures/task-b/seed-payloads.json"))[sys.argv[1]]))' "$1"; }
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
call() { curl -sS -o /tmp/tb.out -w "%{http_code}" "$@"; echo "  $(cat /tmp/tb.out)"; }

call "$BASE/health"

FLIGHT=$(seed flight  | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)
HOTEL=$(seed hotel   | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)
RAIL=$(seed rail     | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)
TRIP=$(seed trip_fee | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)
AGENT=$(seed agent_call_fee | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)

call -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d "$(seed duplicate_flight)"
call "$BASE/transactions/$FLIGHT"
call "$BASE/transactions?limit=2"
call "$BASE/transactions?limit=1000"
call "$BASE/transactions?cursor=garbage"

INVOICE=$(curl -sS -X POST "$BASE/invoices" -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$AGENT\"]}" | field id)
call -X POST "$BASE/invoices" -H 'Content-Type: application/json' \
  -d "{\"transaction_ids\":[\"$FLIGHT\",\"$HOTEL\",\"$RAIL\",\"$TRIP\",\"$AGENT\"]}"
call "$BASE/invoices/$INVOICE?items_limit=2"
CURSOR=$(curl -sS "$BASE/invoices/$INVOICE?items_limit=2" | field items_next_cursor)
call "$BASE/invoices/$INVOICE?items_limit=2&items_cursor=$CURSOR"
call "$BASE/invoices?limit=10"
call -X PATCH "$BASE/transactions/$FLIGHT" -H 'Content-Type: application/json' -d "$(seed flight)"
call -X DELETE "$BASE/transactions/$FLIGHT"

LIVE_BODY=$(python3 -c 'import json; b=json.load(open("fixtures/task-b/seed-payloads.json"))["rail"]; b["external_id"]="rail-live-1"; print(json.dumps(b))')
LIVE=$(echo "$LIVE_BODY" | curl -sS -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d @- | field id)
call -X PATCH "$BASE/transactions/$LIVE" -H 'Content-Type: application/json' -d '{"total":99.0}'
call -X PATCH "$BASE/invoices/$INVOICE" -H 'Content-Type: application/json' -d "{\"transaction_id\":\"$LIVE\"}"
call -X DELETE "$BASE/invoices/$INVOICE"
call "$BASE/invoices/$INVOICE"
call "$BASE/transactions/$FLIGHT"
call -X DELETE "$BASE/transactions/$LIVE"
```

## Further reading

`ARCHITECTURE.md` covers the data model, `external_id` uniqueness and concurrent
POSTs, the frozen-snapshot design, taxes vs fees and type-specific metadata, the
fingerprint, stored rollups, chunked `FOR UPDATE` loading, the 1000 guardrail,
keyset pagination, and what fails first under load.
