# GIFs and stickers setup

Samvad lets people send GIFs and stickers from the attach sheet ("GIFs and stickers"). The images come from **GIPHY**
by default. Until you finish the steps below, the app builds and runs normally and the picker shows "GIFs aren't set up
yet". Nothing else is affected.

## How it works

- The app never holds the GIPHY key. It calls two Firebase callables (`searchGifs`, `trendingGifs`, region
  `asia-south1`), which call GIPHY from the server with the key stored as the Functions secret `GIPHY_API_KEY`.
- The callables require a signed-in phone account and App Check, limit each account per day (300 searches and 150
  trending loads, `DAILY_SEARCHES` / `DAILY_TRENDING` in `firebase/functions/src/gifs.ts`), cap a search at 50
  characters, and ask GIPHY for rating **g** only (`GIF_RATING` in `firebase/functions/src/lib/gifs.ts`). Answers are
  cached in memory for 60 seconds per function instance. Only `id`, `title`, small preview link, full link, `width` and
  `height` are returned, and any result whose image host is not `media*.giphy.com` / `i.giphy.com` over https is dropped.
- A sent GIF or sticker is a message of kind `gif` or `sticker` holding **links** (`gif: {url, previewUrl, width,
  height, provider, id}`). Nothing is uploaded to Firebase Storage. The Firestore rules accept only https links on the
  allow-listed GIPHY hosts, `provider == 'giphy'`, sizes up to 1000, no caption on a sticker and a caption of up to
  1000 characters on a GIF.
- Recent and favourite GIFs are kept on the phone (DataStore) and cleared on sign-out. They are never uploaded.

### What GIPHY can see (also stated in the app's picker footer and the privacy policy)

- Search words reach GIPHY through our server, together with our API key but **no account data or phone number**.
- The phone that **receives** a GIF loads the image from GIPHY's servers, so GIPHY can see that phone's IP address and
  the image requested. This is the cost of sending by link instead of copying the image into our storage. The picker
  footer says so. If you ever need to avoid it, the alternative is to proxy or copy images through our own storage,
  which costs storage and bandwidth and needs the provider's permission.
- With Data saver on, or auto-download set to Wi-Fi only or Never, a GIF in the chat shows only the small preview's
  first frame with "Tap to play"; the full image loads after the tap. That preview is still a small download.

### Attribution

GIPHY's terms require attribution ("Powered by GIPHY") wherever its content is shown. The picker shows the text
**Powered by GIPHY** at the bottom. The official logo asset is not in the repository, so text is used. Before a public
release, check GIPHY's current API terms and brand guidelines and swap in the official mark if they ask for it
(add it as a drawable and replace the text in `GifPickerContent` in `ui/chat/GifViews.kt`).

## 1. Create the GIPHY key

1. Sign up at https://developers.giphy.com and create an app ("Create an App", choose **API**, not SDK).
2. Copy the API key. A new key starts as a **development (beta) key** with a low hourly request limit.
3. Before you launch publicly, apply for **production access** in the GIPHY dashboard. Production access, rate
   limits, attribution rules and what you may do with the content are GIPHY's. **Read their current terms of service
   and API terms yourself**; they change, and they were not verified when this was written.

## 2. Set the secret

From the repository root, logged in with `firebase login` and the right project selected:

```
firebase functions:secrets:set GIPHY_API_KEY
```

Paste the key when asked. To deploy before you have a key, set the secret to the word `not-configured`: Firebase will
not deploy a function whose secret is unset, and the app then shows "GIFs aren't set up yet".

## 3. Deploy

```
cd firebase
npm --prefix functions run build
firebase deploy --only functions:searchGifs,functions:trendingGifs,functions:onMessageCreated,firestore:rules
```

`onMessageCreated` is included because chat-list previews now say "GIF" and "Sticker"; the rules are included because
they accept the new message kinds. Deploy the rules **before** the app version that sends GIFs reaches people. After
changing the secret value, redeploy the two functions so a new instance reads it.

## 4. Check it

1. Open a chat, tap the paperclip, then "GIFs and stickers".
2. Trending loads. Search for "hello". Switch to Stickers. Send one of each.
3. Long-press a tile to favourite it; it appears under "Yours".
4. Switch the phone to airplane mode: the picker says it is offline.

## Costs and limits to watch

- **GIPHY request limits** (the main one). Each search or trending page is one GIPHY call; scrolling loads a page of 24.
  Watch the GIPHY dashboard. The server cache removes repeat calls for 60 seconds on the same instance.
- **Per-account daily limits** keep one person from burning the quota. Raise them only after production access.
- **Cloud Functions**: each call is a short invocation (small, a few hundred ms plus the GIPHY round trip). Costs are
  tiny next to messaging, but watch the invocation count of `searchGifs` and `trendingGifs` after launch.
- **Firestore**: one extra read and write per call for the daily limit counter (`rateLimits/{uid}`).
- **Phone data**: GIFs are loaded from GIPHY's CDN by the phones. Previews are small; the sent image is about 200 px
  wide. Data saver and the auto-download setting apply.
- **App size**: Coil with GIF support adds a little code (Coil 3.3.0 `coil-compose`, `coil-gif`, `coil-network-okhttp`;
  OkHttp may already be in the app through other libraries). Measure the release APK size before and after.

## Swap the provider (Klipy, Tenor or another)

Only the server and the host allow-list change; the app UI does not.

1. In `firebase/functions/src/lib/gifs.ts`, write a `createXProvider(apiKey, fetcher): GifProvider` next to
   `createGiphyProvider`. It must return `{ items: GifItem[]; next }` with only `id`, `title`, `previewUrl`, `url`,
   `width`, `height`, drop any result whose host fails `isAllowedGifUrl`, and map 401/403 to `gifs-not-configured` and
   other failures to `unavailable`. Return it from `defaultGifProvider()`, and rename or add the secret in `GIF_SECRETS`.
2. Add the new image hosts to the allow-list in **three** places and keep them identical: `GIPHY_HOST` in
   `lib/gifs.ts`, `validGifUrl` and the `provider` check in `firebase/firestore.rules`, and `GifRules.HOST` /
   `GifRules.PROVIDER` in `android-native/core/.../gifs/Gifs.kt`. Existing messages keep their old provider links, so
   keep the old hosts allowed for as long as old messages should display.
3. Change the attribution text (`gifs_powered_by`) and the privacy sentences (picker footer `gifs_privacy_note` in all
   four languages, `privacy-policy.md`, `PLAY_DATA_SAFETY.md`, `service-providers.md`).
4. Update the tests: `firebase/functions/test/gifs.test.ts` uses a fake fetcher, and `firebase/rules-tests/gifs.test.ts`
   covers the rules. Tenor's public API has been announced for discontinuation, so check its current status before choosing it.
