# Narrumo AI reading lab — private test backend

Narrumo 1.5.0 performs English transcription entirely on the phone with a bundled Vosk model. It does not call `/transcribe`, need an AI connection for transcripts, or use the server audio allowance. Only explicitly confirmed recaps, questions, comics and the optional book word guide use this backend. Keep the existing Worker and connection; the offline-only update needs no deployment change; versions 1.6.0/1.7.0 add `/vocabulary` and `/chapter-summary` and need the updated Worker. The audio endpoint below remains for older 1.4 clients.

Narrumo is the new name for Morrow Audio. Keep the existing `morrow-ai-trial` Worker, `MORROW_TRIAL_TOKEN` secret and saved server URL when upgrading; these identifiers stay unchanged for compatibility. No new connection or API key is needed for the rename.

This is a real OpenAI Responses API integration, not a ChatGPT website wrapper. Android 1.4.0 adds a timestamped read-along screen, concise progress recaps, and graphic-novel pages from selected passages. The older passage-only reading lab remains available. No request runs automatically, and no provider key belongs in the APK.

## What GitHub does

GitHub stores the app/backend code and runs the automated checks. It does not need your OpenAI key to build or test. GitHub Actions is not the live mobile API server. GitHub Models was retired on 30 July 2026: https://docs.github.com/en/github-models.

The runtime here is a Cloudflare Worker with a SQLite-backed Durable Object for a global request allowance. Your existing Narrumo distribution website can remain static. A separate Worker provides the private `/ai` URL.

## Connect your API account

Use your own Cloudflare account and a recent Node.js (22+):

```sh
cd ai-backend
node --test test/*.test.mjs
npx wrangler@4 login --device
npx wrangler@4 secret put OPENAI_API_KEY
npx wrangler@4 secret put MORROW_TRIAL_TOKEN
npx wrangler@4 deploy
```

Enter the OpenAI API key only at the first secret prompt. For the second prompt, generate a separate random test token of at least 24 characters, for example with `openssl rand -hex 32`, and save it privately. That token grants access to this limited test server; it is **not** your provider key. Never commit either secret, paste an API key into the app, or add secrets to public issues. Wrangler sends them to Cloudflare’s secret store; this repository contains no real secret values.

Review `wrangler.jsonc` before deployment. It defaults to `gpt-6.1-sol` (the existing private trial’s configured model); set `OPENAI_MODEL` to an available Responses-compatible model on your account that supports Structured Outputs. The model is selected on the server, not by app users. Review your Cloudflare plan and OpenAI billing before deploying. ChatGPT subscriptions and API billing are separate.

If this is the first Worker under your account, complete any setup prompted by Wrangler for your workers.dev subdomain. It will print a URL such as `https://morrow-ai-trial.YOUR-SUBDOMAIN.workers.dev`. In Narrumo → Settings → AI reading lab:

1. Set the test-server URL to that URL followed by `/ai`.
2. Enter the separate `MORROW_TRIAL_TOKEN`, then Save connection.
3. Load the original sample passage or paste your own passage (up to 12,000 characters).
4. Choose a recap or question, then explicitly confirm Send.

`GET /health` reports `ready` only when key, model, token and budget binding are configured. With missing configuration the app explains that setup is required; it never substitutes a canned answer for live AI. The access token and server address stay in app-private preferences excluded from app backups. Passages/questions are not saved to the library or backups; transient screen state may retain the text during rotation.

## Bounds and evidence

- Authentication is required. Provider API keys stay server-side, and redirects are disabled in the Android client so its access token cannot silently follow a redirect.
- The default global limit is **20 attempts per UTC day**, enforced atomically before each provider request by a Durable Object. Failed attempts count too. `AI_DAILY_LIMIT` can be set to 1–200. This limits requests, not exact currency spend; configure provider spending controls as well.
- Request body: 64 KiB; passage: 12,000 characters; question: 1,000 characters; model output: 1,000 tokens; provider timeout: 30 seconds. There are no automatic paid retries.
- Only the fixed OpenAI Responses endpoint is contacted, with `store: false`. This does not assert zero provider retention; review OpenAI’s current API data policies.
- The model receives the passage as untrusted data and is instructed to use only that passage. Returned quotes must match it verbatim; invalid evidence rejects the answer. No evidence returns “I couldn’t find that in this passage.” Quotation checks do not guarantee an answer’s interpretation is correct.
- The older `/ai` passage lab sends no audio, book titles, bookmark notes, file URIs or full library. Server application logging is disabled, but infrastructure providers may still process operational connection data.

