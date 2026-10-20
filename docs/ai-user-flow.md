# Narrumo 1.9.1: AI user flow

The bottom navigation is **Library · Bookmarks · AI · Settings**. Settings and book details link to the same AI section. The listening screen retains a small AI companion card and a one-tap last-chapter recap on Library.

```mermaid
flowchart TD
    L[Listening screen] --> R[Chapter recap]
    R --> S[Chapter studio]
    AI[AI tab: choose book and chapter] --> S
    Settings --> AI
    S --> T[Transcript and suggested passage]
    T --> P{Use this passage?}
    P -->|Keep| N[Choose 1–3 pictures]
    P -->|Select another| M[Select passage in transcript]
    P -->|Let AI choose| A[AI suggests a source passage]
    M --> T
    A --> T
    N --> Q[Review exact passage, count and allowance]
    Q --> G[Confirm and generate]
    G --> Saved[Automatically saved with the chapter]
    Saved --> V[Review pictures]
    V --> C[Chapter gallery]
    V -->|Generate another: uses allowance| T
    AI --> Words[Book words and pronunciation]
    AI --> Ask[Ask about a passage]
    AI --> Connection[AI connection settings]
    S --> L
    Auto[Android Auto] --> B[Browse or resume]
    B --> Play[Shared audio playback and progress]
```

The chapter studio places the illustration panel immediately beneath a scrollable transcript. A free suggestion uses only available, already-heard chapter text; its passage preview is visible and the source lines are highlighted. Long-press text → **Use for illustration**, or select text and use the visible button, to replace it. **Let AI choose a key moment** is an explicitly confirmed request using one shared text attempt. AI returns only validated cue indices, so it cannot supply an invented passage.

Choose **Keep this passage** to reveal picture count options. **Select another passage** scrolls back to the transcript. Choose 1–3 pictures, then review the exact passage, count and last checked shared allowance before confirming. Each picture is one medium-quality, four-panel graphic-novel page. Multiple pictures use different framing of the same selected moment. Every successful page is saved with its chapter before opening the preview, so closing it loses nothing. The preview links to Chapter gallery and **Generate another · uses allowance**, which returns to passage selection and requires a fresh review. An identical cached page reopens free; choose new text for a new scene. Pages are requested sequentially and cached individually; failure stops the remaining batch, without automatic retries. Leaving stops pages not yet started. Saved pictures link directly to their chapter even if no recap exists yet. Rewinding hides unavailable source passages and pictures.

The private server shares 25 chapter summary attempts, 20 other text attempts, and 3 image attempts per UTC day. Failed generation attempts count. Reading saved content, free passage suggestions and local transcription use no AI allowance. Android transcription remains English and may mishear names; review the source passage before creating pictures.

Android Auto uses its standard media interface for library browsing and playback. AI transcript, passage selection and illustration controls remain in the phone’s AI section.
