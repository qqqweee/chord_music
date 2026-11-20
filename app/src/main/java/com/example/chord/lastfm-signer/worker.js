// Chord Last.fm signer: a tiny Cloudflare Worker. It holds the shared secret so the app never does.
// Deploy:  npx wrangler deploy lastfm-signer/worker.js --name chord-lastfm-signer --compatibility-date 2024-01-01
//          npx wrangler secret put LASTFM_API_KEY --name chord-lastfm-signer
//          npx wrangler secret put LASTFM_SECRET  --name chord-lastfm-signer
// Then paste the https://chord-lastfm-signer.<you>.workers.dev address in Chord > Settings > Last.fm scrobbling.
// Use the key + secret from https://www.last.fm/api/account/create (the key and secret must be a pair).

const API = "https://ws.audioscrobbler.com/2.0/";
// Only these writes can be signed. Nothing else is ever signed for a caller.
const SIGNABLE = new Set(["track.scrobble", "track.updateNowPlaying"]);

const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

async function md5(s) {
  const d = await crypto.subtle.digest("MD5", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Last.fm rule: sort params by name, glue name+value, add the secret, md5. "format" and "callback" are not signed.
async function sign(params, secret) {
  const s = Object.keys(params).filter((k) => k !== "format" && k !== "callback").sort()
    .map((k) => k + params[k]).join("") + secret;
  return md5(s);
}

async function lastfm(params, env) {
  const all = { ...params, api_key: env.LASTFM_API_KEY };
  all.api_sig = await sign(all, env.LASTFM_SECRET);
  all.format = "json";
  const r = await fetch(API + "?" + new URLSearchParams(all));
  return r.json();
}

export default {
  async fetch(req, env) {
    if (!env.LASTFM_API_KEY || !env.LASTFM_SECRET) return json({ error: 500, message: "Signer not set up" }, 500);
    const { pathname } = new URL(req.url);
    try {
      if (pathname === "/auth/token" && req.method === "GET") {
        const r = await lastfm({ method: "auth.getToken" }, env);
        if (r.error) return json(r, 400);
        return json({ token: r.token, url: `https://www.last.fm/api/auth/?api_key=${env.LASTFM_API_KEY}&token=${r.token}` });
      }
      if (pathname === "/auth/session" && req.method === "POST") {
        const { token } = await req.json();
        const r = await lastfm({ method: "auth.getSession", token: String(token || "") }, env);
        if (r.error) return json(r, 400);
        return json({ sk: r.session.key, name: r.session.name, key: env.LASTFM_API_KEY });
      }
      if (pathname === "/sign" && req.method === "POST") {
        const { params } = await req.json();
        if (!params || !SIGNABLE.has(params.method)) return json({ error: 403, message: "Method not allowed" }, 403);
        const all = { ...params, api_key: env.LASTFM_API_KEY };
        return json({ api_key: env.LASTFM_API_KEY, api_sig: await sign(all, env.LASTFM_SECRET) });
      }
    } catch (e) {
      return json({ error: 500, message: "Bad request" }, 400);
    }
    return json({ error: 404, message: "Not found" }, 404);
  },
};
