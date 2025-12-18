import { concurrently } from 'concurrently'

const SERVE_HOST = 'http://localhost'
const SERVE_PORT = 3000

concurrently([
  `tsx script/build.ts --watch`,
  `serve dist -p ${SERVE_PORT} --cors --no-etag`
])

console.log(`webserver running at ${SERVE_HOST}:${SERVE_PORT}`)
console.log('Note: Caching is disabled for development')