## Private trial, not paid production

This shared access-token setup is for the owner and a small trusted testing group. Rotate the token in Cloudflare to revoke access; use Forget connection on the phone to remove it locally. Before a wider AI launch, add individual accounts, per-user quotas/billing, abuse controls, appropriate privacy/consent disclosures, and measured evaluation on licensed transcripts with listening-position boundaries. Do not embed the shared test token in a public APK.

Primary docs:
- https://developers.openai.com/api/reference/overview
- https://developers.openai.com/api/docs/guides/text
- https://developers.openai.com/api/docs/guides/structured-outputs
- https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform

## Read along, recaps and comics (1.4.0)

Keep the same Worker, Durable Object binding, secrets and saved `/ai` app connection when upgrading. From a phone, `wrangler login --device` prints a Cloudflare verification URL and code; approval in your browser works without a localhost callback. `wrangler deploy` updates the claimed Worker and preserves its secret values. Do not create a new temporary Worker for an upgrade.

- **`POST /transcribe`** accepts only canonical PCM16, mono 16 kHz WAV clips, at most 300 seconds / 9,600,044 bytes. The Android app decodes only a user-confirmed interval ending at its playback cutoff, including chapter offsets inside M4B files. The server derives actual duration from the WAV header and length, then atomically reserves up to **1,800 seconds per UTC day** before calling OpenAI `whisper-1` with `verbose_json` segment timestamps. Audio is not stored by this server; OpenAI's processing/retention policies still apply. Local temporary clips are deleted on completion or cancellation. SRT/VTT import and SRT export work without AI.
- **`POST /companion`**, `action: recap | ask | comic`, requires timestamped cues and a chapter/position cutoff. The server removes incomplete/future cues before any provider request. No book titles, author metadata, source URIs or library are sent. The phone also rechecks the cutoff after selection and before sending; rewinding hides answers whose evidence lies ahead. Completed lines appear beside playback and the current completed line is highlighted. Audio recognition/timestamp mistakes can still affect alignment.
- **Recaps:** available recent transcript excerpts, up to 12,000 characters, and at most 100 words of output. Missing transcript is not invented; the interface states that context may have gaps. Up to three short, exact evidence quotes are checked. Text shares the existing daily allowance (20 by default). A quotation check does not prove every interpretation or establish a formal spoiler guarantee; evaluate against known texts before a public AI launch.
- **Comics:** a selected passage of at most 2,500 characters produces **one four-panel, 1024 × 1024 medium-quality JPEG page**, using `gpt-image-2.5-flare`. Style is original drawn graphic-novel art with a lofi palette. The client cannot change model, size, quality or `n: 1`. Image-model access or organisation verification may be required in your OpenAI account.
- **Three image attempts per UTC day across the whole private server**, including failed attempts. Concurrent duplicate requests reserve only once. Completed pages are cached locally and the server retains up to six cached source/page pairs in the existing Durable Object, in values smaller than its per-value limit. Reopening the same locally cached passage/page is free of another provider call. The server cache necessarily retains those selected passages indirectly as image content and a hash; it does not save the literal source text. No automatic paid retries. Graphic-novel styling alone does not reduce API token charges; fixed medium quality, square size and request quotas bound usage. These are usage limits, not a dollar cap.

Transcripts, cached answers and images live in app-private storage, excluded from library backups and Android backup. Export chapter SRTs and comic JPEGs separately. Changing/reconnecting an audio URI invalidates its transcript match so unrelated audio cannot inherit old text. Each audio source/chapter needs its own timed transcript. Transcription requires speech; silence may return no lines while still marking the processed interval.

Private shared-token authentication remains suitable only for trusted testers. There are **no per-user paid entitlements** in this build. Wider distribution needs individual accounts and per-user metering. Do not share this server token publicly. Public APK downloads contain no secret or automatic access to your AI budget.

Primary API docs for the new routes:
- https://developers.openai.com/api/docs/guides/speech-to-text
- https://developers.openai.com/api/docs/guides/image-generation
- https://developers.openai.com/api/docs/models/gpt-image-2.5-flare
- https://developers.cloudflare.com/workers/wrangler/commands/general/

## Book word guide (1.6.0)

