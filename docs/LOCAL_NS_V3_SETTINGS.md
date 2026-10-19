# Local V3 Settings Wire

The existing authenticated loopback TLS listener supports the stock RC1 settings
endpoints. This is wire storage, not permission to execute a target. Master ACKs
and client envelopes remain opaque; no clinical projection or READY is granted.

- GET by identifier, POST create/deduplicate, PUT named upsert, PATCH supplied
  fields, DELETE soft deletion and bounded modification history. Write receipts
  use the SDK's bare response. Identical retries do not advance revisions.
- Common date/utcOffset/app are typed and immutable. Native control uses constant
  document date946684800001; the changing signed timestamp stays in the envelope.
  Supplied server metadata must match an existing row.
- Strict UTF-8/object/depth/duplicate parsing, finite numbers, 64KiB bodies,
  bounded identifiers and 1MiB serialized list responses. Unknown/repeated filters
  fail. Search is complete-or507 because native offer/pending-command consumers
  have no continuation loop; modification history is paginated.
- HTTP writes cannot modify an internal controller source. Raw secretHex fields,
  including nested fields, are refused: PIN offers carry ciphertext, not QR keys.
- Deleted identifiers GET404 and cannot be replaced (410). Permanent deletion is
  supported only for a one-use UUID pairing-offer identifier: its wrapped payload
  becomes an empty object, retaining a non-secret tombstone/revision. This meets
  native secret-cleanup needs without regressing history/accounting. It is not
  general hard deletion or physical secure erasure; other permanent deletes501.

Settings advertises crud; unsupported treatments/profile/food remain501. There is
no schema, TLS-policy or live command change. Tests cover upsert/retry/metadata,
history/deletion, secret cleanup, scope, types, concurrency and response capacity.
The pinned unchanged SDK additionally exercises these routes through real TLS
and bearer authentication. Target codec and remaining gates: AAPS4_TARGET_PROTOCOL.md.
