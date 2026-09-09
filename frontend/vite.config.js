import { readdirSync, statSync } from 'fs'
import { resolve } from 'path'
import { createHtmlPlugin } from 'vite-plugin-html'
import { scalaMetadata } from "./scala-metadata"

import { defineConfig } from 'vite'

const scalaVersion = scalaMetadata.scalaVersion
const frontendName = scalaMetadata.frontendName

const certsDir = resolve(process.cwd(), '../certs')

// mkcert names its output "<name1>+<n>.pem" / "<name1>+<n>-key.pem", where <name1> is whatever came first on the
// command line -- for us that's always the machine's LAN IP (see README: `mkcert <ip> localhost 127.0.0.1`). Rather
// than re-detecting that IP here too, just pick up whatever matching pair is newest in certs/, so this keeps working
// across machines and after the IP changes without editing this file.
function findMkcertCerts(dir) {
    let files
    try {
        files = readdirSync(dir)
    } catch {
        return undefined
    }

    const pairs = files
        .filter(f => /\+\d+\.pem$/.test(f))
        .map(cert => ({ cert, key: cert.replace(/\.pem$/, '-key.pem') }))
        .filter(({ key }) => files.includes(key))
        .map(({ cert, key }) => ({ cert, key, mtime: statSync(resolve(dir, cert)).mtimeMs }))
        .sort((a, b) => b.mtime - a.mtime)

    if (pairs.length === 0) return undefined

    const { cert, key } = pairs[0]
    return { cert: resolve(dir, cert), key: resolve(dir, key) }
}

export default defineConfig(({ command, mode, ssrBuild }) => {

    const htmlPlugin = createHtmlPlugin()

    const mainJS = `/generated/${mode === 'production' ? 'opt' : 'fastopt'}/main.js`
    console.log('mainJS', mainJS)
    const script = `<script type="module" src="${mainJS}"></script>`

    const https = findMkcertCerts(certsDir)
    if (!https) {
        console.log(
            `No mkcert certificate found in ${certsDir}/ -- serving over plain HTTP.\n` +
            `To test over HTTPS (e.g. from a phone on the LAN), run from certs/:\n` +
            `  mkcert <this-machine's-LAN-IP> localhost 127.0.0.1`
        )
    }

    return {
        build: {
            target: "esnext"
        },
        publicDir: './public',
        plugins: createHtmlPlugin({
            minify: process.env.NODE_ENV === 'production',
            inject: {
                data: {
                    script
                }
            }
        }),
        optimizeDeps: {
            esbuildOptions: {
                target: "esnext"
            }
        },
        server: {
            host: "0.0.0.0",
            port: 3000,
            proxy: {
                "/api": {
                  target: "http://127.0.0.1:8080"
                },
                "/ws": {
                  target: "http://127.0.0.1:8080",
                  ws: true
                }
            },
            https
        },
        base: "/static/"
    }
})
