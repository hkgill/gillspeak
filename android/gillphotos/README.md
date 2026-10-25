# gillphotos (prototype)

Search the photos on your phone the way Google Photos does, with everything running on the phone: *"dog on a beach"*, *"receipts"*, *"Sam at the beach last summer"*. Nothing leaves the phone. Why these models, and what was measured elsewhere: [docs/gillphotos-research.md](../../docs/gillphotos-research.md).

**Status: an untested prototype.** It builds in CI. The face code has been checked against OpenCV on a desktop, but the app hasn't run on a phone yet. It's for trying the idea and measuring it on a real library.

## What it does

- **Search by meaning.** Each photo becomes an EmbeddingGemma 2 vector, on the GPU. A search becomes one too, and the closest photos come first.
- **People.** YuNet finds faces and SFace turns each one into a vector. Similar faces are grouped on the phone. Long-press a group to name it; named people work in searches ("Mum 2023").
- **Times, by rules.** "yesterday", "last week", "March", "2023", "last summer" (December to February in Australia) narrow the search before meaning is matched. The rules live in `core/Queries.kt`.
- **Documents.** The Documents, Receipts, IDs and cards, Notes and Whiteboards chips are searches by meaning. Screenshots come from their folder.
- **More like this** on any photo.
- **Ask Gemma about a photo** (optional, 2.6 GB). Gemma 4 E2B describes the photo or reads its text, and shows how long it took.
- **It measures.** The status line shows milliseconds per photo for decoding, EmbeddingGemma and faces. These are the numbers the research is missing.

## Try it

1. Install the APK: download the `gillphotos-debug-apk` artifact from a CI run, or build it (below). Each CI build is signed with a different debug key, so uninstall the old one first.
2. Open gillphotos and tap **Allow photos**.
3. **Get the search model.** Google's EmbeddingGemma 2 files on Hugging Face need a sign-in and accepting Google's terms, so the app can't download one itself. On the phone, tap **Get it**, sign in, and download the `.litertlm` file from [litert-community/embeddinggemma-2-text-vision-440m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm). The full 740M model also works. Then tap **Import file** and pick it. The app checks that it reads both text and pictures. It shows the file's SHA256, so a good file can be pinned later.
4. Tap **Index photos**. It runs with a notification, newest photos first, and pauses if the phone gets hot. Faces work without the search model.
5. Search, name people (long-press a face), and open a photo for **More like this** and **Ask Gemma**.

The **⋯** menu changes how much detail EmbeddingGemma sees per photo (70 to 560 image tokens). It also downloads or deletes Gemma, groups faces again, and removes the search model.

### Worth measuring

- Milliseconds per photo on the status line, with 70 and with 280 tokens.
- How good searches are on about 30 real queries, with and without the `task: search result | query:` prompt (`Embedder.QUERY`).
- Whether face groups are clean, which tunes the thresholds in `core/People.kt` (join 0.45, merge 0.5).
- Gemma's time per question with a photo.

## Privacy

- Photos, vectors, faces and names stay in the app's private storage. Android backups are off.
- The network is used only for the optional Gemma download. The search model is imported from a file, and the face models are inside the APK.
- Face vectors are biometric data. Removing the app removes them.

## Models

| Model | Job | Licence | How it gets there |
| --- | --- | --- | --- |
| [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) (2023mar) | Finds faces | MIT | Downloaded at build time, pinned by commit and SHA256, packed into the APK |
| [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) (int8) | Tells people apart | Apache 2.0 | Same |
| EmbeddingGemma 2 | Search by meaning | Apache 2.0 (gated download) | Imported by the user |
| Gemma 4 E2B | Questions about a photo | Apache 2.0 | Optional in-app download, pinned like gillspeak's |

## Build

gillphotos is a Gradle build of its own, next to gillspeak's. LiteRT-LM's embedding engine (0.17+) is built with Kotlin 2.4, and gillspeak stays on Kotlin 2.2. From `android/`:

```bash
./gradlew -p gillphotos :core:test assembleDebug
adb install -r gillphotos/app/build/outputs/apk/debug/app-debug.apk
```

`core/` is plain Kotlin, tested on any JVM. It holds the YuNet decoding and SFace alignment (ported from OpenCV's FaceDetectorYN and FaceRecognizerSF), face grouping, the photo vectors and the search rules. `app/` is the Android app, with no AndroidX.

## Not yet

- Text recognition (PP-OCRv5) for exact words in documents: the chips search by meaning only.
- Place names from GPS, videos, duplicates.
- Gemma understanding longer searches: the rules handle people and times for now.
- Indexing only while charging.
