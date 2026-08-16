import { resolve } from 'path'
import { createHtmlPlugin } from 'vite-plugin-html'
import { scalaMetadata } from "./scala-metadata"

import { defineConfig } from 'vite'

const scalaVersion = scalaMetadata.scalaVersion
const frontendName = scalaMetadata.frontendName

export default defineConfig(({ command, mode, ssrBuild }) => {

    const htmlPlugin = createHtmlPlugin()

    const mainJS = `/generated/${mode === 'production' ? 'opt' : 'fastopt'}/main.js`
    console.log('mainJS', mainJS)
    const script = `<script type="module" src="${mainJS}"></script>`

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
            https: {
                cert: "../certs/192.168.0.12+2.pem",
                key: "../certs/192.168.0.12+2-key.pem",
            }
        },
        base: "/static/"
    }
})
