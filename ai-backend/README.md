# Morrow AI reading lab — private test backend

This is a real OpenAI Responses API integration, not a ChatGPT website wrapper. The Android 1.3.0 reading lab sends an explicitly chosen short passage and question to this backend. It can recap the passage or answer questions using quoted evidence. It does **not** transcribe audiobooks, infer listening position, generate whole-book recaps from titles, or upload the library.

## What GitHub does

GitHub stores the app/backend code and runs the automated checks. It does not need your OpenAI key to build or test. GitHub Actions is not the live mobile API server. GitHub Models was retired on 30 July 2026: https://docs.github.com/en/github-models.

The runtime here is a Cloudflare Worker with a SQLite-backed Durable Object for a global request allowance. Your existing Morrow distribution website can remain static. A separate Worker provides the private `/ai` URL.

## Connect your API account

Use your own Cloudflare account and a recent Node.js (22+):

```sh
cd ai-backend
node --test test/*.test.mjs
npx wrangler@4 login
npx wrangler@4 secret put OPENAI_API_KEY
npx wrangler@4 secret put MORROW_TRIAL_TOKEN
npx wrangler@4 deploy
```

Enter the OpenAI API key only at the first secret prompt. For the second prompt, generate a separate random test token of at least 24 characters, for example with `openssl rand -hex 32`, and save it privately. That token grants access to this limited test server; it is **not** your provider key. Never commit either secret, paste an API key into the app, or add secrets to public issues. Wrangler sends them to Cloudflare’s secret store; this repository contains no real secret values.

Review `wrangler.jsonc` before deployment. It defaults to `gpt-4.1-mini`; set `OPENAI_MODEL` to an available Responses-compatible model on your account that supports Structured Outputs. The model is selected on the server, not by app users. Review your Cloudflare plan and OpenAI billing before deploying. ChatGPT subscriptions and API billing are separate.

If this is the first Worker under your account, complete any setup prompted by Wrangler for your workers.dev subdomain. It will print a URL such as `https://morrow-ai-trial.YOUR-SUBDOMAIN.workers.dev`. In Morrow → Settings → AI reading lab:

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
- No audio, book titles, bookmark notes, file URIs or full library are included. Server application logging is disabled, but infrastructure providers may still process operational connection data.

## Private trial, not paid production

This shared access-token setup is for the owner and a small trusted testing group. Rotate the token in Cloudflare to revoke access; use Forget connection on the phone to remove it locally. Before a wider AI launch, add individual accounts, per-user quotas/billing, abuse controls, appropriate privacy/consent disclosures, and measured evaluation on licensed transcripts with listening-position boundaries. Do not embed the shared test token in a public APK.

Primary docs:
- https://developers.openai.com/api/reference/overview
- https://developers.openai.com/api/docs/guides/text
- https://developers.openai.com/api/docs/guides/structured-outputs
- https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform
