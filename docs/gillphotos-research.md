# gillphotos: an offline Google Photos for Android (research)

Status: **research done; prototype in [`android/gillphotos`](../android/gillphotos/README.md)** (October 2026), not yet run on a phone. This records what's possible, which models fit on a phone, and a proposed plan.

**The question:** can we make a gallery app, **gillphotos**, that searches your photos the way Google Photos does (*"passport"*, *"receipt from Bunnings"*, *"Sam at the beach"*, *"the whiteboard from Tuesday's meeting"*) with everything running on the phone? Can the Gemma that gillspeak already runs do it? And which other models on Hugging Face fit on a phone?

## Short answer

**Yes, but not with Gemma alone.** Gemma 4 E2B and E4B are *generators*: they can describe a photo and read the text in it, but they take one to a few seconds per photo, and they can't tell who is who. Indexing a whole library needs small, fast models that each do one job. Gemma is best kept for understanding what you search for, and for answering questions about the few photos you found.

| Job | Model | Size | Licence | Speed on the phone |
| --- | --- | --- | --- | --- |
| Search by description ("dog on a beach", "birthday cake") | **EmbeddingGemma 2**, text + vision (440M) | ~0.3–0.6 GB | Apache 2.0 | ~120–150 ms per photo on the GPU (S26 Ultra, S25) |
| Faces: find them | **YuNet** (OpenCV Zoo) | < 1 MB | MIT | milliseconds |
| Faces: who is who | **SFace** (OpenCV Zoo, a MobileFaceNet) | ~37 MB, ~10 MB int8 | Apache 2.0 | milliseconds per face |
| Reading text (documents, receipts, screenshots) | **PP-OCRv5 mobile** (PaddleOCR) | ~5 MB + ~16 MB | Apache 2.0 | well under a second per photo (estimate) |
| Understanding the search, answering questions | **Gemma 4 E2B** (already in gillspeak) | 2.58 GB | Apache 2.0 | ~0.4 s for a query; ~1–2 s per photo it looks at |

The search core (EmbeddingGemma 2, faces, OCR) is under 700 MB of downloads and would index 10,000 photos in about an hour while the phone charges. Gemma is an optional extra on top, as it is in gillspeak.

**"Gemma 4 B"**: assuming this means **Gemma 4 E4B** (3.66 GB), it is better at documents than E2B (OmniDocBench edit distance 0.181 against 0.290, lower is better) but about 2.5× slower (22 against 52 tokens a second on an S26 Ultra GPU). It's not needed for indexing. It could be an optional choice for reading documents ("when does my passport expire?"), where it's asked about a few photos at a time.

## What Gemma can and can't do here

| | Gemma 4 E2B / E4B | Why |
| --- | --- | --- |
| Describe a photo, tag it ("beach, two people, sunset") | ✅ but slow | ~1–2 s a photo (E2B) is 3–6 hours for 10,000 photos, and the description is only as searchable as its words |
| Read text in a photo | ✅ good, slow | it writes the text out token by token: a receipt takes several seconds |
| Search by meaning | ❌ not directly | a chat model doesn't produce vectors to compare; **EmbeddingGemma 2** (same family, same LiteRT-LM runtime) does |
| Recognise the same person across photos | ❌ | vision-language models aren't trained to match faces, and comparing every pair of faces with a 2.6 GB model would never finish. It can say "two people smiling", not "this is Sam" |
| Understand the search ("Sam at the beach last summer") | ✅ fast | text in, a short JSON answer out: the same job it does for voice commands |
| Answer a question about a few photos ("what's the total on this receipt?") | ✅ | a handful of photos at a time is fine |

## Models found, by job

Hugging Face itself was blocked from the research machine, so the figures come from model cards and Google's pages as quoted in search results, from the LiteRT-LM source on GitHub, and from other open-source apps. Speeds marked *(est.)* are worked out from Google's published text-only figures, not measured with images.

### Search by description: image and text in one vector space

Each photo becomes a vector once, when it's indexed. A search's text becomes a vector too, and the closest photos win. This is how Google Photos, Ente and Immich do "magic search".

