# Plex integration

Plex is a read-only media-server source mounted beside Jellyfin under the device root.

## Scope

- Plex account PIN sign-in, browser/QR hand-off, multiple accounts and Plex Home users.
- Owned and shared server discovery, connection probing and account-scoped configuration.
- Movies, shows, seasons and episodes; original-file playback only.
- Direct `Media -> Part -> key` URLs through Piko's local HTTP proxy. The transcode and Direct
  Stream endpoints are not used.
- Playback history, resume positions and `/:/timeline` reporting.

Plex stars are not mapped to ratings, Watchlists or Plex playlists. Multi-file versions are
reported as unsupported instead of silently playing only the first file.

## Credentials

`PlexAccount` and `PlexServer` contain no tokens. `PlexCredentialBundle` stores the account
token, per-Home-user tokens and per-server resource tokens in the platform secret store. Android
encrypts the bundle with the existing `CredentialCipher`; desktop stores it through the existing
layered secret vault. Plex configuration is device-local and is not part of Piko settings sync.

Changing a Plex Home user discards the previous server-token set and re-fetches `/resources`
with the new identity. A server's stable `mountId` is persisted locally and is not derived from
the connection URL.

## Endpoints

- `POST https://plex.tv/api/v2/pins?strong=true`
- `GET https://plex.tv/api/v2/pins/{id}`
- `GET https://plex.tv/api/v2/user`
- `GET https://plex.tv/api/v2/home/users`
- `POST https://plex.tv/api/v2/home/users/{uuid}/switch`
- `GET https://clients.plex.tv/api/v2/resources`
- PMS: `/library/sections`, `/library/sections/{id}/all`,
  `/library/metadata/{id}/children`, `/library/metadata/{id}`, `/library/onDeck`,
  `/:/timeline`, `/identity`

Only `404/410` marks a PIN expired. Authentication failures are not retried through another
plex.tv hostname. Resource discovery may fall back from `clients.plex.tv` to `plex.tv` only on a
transport failure.

## Attribution

See `THIRD_PARTY_NOTICES.md` for Plezy, PlexKodiConnect and ZXing attribution.