`POST /vocabulary` uses the same bearer authentication. Input: `{title, author, cues, cutoff}`. Title ≤180 characters, author ≤120; only completed cues at/before the supplied cutoff are used, with up to 3,000 characters of text. No audio, descriptions, covers, or full library are sent. Output: `{entries:[{heard, spelling, pronunciation}], cached}` with at most 8 terms / 100 whitespace-separated words across all field values, 60 characters per field, and each heard phrase validated against whole words in the supplied text. The model is server-selected, `store:false`, output ≤800 tokens. This is a spelling suggestion for user review; readable pronunciations are not fed into Vosk. No definitions or plot information are requested. AI mistakes and inferred spoilers are still possible.

The existing Durable Object atomically shares the `usage` text counter with recaps/questions (default20 per UTC day). Reserve before the provider; failures count and identical failed attempts cannot retry that day. Concurrent identical requests return409; completed guides reopen without a provider call. Cache keys hash title/author/heard passage and schema version. At most32 small results are held server-side; original passages are not retained in this cache, but returned terms necessarily contain short source phrases. Local per-book caches avoid repeat calls across later audio sections. No new Worker bindings or migrations. Mock-provider tests exercise authentication, heard cutoff, limits, failed attempts, duplicate reservation and cache behaviour without spending API credits.

## Complete chapter summaries (1.7.0)

`POST /chapter-summary`: same bearer authentication and shared text quota. JSON has a SHA-256 `chapterKey` (no raw file URI), zero-based `chapterIndex`, `durationMs` (1 second–6 hours), listening `cutoff`, `allowUnheard:false`, and all chapter `cues`. The normal app only selects completed chapters. The server rejects future/incomplete chapters without an explicit `allowUnheard:true` flag; the released UI does not expose this override. Every cue must lie within that chapter, ordered by start; total text ≤100,000 characters and request body ≤700,000 bytes. No title, author, surrounding chapters or library metadata is forwarded to OpenAI. One fixed-model Responses call summarises all supplied text, with `store:false`, output cap1,000 tokens and a100-word total across overview/key points. At least one exact source quote is required. A whole chapter is not silently truncated to the old12,000-character passage limit.

The existing Durable Object reserves one shared text attempt atomically, coalesces duplicate requests, and stores up to24 small successful summaries (no full source text), keyed by chapter identity/duration/content hash/schema version. Failures count and block identical retries until the next UTC day. `lookupOnly:true` can only read an existing result: absent, failed and pending entries return404/502/409 without a provider call or reservation. The client uses this mode after a process interruption to avoid automatically regenerating a possibly charged result. No new secrets, bindings, migrations or provider keys in the APK.

### Catch-up library (1.8.0)

`CHAPTER_DAILY_LIMIT=25` is a separate shared private-server attempt allowance for `/chapter-summary`. Existing `AI_DAILY_LIMIT=20` still applies to other text tools; image attempts remain three per UTC day. `/chapter-usage` is an authenticated POST with `{}` that reads counts and reset time without reserving or contacting OpenAI. Failed chapter attempts count; successful cached lookups are free. These limits are shared across the private token, not individual user accounts.

New clients use `formatVersion: 2` with up to four ordered `priorSummaries` from earlier completed chapters. Each contains chapterIndex, overview and points, at most 100 words per recap and 4,500 characters across the array. The provider receives the full current transcript plus this bounded context; it is instructed to cover grounded emotion, stakes, plot and themes, and to use earlier recaps only for supported connections. Evidence must quote the current chapter. Output remains at most 100 words plus quotes and 1,000 output tokens. V1 cache keys are preserved for interrupted older clients. Mocks cover context rejection, quota isolation, concurrent reservations, day rollover and free reopening; tests never require provider credentials.


## Chapter illustration selection (1.9.0)

Authenticated `/companion` action `scene` accepts up to 10,000 already-heard cues and 100,000 characters from a chapter. It reserves one request from the existing 20-request text allowance before one provider call. The fixed prompt returns only contiguous source indices; bounds, ordering and a 2,500-character/1,000-cue selected passage are checked before returning original source cues. The app caches successful choices and requires a separate image confirmation. Invalid or failed provider output counts and never retries automatically.

The existing `comic` action optionally accepts integer `variation` 1–3. Each variation keeps `n: 1`, medium quality, 1024×1024 JPEG, and its own cache identity. Variation 1 retains the existing cache key. The app requests 1–3 pages sequentially, stopping after a failure or leaving; the Durable Object still atomically enforces three shared image attempts per UTC day across every passage and variation. Other image options supplied by clients remain ignored.
