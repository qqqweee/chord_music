# Chord Last.fm signer (you do this ONCE, friends do nothing)

Last.fm writes (scrobble, sign-in) need your API **shared secret**. A secret inside an APK can be pulled out, so it lives on this tiny server instead.

1. Make an API account at https://www.last.fm/api/account/create (save key + shared secret).
2. `npx wrangler deploy lastfm-signer/worker.js --name chord-lastfm-signer --compatibility-date 2024-01-01`
3. `npx wrangler secret put LASTFM_API_KEY --name chord-lastfm-signer` and the same for `LASTFM_SECRET`.
4. Put the address (`https://chord-lastfm-signer.<you>.workers.dev`) in `AppSettings.kt` as `DEFAULT_SIGNER`, then build the APK.

Friends: Settings > Last.fm scrobbling > **Connect** > tap **Allow** on the Last.fm page > come back. Done.
That one tap also fills in the API key, so Daily/Mood mixes, import and smarter radio work with no setup.

Manifest needs `ACCESS_NETWORK_STATE` (queued scrobbles send as soon as the phone is online).
