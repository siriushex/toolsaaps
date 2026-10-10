# Gentle alerts and trusted Telegram delivery

Authorized in the side conversation on 2026-09-13. The isolated Android candidate
is integrated on the September main source release, preserving its newer server-AI
activation and credential storage. No live Telegram sends, tokens or device installs.

## Scope

1. Soft sound defaults to 2 seconds (user range 1-5 seconds); urgent sound stays independent.
   WATCH is silent; the system ringtone cannot overlap the app's soft audio clip.
2. Initial soft-risk confirmation uses distinct CGM timestamps; +10-minute forecasts
   participate in crossing-time estimation. Existing exact global OFF remains authoritative.
3. Telegram settings: encrypted token, explicit enable, private verified recipients,
   per-recipient alerts/reports, disconnect and bounded connection feedback.
4. One-time pairing: expected username plus expiring random deep-link challenge,
   private /start from the matching user, then explicit local confirmation of chat ID.
5. Forward delivered alert episodes with durable per-recipient claims, a short freshness
   limit, mute cancellation and no automatic retry after an uncertain network outcome.
6. Send canonical 7/30-day compensation summaries on explicit user action, or completed
   new reports only after a separate setting. No new clinical analysis or therapy command.

## Boundaries

Telegram is a supplementary remote delivery channel. Local alerts do not await it.
No continuous Telegram polling: getUpdates is used only while checking a pairing.
No usernames as delivery addresses: recipients are bound to numeric private chat IDs.
No bot commands can modify therapy, acknowledge local alarms or disable alerts.
HTTP uses official Telegram HTTPS, no redirects, request logs or automatic retries.
Credentials, recipients and bounded deduplication receipts use Keystore/AES-GCM.
UI and error messages never contain a saved token or a token-bearing request URL.

## Verification

Focused alert confirmation/audio/mute tests; pairing, revocation, no-repeat, mute,
stale-event, report consent, cancellation, response-budget and sanitized-error tests.
The initial isolated candidate passed 224 selected unit tests and APK compilation.
Integration verification reruns the full unit suite, lint and debug build on current
main; final results are recorded in AI_NOTES.md, not inferred from that earlier run.
Phone and real bot verification remain a separate step requiring user-entered
credentials and confirmed recipients.

## Integration

The integration preserves newer code in secret storage, AppContainer, MainViewModel,
CopilotFoundationRoot, SettingsScreen and both backup rule files. Telegram and server-AI
use separate deletion coordinators and credential namespaces. Regression tests cover
namespace quarantine isolation and both cloud-backup/device-transfer exclusions.

The token is entered in Settings on the phone. No token or recipient was configured
during development, and all HTTP tests intercept requests without contacting Telegram.

Telegram delivery success means an acknowledged message for the intended chat, not a
read receipt. An interrupted/uncertain request stays claimed and is not retried. These
alerts require the phone to have connectivity and the app to be running; they cannot
detect a powered-off phone remotely. Reports here are newly completed or explicitly
requested local summaries, not scheduled daily PDFs.

## Next design work

Adaptive probability thresholds and changes to urgent mute semantics require replay
evidence. This patch does not change those thresholds or claim clinical validation.
PDFs continue to use the existing explicit Android share flow; automated Telegram
reports in this change contain the canonical local summary, not a medical raw dump.

## Setup flow after integration

1. Use a dedicated Telegram bot. Enter its token in the app, not in a conversation
   or checked-in configuration. Successful getMe verification keeps delivery OFF.
2. Enter a trusted person's username and create an invitation. Share that invitation
   with that person; they open the bot link and press Start in their private chat.
3. Check the invitation in Copilot, verify the username and numeric chat identity,
   then explicitly confirm. Usernames alone never authorize a recipient.
4. Choose alerts and/or reports per recipient; separately enable overall delivery.
   Early warnings and automatically forwarded completed summaries are optional.
5. Generate a current local clinical summary before using Send current summary.
   Summary delivery is separate from the existing manual PDF share flow.
6. Remove recipients or disconnect the bot to stop future routing. Messages already
   accepted by Telegram cannot be recalled by an OFF action in Copilot.

## Release checks still required

- Repeat full lint and unit tests for any further changes before a device release.
- Exercise pairing and delivery with explicitly authorized test chats; test wrong
  username, group chat, expired invitation, lost network, revocation and app restart.
- Confirm exact OFF 30/60 behavior on a phone, including a mute pressed during HTTP.
  Cancellation cannot retract a request Telegram has already accepted.
- Check Compose layout, accessibility, Android Keystore storage and backup exclusions
  on the device. Compare CPU/PSS against a fresh baseline; no resource savings claimed.
- Pairing reads at most 100 currently pending bot updates and does not acknowledge
  offsets. A busy or shared bot can hide a new invitation; use a dedicated bot. A
  robust bounded backlog workflow and a clearer backlog error are future hardening.
- This is supplemental at-most-once-attempt delivery, not guaranteed delivery,
  remote escalation, or offline-phone monitoring. Uncertain results remain visible.