| Model | Size | Licence | Notes | Verdict |
| --- | --- | --- | --- | --- |
| **EmbeddingGemma 2**, text + vision (`litert-community/embeddinggemma-2-text-vision-440m-litert-lm`) | 440M parameters: a 270M text model + a 170M vision encoder | Apache 2.0 | Released 6 October 2026. Runs on **LiteRT-LM**, the runtime gillspeak already uses (`EmbeddingEngine` from v0.17.0, EmbeddingGemma 2 from v0.18.0). 768-d vectors, which can be cut to 512, 256 or 128 (Matryoshka). 70 to 1,120 image tokens (default 280), but the LiteRT build has only 70 and 140. A plain, ungated 388 MB download, with NPU builds for Snapdragon 8 Elite (SM8750) and others. Reported per image at 70 tokens: 119 ms on an S26 Ultra GPU, 175 ms on its CPU, 49 ms on a Pixel 11 Pro TPU; one community build measured ~148 ms on a **Galaxy S25 GPU**. ~125 MB of CPU memory with vision | **Pick.** Same family and runtime as gillspeak's Gemma |
| EmbeddingGemma 2, full (740M) | adds a 300M audio encoder | Apache 2.0 | Also embeds audio and video frames (29 images or 58 frames in its 8K context) | Later, for searching videos |
| SigLIP 2 base patch16-224 (`litert-community/SigLIP2-base-patch16-224`) | ~185 MB FP16 | Apache 2.0 (Google's checkpoints) | LiteRT build runs as one graph on the GPU; 768-d. The text tower is separate and needs its own conversion | **Fallback** if EmbeddingGemma 2 disappoints |
| Apple MobileCLIP / MobileCLIP2 (S0–S4) | small, fast | Apple's research licence (`apple-amlr`) | Used by Ente's search and the iOS app Queryable | Licence doesn't suit a public open-source app |
| OpenAI CLIP ViT-B/32 | 151M parameters | MIT | What PicQuery (Android) and Immich use by default | Older and weaker; no reason to pick it now |

### Faces: find them, then tell people apart

Finding faces and recognising people are two models. The second turns each face into a vector, and faces of the same person end up close together.

| Model | Size | Licence | Notes | Verdict |
| --- | --- | --- | --- | --- |
| **YuNet** (OpenCV Zoo) | < 1 MB | MIT | Finds faces from about 10×10 to 300×300 pixels, so it runs on a ~640 px copy of each photo. Gives 5 landmarks (eyes, nose, mouth corners) for lining a face up. WIDER Face AP 0.88 / 0.87 / 0.75 (easy / medium / hard) | **Pick** for finding faces |
| **SFace** (OpenCV Zoo) | ~37 MB, or ~10 MB int8 | Apache 2.0 | A MobileFaceNet trained with the SFace loss; 99.40% on LFW (99.32% int8). 128-d vectors from a 112×112 lined-up face. Made to pair with YuNet's landmarks | **Pick** for telling people apart |
| MediaPipe Face Detector (BlazeFace) | ~200 KB | Apache 2.0 | Tuned for faces near the camera (selfies, video calls); weaker on group photos | Backup |
| InsightFace (SCRFD, ArcFace, `buffalo_l`) | 300 MB pack | InsightFace's pretrained models are for non-commercial research | What Immich uses; the best accuracy | Licence rules it out for a public app |
| YOLOv5-Face | small | GPL-3.0 | Qualcomm won't host it for licensing reasons | Avoid |
| GhostFaceNets, EdgeFace, AdaFace | small | Code is MIT/Apache; the weights are trained on research-only datasets | Check each before using | Not needed |

Almost every face model in this field was trained on academic datasets (MS1M, WebFace, CASIA-WebFace). OpenCV publishes YuNet and SFace under MIT and Apache 2.0, which is the clearest licensing found.

### Reading text: documents, receipts, screenshots

| Model | Size | Licence | Notes | Verdict |
| --- | --- | --- | --- | --- |
| **PP-OCRv5 mobile** (PaddleOCR) | detector 4.6 MB + recogniser ~16 MB | Apache 2.0 | ONNX builds run with RapidOCR's pre- and post-processing, or with ncnn. Handles photos of paper as well as screenshots. One model reads Chinese, English and Japanese; other scripts have their own models | **Pick** |
| Google ML Kit Text Recognition v2 | ~4 MB per script, bundled | Free, closed source | Recognition is on the device, but ML Kit sends usage metrics to Google and can fetch model updates | Breaks gillspeak's rule that the app talks to nothing but model downloads |
| Tesseract | a few MB per language | Apache 2.0 | Made for scanned pages; weak on photos at an angle | No |
| Gemma 4 E2B / E4B | (already there) | Apache 2.0 | Reads well, including layout and handwriting, but writes the text out at 52 / 22 tokens a second | For questions about a few documents, not for indexing |

### Describing photos: small vision-language models

Only needed for captions or for questions about a photo. All of these are slow per photo.

| Model | Size | Licence | Speed reported | Notes |
| --- | --- | --- | --- | --- |
| **Gemma 4 E2B** (in gillspeak) | 2.58 GB | Apache 2.0 | ~1–2 s per photo for a few tags *(est.)*; 3,808 / 52 tokens a second prefill / decode on an S26 Ultra GPU | Image budgets of 70, 140, 280, 560 or 1,120 tokens: low for tags, high for small text. LiteRT-LM takes images as `Content.ImageFile` / `Content.ImageBytes` with a `visionBackend` |
| Gemma 4 E4B | 3.66 GB | Apache 2.0 | ~2–3 s per photo *(est.)*; 1,293 / 22 tokens a second on an S26 Ultra GPU | Better documents and reasoning |
| Qwen3-VL-2B | ~1.6 GB (GGUF) | Apache 2.0 | **~15 s per photo** on a Galaxy S24 Ultra (llama.cpp, from the open-source Photosorter-for-Android app) | Needs llama.cpp |
| SmolVLM2-500M | ~550 MB | Apache 2.0 | ~3× faster than Qwen3-VL-2B (same app) | Good one-line captions; ignores a fixed answer format |
| MiniCPM-V 4.6 | 1.3B parameters | Check | Demonstrated on Android phones | Not tested |

Gemma 4 E2B on LiteRT-LM's GPU path is the fastest describer found, and gillspeak already downloads it. Still, captioning every photo isn't needed once the embeddings exist.

## Time and space for 10,000 photos (Galaxy S25)

| Step | Per photo | 10,000 photos | Index size |
| --- | --- | --- | --- |
| Read date, place and camera (MediaStore, EXIF) | < 1 ms | seconds | tiny |
| EmbeddingGemma 2 image vector, 70 tokens, GPU | ~150 ms | **~25 min** | 256-d int8: 2.5 MB (768-d float: 30 MB) |
| YuNet finds faces | ~5–15 ms *(est.)* | ~2 min | n/a |
| SFace, per face (~1.5 faces a photo) | ~5–20 ms *(est.)* | ~5 min | 128-d: ~8 MB |
| PP-OCRv5, only photos with text (say 2,000) | ~0.3–1 s *(est.)* | ~10–30 min | a few MB of full-text index |
| *Optional:* Gemma 4 E2B tags | ~1–2 s *(est.)* | 3–6 hours | small |

So the core index takes **about an hour** of charging time, and a few new photos a day take seconds. Search itself is a comparison against every vector: 10,000 × 256 bytes is a few milliseconds of work, so there's no need for a vector database below roughly 100,000 photos.

## How it would work

```
 INDEXING (while charging, in small batches, paused if the phone gets hot)

 MediaStore ──► new or changed photo ──► ~640 px copy
                                          │
          ┌───────────────────┬───────────┴────────┬────────────────────────┐
   EmbeddingGemma 2       YuNet: faces        Text in it?              EXIF: date, GPS
   image vector           │                   │ yes                      │
          │               line up (5 points)  PP-OCRv5 ─► words        offline place names
          │               SFace: face vector  │                        (a cities list, no Geocoder)
          │               │                   │                          │
          ▼               ▼                   ▼                          ▼
   SQLite on the phone: photo vectors · faces ─► people (groups you name)
                        · full-text index (FTS5) · dates and places

 SEARCH

 typed, or spoken (Parakeet, gillspeak's ear)
          │
 rules, then Gemma 4 E2B constrained to a schema:
 "Sam at the beach last summer" ─► {"people":["Sam"], "looks_like":"at the beach", "when":"last summer"}
          │
 our code turns "last summer" into dates (like Commands.duration does for timers)
          │
 filter by person, date, place ─► rank by EmbeddingGemma 2 (text vector · photo vectors) + words found by OCR
          │
 results grid ─► optional "Ask about these": the top few photos go to Gemma ("when does my passport expire?")
```

The rules that keep gillspeak's commands safe carry over:

- **Works without Gemma.** Typing "beach" or "receipt" goes straight to the embedding search. Gemma only adds understanding of longer queries, as it does for commands the rules miss.
- **Gemma picks, our code computes.** Gemma names the person, the place and the words for the time ("last summer"); tested code turns them into a date range.
- **Constrained output.** LiteRT-LM's constrained decoding (`enableResponseFormat`) keeps the query JSON valid.
- **Show the photo with the answer.** An answer read from a document ("expires 3 March 2031") is shown next to the photo it came from, so it can be checked.

## Faces in more detail

1. **Find:** YuNet on the ~640 px copy. Skip faces that are tiny, blurred or turned away (the landmarks show this), which otherwise make junk groups.
2. **Line up and embed:** warp each face to 112×112 from its 5 landmarks, then SFace gives a 128-number vector.
3. **Group:** a new face joins a named person if it's close enough to them; the rest are grouped from time to time (agglomerative clustering or DBSCAN on cosine distance). 15,000 faces is a few seconds of work on a phone.
4. **You name them:** "This is Sam". Merges, splits and "not Sam" stick, and later grouping respects them. People only appear in search once you've named them.

**Privacy.** Face vectors are biometric data. They stay in the app's private storage, are never backed up (`allowBackup="false"`, as in gillspeak), and face grouping is **off until you turn it on**, with one button to delete it all. Nothing is ever uploaded, so there is no server to leak from.

## Documents in more detail

- **Which photos are documents:** compare each photo's EmbeddingGemma 2 vector with short descriptions ("a photo of a receipt", "a passport", "a driver licence", "a screenshot", "a whiteboard", "a handwritten note"). That's a document type for every photo at no extra cost.
- **Exact words:** OCR text goes into SQLite full-text search, so "Bunnings", an invoice number or a flight code are found exactly.
- **Questions:** "when does my passport expire?" finds the passport photo by type and words, then Gemma 4 reads it at a high image budget (560 or 1,120 tokens) and the answer is shown with the photo.

## Android notes

- **Permissions:** `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (Android 13+), `READ_EXTERNAL_STORAGE` on 11–12. Android 14+ lets people share only some photos (`READ_MEDIA_VISUAL_USER_SELECTED`); the app must handle that and offer "Choose more photos". GPS in EXIF is hidden unless the app holds `ACCESS_MEDIA_LOCATION` and opens the original (`MediaStore.setRequireOriginal`).
- **Indexing in the background:** WorkManager with "charging" and "battery not low" constraints, small batches that save their progress (a job gets about 10 minutes), and a pause when the phone's thermal status rises. New photos are picked up by a MediaStore observer.
- **Places with no network:** Android's `Geocoder` asks Google's servers, so it's out. A bundled list of cities with coordinates (for example GeoNames, CC BY 4.0) turns GPS into "Sydney" or "Bondi" on the phone.
- **Memory:** EmbeddingGemma 2 needs ~125–600 MB, so it can index while other apps run. Gemma 4 E2B (2–3 GB) loads only to understand a query or answer a question, and is released when idle, as `LocalLlm` does now.
- **Network:** only for the model downloads, through DownloadManager and SHA256-checked, as in gillspeak.

## Where gillphotos would live

- **A separate app** (`dev.hkgill.gillphotos`) in this repository as `android/gillphotos`. It is a Gradle build of its own rather than a module next to `watch` and `watchface`, because of the Kotlin version (below). The prototype copies the little download code it needs from `LocalLlm.kt`.
- **One catch: a second Gemma download.** Android keeps each app's files private, so gillphotos can't use the 2.6 GB Gemma that gillspeak already downloaded. The options are: download it again; have gillspeak share it through a signature-protected ContentProvider that hands out a file descriptor (LiteRT-LM's `EmbeddingEngineConfig` takes a `modelFd`, and whether `EngineConfig` does too needs checking); or build photo search into gillspeak. The first version doesn't need Gemma at all, so this can wait.
- **LiteRT-LM version:** `EmbeddingEngine` arrived in v0.17.0, and EmbeddingGemma 2 with v0.18.0. gillspeak pins 0.16.1 because later releases need Kotlin 2.4 (see `android/app/build.gradle.kts`). gillphotos uses 0.18.1 with Kotlin 2.4, and gillspeak could follow.

## Proposed plan

1. **Measure first (a few days).** On the Galaxy S25, with a test library of a few hundred photos: EmbeddingGemma 2's time per photo and its results on ~30 real searches (English and Punjabi); YuNet + SFace speed and how clean the groups are; PP-OCRv5 speed on receipts and screenshots; Gemma 4 E2B and E4B with images at 70, 280 and 1,120 tokens. Check search quality against SigLIP 2.
2. **Gallery and search by description:** browse by date, EmbeddingGemma 2 search, date and place filters. No Gemma.
3. **Documents:** OCR, exact-word search, document types.
4. **People:** face grouping, off by default, naming, merge and split.
5. **Gemma:** longer queries understood, voice search with Parakeet, "Ask about these photos".
6. **Later:** videos (EmbeddingGemma 2 takes frames and audio), duplicates and near-duplicates (the same vectors), "on this day".

## Risks and open questions

- **EmbeddingGemma 2 is three weeks old.** The speeds are Google's plus one community measurement, and there are no independent quality tests yet. SigLIP 2 is the fallback, through the same LiteRT runtime.
- **Battery and heat** on the first full index: should it be limited to the newest 1,000 photos first, then the rest over a few nights?
- **Languages:** EmbeddingGemma's text side is multilingual; whether Punjabi searches find the right photos needs testing, and PP-OCRv5's support for Gurmukhi text is unconfirmed.
- **Scope:** gillphotos searches the photos already on the phone. It doesn't back them up, which is half of what Google Photos does.
- **Face model licences:** the weights come from academic training data, as almost all face models' do. Worth a second look before a public release.

## Sources

- [LiteRT-LM README: v0.18.0 and EmbeddingGemma 2](https://github.com/google-ai-edge/LiteRT-LM), [Kotlin guide: images and `visionBackend`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md), [`EmbeddingEngine.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt)
- [EmbeddingGemma 2 (Google blog)](https://blog.google/innovation-and-ai/technology/developers-tools/embeddinggemma-2/), [Multimodal search on the edge with EmbeddingGemma 2 (Google Developers Blog)](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/), [`google/embeddinggemma-2`](https://huggingface.co/google/embeddinggemma-2), [`litert-community/embeddinggemma-2-text-vision-440m-litert-lm`](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm), [compact build measured on a Galaxy S25](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact), [EmbeddingGemma 2 in Transformers](https://huggingface.co/docs/transformers/model_doc/embedding_gemma2)
- [Gemma 4 on LiteRT-LM: S26 Ultra figures](https://developers.google.com/edge/litert-lm/models/gemma-4), [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm), [`litert-community/gemma-4-E4B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm), [Gemma 4 Technical Report](https://arxiv.org/pdf/2607.02770), [Gemma 4's image token budgets](https://datature.io/blog/gemma-4-what-computer-vision-engineers-actually-need-to-know)
- [SigLIP 2 for LiteRT](https://huggingface.co/litert-community/SigLIP2-base-patch16-224), [MobileCLIP2 licence](https://huggingface.co/apple/mobileclip2_coca_dfn2b_s13b_context77/blob/main/README.md), [Ente's machine learning](https://ente.com/help/photos/features/machine-learning), [PicQuery](https://www.sourcepulse.org/projects/2336758), [Immich machine learning](https://docs.v2.5.6.archive.immich.app/features/searching)
- [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) and [SFace](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) in OpenCV Zoo, [MediaPipe Face Detector](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector), [Qualcomm AI Hub face models and licences](https://aihub.qualcomm.com/models/mediapipe_face), [GhostFaceNets](https://github.com/HamadYA/GhostFaceNets)
- [PP-OCRv5 mobile ONNX](https://huggingface.co/ilaylow/PP_OCRv5_mobile_onnx), [PP-OCRv5 model sizes](https://docs.rs/crate/oar-ocr/0.8.1/source/docs/models.md), [ML Kit terms: metrics sent to Google](https://developers.google.com/ml-kit/terms)
- [Photosorter-for-Android: Qwen3-VL-2B and SmolVLM2 on phones](https://github.com/adityabp721/Photosorter-for-Android), [SmolVLM2-500M](https://huggingface.co/HuggingFaceTB/SmolVLM2-500M-Video-Instruct)
- [Android 14 partial photo access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
