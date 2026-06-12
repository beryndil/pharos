# Pharos — Design Standards (binding on every UI executor)

The bar is **Apple-grade**: calm, clean, professional, content-first. A medication app
earns trust by feeling like a precise instrument, not a marketing funnel. This is a gate,
same standing as PROGRAMMING_STANDARDS.md. UI that reads as generic/AI-generated is a
defect — rework it.

## North star

Restraint over decoration. The interface should look like it was designed by someone who
removed everything that wasn't necessary, then aligned what remained to a grid. If a
pixel isn't doing a job, it's gone.

## Layout & spacing

- Generous whitespace; let content breathe. Crowding reads as cheap.
- One clear primary action per screen. The three dose actions (Taken / Snooze / Skip) are
  unmistakable, large, and never compete with chrome.
- Consistent spacing scale (4/8/12/16/24/32dp). Align to a single grid; nothing floats.
- Clear hierarchy through size, weight, and spacing — not through boxes, borders, and
  dividers everywhere. Prefer whitespace to rules; use a divider only when grouping
  genuinely needs it.
- Edge-to-edge, respect insets; large readable headers (think iOS large-title pattern).

## Type

- System/Material 3 typography, used with discipline — a small, deliberate set of styles
  (display/headline for titles, body for content, label for actions). No more.
- Real hierarchy: one dominant element per screen. Avoid all-caps except tiny labels.
- Numbers (doses, strengths, times) are first-class — set them clearly; they're the data
  the user came for.

## Color

- Mostly neutral: near-black text on near-white (and a true dark mode), with **one**
  restrained accent. Material 3 dynamic color is welcome; the static palette stays quiet.
- **No purple→blue/pink gradients, no glassmorphism, no neon, no gradient blobs.** These
  are the house style of generic AI mockups — banned.
- Color never carries meaning alone (Law 10): every state/warning pairs color with an
  icon and text. Warning ≠ just red.
- Semantic, limited states: a calm neutral for normal, a serious (not alarming) tone for
  DUE, a quiet success for TAKEN. Avoid a rainbow of status colors.

## Iconography & imagery

- Material Symbols (outlined, consistent weight) or a single coherent set. One style, one
  weight. Directional icons set `autoMirrored=true`.
- **No AI-generated images, no stock-art people, no 3D pill renders, no mascots, no
  decorative illustrations.** If an empty state needs art, use a simple, flat,
  single-color line glyph from the icon set — not an illustration.
- The launcher/app icon is simple and geometric (the current "+" mark is the direction):
  flat, legible at small sizes, no gradients-for-the-sake-of-it.
- Empty states: one quiet glyph + one plain sentence + the primary action. Not a poster.

## Copy — no AI-isms

Write like a careful pharmacist, not a growth marketer. Every user-facing string still
lives in `strings.xml` (PROGRAMMING_STANDARDS §7) — this section governs its *voice*.

Banned:
- Marketing fluff and hype: "Empower your health journey," "Take control," "Seamlessly,"
  "Effortlessly," "Unlock," "Supercharge," "Elevate," "Game-changing."
- Emoji in UI copy and notifications. None. (The dose channel is sacred and sober.)
- Exclamation points except where genuinely warranted (≈never). No "Great job!! 🎉".
- Decorative em-dash sprinkling, rhetorical "It's not just X, it's Y," and the
  "Here's the thing:" register. Plain declaratives.
- Fake warmth, anthropomorphizing the app, or chitchat ("Let's get you set up!").
- Hedge-words and filler ("simply," "just," "actually," "basically").

Required:
- Short, concrete, literal. Say what the control does. "Add medication," not
  "Let's add your first medication!".
- Respect Law 3 absolutely: the app reminds/records/displays — it never advises. Warnings
  point outward ("Check with your doctor or pharmacist."), never "skip," "double up," or
  "you should."
- Sentence case for everything except proper nouns. Consistent terminology (a "dose" is
  always a "dose").
- Times and dates rendered by the locale-aware formatters (PROGRAMMING_STANDARDS §7),
  never hand-built strings.

## Motion

- Subtle, fast, purposeful (standard Material motion). Animation clarifies a transition;
  it never performs. No bouncy, springy, attention-seeking effects. Respect
  reduce-motion.

## Accessibility is part of "clean" (Law 10, PROGRAMMING_STANDARDS §8)

Clean and accessible are the same goal: legible at large font scales with no truncation,
≥48dp targets, full TalkBack labels, strong contrast in both themes. A layout that breaks
at 200% font scale is not clean — it's fragile.

## Review check (every UI slice)

Before a UI slice is DONE, the executor self-checks: Would this screen look at home in a
first-party iOS/Android system app? Is there exactly one primary action? Any gradient
blob, emoji, stock image, or marketing sentence? Does it hold up in dark mode, at 200%
font, and under TalkBack? If any answer is wrong, rework before commit.
