# On-device AI for gillspeak on Android: Parakeet + Gemma 4 E2B

Status: **decided, not built yet** (October 2026). This records the experiments, why Gemma 4 E2B was picked, and the architecture it will sit in.

**Decision:** keep **Parakeet** for speech-to-text and add **Gemma 4 E2B** (through Google's LiteRT-LM runtime) for understanding. Both run on the phone: nothing the user says, types or watches goes to the internet.

## Why a second model

Parakeet TDT 0.6B v3 only turns speech into text. The "understanding" in voice commands is rules (`Commands.kt`), and rules miss ordinary phrasing. The first real-world test of side-button commands failed on *"Set up an alarm for three o'clock"*: the rules knew "set an alarm" but not "set **up** an alarm". The rule was fixed, but every new phrasing needs another rule. A small language model handles phrasing; the rules stay for what they're good at.

## Models considered

| Model | Size | Notes | Verdict |
| --- | --- | --- | --- |
| FunctionGemma 270M | ~290 MB, ~550 MB RAM | Built for function calling; ~58% accurate out of the box, ~85% after fine-tuning (Google's figures) | Text only, so it can't see a screen; would need fine-tuning |
| **Gemma 4 E2B** | **2.6 GB** | Text, image and audio input; LiteRT-LM build for Android; up to 32K context in the Gallery build | **Picked** |
| Gemma 4 E4B | 3.7 GB | Better reasoning; about 3× slower than E2B | Too slow and power-hungry for commands or watching a feed |
| Gemma 3n E2B / E4B | 3.7 / 4.9 GB | Previous generation | Bigger than Gemma 4 E2B for less |
| Gemma 3 1B | ~0.6 GB | Text only, weaker | Too limited |
| Qwen 2.5 1.5B, DeepSeek-R1 distill 1.5B | 1.6–1.8 GB | Not Gemma; no audio or image input | Not considered further |
| Gemini Nano / Gemma 4 in AICore | No download | System-managed; Gemma 4 there was a developer preview | Not available to rely on yet |

## Experiment: Gemma 4 E2B on a Galaxy S25

### Method

- Google's **AI Edge Gallery** app (Play Store), **AI Chat**, model **Gemma-4-E2B-it**, running on the **GPU**.
- A first attempt in the Gallery's **Agent Skills** screen answered "No relevant skills found" to everything: that screen matches messages against its own built-in skills and ignores a custom prompt. AI Chat is the right place for this test.
- One setup prompt, then ten test phrases sent one at a time. Times are the Gallery's own per-reply figures (model already loaded).

Setup prompt:

```text
You turn spoken phone commands into JSON for a voice assistant. For every message I send after this one, reply with only one JSON object and nothing else.

Allowed actions and their fields:
- open_app: {"action":"open_app","name":string}
- timer: {"action":"timer","seconds":number}
- alarm: {"action":"alarm","hour":0-23,"minute":0-59}
- torch: {"action":"torch","on":true|false}
- media: {"action":"media","do":"play"|"pause"|"next"|"previous"}
- text: {"action":"text","contact":string,"message":string}
- call: {"action":"call","contact":string}
- navigate: {"action":"navigate","place":string}
- search: {"action":"search","query":string}
- several: {"action":"several","steps":[ ...actions above... ]}
- none: {"action":"none"} when it is not a command.

It is 10:15 am now. If a time has no am or pm, use the next time it comes round. Write text messages the way the person would type them.

Reply "ready" now.
```

### Results

| Phrase | Gemma 4 E2B replied | Result | Time |
| --- | --- | --- | --- |
| Set up an alarm for three o'clock | `alarm 15:00` | ✅ (the phrase the rules missed) | 427 ms |
| Can you wake me before my 3 pm meeting, like 2:30 | `alarm 14:30` | ✅ | 485 ms |
| Chuck on the torch would you | `torch on` | ✅ | n/a |
| Fire up Spotify | `open_app Spotify` | ✅ | 395–403 ms |
| How do I get to Bondi from here | `navigate Bondi` | ✅ | 369 ms |
| I think the meeting went well today | `none` | ✅ dictation is not taken as a command | 256 ms |
| Set an alarm for 7 and turn the torch off | `several: alarm 7:00, torch off` | ⚠️ split correctly, but 7 am instead of the next 7 (7 pm) | 866 ms |
| Skip this song, I hate it | `{"action":"media","next"}` | ⚠️ right meaning, invalid JSON (no `"do":`) | 308 ms |
| Text Sam that I'll be ten minutes late, and say sorry | `text Sam: "I'll be ten minutes late, and say sorry"` | ⚠️ right action and contact; copied the instruction into the message | 701–866 ms |
| Give me twenty minutes on the clock for the pasta | `timer 120` | ❌ should be 1200 | 416 ms |

### What it showed

- **Intent: 10/10.** Gemma understood every request, including slang, a buried time, two commands in one sentence, and plain speech that isn't a command. The rules alone get one of these ten.
- **Exact values: 3 slips in 10.** Arithmetic (20 minutes → 120 s), am/pm, and following an instruction inside a message. These are the mistakes that matter: a timer that's quietly wrong is worse than "I didn't understand".
- **Format: 1 slip in 10.** Invalid JSON once. LiteRT-LM's constrained decoding removes this class of error.
- **Speed: 0.26–0.87 s** on the GPU. With Parakeet's ~0.2 s for a short command, a command that needs the model is understood in about a second.

## Why Gemma 4 E2B

1. **Understanding was right every time,** at a speed that suits the side button.
2. **It can see and hear,** which the anti-doomscroll roadmap needs (judging what's on screen, a spoken "why 5 more minutes?"). FunctionGemma is text only, so it would have meant a second model later.
3. **E4B's extra reasoning isn't needed** once our own code does the arithmetic (see below), and it's ~3× slower and heavier on the battery, which matters for anything that runs while scrolling.
4. **Runs fully on the phone,** in line with the repo's rule that nothing goes to the internet.

### Why not let Gemma replace Parakeet

Gemma 4 E2B accepts audio, so it could transcribe too. It doesn't replace Parakeet:

| | Parakeet | Gemma 4 E2B audio |
| --- | --- | --- |
| Longest clip | 5 minutes (the app's cap) | 30 seconds |
| Speed | 0.2 s for a 3.5 s command | Slower: it writes the text out word by word |
| Behaviour | Literal transcription | A general model can tidy up or invent words |
| Languages | English + 24 European | Many more, including Punjabi |

Parakeet stays the ear. Gemma's audio input remains an option for a later "mixed language" mode (Punjabi and English), the one thing Parakeet can't do offline.

## Architecture

```
 voice ──► Parakeet (on phone, ~0.2 s, any length) ──► text
                                                        │
          ┌─────────────────────────────────────────────┴──────────────┐
    side button (commands)                               bubble / keyboard (dictation)
          │                                                             │
    Commands rules ── match ──► run (instant)                 Rules clean-up (as now)
          │ no match                                                    │
    Gemma 4 E2B, constrained to the command schema,          Gate: worth polishing?
    returns the action + the exact words said                           │ yes
          │                                                  Gemma 4 E2B polish (clean_v2 prompt)
    Commands.duration / clockTime / nextOccurrence                      │
    turn the words into numbers                              Validate ── fails ──► rules text
          │
    confirmation card ──► tap ──► Actions runs it
```

### Rules that keep it safe

- **Rules first.** `Commands.parse` runs before the model. Common commands stay instant and work with no model downloaded.
- **Gemma picks, our code computes.** The schema asks for the action and the exact words (`{"action":"timer","duration":"twenty minutes"}`, `{"action":"alarm","time":"7"}`); the unit-tested `Commands.duration`, `Commands.clockTime` and `Commands.nextOccurrence` produce the numbers. This removes all three value mistakes from the experiment.
- **Constrained decoding.** Output is limited to the command schema, so it is always valid and never names an action that doesn't exist.
- **A fixed list of actions.** `Actions` only knows the commands on the list; the model can't add one.
- **Anything the model chose asks first.** A card shows what it understood ("Alarm 7:00 pm?") and waits for a tap.
- **Dictation keeps its validator.** Polish goes through the existing `Gate` and `Validate` (as the Groq path did); if the polished text fails, the rules-cleaned text is used.

### Running both models

| | Parakeet | Gemma 4 E2B |
| --- | --- | --- |
| Storage | 640 MB | 2.6 GB, optional download with a SHA256 check like Parakeet's |
| Memory when loaded | ~0.7 GB | ~2–3 GB |
| Loaded | while dictating or listening | only when the rules miss a command (or polish is on), unloaded after ten idle minutes (as `LocalAsr.scheduleRelease` does) |
| Runs on | CPU (sherpa-onnx) | GPU (LiteRT-LM) |

Both together fit comfortably on 12 GB phones (Galaxy S25, Z Fold5).

## Roadmap

1. **Command fallback.** Add LiteRT-LM and a `LocalLlm.kt` (download, warm-up, release; reuse `LocalAsr`'s download code). During development the model is pushed with adb. A debug compare log records, for each spoken command: Parakeet's text, the rules result, Gemma's result, and timings.
2. **Local dictation polish.** Gemma with the desktop `clean_v2` prompt, behind `Gate` and `Validate`.
3. **Anti-doomscroll, rules only.** A separate accessibility service spots YouTube Shorts, Instagram Reels and TikTok, counts real scrolling, and stops it at 10 minutes. No model needed.
4. **Anti-doomscroll, with the model.** On each new video, one screenshot and the caption go to Gemma to judge worthwhile versus mindless (log-only first, then counting); a spoken intention check for "5 more minutes"; a stop card that fits the moment. Screenshots are checked and discarded, never stored or sent.
5. **Maybe:** Gemma's audio input for mixed Punjabi and English speech.

## Open questions

- **Cloud engines.** Decided: removed. The Android app had optional Gemini and Groq engines that sent audio to those services; they're gone, and the network is used only for the model downloads (which send nothing of the user's).
- **"Contentful mode"** for anti-doomscroll: judging whether what's being watched is worthwhile (needs the model), or the on/off switch for the feature?
- **Measured on the real path.** The figures above come from the Gallery. Step 1's compare log will measure Parakeet + Gemma inside gillspeak, on real voice input.

## Sources

- [Gemma 4 announcement (The Next Web)](https://thenextweb.com/news/google-gemma-4-open-models-apache-2-launch)
- [Gemma 4 in the AICore developer preview (Android Developers)](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview)
- [Gemma audio understanding: 30-second limit, preprocessing](https://ai.google.dev/gemma/docs/capabilities/audio)
- [LiteRT-LM overview](https://ai.google.dev/edge/litert-lm/overview)
- [LiteRT-LM and Gemma 4 (InfoQ)](https://infoq.com/news/2026/06/google-litertlm-gemma4)
- [FunctionGemma (Google blog)](https://blog.google/technology/developers/functiongemma/)
- [On-device function calling in AI Edge Gallery](https://developers.googleblog.com/on-device-function-calling-in-google-ai-edge-gallery/)
