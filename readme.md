# Stop Motion Lab

## Running in production

`sbt packageApplication` builds the frontend (via the `buildFrontend` task) and produces a self-contained fat
jar at `dist/app.jar`. Run it with:

```
java -DisProd=true -jar dist/app.jar
```

`-DisProd=true` (`AppConfig.isProd`) switches the server into "prod mode":
- serves over HTTPS, using a self-signed certificate authority it mints once and reuses (see
  `MakeSslContext`/`LocalServerCertificate`) -- a phone that installs the CA once (served at `GET /api/ca-cert`)
  trusts every future certificate automatically, even after this machine changes Wi-Fi networks;
- auto-opens the app in the default browser on startup, so there's no URL/port to type in by hand;
- stores its data (`db/`, `storage/`, `certs/`) next to wherever the running jar/app physically lives
  (`AppPaths.dataDir`), rather than relative to whatever directory it happened to be launched from -- important
  since double-clicking a packaged app doesn't reliably set the working directory the way running from a
  terminal does.

In dev (no `-DisProd`), none of the above applies: the server speaks plain HTTP, doesn't auto-open a browser
(Vite's dev server is the actual page you load), and data lives under `./data` relative to wherever sbt/the IDE
runs from, as before.

### Native app (no Java install required)

`sbt packageNativeApp` wraps that fat jar with the JDK's own `jpackage` into a self-contained app-image: a
folder with a native launcher and a bundled, trimmed-down JRE, written to `dist-native/`. Double-clicking the
launcher (`StopMotionLab/bin/StopMotionLab` on Linux, `StopMotionLab.exe` on Windows, `StopMotionLab.app` on
macOS) runs the app with `-DisProd=true` already baked in -- no terminal, no separate Java install. This is the
easiest way to hand the app to someone else, or to put it on an old laptop.

Caveats:
- `jpackage` only ever builds for the OS/architecture it runs on -- there's no cross-compiling. See
  `.github/workflows/release.yml` for a CI matrix that builds all three from a single tag push.
- This produces a portable *app-image* (a plain folder you can copy around, e.g. onto a USB stick), not an
  installer (`.msi`/`.deb`/`.dmg`). Installer types would put the app under a system location a regular user
  can't write to, which conflicts with keeping its data folder next to itself -- stick to app-image unless
  that changes.

### Cutting a release

Pushing a tag named `release-*` that points at a commit on `master` triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds the native app-image on Linux,
Windows and macOS runners and publishes them as assets on a GitHub Release for that tag:

```
git tag release-1.0.0
git push origin release-1.0.0
```

### Certificates shenanigans

Superseded by the automatic CA/cert minting described above (`MakeSslContext`) -- kept here for history, not
needed anymore.

```
openssl pkcs12 -export `
  -out certs/server.p12 `
  -inkey certs/192.168.0.12+2-key.pem `
  -in certs/192.168.0.12+2.pem `
  -name stop-motion-lab
```

mdp: stopmotion
