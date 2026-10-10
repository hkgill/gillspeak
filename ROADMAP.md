# Roadmap

What's planned for gillspeak, as a checklist. The rule for everything here: **it runs on the device.** Nothing a person says, types or watches goes to the internet unless they choose a cloud engine.

Background, experiments and architecture for the on-device AI work: [docs/android-on-device-ai.md](docs/android-on-device-ai.md).

## Done (Android)

- [x] Voice commands from the side button: gillspeak as the digital assistant, the see-through listening screen with the edge wave, and rules for apps, timers, alarms, torch, media, directions, search, texts and calls
- [x] "Set up an alarm for three o'clock": more ways to ask, and times without am or pm go to the next one
- [x] Gemma 4 E2B on the phone understands commands the rules miss (about 0.4 s on a Galaxy S25); anything it chose waits for a tap
- [x] Gemma polishes Local dictation behind the existing gate and validator (off by default)
- [x] Validator rejects output in a writing system the input didn't use
- [x] Gemma download in the app, pinned and checked against its SHA256

## Next: anti-doomscroll ("Mindful mode")

Stop endless scrolling of YouTube Shorts, Instagram Reels and TikTok. Plain rules on accessibility events: no model needed for the core, and nothing leaves the phone.

Decide first:

- [x] How strict at the limit: a firm stop with one "5 more minutes" (chosen over a nudge that can be dismissed)
- [x] One timer shared across all three apps
- [x] Five minutes away counts as a real break and resets the timer
- [x] The name: "Mindful mode"
- [ ] What "contentful mode" means: judging whether what's being watched is worthwhile (needs Gemma), or the on/off switch

Phase 1, detection only:

- [x] A separate accessibility service ("gillspeak focus") that only watches YouTube, Instagram and TikTok, so the bubble's privacy description stays accurate
- [x] Spot the feeds from layout ids, not content: the Shorts player in YouTube, the Reels viewer in Instagram, all of TikTok; normal videos, the feed, search and messages stay free (Reels and TikTok checked on a phone; Shorts still to check)
- [x] A debug log of what was detected and for how long
- [x] Check it against a day of normal use, then take the service out of debug-only builds
- [x] Unit tests for the timer: shared time across apps, the break that resets it, the extension, the cool-off

Phase 2, the stop:

- [x] At the limit (10 minutes by default), a full-screen gillspeak card over the feed: "Leave" goes home; "5 more minutes" allowed once, after a 10-second wait
- [x] After leaving, the feeds stay paused for 30 minutes; the rest of each app keeps working

Phase 3, the switch and stats:

- [x] A Mindful mode page with the time limit, from 30 seconds to 30 minutes
- [ ] A Mindful mode switch in the app (off by default), with friction to turn it off, such as a 30-second wait
- [ ] Today's minutes per app and how often it stopped you, kept on the phone

Phase 4, with Gemma:

- [ ] Judge each new video from one screenshot and its caption: worthwhile or mindless (log-only first, then counting); screenshots are checked and discarded, never stored or sent
- [ ] A spoken reason for "5 more minutes", judged by Gemma
- [ ] A stop card that fits the moment ("It's 11:40 pm and you've watched 14 minutes of Reels")

Later:

- [ ] Different limits by time of day (stricter at night), a daily total, more apps (Facebook Reels, Snapchat Spotlight)

## Voice commands and Gemma: still to do

- [ ] Check the "Gemma on this phone" settings card on a phone
- [x] Merge the voice-commands work
- [ ] Merge the Gemma work
- [ ] Measure memory, battery and heat with Parakeet and Gemma both loaded
- [ ] Try Gemma's NPU build for the Snapdragon 8 Elite (`sm8750`) on the Galaxy S25: likely faster and lighter on the battery
- [ ] Two commands in one sentence ("set an alarm for 7 and turn the torch off"); today only the first runs
- [ ] Better message wording ("say sorry" should make the message apologise), with a better prompt or Gemma 4 E4B
- [x] Calendar events: the new-event screen opens filled in (title, day, time), and you save it
- [ ] More commands: reminders with a note, WhatsApp and email, "play X on Spotify", volume, screenshot and lock, settings panels
- [ ] "Send it" while dictating: tap the app's Send button
- [ ] Your own commands: a phrase that opens an app or a link
- [ ] Safe commands from the lock screen (timer, torch, alarm, media)
- [ ] Line the edge wave up with the side button on each phone (the Fold5's sits elsewhere)
- [ ] Words appearing while you talk: needs a streaming speech model
- [ ] Mixed Punjabi and English offline, with Gemma's audio input
- [ ] Bring the new-script validator check to the desktop app

## Open questions

- [ ] The cloud engines (Gemini, Groq) send audio off the phone. They stay as a labelled opt-in for now; revisit against the on-device rule.
